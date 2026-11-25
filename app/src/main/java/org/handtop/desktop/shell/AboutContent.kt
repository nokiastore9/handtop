package org.handtop.desktop.shell

import android.content.Context
import android.text.util.Linkify
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(18), dpi(20), dpi(20))
        }
        // The app's icon in the system's mask (the package manager's copy may come wrapped in a
        // launcher's frame, as on Samsung).
        column.addView(ImageView(context).apply {
            setImageDrawable(context.getDrawable(R.mipmap.ic_launcher))
        }, LinearLayout.LayoutParams(dpi(64), dpi(64)).apply { bottomMargin = dpi(12) })
        column.addView(TextView(context).apply {
            text = context.getString(R.string.about_text, version)
            setTextColor(Palette.TEXT)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            autoLinkMask = Linkify.WEB_URLS
            setTextIsSelectable(true)
        })
        addView(column)
    }
}
