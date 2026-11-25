package org.handtop.desktop.ui

import android.widget.ImageView
import androidx.annotation.DrawableRes
import org.handtop.desktop.R

/**
 * The classic theme's icons: for each of HandTop's icons `ic_x` a colour one, `classic_ic_x`
 * (drawn by tools/icons/make_classic_icons.py). Found by name once, from the resource table.
 */
object ClassicIcons {

    private val map: Map<Int, Int> by lazy {
        val ids = R.drawable::class.java.fields.associate { it.name to it.getInt(null) }
        ids.filterKeys { it.startsWith("ic_") }
            .mapNotNull { (name, id) -> ids["classic_$name"]?.let { id to it } }
            .toMap()
    }

    /** The classic counterpart of [res], or [res] itself when there is none. */
    fun forBase(@DrawableRes res: Int): Int = map[res] ?: res
}

/**
 * One of HandTop's icons in the current theme: the white icon tinted [tint] (or as it is)
 * in the modern look, the colour icon untinted in the classic one.
 */
fun ImageView.setThemedIcon(@DrawableRes res: Int, tint: Int? = null) {
    imageTintList = null
    if (Themes.current.tintIcons) {
        setImageResource(res)
        if (tint != null) setColorFilter(tint) else clearColorFilter()
    } else {
        setImageResource(ClassicIcons.forBase(res))
        clearColorFilter()
    }
}
