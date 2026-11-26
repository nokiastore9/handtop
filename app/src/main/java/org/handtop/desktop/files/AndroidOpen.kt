package org.handtop.desktop.files

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Files and links handed to Android apps (from the file manager, or from Linux programs). */
object AndroidOpen {

    /**
     * [file] in an Android app the user picks ([title] over the choice), on display [displayId]
     * if given. False when no app can take it.
     */
    fun file(context: Context, file: File, mime: String, title: String, displayId: Int? = null): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(view, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), displayId)
    }

    /** A link (https:, mailto:, tel:…) in the app Android has for it. False when there is none. */
    fun link(context: Context, uri: String, displayId: Int? = null): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)), displayId)

    private fun start(context: Context, intent: Intent, displayId: Int?): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        displayId?.let { options.launchDisplayId = it }
        return try {
            context.startActivity(intent, options.toBundle())
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
