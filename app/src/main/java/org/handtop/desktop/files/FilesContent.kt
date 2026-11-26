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
import android.graphics.drawable.Drawable
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.util.Size
import android.view.DragEvent
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import org.handtop.desktop.ui.setThemedIcon
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
import org.handtop.desktop.ui.selectionMark
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
 * Touch: tap opens, long press shows the menu; a tap on a file's icon (or "Select" in its
 * menu) starts picking files, then taps pick them and a bar at the top acts on them.
 * Search (the magnifier, Ctrl+F) finds files by name in the folder and the folders inside it.
 * Drag and drop (a long press then a move, or a mouse drag): onto a folder, another file
 * manager window (asking: move, copy) or the desktop (folders become shortcuts).
 * Keys: Enter, Delete, F2, F5, Backspace, Alt+arrows, Ctrl+C / X / V / A / H / F.
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
    private val selection = FileSelection<File>()
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
    private val selectionText = TextView(context)
    private val selectionBar = LinearLayout(context)
    private val toolbar = LinearLayout(context)
    private val searchField = EditText(context)
    private val searchButton = toolButton(R.drawable.ic_search, R.string.files_search) { if (searching) endSearch() else startSearch() }
    /** The search field is shown (in place of the path). */
    private var searching = false
    private var searchQuery = ""
    /** The folder searched in: results show their place relative to it. */
    private var searchRoot: File = dir
    private var searchRunning = false
    private var searchGeneration = 0
    private val runSearch = Runnable { search() }

    // Drag and drop: where the finger went down, and the drop target being shown.
    private var downX = 0f
    private var downY = 0f
    private var pressedPosition = ListView.INVALID_POSITION
    /** A long press waits for the finger: lifted, the menu; moved, a drag. */
    private var longPressed = false
    private var dragging = false
    private var dropFolder: File? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private lateinit var listArea: FrameLayout
    private val adapter = EntryAdapter()
    private val iconPx = dpi(24)

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.WINDOW_BG)
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS

        toolbar.apply {
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
            searchField.apply {
                visibility = GONE
                setSingleLine()
                textSize = 14f
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setTextColor(Palette.TEXT)
                setHintTextColor(Palette.TEXT_DIM)
                if (bevelled) styleField() else {
                    background = roundedRect(Palette.FIELD_BG, dp(8f))
                    setPadding(dpi(10), 0, dpi(10), 0)
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(text: Editable?) {
                        if (!searching) return
                        searchQuery = text?.toString().orEmpty()
                        main.removeCallbacks(runSearch)
                        main.postDelayed(runSearch, SEARCH_DELAY_MS)
                    }
                })
                setOnEditorActionListener { _, _, _ ->
                    hideKeyboard()
                    true
                }
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN) {
                        endSearch()
                        true
                    } else {
                        false
                    }
                }
            }
            addView(searchField, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dpi(6), dpi(4), dpi(4), dpi(4)) })
            addView(searchButton)
            addView(toolButton(R.drawable.ic_more, R.string.files_menu) { v ->
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                folderMenu(at[0].toFloat(), at[1] + v.height.toFloat())
            })
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(42)))

        // Picking files with a finger: in place of the toolbar (as Android apps do; the bottom
        // corners of a window are its resize grips), what is picked and what to do with it.
        selectionBar.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            setPadding(dpi(4), 0, dpi(4), 0)
            setBackgroundColor(Palette.BAR_BG)
            addView(toolButton(R.drawable.ic_win_close, R.string.files_select_done) { endSelection() })
            selectionText.apply {
                textSize = 14f
                setTextColor(Palette.TEXT)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(8), 0, dpi(8), 0)
            }
            addView(selectionText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = context.getString(R.string.files_select_all)
                textSize = 14f
                setTextColor(Palette.TEXT)
                gravity = Gravity.CENTER
                setPadding(dpi(12), 0, dpi(12), 0)
                background = if (bevelled) Themes.current.toolButton(context) else context.selectableBackground(borderless = true)
                setOnClickListener { selectAll() }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dpi(38)))
            addView(toolButton(R.drawable.ic_more, R.string.files_actions) { v ->
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                itemMenu(at[0].toFloat(), at[1] + v.height.toFloat())
            })
        }
        addView(selectionBar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(42)))

        list.adapter = adapter
        list.divider = null
        list.isFocusable = false
        list.selector = context.selectableBackground() ?: list.selector
        list.setOnTouchListener { _, e -> listTouch(e) }
        list.setOnDragListener { _, e -> onDrag(e) }
        list.setOnItemClickListener { _, _, position, _ -> itemClicked(position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (selection.touchMode) {
                // The menu acts on the picked files; the pressed one joins them.
                if (entries[position].file !in selection) selection.tap(files, position)
                selectionChanged()
            } else {
                select(position, add = false, range = false)
            }
            // A mouse gets the menu at once; a finger's waits: a move instead starts a drag.
            if (mouse) itemMenu(rawX, rawY) else longPressed = true
            true
        }
        listArea = FrameLayout(context).apply {
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
        openWindows += this
        updateTitle()
    }

    override fun onClosed() {
        openWindows -= this
    }

    override val takesBack: Boolean get() = selection.touchMode || searching

    /** Back ends picking files, then the search. */
    override fun back() = if (selection.touchMode) endSelection() else endSearch()

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
        selection.clear()
        closeSearch()
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
        selection.highlight(from) // show where we came from
    }

    /** Shows [dir] again: the search results while searching, else the folder. */
    private fun load() {
        if (searching && searchQuery.isNotBlank()) search() else listFolder()
    }

    /** Lists [dir] in the background; a newer request wins. */
    private fun listFolder() {
        val generation = ++loadGeneration
        val target = dir
        val hidden = showHidden
        val appContext = context.applicationContext
        updateChrome()
        worker.execute {
            val files = target.listFiles()
            val result = files?.filter { hidden || !it.name.startsWith(".") }?.map { entryOf(appContext, it) }?.sortedWith(compareBy<Entry> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            main.post {
                if (generation != loadGeneration || searching && searchQuery.isNotBlank()) return@post
                entries = result ?: emptyList()
                selection.retain(entries.map { it.file })
                adapter.notifyDataSetChanged()
                showMessage(files == null, result?.isEmpty() == true)
                updateStatus()
            }
        }
    }

    private fun entryOf(appContext: Context, f: File): Entry {
        val real = FilePlaces.resolve(appContext, f)
        val isDir = real.isDirectory
        return Entry(f, isDir, if (isDir) 0 else real.length(), real.lastModified(), FileKinds.mimeOf(f, isDir))
    }

    // ---- search ----

    private fun startSearch() {
        if (selection.touchMode) endSelection()
        searching = true
        searchRoot = dir
        searchQuery = ""
        crumbScroll.visibility = GONE
        searchField.visibility = VISIBLE
        searchField.hint = context.getString(R.string.files_search_hint, FilePlaces.crumbs(context, dir).last().first)
        searchField.setText("")
        searchButton.setThemedIcon(R.drawable.ic_win_close, Palette.TEXT_SOFT)
        searchButton.contentDescription = context.getString(R.string.files_search_close)
        searchField.requestFocus()
        searchField.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(searchField, 0) }
    }

    /** Runs the search for [searchQuery] in the background; a newer one wins. */
    private fun search() {
        val query = searchQuery.trim()
        val generation = ++searchGeneration
        if (query.isEmpty()) {
            searchRunning = false
            listFolder()
            return
        }
        val root = searchRoot
        val hidden = showHidden
        val appContext = context.applicationContext
        searchRunning = true
        updateStatus()
        searchWorker.execute {
            val found = FileSearch.search(root, query, hidden, SEARCH_LIMIT) { generation != searchGeneration }
                .map { entryOf(appContext, it) }
            main.post {
                if (generation != searchGeneration || !searching) return@post
                searchRunning = false
                entries = found
                selection.retain(entries.map { it.file })
                adapter.notifyDataSetChanged()
                showMessage(unreadable = false, empty = found.isEmpty())
                updateStatus()
            }
        }
    }

    /** Hides the search field; the caller shows the folder. */
    private fun closeSearch() {
        if (!searching) return
        searching = false
        searchRunning = false
        searchGeneration++ // a search still running is dropped
        searchQuery = ""
        main.removeCallbacks(runSearch)
        hideKeyboard()
        searchField.visibility = GONE
        crumbScroll.visibility = VISIBLE
        searchButton.setThemedIcon(R.drawable.ic_search, Palette.TEXT_SOFT)
        searchButton.contentDescription = context.getString(R.string.files_search)
        requestFocus()
    }

    private fun endSearch() {
        closeSearch()
        listFolder()
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    /** Where a search result is, relative to the folder searched ("" when right in it). */
    private fun placeOf(entry: Entry): String =
        entry.file.parentFile?.relativeToOrNull(searchRoot)?.path?.takeIf { it.isNotEmpty() } ?: ""

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
                    searching -> R.string.files_nothing_found
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
                    setThemedIcon(place.icon, if (chosen) Palette.ACCENT else Palette.TEXT_SOFT)
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
        val picking = selection.touchMode
        selectionBar.visibility = if (picking) VISIBLE else GONE
        toolbar.visibility = if (picking) GONE else VISIBLE
        if (picking) selectionText.text = selectedSummary()
        if (busy) return
        val free = try {
            Formatter.formatShortFileSize(context, StatFs(dir.path).availableBytes)
        } catch (_: IllegalArgumentException) {
            null
        }
        val text = if (selection.size > 0) {
            selectedSummary()
        } else if (searching && searchQuery.isNotBlank()) {
            if (searchRunning) context.getString(R.string.files_searching)
            else context.resources.getQuantityString(R.plurals.files_found, entries.size, entries.size)
        } else {
            context.resources.getQuantityString(R.plurals.files_items, entries.size, entries.size)
        }
        status.text = if (free != null) text + " · " + context.getString(R.string.files_free, free) else text
    }

    // ---- input ----

    /** The list's touches: what [pointerDown] notes, and drags after a long press or with a mouse. */
    private fun listTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                pressedPosition = list.pointToPosition(e.x.toInt(), e.y.toInt())
                longPressed = false
                dragging = false
                return pointerDown(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging || pressedPosition == ListView.INVALID_POSITION) return longPressed
                val moved = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > touchSlop
                val mouseDrag = mouse && e.buttonState and MotionEvent.BUTTON_PRIMARY != 0
                if (moved && (longPressed || mouseDrag)) {
                    startDrag(pressedPosition)
                    return true
                }
                return longPressed // after a long press the list does not scroll
            }
            MotionEvent.ACTION_UP -> if (longPressed && !dragging) {
                longPressed = false
                itemMenu(e.rawX, e.rawY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> longPressed = false
        }
        return false
    }

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
                selection.clear()
                selectionChanged()
                folderMenu(rawX, rawY)
            } else {
                if (entries[position].file !in selection) select(position, add = false, range = false)
                itemMenu(rawX, rawY)
            }
            return true
        }
        return false
    }

    private fun itemClicked(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        if (!mouse) {
            when {
                selection.tap(files, position) -> selectionChanged()
                tappedIcon() -> {
                    selection.startTouch(files, position)
                    selectionChanged()
                }
                else -> open(entry) // a finger tap opens, as on Android
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        val double = entry.file == lastClickFile && now - lastClickTime < DOUBLE_CLICK_MS && !ctrl && !shift
        lastClickFile = entry.file
        lastClickTime = now
        if (double) open(entry) else select(position, add = ctrl, range = shift)
    }

    private fun select(position: Int, add: Boolean, range: Boolean) {
        selection.click(files, position, add, range)
        selectionChanged()
    }

    /** The last finger tap landed on a file's icon (the left of its row): it picks the file. */
    private fun tappedIcon(): Boolean {
        val at = IntArray(2).also { list.getLocationOnScreen(it) }
        return rawX - at[0] < dpi(ICON_ZONE_DP)
    }

    private fun endSelection() {
        selection.clear()
        selectionChanged()
    }

    private fun selectionChanged() {
        adapter.notifyDataSetChanged()
        updateStatus()
    }

    private fun selectedSummary(): String {
        val bytes = entries.filter { it.file in selection && !it.isDir }.sumOf { it.size }
        return context.resources.getQuantityString(R.plurals.files_selected, selection.size, selection.size) +
            if (bytes > 0) " · " + Formatter.formatShortFileSize(context, bytes) else ""
    }

    /** The files of the folder, in list order. */
    private val files: List<File> get() = entries.map { it.file }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Typing a search: Backspace, arrows and Enter belong to the field.
        if (searchField.hasFocus()) return super.dispatchKeyEvent(event)
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
            KeyEvent.KEYCODE_F -> if (e.isCtrlPressed) startSearch() else return false
            KeyEvent.KEYCODE_N -> if (e.isCtrlPressed && e.isShiftPressed) newFolder() else return false
            else -> return false
        }
        return true
    }

    private fun moveSelection(step: Int, extend: Boolean) {
        if (entries.isEmpty()) return
        val chosen = entries.indices.filter { entries[it].file in selection }
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
        selection.selectAll(files)
        selectionChanged()
    }

    private fun toggleHidden() {
        showHidden = !showHidden
        load()
    }

    private fun selectedEntries() = entries.filter { it.file in selection }

    // ---- drag and drop ----

    /** Drags the file at [position], with the other selected files if it is one of them. */
    private fun startDrag(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        if (entry.file !in selection) select(position, add = false, range = false)
        val dragged = selectedEntries().map { it.file }
        if (dragged.isEmpty()) return
        longPressed = false
        dragging = true
        val label = if (dragged.size == 1) dragged.single().name
        else context.resources.getQuantityString(R.plurals.files_items, dragged.size, dragged.size)
        val icon = (list.getChildAt(position - list.firstVisiblePosition) as? Row)?.iconDrawable()
        // The Linux paths as text, for whatever may take text one day (a terminal).
        val clip = ClipData.newPlainText(label, dragged.joinToString("\n") { FilePlaces.linuxPath(context, it) ?: it.path })
        if (!list.startDragAndDrop(clip, DragChip(label, icon), FileDrag(dragged), 0)) dragging = false
    }

    private fun onDrag(e: DragEvent): Boolean {
        val drag = e.localState as? FileDrag ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> showDropTarget(drag, e.x, e.y)
            DragEvent.ACTION_DRAG_EXITED -> hideDropTarget()
            DragEvent.ACTION_DRAG_ENDED -> {
                hideDropTarget()
                dragging = false
            }
            DragEvent.ACTION_DROP -> {
                val target = dropTargetAt(drag, e.x, e.y)
                hideDropTarget()
                if (target == null) return false
                val at = IntArray(2).also { list.getLocationOnScreen(it) }
                dropMenu(drag.files, target, at[0] + e.x, at[1] + e.y)
            }
        }
        return true
    }

    /** The folder dropped files would go to at ([x], [y]) in the list, or null. */
    private fun dropTargetAt(drag: FileDrag, x: Float, y: Float): File? {
        val folder = entries.getOrNull(list.pointToPosition(x.toInt(), y.toInt()))?.takeIf { it.isDir }?.file
        return DropRules.target(drag.files, folder, if (searching) searchRoot else dir)
    }

    /** A folder row lights up, or the whole list when the shown folder takes the files. */
    private fun showDropTarget(drag: FileDrag, x: Float, y: Float) {
        val target = dropTargetAt(drag, x, y)
        val rowTarget = target?.takeIf { t -> entries.any { it.file == t } }
        if (rowTarget != dropFolder) {
            dropFolder = rowTarget
            adapter.notifyDataSetChanged()
        }
        listArea.foreground = if (target != null && rowTarget == null) {
            roundedRect(0, dp(4f), Themes.current.colors.accent, dpi(2))
        } else {
            null
        }
    }

    private fun hideDropTarget() {
        listArea.foreground = null
        if (dropFolder != null) {
            dropFolder = null
            adapter.notifyDataSetChanged()
        }
    }

    private fun dropMenu(files: List<File>, into: File, x: Float, y: Float) {
        showPopupMenu(this, x, y, listOf(
            MenuEntry(context.getString(R.string.files_move_here)) { transfer(files, into, move = true) },
            MenuEntry(context.getString(R.string.files_copy_here)) { transfer(files, into, move = false) },
            null,
            MenuEntry(context.getString(android.R.string.cancel)) {},
        ))
    }

    private fun transfer(files: List<File>, into: File, move: Boolean) {
        runOperation(context.getString(if (move) R.string.files_moving else R.string.files_copying)) { progress ->
            for (file in files) if (move) FileOps.move(file, into, progress) else FileOps.copy(file, into, progress)
        }
    }

    /** What follows the finger: the file's icon and name (or how many), above the finger. */
    private inner class DragChip(private val label: String, private val icon: Drawable?) : View.DragShadowBuilder() {
        private val pad = dpi(10)
        private val iconSize = dpi(24)
        private val lift = dpi(20)
        private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(14f)
            color = Palette.TEXT
        }
        private val panel = Themes.current.panel(context, 8f)
        private val chipWidth = pad * 3 + iconSize + text.measureText(label).toInt().coerceAtMost(dpi(240))
        private val chipHeight = iconSize + pad * 2

        override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
            size.set(chipWidth, chipHeight + lift)
            touch.set(pad + iconSize / 2, chipHeight + lift - 1)
        }

        override fun onDrawShadow(canvas: android.graphics.Canvas) {
            panel.setBounds(0, 0, chipWidth, chipHeight)
            panel.draw(canvas)
            icon?.let {
                it.setBounds(pad, pad, pad + iconSize, pad + iconSize)
                it.draw(canvas)
            }
            val baseline = chipHeight / 2f - (text.descent() + text.ascent()) / 2
            canvas.save()
            canvas.clipRect(0, 0, chipWidth - pad, chipHeight)
            canvas.drawText(label, (pad * 2 + iconSize).toFloat(), baseline, text)
            canvas.restore()
        }
    }

    // ---- menus ----

    private fun itemMenu(x: Float, y: Float) {
        val chosen = selectedEntries()
        val single = chosen.singleOrNull()
        val items = ArrayList<MenuEntry?>()
        if (single != null && !mouse && !selection.touchMode) {
            items += MenuEntry(context.getString(R.string.files_select)) {
                selection.startTouch(files, entries.indexOf(single))
                selectionChanged()
            }
        }
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
        // On the display HandTop is on (DeX or the phone).
        if (!AndroidOpen.file(context, FilePlaces.resolve(context, entry.file), entry.mime, entry.name, display?.displayId)) {
            setStatus(context.getString(R.string.files_no_app))
        }
    }

    // ---- operations ----

    private fun clip(cut: Boolean) {
        val files = selectedEntries().map { it.file }
        if (files.isEmpty()) return
        clipboard = Clip(files, cut)
        // Picked with a finger: done picking, the next taps go to the folder to paste into.
        if (selection.touchMode) endSelection()
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
                // A move empties the folder it came from, maybe shown in another window.
                openWindows.filter { it !== this }.forEach { it.load() }
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

        /** A copy of the icon shown (kind or thumbnail), for the drag. */
        fun iconDrawable(): Drawable? = icon.drawable?.constantState?.newDrawable(resources)?.mutate()?.also {
            it.colorFilter = icon.colorFilter
        }

        fun bind(entry: Entry) {
            val place = if (searching) placeOf(entry) else ""
            name.text = if (place.isEmpty()) entry.name else SpannableString("${entry.name}   $place").apply {
                setSpan(ForegroundColorSpan(Palette.TEXT_DIM), entry.name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            size.text = if (entry.isDir) "" else Formatter.formatShortFileSize(context, entry.size)
            val wide = this@FilesContent.width == 0 || this@FilesContent.width >= dpi(640)
            date.visibility = if (wide) VISIBLE else GONE
            if (wide) date.text = DateFormat.getDateFormat(context).format(Date(entry.modified)) + " " +
                DateFormat.getTimeFormat(context).format(Date(entry.modified))
            val picked = entry.file in selection || entry.file == dropFolder
            setBackgroundColor(if (picked) Palette.SELECTION else 0)
            name.setTextColor(if (picked) Palette.SELECTION_TEXT else Palette.TEXT)
            val minor = if (picked && bevelled) Palette.SELECTION_TEXT else Palette.TEXT_DIM
            size.setTextColor(minor)
            date.setTextColor(minor)
            // Picking files with a finger: a mark instead of the icon.
            if (selection.touchMode) {
                icon.tag = null
                icon.clearColorFilter()
                icon.setImageDrawable(context.selectionMark(picked))
                return
            }
            // Kind icon, replaced by a thumbnail for pictures and videos when there is one.
            icon.tag = entry.file.path
            val thumb = thumbnails.get(thumbKey(entry))
            if (thumb != null) {
                icon.clearColorFilter()
                icon.setImageBitmap(thumb)
            } else {
                icon.setThemedIcon(FileKinds.iconOf(entry.mime), FileKinds.colorOf(entry.mime))
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
        setThemedIcon(icon, Palette.TEXT_SOFT)
        contentDescription = context.getString(label)
        tooltipText = contentDescription
        background = if (bevelled) Themes.current.toolButton(context) else context.selectableBackground(borderless = true)
        isFocusable = false
        setOnClickListener { onClick(it) }
        layoutParams = LayoutParams(dpi(38), dpi(38))
    }

    private companion object {
        const val DOUBLE_CLICK_MS = 400L
        /** A finger tap this close to a row's left edge lands on its icon. */
        const val ICON_ZONE_DP = 52
        const val STATUS_MESSAGE_MS = 4000L
        const val MAX_APPS = 6
        const val SEARCH_DELAY_MS = 300L
        const val SEARCH_LIMIT = 500

        var clipboard: Clip? = null
        val worker = Executors.newSingleThreadExecutor()
        val searchWorker = Executors.newSingleThreadExecutor()
        /** The file manager windows open now: a move refreshes them all. */
        val openWindows = LinkedHashSet<FilesContent>()
        val thumbWorker = Executors.newFixedThreadPool(2)
        val pendingThumbs = HashSet<String>()
        val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
