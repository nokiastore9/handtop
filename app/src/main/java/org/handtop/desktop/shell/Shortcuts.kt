package org.handtop.desktop.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Something on the desktop that opens with a tap. */
sealed class Shortcut {
    /** One of HandTop's own windows (see [Shortcuts.BUILT_IN]). */
    data class BuiltIn(val id: String) : Shortcut()

    /** A Linux program from the distribution's menu, by its desktop file id. */
    data class LinuxProgram(val appId: String, val name: String) : Shortcut()

    /** A folder, opened in Files. */
    data class Folder(val path: String, val name: String) : Shortcut()
}

/** A place on the desktop's grid of icons. */
data class Cell(val column: Int, val row: Int)

/**
 * The desktop's shortcuts, in order, kept in the app's preferences, with the grid cell the
 * user dragged each one to. A fresh desktop has HandTop's own windows; the user adds Linux
 * programs and folders and removes what they do not want, HandTop's own ones included (they
 * can be brought back).
 */
object Shortcuts {

    const val LINUX_TERMINAL = "linux_terminal"
    const val LINUX_APPS = "linux_apps"
    const val FILES = "files"
    const val ANDROID_TERMINAL = "android_terminal"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    val BUILT_IN = listOf(LINUX_TERMINAL, LINUX_APPS, FILES, ANDROID_TERMINAL, SETTINGS, ABOUT)

    private const val PREFS = "desktop"
    private const val KEY = "shortcuts"
    private const val KEY_CELLS = "shortcut_cells"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun load(context: Context): List<Shortcut> {
        val saved = prefs(context).getString(KEY, null) ?: return BUILT_IN.map { Shortcut.BuiltIn(it) }
        return try {
            val array = JSONArray(saved)
            List(array.length()) { array.getJSONObject(it) }.mapNotNull(::fromJson)
        } catch (_: Exception) {
            BUILT_IN.map { Shortcut.BuiltIn(it) }
        }
    }

    /** Adds [shortcut] at the end; false when it is already on the desktop. */
    fun add(context: Context, shortcut: Shortcut): Boolean {
        val list = load(context)
        if (list.any { same(it, shortcut) }) return false
        save(context, list + shortcut)
        return true
    }

    fun remove(context: Context, shortcut: Shortcut) {
        // A shortcut added again later goes to the first free cell, not to this one.
        val cells = cells(context) - key(shortcut)
        prefs(context).edit().putString(KEY_CELLS, cellsJson(cells)).apply()
        save(context, load(context).filterNot { same(it, shortcut) })
    }

    /** Identifies a shortcut among the others (for its cell). */
    fun key(shortcut: Shortcut): String = when (shortcut) {
        is Shortcut.BuiltIn -> "builtin:${shortcut.id}"
        is Shortcut.LinuxProgram -> "linux:${shortcut.appId}"
        is Shortcut.Folder -> "folder:${shortcut.path}"
    }

    /** Where the user put shortcuts (by [key]); a shortcut without a cell takes the first free one. */
    fun cells(context: Context): Map<String, Cell> = try {
        val json = JSONObject(prefs(context).getString(KEY_CELLS, null) ?: "{}")
        json.keys().asSequence().associateWith { key ->
            json.getJSONArray(key).let { Cell(it.getInt(0), it.getInt(1)) }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Remembers where every shortcut is now (after a drag). */
    fun setCells(context: Context, cells: Map<String, Cell>) {
        prefs(context).edit().putString(KEY_CELLS, cellsJson(cells)).apply()
        listeners.forEach { it() }
    }

    private fun cellsJson(cells: Map<String, Cell>) = JSONObject().apply {
        cells.forEach { (key, cell) -> put(key, JSONArray().put(cell.column).put(cell.row)) }
    }.toString()

    /** HandTop's own shortcuts that were removed. */
    fun missingBuiltIns(context: Context): List<String> {
        val present = load(context).filterIsInstance<Shortcut.BuiltIn>().map { it.id }.toSet()
        return BUILT_IN.filter { it !in present }
    }

    /** Puts HandTop's removed shortcuts back, in their usual order, before the user's own. */
    fun restoreBuiltIns(context: Context) {
        val list = load(context)
        val builtIns = BUILT_IN.map { Shortcut.BuiltIn(it) }
        save(context, builtIns + list.filter { it !is Shortcut.BuiltIn })
    }

    private fun same(a: Shortcut, b: Shortcut) = when {
        a is Shortcut.BuiltIn && b is Shortcut.BuiltIn -> a.id == b.id
        a is Shortcut.LinuxProgram && b is Shortcut.LinuxProgram -> a.appId == b.appId
        a is Shortcut.Folder && b is Shortcut.Folder -> a.path == b.path
        else -> false
    }

    private fun save(context: Context, list: List<Shortcut>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY, array.toString()).apply()
        listeners.forEach { it() }
    }

    private fun toJson(shortcut: Shortcut) = JSONObject().apply {
        when (shortcut) {
            is Shortcut.BuiltIn -> put("type", "builtin").put("id", shortcut.id)
            is Shortcut.LinuxProgram -> put("type", "linux").put("id", shortcut.appId).put("name", shortcut.name)
            is Shortcut.Folder -> put("type", "folder").put("path", shortcut.path).put("name", shortcut.name)
        }
    }

    private fun fromJson(json: JSONObject): Shortcut? = when (json.optString("type")) {
        "builtin" -> json.optString("id").takeIf { it in BUILT_IN }?.let { Shortcut.BuiltIn(it) }
        "linux" -> Shortcut.LinuxProgram(json.getString("id"), json.optString("name"))
        "folder" -> Shortcut.Folder(json.getString("path"), json.optString("name"))
        else -> null
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
