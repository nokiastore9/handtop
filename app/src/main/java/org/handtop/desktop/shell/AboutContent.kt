package org.handtop.desktop.shell

import android.content.Context
import android.text.util.Linkify
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.WindowContent

/** A simple native window: version, license and shortcuts. */
class AboutContent(context: Context) : ScrollView(context), WindowContent {

    override val view: View get() = this

    init {
        setBackgroundColor(0xFF0F1420.toInt())
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        addView(TextView(context).apply {
            text = context.getString(R.string.about_text, version)
            setTextColor(Palette.TEXT)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setPadding(dpi(20), dpi(16), dpi(20), dpi(20))
            autoLinkMask = Linkify.WEB_URLS
            setTextIsSelectable(true)
        })
    }
}
