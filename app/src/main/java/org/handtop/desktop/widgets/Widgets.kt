package org.handtop.desktop.widgets

import android.content.Context
import org.handtop.desktop.shell.Cell
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** A kind of desktop widget and the cells it takes. */
enum class WidgetKind(val key: String, val size: CellSize) {
    CLOCK("clock", CellSize(3, 1)),
    WEATHER("weather", CellSize(3, 2)),
    LINUX("linux", CellSize(3, 1)),
    PLAYER("player", CellSize(3, 2));

    companion object {
        fun fromKey(key: String?): WidgetKind? = entries.firstOrNull { it.key == key }
    }
}

/** A widget on the desktop: where the user put it (null: not yet) and its own settings. */
data class PlacedWidget(val kind: WidgetKind, val cell: Cell?, val settings: Map<String, String> = emptyMap())

/**
 * The user's desktop widgets, kept with the shortcuts in the app's preferences. A fresh or
 * upgraded desktop has none; each kind is on the desktop at most once.
 */
object Widgets {

    private const val PREFS = "desktop"
    private const val KEY = "widgets"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun load(context: Context): List<PlacedWidget> = fromJson(prefs(context).getString(KEY, null))

    /** Puts [kind] on the desktop at [cell]; nothing happens when it is already there. */
    fun add(context: Context, kind: WidgetKind, cell: Cell?) {
        val list = load(context)
        if (list.any { it.kind == kind }) return
        save(context, list + PlacedWidget(kind, cell))
    }

    fun remove(context: Context, kind: WidgetKind) = save(context, load(context).filterNot { it.kind == kind })

    fun move(context: Context, kind: WidgetKind, cell: Cell) =
        save(context, load(context).map { if (it.kind == kind) it.copy(cell = cell) else it })

    /** Sets one of [kind]'s settings; null removes it. */
    fun setSetting(context: Context, kind: WidgetKind, key: String, value: String?) = save(
        context,
        load(context).map {
            if (it.kind != kind) it
            else it.copy(settings = if (value == null) it.settings - key else it.settings + (key to value))
        },
    )

    fun toJson(list: List<PlacedWidget>): String = JSONArray().apply {
        for (w in list) put(
            JSONObject().apply {
                put("kind", w.kind.key)
                w.cell?.let { put("cell", JSONArray().put(it.column).put(it.row)) }
                if (w.settings.isNotEmpty()) put("settings", JSONObject(w.settings))
            },
        )
    }.toString()

    /** The widgets in [text]; nothing stored or anything unreadable is no widgets, unknown kinds are dropped. */
    fun fromJson(text: String?): List<PlacedWidget> {
        if (text == null) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val kind = WidgetKind.fromKey(o.optString("kind")) ?: return@mapNotNull null
                val cell = o.optJSONArray("cell")?.let { Cell(it.getInt(0), it.getInt(1)) }
                val settings = o.optJSONObject("settings")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } }.orEmpty()
                PlacedWidget(kind, cell, settings)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<PlacedWidget>) {
        prefs(context).edit().putString(KEY, toJson(list)).apply()
        listeners.forEach { it() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
