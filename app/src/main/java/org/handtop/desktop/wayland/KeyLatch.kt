package org.handtop.desktop.wayland

import android.view.KeyEvent

/**
 * Ctrl, Alt and Shift tapped on HandTop's key row: they hold for the next key only — one from
 * the row or a letter from the on-screen keyboard — like a terminal's extra keys.
 */
class KeyLatch {

    enum class Mod(val meta: Int) {
        CTRL(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        ALT(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
        SHIFT(KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
    }

    private val on = LinkedHashSet<Mod>()
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun isOn(mod: Mod) = mod in on

    /** Ctrl or Alt: the next letter makes a shortcut, not text. */
    fun holdsShortcut() = isOn(Mod.CTRL) || isOn(Mod.ALT)

    fun toggle(mod: Mod) {
        if (!on.remove(mod)) on.add(mod)
        changed()
    }

    /** The meta state of the latched modifiers. */
    fun metaState(): Int = on.fold(0) { meta, mod -> meta or mod.meta }

    /** The latched modifiers' meta state, for one key: they are released. */
    fun consume(): Int {
        val meta = metaState()
        if (on.isNotEmpty()) {
            on.clear()
            changed()
        }
        return meta
    }

    private fun changed() = listeners.toList().forEach { it() }
}
