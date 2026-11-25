package org.handtop.desktop.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import org.handtop.desktop.R

/**
 * A dialog builder in the current theme: Android's dark dialog for the modern look, a light
 * one (dark text, navy buttons) for the classic look, whose window [showDialog] then gives
 * a raised gray frame and raised buttons.
 */
fun Context.dialogBuilder(): AlertDialog.Builder =
    if (!Themes.current.bevelled) AlertDialog.Builder(this)
    else AlertDialog.Builder(this, R.style.HandTop_ClassicDialog)

/** Shows the dialog from [builder], keeping HandTop full screen while it is open. */
fun showDialog(builder: AlertDialog.Builder): AlertDialog {
    val dialog = builder.create()
    dialog.setOnShowListener {
        dialog.window?.decorView?.hideSystemBarsOfWindow()
        if (Themes.current.bevelled) {
            val context = dialog.context
            dialog.window?.setBackgroundDrawable(context.bevel(Bevel.RAISED))
            for (which in listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)) {
                dialog.getButton(which)?.styleButton()
            }
        }
    }
    dialog.show()
    return dialog
}
