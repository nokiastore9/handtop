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
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.showPopupMenu
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
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DesktopIcons(
    context: Context,
    private val open: (Shortcut) -> Unit,
    /** The desktop's menu (a long press or a right click on the wallpaper). */
    private val desktopMenu: () -> List<MenuEntry?>,
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
    private val dropRect = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
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
        reload()
    }

    override fun onDetachedFromWindow() {
        Shortcuts.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun reload() {
        if (dragging != null) return // the drop saves, then reloads
        removeAllViews()
        shortcuts = Shortcuts.load(context)
        for (shortcut in shortcuts) addView(item(shortcut))
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
            background = context.selectableBackground()
            setOnClickListener { open(shortcut) }
            setOnLongClickListener {
                menu = showMenu(shortcut, rawX, rawY)
                true
            }
        }
        item.setOnTouchListener { view, e -> itemTouch(view, shortcut, e) }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(context).apply {
            setTextColor(Palette.TEXT)
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
                icon.setImageResource(drawable)
                label.setText(name)
            }
            is Shortcut.LinuxProgram -> {
                icon.setImageResource(R.drawable.ic_app_generic) // until its own icon is read
                icon.setColorFilter(Palette.TEXT_SOFT)
                label.text = shortcut.name
                loadLinuxIcon(shortcut, icon)
            }
            is Shortcut.Folder -> {
                icon.setImageResource(R.drawable.ic_folder)
                icon.setColorFilter(FOLDER_COLOR) // as folders look in Files
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
    private fun itemTouch(view: View, shortcut: Shortcut, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rawX = e.rawX
                rawY = e.rawY
                pressOpenedMenu = e.buttonState and MotionEvent.BUTTON_SECONDARY != 0
                if (pressOpenedMenu) {
                    menu = showMenu(shortcut, e.rawX, e.rawY)
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
        dropCell = cellAt(view.left + view.translationX + view.width / 2f, view.top + view.translationY + view.height / 2f)
        invalidate()
    }

    private fun endDrag(view: View, drop: Boolean) {
        val index = indexOfChild(view)
        val target = dropCell
        dragging = null
        dropCell = null
        view.animate().translationX(0f).translationY(0f).translationZ(0f).alpha(1f).setDuration(120).start()
        invalidate()
        if (!drop || target == null || index < 0 || index >= placed.size) return
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
        for (i in 0 until childCount) getChildAt(i).measure(cell(cellWidth), cell(cellHeight))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rows = ((b - t - paddingTop - paddingBottom + gap) / (cellHeight + gap)).coerceAtLeast(1)
        columns = ((r - l - paddingLeft - paddingRight + gap) / (cellWidth + gap)).coerceAtLeast(1)
        placed = place()
        placed.forEachIndexed { i, cell ->
            val x = paddingLeft + cell.column * (cellWidth + gap)
            val y = paddingTop + cell.row * (cellHeight + gap)
            getChildAt(i)?.layout(x, y, x + cellWidth, y + cellHeight)
        }
    }

    /**
     * The cell of every shortcut: its own when it fits this screen and is free, otherwise the
     * first free one. A cell that does not fit (a smaller screen) is not forgotten: on a
     * larger screen the shortcut goes back there.
     */
    private fun place(): List<Cell> {
        val saved = Shortcuts.cells(context)
        val taken = HashSet<Cell>()
        val result = arrayOfNulls<Cell>(shortcuts.size)
        shortcuts.forEachIndexed { i, s ->
            val cell = saved[Shortcuts.key(s)] ?: return@forEachIndexed
            if (cell.column < columns && cell.row < rows && taken.add(cell)) result[i] = cell
        }
        var next = 0
        return result.mapIndexed { _, cell ->
            cell ?: run {
                while (Cell(next / rows, next % rows) in taken) next++
                Cell(next / rows, next % rows).also { taken += it }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cell = dropCell ?: return
        val x = paddingLeft + cell.column * (cellWidth + gap).toFloat()
        val y = paddingTop + cell.row * (cellHeight + gap).toFloat()
        dropRect.set(x, y, x + cellWidth, y + cellHeight)
        canvas.drawRoundRect(dropRect, dp(10f), dp(10f), dropPaint)
    }

    companion object {
        private const val FOLDER_COLOR = 0xFFFFC857.toInt()

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
