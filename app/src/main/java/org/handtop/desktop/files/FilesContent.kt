package org.handtop.desktop.files

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxApp
import org.handtop.desktop.linux.LinuxApps
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.ui.MenuEntry
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.styleListBox
import org.handtop.desktop.ui.styleField
import org.handtop.desktop.ui.showDialog
import org.handtop.desktop.ui.pushButton
import org.handtop.desktop.ui.dialogBuilder
import org.handtop.desktop.ui.bevel
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.Bevel
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.hideSystemBarsOfWindow
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.showPopupMenu
import org.handtop.desktop.wm.WindowContent
import org.handtop.desktop.wm.WindowHost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Date
import java.util.concurrent.Executors

/**
 * HandTop's file manager: the phone's storage, SD cards and the Linux file system in one
 * window. Files open in Android apps or in Linux programs (by MIME type, from their menu
 * entries); copy / move / rename / delete run in the background.
 *
 * Mouse: click selects (Ctrl / Shift add), double click opens, right click shows the menu.
 * Touch: tap opens, long press shows the menu. Keys: Enter, Delete, F2, F5, Backspace,
 * Alt+arrows, Ctrl+C / X / V / A / H.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FilesContent(
    context: Context,
    private val actions: Actions,
    start: File? = null,
) : LinearLayout(context), WindowContent {

    /** What the file manager asks the desktop for. */
    interface Actions {
        /** Opens [linuxPaths] with a Linux program. */
        fun openWithLinux(app: LinuxApp, linuxPaths: List<String>)
        /** A Linux terminal window whose shell starts in [linuxPath]. */
        fun openTerminalAt(linuxPath: String)
        /** Puts a shortcut to [dir] on the desktop. */
        fun addToDesktop(dir: File)
    }

    private class Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val mime: String) {
        val name: String get() = file.name
    }

    /** Copied or cut files, shared by all file manager windows. */
    private class Clip(val files: List<File>, val cut: Boolean)

    override val view: View get() = this

    private var host: WindowHost? = null
    private val main = Handler(Looper.getMainLooper())
    private var dir: File = start ?: FilePlaces.phoneRoot()
    private val history = ArrayList<File>()
    private var historyIndex = -1
    private var entries: List<Entry> = emptyList()
    private val selected = LinkedHashSet<File>()
    private var anchor = -1
    private var showHidden = false
    private var loadGeneration = 0
    private var busy = false

    // Last pointer input, for click handling (mouse or finger, modifiers, where).
    private var mouse = false
    private var ctrl = false
    private var shift = false
    private var rawX = 0f
    private var rawY = 0f
    private var lastClickFile: File? = null
    private var lastClickTime = 0L

    private val backButton = toolButton(R.drawable.ic_arrow_back, R.string.files_back) { goBack() }
    private val forwardButton = toolButton(R.drawable.ic_arrow_forward, R.string.files_forward) { goForward() }
    private val upButton = toolButton(R.drawable.ic_arrow_up, R.string.files_up) { goUp() }
    private val crumbs = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val crumbScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(crumbs) }
    private val sidebar = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dpi(6), 0, dpi(6)) }
    private val sidebarScroll = ScrollView(context).apply { addView(sidebar); styleListBox() }
    private val list = ListView(context)
    private val message = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; visibility = GONE }
    private val status = TextView(context)
    private val adapter = EntryAdapter()
    private val iconPx = dpi(24)

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.WINDOW_BG)
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS

        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4), 0, dpi(4), 0)
            setBackgroundColor(Palette.BAR_BG)
            addView(backButton)
            addView(forwardButton)
            addView(upButton)
            // Classic: the path sits in a sunken white address box.
            if (bevelled) crumbScroll.background = context.bevel(Bevel.SUNKEN, Palette.FIELD)
            addView(crumbScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpi(6)
                if (bevelled) setMargins(dpi(6), dpi(4), dpi(4), dpi(4))
            })
            addView(toolButton(R.drawable.ic_more, R.string.files_menu) { v ->
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                folderMenu(at[0].toFloat(), at[1] + v.height.toFloat())
            })
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(42)))

        list.adapter = adapter
        list.divider = null
        list.isFocusable = false
        list.selector = context.selectableBackground() ?: list.selector
        list.setOnTouchListener { _, e -> pointerDown(e) }
        list.setOnItemClickListener { _, _, position, _ -> itemClicked(position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            select(position, add = false, range = false)
            itemMenu(rawX, rawY)
            true
        }
        val listArea = FrameLayout(context).apply {
            // Classic: the files in a sunken white box.
            if (bevelled) background = context.bevel(Bevel.SUNKEN, Palette.FIELD)
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val gap = if (bevelled) dpi(4) else 0
            addView(sidebarScroll, LayoutParams(dpi(180), LayoutParams.MATCH_PARENT).apply { setMargins(gap, gap, 0, gap) })
            addView(listArea, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(gap, gap, gap, gap) })
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        status.apply {
            textSize = 12f
            setTextColor(Palette.TEXT_DIM)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            // Classic: a status bar with a thin sunken edge.
            background = if (bevelled) context.bevel(Bevel.SHALLOW, Palette.BAR_BG) else ColorDrawable(Palette.BAR_BG)
            setPadding(dpi(12), 0, dpi(12), 0)
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, dpi(26)))
        navigate(dir)
    }

    // ---- WindowContent ----

    override fun attach(host: WindowHost) {
        this.host = host
        updateTitle()
    }

    override fun onActivated() {
        requestFocus()
        load() // files may have changed, or storage access been granted meanwhile
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A narrow window (phone) gives the list the room; the places stay in the ⋮ menu.
        sidebarScroll.visibility = if (w < dpi(520)) GONE else VISIBLE
        adapter.notifyDataSetChanged() // the date column comes and goes with the width
    }

    // ---- navigation ----

    private fun navigate(target: File, record: Boolean = true) {
        val resolved = FilePlaces.resolve(context, target)
        if (record) {
            while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
            if (history.lastOrNull() != resolved) history += resolved
            historyIndex = history.size - 1
        }
        dir = resolved
        selected.clear()
        anchor = -1
        list.setSelection(0)
        load()
    }

    private fun goBack() {
        if (historyIndex <= 0) return
        historyIndex--
        navigate(history[historyIndex], record = false)
    }

    private fun goForward() {
        if (historyIndex >= history.size - 1) return
        historyIndex++
        navigate(history[historyIndex], record = false)
    }

    private fun goUp() {
        val parent = FilePlaces.parentOf(context, dir) ?: return
        val from = dir
        navigate(parent)
        selected += from // show where we came from
    }

    /** Lists [dir] in the background; a newer request wins. */
    private fun load() {
        val generation = ++loadGeneration
        val target = dir
        val hidden = showHidden
        val appContext = context.applicationContext
        updateChrome()
        worker.execute {
            val files = target.listFiles()
            val result = files?.filter { hidden || !it.name.startsWith(".") }?.map { f ->
                val real = FilePlaces.resolve(appContext, f)
                val isDir = real.isDirectory
                Entry(f, isDir, if (isDir) 0 else real.length(), real.lastModified(), FileKinds.mimeOf(f, isDir))
            }?.sortedWith(compareBy<Entry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            main.post {
                if (generation != loadGeneration) return@post
                entries = result ?: emptyList()
                selected.retainAll(entries.map { it.file }.toSet())
                adapter.notifyDataSetChanged()
                showMessage(files == null, result?.isEmpty() == true)
                updateStatus()
            }
        }
    }

    private fun showMessage(unreadable: Boolean, empty: Boolean) {
        message.removeAllViews()
        if (!unreadable && !empty) {
            message.visibility = GONE
            return
        }
        message.visibility = VISIBLE
        val noAccess = unreadable && FilePlaces.needsStorageAccess(context, dir) && !LinuxEnv.hasStorageAccess()
        message.addView(TextView(context).apply {
            setText(
                when {
                    noAccess -> R.string.files_no_access
                    unreadable -> R.string.files_unreadable
                    else -> R.string.files_empty
                },
            )
            setTextColor(Palette.TEXT_DIM)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dpi(24), 0, dpi(24), dpi(10))
        })
        if (noAccess) {
            message.addView(context.pushButton(context.getString(R.string.files_grant)) { LinuxEnv.requestStorageAccess(context) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun updateChrome() {
        backButton.isEnabled = historyIndex > 0
        backButton.alpha = if (backButton.isEnabled) 1f else 0.35f
        forwardButton.isEnabled = historyIndex < history.size - 1
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
        val canUp = FilePlaces.parentOf(context, dir) != null
        upButton.isEnabled = canUp
        upButton.alpha = if (canUp) 1f else 0.35f
        buildCrumbs()
        buildSidebar()
        updateTitle()
    }

    private fun updateTitle() {
        val name = FilePlaces.crumbs(context, dir).last().first
        host?.setTitle(context.getString(R.string.files_title, name))
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        val parts = FilePlaces.crumbs(context, dir)
        parts.forEachIndexed { i, (label, target) ->
            if (i > 0) crumbs.addView(TextView(context).apply {
                text = "›"
                setTextColor(Palette.TEXT_DIM)
                textSize = 14f
                setPadding(dpi(2), 0, dpi(2), 0)
            })
            crumbs.addView(TextView(context).apply {
                text = label
                textSize = 13f
                isSingleLine = true
                setTextColor(if (i == parts.lastIndex) Palette.TEXT else Palette.TEXT_SOFT)
                if (i == parts.lastIndex) setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(8), 0, dpi(8), 0)
                background = context.selectableBackground()
                setOnClickListener { if (target != dir) navigate(target) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dpi(30)))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun buildSidebar() {
        sidebar.removeAllViews()
        val places = FilePlaces.places(context)
        // The place that contains this folder most closely.
        val current = places.filter { dir.absolutePath == it.dir.absolutePath || dir.absolutePath.startsWith(it.dir.absolutePath + "/") }
            .maxByOrNull { it.dir.absolutePath.length }
        for (place in places) {
            val chosen = place === current
            sidebar.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(14), 0, dpi(10), 0)
                background = when {
                    !chosen -> context.selectableBackground()
                    bevelled -> ColorDrawable(Palette.SELECTION)
                    else -> roundedRect(Palette.SELECTION, dp(8f))
                }
                setOnClickListener { navigate(place.dir) }
                addView(ImageView(context).apply {
                    setImageResource(place.icon)
                    setColorFilter(if (!chosen) Palette.TEXT_SOFT else if (bevelled) Palette.SELECTION_TEXT else Palette.ACCENT)
                }, LayoutParams(dpi(18), dpi(18)))
                addView(TextView(context).apply {
                    text = place.label
                    textSize = 13f
                    setTextColor(if (chosen) Palette.SELECTION_TEXT else Palette.TEXT)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dpi(10), 0, 0, 0)
                })
            }, LayoutParams(LayoutParams.MATCH_PARENT, dpi(36)).apply { setMargins(dpi(6), 0, dpi(6), 0) })
        }
    }

    private fun updateStatus() {
        if (busy) return
        val free = try {
            Formatter.formatShortFileSize(context, StatFs(dir.path).availableBytes)
        } catch (_: IllegalArgumentException) {
            null
        }
        val text = if (selected.isNotEmpty()) {
            val bytes = entries.filter { it.file in selected && !it.isDir }.sumOf { it.size }
            context.resources.getQuantityString(R.plurals.files_selected, selected.size, selected.size) +
                if (bytes > 0) " · " + Formatter.formatShortFileSize(context, bytes) else ""
        } else {
            context.resources.getQuantityString(R.plurals.files_items, entries.size, entries.size)
        }
        status.text = if (free != null) text + " · " + context.getString(R.string.files_free, free) else text
    }

    // ---- input ----

    /** Remembers how the list is being touched; a right click opens a menu at once. */
    private fun pointerDown(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return false
        mouse = e.isFromSource(InputDevice.SOURCE_MOUSE)
        ctrl = e.metaState and KeyEvent.META_CTRL_ON != 0
        shift = e.metaState and KeyEvent.META_SHIFT_ON != 0
        rawX = e.rawX
        rawY = e.rawY
        requestFocus()
        if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
            val position = list.pointToPosition(e.x.toInt(), e.y.toInt())
            if (position == ListView.INVALID_POSITION) {
                selected.clear()
                adapter.notifyDataSetChanged()
                updateStatus()
                folderMenu(rawX, rawY)
            } else {
                if (entries[position].file !in selected) select(position, add = false, range = false)
                itemMenu(rawX, rawY)
            }
            return true
        }
        return false
    }

    private fun itemClicked(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        if (!mouse) {
            open(entry) // a finger tap opens, as on Android
            return
        }
        val now = SystemClock.uptimeMillis()
        val double = entry.file == lastClickFile && now - lastClickTime < DOUBLE_CLICK_MS && !ctrl && !shift
        lastClickFile = entry.file
        lastClickTime = now
        if (double) open(entry) else select(position, add = ctrl, range = shift)
    }

    private fun select(position: Int, add: Boolean, range: Boolean) {
        val entry = entries.getOrNull(position) ?: return
        when {
            range && anchor >= 0 -> {
                if (!add) selected.clear()
                for (i in minOf(anchor, position)..maxOf(anchor, position)) selected += entries[i].file
            }
            add -> {
                if (!selected.remove(entry.file)) selected += entry.file
                anchor = position
            }
            else -> {
                selected.clear()
                selected += entry.file
                anchor = position
            }
        }
        adapter.notifyDataSetChanged()
        updateStatus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(e: KeyEvent): Boolean {
        val first = selectedEntries().firstOrNull()
        when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> first?.let { open(it) }
            KeyEvent.KEYCODE_FORWARD_DEL -> confirmDelete()
            KeyEvent.KEYCODE_F2 -> first?.let { rename(it) }
            KeyEvent.KEYCODE_F5 -> load()
            KeyEvent.KEYCODE_DEL -> goBack()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (e.isAltPressed) goBack() else return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (e.isAltPressed) goForward() else return false
            KeyEvent.KEYCODE_DPAD_UP -> if (e.isAltPressed) goUp() else moveSelection(-1, e.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveSelection(1, e.isShiftPressed)
            KeyEvent.KEYCODE_C -> if (e.isCtrlPressed) clip(cut = false) else return false
            KeyEvent.KEYCODE_X -> if (e.isCtrlPressed) clip(cut = true) else return false
            KeyEvent.KEYCODE_V -> if (e.isCtrlPressed) paste() else return false
            KeyEvent.KEYCODE_A -> if (e.isCtrlPressed) selectAll() else return false
            KeyEvent.KEYCODE_H -> if (e.isCtrlPressed) toggleHidden() else return false
            KeyEvent.KEYCODE_N -> if (e.isCtrlPressed && e.isShiftPressed) newFolder() else return false
            else -> return false
        }
        return true
    }

    private fun moveSelection(step: Int, extend: Boolean) {
        if (entries.isEmpty()) return
        val chosen = entries.indices.filter { entries[it].file in selected }
        val from = when {
            chosen.isEmpty() -> if (step > 0) -1 else entries.size
            step > 0 -> chosen.last()
            else -> chosen.first()
        }
        val position = (from + step).coerceIn(0, entries.lastIndex)
        select(position, add = false, range = extend)
        list.setSelection(position)
    }

    private fun selectAll() {
        selected.clear()
        entries.forEach { selected += it.file }
        adapter.notifyDataSetChanged()
        updateStatus()
    }

    private fun toggleHidden() {
        showHidden = !showHidden
        load()
    }

    private fun selectedEntries() = entries.filter { it.file in selected }

    // ---- menus ----

    private fun itemMenu(x: Float, y: Float) {
        val chosen = selectedEntries()
        val single = chosen.singleOrNull()
        val items = ArrayList<MenuEntry?>()
        if (single != null) {
            items += MenuEntry(context.getString(R.string.files_open), FileKinds.iconOf(single.mime)) { open(single) }
            val linuxPath = FilePlaces.linuxPath(context, single.file)
            if (single.isDir) {
                if (linuxPath != null) {
                    for (app in linuxApps(single.mime).take(MAX_APPS)) {
                        items += MenuEntry(context.getString(R.string.files_open_in, app.name), R.drawable.ic_linux) {
                            actions.openWithLinux(app, listOf(linuxPath))
                        }
                    }
                    items += MenuEntry(context.getString(R.string.files_terminal_here), R.drawable.ic_terminal) { actions.openTerminalAt(linuxPath) }
                }
                items += MenuEntry(context.getString(R.string.shortcut_add), R.drawable.ic_home) { actions.addToDesktop(single.file) }
            } else {
                items += MenuEntry(context.getString(R.string.files_open_android), R.drawable.ic_phone) { openWithAndroid(single) }
                if (linuxPath != null) for (app in linuxApps(single.mime).take(MAX_APPS)) {
                    items += MenuEntry(context.getString(R.string.files_open_in, app.name), R.drawable.ic_linux) {
                        actions.openWithLinux(app, listOf(linuxPath))
                    }
                }
            }
            items += null
        } else if (chosen.size > 1) {
            // Several files: one Linux program can take them all (e.g. a playlist).
            val paths = chosen.mapNotNull { FilePlaces.linuxPath(context, it.file) }
            val mimes = chosen.map { it.mime }.distinct()
            if (paths.size == chosen.size && mimes.size == 1) for (app in linuxApps(mimes.single()).take(MAX_APPS)) {
                items += MenuEntry(context.getString(R.string.files_open_in, app.name), R.drawable.ic_linux) { actions.openWithLinux(app, paths) }
            }
            if (items.isNotEmpty()) items += null
        }
        items += MenuEntry(context.getString(R.string.files_cut)) { clip(cut = true) }
        items += MenuEntry(context.getString(R.string.files_copy)) { clip(cut = false) }
        if (single?.isDir == true) items += MenuEntry(context.getString(R.string.files_paste_into), enabled = clipboard != null) { paste(single.file) }
        if (single != null) items += MenuEntry(context.getString(R.string.files_rename)) { rename(single) }
        items += MenuEntry(context.getString(R.string.files_copy_path)) { copyPath(chosen) }
        items += null
        items += MenuEntry(context.getString(R.string.files_delete), tint = Palette.CLOSE_HOVER) { confirmDelete() }
        showPopupMenu(this, x, y, items)
    }

    private fun folderMenu(x: Float, y: Float) {
        val items = ArrayList<MenuEntry?>()
        items += MenuEntry(context.getString(R.string.files_new_folder), R.drawable.ic_folder) { newFolder() }
        items += MenuEntry(context.getString(R.string.files_paste), enabled = clipboard != null) { paste() }
        items += MenuEntry(context.getString(if (showHidden) R.string.files_hide_hidden else R.string.files_show_hidden)) { toggleHidden() }
        items += MenuEntry(context.getString(R.string.files_refresh)) { load() }
        FilePlaces.linuxPath(context, dir)?.let { path ->
            items += null
            items += MenuEntry(context.getString(R.string.files_terminal_here), R.drawable.ic_terminal) { actions.openTerminalAt(path) }
            for (app in linuxApps("inode/directory").take(MAX_APPS)) {
                items += MenuEntry(context.getString(R.string.files_open_in, app.name), R.drawable.ic_linux) { actions.openWithLinux(app, listOf(path)) }
            }
        }
        if (sidebarScroll.visibility != VISIBLE) {
            // Narrow window: the places live here.
            items += null
            for (place in FilePlaces.places(context)) items += MenuEntry(place.label, place.icon) { navigate(place.dir) }
        }
        showPopupMenu(this, x, y, items)
    }

    private fun linuxApps(mime: String): List<LinuxApp> =
        if (LinuxEnv.isInstalled(context)) LinuxApps.appsFor(context, mime).filter { !it.terminal || mime != "inode/directory" } else emptyList()

    // ---- opening ----

    private fun open(entry: Entry) {
        if (entry.isDir) {
            navigate(entry.file)
            return
        }
        // Linux files open in Linux programs, phone files in Android apps (both: menu).
        val linuxPath = FilePlaces.linuxPath(context, entry.file)
        val app = if (linuxPath != null && FilePlaces.isInLinux(context, entry.file)) linuxApps(entry.mime).firstOrNull() else null
        if (app != null) actions.openWithLinux(app, listOf(linuxPath!!)) else openWithAndroid(entry)
    }

    private fun openWithAndroid(entry: Entry) {
        val file = FilePlaces.resolve(context, entry.file)
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (_: IllegalArgumentException) {
            setStatus(context.getString(R.string.files_no_app))
            return
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, entry.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // On the display HandTop is on (DeX or the phone).
        val options = ActivityOptions.makeBasic()
        display?.let { options.launchDisplayId = it.displayId }
        try {
            context.startActivity(chooser, options.toBundle())
        } catch (_: ActivityNotFoundException) {
            setStatus(context.getString(R.string.files_no_app))
        }
    }

    // ---- operations ----

    private fun clip(cut: Boolean) {
        val files = selectedEntries().map { it.file }
        if (files.isEmpty()) return
        clipboard = Clip(files, cut)
        setStatus(context.resources.getQuantityString(if (cut) R.plurals.files_cut_done else R.plurals.files_copied, files.size, files.size))
    }

    private fun paste(into: File = dir) {
        val clip = clipboard ?: return
        if (clip.cut) clipboard = null
        runOperation(context.getString(if (clip.cut) R.string.files_moving else R.string.files_copying)) { progress ->
            for (file in clip.files) {
                if (clip.cut) FileOps.move(file, into, progress) else FileOps.copy(file, into, progress)
            }
        }
    }

    private fun confirmDelete() {
        val chosen = selectedEntries()
        if (chosen.isEmpty()) return
        val question = if (chosen.size == 1) context.getString(R.string.files_delete_one, chosen.single().name)
        else context.resources.getQuantityString(R.plurals.files_delete_many, chosen.size, chosen.size)
        showDialog(
            context.dialogBuilder()
                .setTitle(R.string.files_delete)
                .setMessage(question)
                .setPositiveButton(R.string.files_delete) { _, _ ->
                    runOperation(context.getString(R.string.files_deleting)) { _ -> chosen.forEach { FileOps.delete(it.file) } }
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun rename(entry: Entry) {
        val field = nameField(entry.name)
        // Select the name without its extension, as desktops do.
        val dot = entry.name.lastIndexOf('.').takeIf { it > 0 && !entry.isDir } ?: entry.name.length
        field.setSelection(0, dot)
        showDialog(
            context.dialogBuilder()
                .setTitle(R.string.files_rename)
                .setView(padded(field))
                .setPositiveButton(R.string.files_rename) { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isEmpty() || name == entry.name || '/' in name) return@setPositiveButton
                    val target = File(entry.file.parentFile, name)
                    runOperation(context.getString(R.string.files_renaming)) { _ ->
                        if (target.exists() || !entry.file.renameTo(target)) throw IOException(context.getString(R.string.files_name_taken, name))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun newFolder() {
        val field = nameField(context.getString(R.string.files_new_folder_name))
        field.selectAll()
        showDialog(
            context.dialogBuilder()
                .setTitle(R.string.files_new_folder)
                .setView(padded(field))
                .setPositiveButton(R.string.files_create) { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isEmpty() || '/' in name) return@setPositiveButton
                    val target = File(dir, name)
                    runOperation(context.getString(R.string.files_creating)) { _ ->
                        if (target.exists() || !target.mkdir()) throw IOException(context.getString(R.string.files_name_taken, name))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    private fun copyPath(chosen: List<Entry>) {
        val text = chosen.joinToString("\n") { FilePlaces.linuxPath(context, it.file) ?: it.file.absolutePath }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("path", text))
    }

    /** Runs [work] in the background with [label] in the status line, then reloads. */
    private fun runOperation(label: String, work: (progress: (String) -> Unit) -> Unit) {
        busy = true
        status.text = label
        worker.execute {
            val error = try {
                work { name -> main.post { status.text = "$label $name" } }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                busy = false
                load()
                if (error != null) setStatus(context.getString(R.string.files_failed, error))
            }
        }
    }

    private fun setStatus(text: String) {
        status.text = text
        main.removeCallbacks(restoreStatus)
        main.postDelayed(restoreStatus, STATUS_MESSAGE_MS)
    }

    private val restoreStatus = Runnable { updateStatus() }

    private fun nameField(text: String) = EditText(context).apply {
        setText(text)
        setSingleLine()
        styleField()
    }

    private fun padded(view: View) = FrameLayout(context).apply {
        setPadding(dpi(20), dpi(8), dpi(20), 0)
        addView(view)
    }

    /** Drawn in the classic 3D style. */
    private val bevelled get() = Themes.current.bevelled

    // ---- the list ----

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? Row ?: Row()
            row.bind(entries[position])
            return row
        }
    }

    private inner class Row : LinearLayout(this@FilesContent.context) {
        private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val name = TextView(context).apply {
            textSize = 14f
            setTextColor(Palette.TEXT)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dpi(12), 0, dpi(8), 0)
        }
        private val size = TextView(context).apply {
            textSize = 12f
            setTextColor(Palette.TEXT_DIM)
            gravity = Gravity.END
            isSingleLine = true
        }
        private val date = TextView(context).apply {
            textSize = 12f
            setTextColor(Palette.TEXT_DIM)
            gravity = Gravity.END
            isSingleLine = true
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), 0, dpi(14), 0)
            addView(icon, LayoutParams(iconPx, iconPx))
            addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(size, LayoutParams(dpi(76), LayoutParams.WRAP_CONTENT))
            addView(date, LayoutParams(dpi(132), LayoutParams.WRAP_CONTENT))
            layoutParams = android.widget.AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, dpi(38))
        }

        fun bind(entry: Entry) {
            name.text = entry.name
            size.text = if (entry.isDir) "" else Formatter.formatShortFileSize(context, entry.size)
            val wide = this@FilesContent.width == 0 || this@FilesContent.width >= dpi(640)
            date.visibility = if (wide) VISIBLE else GONE
            if (wide) date.text = DateFormat.getDateFormat(context).format(Date(entry.modified)) + " " +
                DateFormat.getTimeFormat(context).format(Date(entry.modified))
            val picked = entry.file in selected
            setBackgroundColor(if (picked) Palette.SELECTION else 0)
            name.setTextColor(if (picked) Palette.SELECTION_TEXT else Palette.TEXT)
            val minor = if (picked && bevelled) Palette.SELECTION_TEXT else Palette.TEXT_DIM
            size.setTextColor(minor)
            date.setTextColor(minor)
            // Kind icon, replaced by a thumbnail for pictures and videos when there is one.
            icon.tag = entry.file.path
            val thumb = thumbnails.get(thumbKey(entry))
            if (thumb != null) {
                icon.clearColorFilter()
                icon.setImageBitmap(thumb)
            } else {
                icon.setImageResource(FileKinds.iconOf(entry.mime))
                icon.setColorFilter(FileKinds.colorOf(entry.mime))
                if (entry.mime.startsWith("image/") || entry.mime.startsWith("video/")) loadThumbnail(entry, icon)
            }
        }
    }

    private fun thumbKey(entry: Entry) = "${entry.file.path}@${entry.modified}"

    private fun loadThumbnail(entry: Entry, view: ImageView) {
        val key = thumbKey(entry)
        if (!pendingThumbs.add(key)) return
        val size = iconPx * 2
        thumbWorker.execute {
            val bitmap = try {
                thumbnail(entry, size)
            } catch (_: Exception) {
                null
            }
            main.post {
                pendingThumbs.remove(key)
                if (bitmap == null) return@post
                thumbnails.put(key, bitmap)
                if (view.tag == entry.file.path) {
                    view.clearColorFilter()
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun thumbnail(entry: Entry, size: Int): Bitmap? {
        val file = FilePlaces.resolve(context, entry.file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return if (entry.mime.startsWith("video/")) ThumbnailUtils.createVideoThumbnail(file, Size(size, size), null)
            else ThumbnailUtils.createImageThumbnail(file, Size(size, size), null)
        }
        if (!entry.mime.startsWith("image/")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun toolButton(icon: Int, label: Int, onClick: (View) -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(Palette.TEXT_SOFT)
        contentDescription = context.getString(label)
        tooltipText = contentDescription
        background = if (bevelled) Themes.current.toolButton(context) else context.selectableBackground(borderless = true)
        isFocusable = false
        setOnClickListener { onClick(it) }
        layoutParams = LayoutParams(dpi(38), dpi(38))
    }

    private companion object {
        const val DOUBLE_CLICK_MS = 400L
        const val STATUS_MESSAGE_MS = 4000L
        const val MAX_APPS = 6

        var clipboard: Clip? = null
        val worker = Executors.newSingleThreadExecutor()
        val thumbWorker = Executors.newFixedThreadPool(2)
        val pendingThumbs = HashSet<String>()
        val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
