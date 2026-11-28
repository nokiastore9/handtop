package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxApps
import org.handtop.desktop.ui.MenuEntry
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.setThemedIcon
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.showPopupMenu
import org.handtop.desktop.widgets.DesktopGrid
import org.handtop.desktop.widgets.Layout
import org.handtop.desktop.widgets.PlacedWidget
import org.handtop.desktop.widgets.WidgetHost
import org.handtop.desktop.widgets.WidgetKind
import org.handtop.desktop.widgets.WidgetView
import org.handtop.desktop.widgets.Widgets
import org.handtop.desktop.widgets.widgetView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The shortcuts on the wallpaper, below all windows, on a grid of cells. Each shortcut sits
 * where the user dragged it; one never dragged takes the first free cell, top to bottom and
 * then the next column, so a short phone screen still shows them all.
 *
 * A tap (a click) opens a shortcut; pressing it and moving drags it to another cell (onto
 * another shortcut, the two swap). A long press or a right click opens its menu, and on the
 * empty wallpaper, the desktop's menu.
 *
 * The user's widgets share the grid, each on a block of cells (see [DesktopGrid]): they drag
 * the same way, land only where they fit, and an icon dropped on one goes back.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DesktopIcons(
    context: Context,
    private val open: (Shortcut) -> Unit,
    /** The desktop's menu (a long press or a right click on the wallpaper). */
    private val desktopMenu: () -> List<MenuEntry?>,
    private val widgetHost: WidgetHost,
) : ViewGroup(context) {

    private val cellWidth = dpi(92)
    private val cellHeight = dpi(90)
    private val gap = dpi(4)
    private val iconPx = dpi(40)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val listener: () -> Unit = { reload() }

    private var shortcuts: List<Shortcut> = emptyList()
    /** The cell each child is in (same order as the children), from the last layout. */
    private var placed: List<Cell> = emptyList()
    private var columns = 1
    private var rows = 1

    // The widgets: in the order stored, their views (kept across reloads), their top left cells.
    private var widgets: List<PlacedWidget> = emptyList()
    private val widgetViews = LinkedHashMap<WidgetKind, WidgetView>()
    private var widgetPlaced: List<Cell> = emptyList()
    /** The desktop is on screen: widgets refresh. */
    private var active = false

    // Where the last touch went down, for menus opened by a long press.
    private var rawX = 0f
    private var rawY = 0f
    private var menu: PopupWindow? = null
    /** The press opened a menu at once (a right click): it does not drag. */
    private var pressOpenedMenu = false

    // A shortcut being dragged: which child, where the finger went down, the cell it would land in.
    private var dragging: View? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dropCell: Cell? = null
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val dropOutline = Paint().apply {
        color = Palette.DESKTOP_LABEL
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val dropRect = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            rawX = e.rawX
            rawY = e.rawY
            menu = showPopupMenu(this@DesktopIcons, e.rawX, e.rawY, desktopMenu())
        }
    })

    init {
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        setWillNotDraw(false) // the drop target is drawn under the icons
        reload()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Shortcuts.addListener(listener)
        Widgets.addListener(listener)
        reload()
    }

    override fun onDetachedFromWindow() {
        Shortcuts.removeListener(listener)
        Widgets.removeListener(listener)
        widgetViews.values.forEach { it.onActive(false) }
        super.onDetachedFromWindow()
    }

    private fun reload() {
        if (dragging != null) return // the drop saves, then reloads
        removeAllViews()
        shortcuts = Shortcuts.load(context)
        for (shortcut in shortcuts) addView(item(shortcut))
        widgets = Widgets.load(context)
        val kinds = widgets.map { it.kind }.toSet()
        (widgetViews.keys - kinds).forEach { widgetViews.remove(it)?.onActive(false) }
        for (w in widgets) addView(widgetViews.getOrPut(w.kind) { widgetView(context, w.kind, widgetHost).also { setUpWidget(it) } })
        updateActive()
    }

    // ---- widgets ----

    /** The desktop came on screen or went away: widgets refresh only while it is shown. */
    fun setActive(active: Boolean) {
        this.active = active
        updateActive()
    }

    private fun updateActive() = widgetViews.values.forEach { it.onActive(active && isAttachedToWindow) }

    /** The kinds not on the desktop yet. */
    fun addableWidgets(): List<WidgetKind> = WidgetKind.entries.filter { k -> widgets.none { it.kind == k } }

    /** Puts [kind] on the first free place that fits (from the top right). */
    fun addWidget(kind: WidgetKind) {
        Widgets.add(context, kind, DesktopGrid.firstFree(columns, rows, kind.size, takenCells(except = null)))
        // The weather goes by location: Android asks once; either way the widget then refreshes.
        if (kind == WidgetKind.WEATHER) widgetHost.requestLocation { widgetViews[kind]?.onActive(active && isAttachedToWindow) }
    }

    /** "Add widget…": the kinds not on the desktop yet, where the desktop's menu was. */
    fun showAddWidgetMenu() {
        menu = showPopupMenu(this, rawX, rawY, addableWidgets().map { kind ->
            MenuEntry(context.getString(widgetName(kind))) { addWidget(kind) }
        })
    }

    private fun setUpWidget(view: WidgetView) {
        view.setOnClickListener { view.onTap() }
        view.setOnLongClickListener {
            menu = showWidgetMenu(view, rawX, rawY)
            true
        }
        view.setOnTouchListener { v, e -> itemTouch(v, e) { x, y -> showWidgetMenu(view, x, y) } }
    }

    private fun showWidgetMenu(view: WidgetView, x: Float, y: Float) = showPopupMenu(this, x, y, buildList {
        if (view.hasSettings) {
            add(MenuEntry(context.getString(R.string.widget_settings)) { view.showSettings() })
            add(null)
        }
        add(MenuEntry(context.getString(R.string.widget_remove), tint = Palette.CLOSE_HOVER) { Widgets.remove(context, view.kind) })
    })

    /** Every cell taken now by icons and by the widgets other than [except]. */
    private fun takenCells(except: WidgetKind?): Set<Cell> {
        val taken = HashSet<Cell>(placed)
        widgets.forEachIndexed { j, w ->
            if (w.kind != except) widgetPlaced.getOrNull(j)?.let { taken += DesktopGrid.cellsOf(it, w.kind.size) }
        }
        return taken
    }

    private fun widgetCells(): Set<Cell> = takenCells(except = null) - placed.toSet()

    /** The widget a child is, or null for an icon. */
    private fun widgetOf(view: View): PlacedWidget? {
        val j = indexOfChild(view) - shortcuts.size
        return if (j >= 0) widgets.getOrNull(j) else null
    }

    // ---- the wallpaper itself ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
            menu = showPopupMenu(this, event.rawX, event.rawY, desktopMenu())
            return true
        }
        return gestures.onTouchEvent(event)
    }

    // ---- one shortcut ----

    private fun item(shortcut: Shortcut): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(6), dpi(8), dpi(6), dpi(4))
            // On the wallpaper, whatever the theme: feedback that shows over a dark picture.
            background = Themes.current.darkFeedback(context)
            setOnClickListener { open(shortcut) }
            setOnLongClickListener {
                menu = showMenu(shortcut, rawX, rawY)
                true
            }
        }
        item.setOnTouchListener { view, e -> itemTouch(view, e) { x, y -> showMenu(shortcut, x, y) } }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(context).apply {
            // On the wallpaper, whatever the theme: light text with a shadow.
            setTextColor(Palette.DESKTOP_LABEL)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
            setPadding(0, dpi(4), 0, 0)
        }
        when (shortcut) {
            is Shortcut.BuiltIn -> {
                val (drawable, name) = builtIn(shortcut.id)
                icon.setThemedIcon(drawable, onWallpaper = true)
                label.setText(name)
            }
            is Shortcut.LinuxProgram -> {
                // Until its own icon is read; white on a light theme's wallpaper, whose gray is dark.
                icon.setThemedIcon(R.drawable.ic_app_generic, if (Themes.current.light) null else Palette.TEXT_SOFT, onWallpaper = true)
                label.text = shortcut.name
                loadLinuxIcon(shortcut, icon)
            }
            is Shortcut.Folder -> {
                icon.setThemedIcon(R.drawable.ic_folder, FOLDER_COLOR) // as folders look in Files
                label.text = shortcut.name
            }
        }
        item.contentDescription = label.text
        item.addView(icon, LinearLayout.LayoutParams(iconPx, iconPx))
        item.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return item
    }

    /**
     * Before the item's own handling (tap, long press): a right click opens the menu, and a
     * press that moves becomes a drag, which takes the rest of the gesture.
     */
    private fun itemTouch(view: View, e: MotionEvent, showItemMenu: (Float, Float) -> PopupWindow): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rawX = e.rawX
                rawY = e.rawY
                pressOpenedMenu = e.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                if (pressOpenedMenu) {
                    menu = showItemMenu(e.rawX, e.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == null && !pressOpenedMenu && (abs(e.rawX - rawX) > touchSlop || abs(e.rawY - rawY) > touchSlop)) {
                    startDrag(view, e)
                }
                if (dragging === view) {
                    moveDrag(view, e)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging === view) {
                endDrag(view, e.actionMasked == MotionEvent.ACTION_UP)
                return true
            }
        }
        return false
    }

    private fun startDrag(view: View, e: MotionEvent) {
        // The item stops being pressed and forgets the tap or long press it was waiting for.
        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
        view.onTouchEvent(cancel)
        cancel.recycle()
        menu?.dismiss() // a long press opened the menu, then the finger moved on
        menu = null
        dragging = view
        dragStartX = rawX
        dragStartY = rawY
        view.alpha = 0.85f
        view.translationZ = dp(8f)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun moveDrag(view: View, e: MotionEvent) {
        view.translationX = e.rawX - dragStartX
        view.translationY = e.rawY - dragStartY
        // A widget lands by its top left cell, an icon by its middle.
        dropCell = if (widgetOf(view) != null) cellAt(view.left + view.translationX + cellWidth / 2f, view.top + view.translationY + cellHeight / 2f)
        else cellAt(view.left + view.translationX + view.width / 2f, view.top + view.translationY + view.height / 2f)
        invalidate()
    }

    private fun endDrag(view: View, drop: Boolean) {
        val index = indexOfChild(view)
        val target = dropCell
        dragging = null
        dropCell = null
        if (Themes.current.animations) {
            view.animate().translationX(0f).translationY(0f).translationZ(0f).alpha(1f).setDuration(120).start()
        } else {
            view.translationX = 0f; view.translationY = 0f; view.translationZ = 0f; view.alpha = 1f
        }
        invalidate()
        if (!drop || target == null || index < 0) return
        val widget = widgetOf(view)
        if (widget != null) {
            // Only where it fits whole and covers nothing; otherwise it goes back.
            if (DesktopGrid.fits(columns, rows, widget.kind.size, target, takenCells(except = widget.kind))) Widgets.move(context, widget.kind, target)
            return
        }
        if (index >= placed.size || target in widgetCells()) return // not onto a widget
        val from = placed[index]
        if (target == from) return
        // Every shortcut keeps the cell it is in now, so nothing else moves; whatever sat in
        // the target cell takes the dragged one's place.
        val cells = HashMap<String, Cell>()
        shortcuts.forEachIndexed { i, s -> placed.getOrNull(i)?.let { cells[Shortcuts.key(s)] = it } }
        shortcuts.forEachIndexed { i, s -> if (placed.getOrNull(i) == target) cells[Shortcuts.key(s)] = from }
        cells[Shortcuts.key(shortcuts[index])] = target
        Shortcuts.setCells(context, cells)
    }

    /** The grid cell nearest to a point in this view. */
    private fun cellAt(x: Float, y: Float): Cell {
        val column = ((x - paddingLeft - cellWidth / 2f) / (cellWidth + gap)).roundToInt().coerceIn(0, columns - 1)
        val row = ((y - paddingTop - cellHeight / 2f) / (cellHeight + gap)).roundToInt().coerceIn(0, rows - 1)
        return Cell(column, row)
    }

    /** The program's own icon, read from Linux off the main thread. */
    private fun loadLinuxIcon(shortcut: Shortcut.LinuxProgram, view: ImageView) {
        val app = context.applicationContext
        Thread({
            val bitmap = LinuxApps.forAppId(app, shortcut.appId)?.let { LinuxApps.icon(it, iconPx) }
            if (bitmap != null) post {
                view.clearColorFilter()
                view.setImageBitmap(bitmap)
            }
        }, "desktop-icon").start()
    }

    private fun showMenu(shortcut: Shortcut, x: Float, y: Float) = showPopupMenu(this, x, y, listOf(
        MenuEntry(context.getString(R.string.shortcut_open)) { open(shortcut) },
        null,
        MenuEntry(context.getString(R.string.shortcut_remove), tint = Palette.CLOSE_HOVER) { Shortcuts.remove(context, shortcut) },
    ))

    // ---- layout: a grid of cells ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cell = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        for (i in 0 until childCount) {
            val w = widgets.getOrNull(i - shortcuts.size)?.kind?.size
            if (w == null) getChildAt(i).measure(cell(cellWidth), cell(cellHeight))
            else getChildAt(i).measure(cell(widthOf(w.columns)), cell(heightOf(w.rows)))
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rows = rowsFor(b - t)
        columns = ((r - l - paddingLeft - paddingRight + gap) / (cellWidth + gap)).coerceAtLeast(1)
        val layout = layOut(columns, rows)
        placed = layout.icons
        widgetPlaced = layout.widgets
        placed.forEachIndexed { i, cell ->
            val x = paddingLeft + cell.column * (cellWidth + gap)
            val y = paddingTop + cell.row * (cellHeight + gap)
            getChildAt(i)?.layout(x, y, x + cellWidth, y + cellHeight)
        }
        widgetPlaced.forEachIndexed { j, cell ->
            val size = widgets[j].kind.size
            val x = paddingLeft + cell.column * (cellWidth + gap)
            val y = paddingTop + cell.row * (cellHeight + gap)
            getChildAt(shortcuts.size + j)?.layout(x, y, x + widthOf(size.columns), y + heightOf(size.rows))
        }
    }

    private fun widthOf(columns: Int) = columns * cellWidth + (columns - 1) * gap
    private fun heightOf(rows: Int) = rows * cellHeight + (rows - 1) * gap

    private fun rowsFor(height: Int) = ((height - paddingTop - paddingBottom + gap) / (cellHeight + gap)).coerceAtLeast(1)

    /**
     * The width of the icons' block at the left when the desktop is [height] tall: up to the
     * last of the columns that have icons from the first one on, plus a gap. An icon dragged
     * away on its own does not count; windows may cover it.
     */
    fun blockWidth(height: Int): Int {
        if (width <= 0 || shortcuts.isEmpty()) return 0
        val columns = ((width - paddingLeft - paddingRight + gap) / (cellWidth + gap)).coerceAtLeast(1)
        val used = layOut(columns, rowsFor(height)).icons.map { it.column }.toSet()
        var last = -1
        while (last + 1 in used) last++
        return if (last < 0) 0 else paddingLeft + (last + 1) * (cellWidth + gap) + dpi(16)
    }

    /** Where icons and widgets go on a grid of [columns] by [rows] (see [DesktopGrid]). */
    private fun layOut(columns: Int, rows: Int): Layout {
        val saved = Shortcuts.cells(context)
        return DesktopGrid.layOut(columns, rows, shortcuts.map { saved[Shortcuts.key(it)] }, widgets)
    }

    override fun onDraw(canvas: Canvas) {
        val cell = dropCell ?: return
        val view = dragging ?: return
        // Where the dragged thing would land; nothing where it cannot (it would go back).
        val widget = widgetOf(view)
        val size = widget?.kind?.size
        if (widget != null && !DesktopGrid.fits(columns, rows, widget.kind.size, cell, takenCells(except = widget.kind))) return
        if (widget == null && cell in widgetCells()) return
        val x = paddingLeft + cell.column * (cellWidth + gap).toFloat()
        val y = paddingTop + cell.row * (cellHeight + gap).toFloat()
        dropRect.set(x, y, x + (size?.let { widthOf(it.columns) } ?: cellWidth), y + (size?.let { heightOf(it.rows) } ?: cellHeight))
        if (Themes.current.roundedCorners) canvas.drawRoundRect(dropRect, dp(10f), dp(10f), dropPaint)
        else canvas.drawRect(dropRect, dropOutline) // classic: nothing translucent, just an outline
    }

    companion object {
        private const val FOLDER_COLOR = 0xFFFFC857.toInt()

        /** A widget kind's name. */
        fun widgetName(kind: WidgetKind): Int = when (kind) {
            WidgetKind.CLOCK -> R.string.widget_clock
            WidgetKind.WEATHER -> R.string.widget_weather
            WidgetKind.LINUX -> R.string.widget_linux
        }

        /** Icon and name of one of HandTop's own shortcuts. */
        fun builtIn(id: String): Pair<Int, Int> = when (id) {
            Shortcuts.LINUX_TERMINAL -> R.drawable.ic_linux to R.string.app_terminal_linux
            Shortcuts.LINUX_APPS -> R.drawable.ic_apps to R.string.app_linux_apps
            Shortcuts.FILES -> R.drawable.ic_files to R.string.app_files
            Shortcuts.ANDROID_TERMINAL -> R.drawable.ic_terminal to R.string.app_terminal_android
            Shortcuts.SETTINGS -> R.drawable.ic_settings to R.string.app_settings
            else -> R.drawable.ic_info to R.string.app_about
        }
    }
}
