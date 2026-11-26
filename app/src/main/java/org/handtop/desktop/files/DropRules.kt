package org.handtop.desktop.files

import java.io.File

/** Files being dragged in HandTop (the drag's local state): from a file manager window. */
class FileDrag(val files: List<File>)

/** Where dragged files may be dropped in a file manager window. */
object DropRules {

    /**
     * The folder that takes [dragged] files: the folder under the finger ([folderUnder]), else
     * the folder the window shows ([shownFolder]). Null when that makes no sense: a folder
     * into itself or a folder inside it, or files into the folder they are already in.
     */
    fun target(dragged: List<File>, folderUnder: File?, shownFolder: File): File? {
        if (folderUnder != null && dragged.none { same(it, folderUnder) }) {
            return folderUnder.takeIf { fits(dragged, it) }
        }
        if (dragged.all { it.parentFile?.let { parent -> same(parent, shownFolder) } == true }) return null
        return shownFolder.takeIf { fits(dragged, it) }
    }

    /** [target] is not one of the dragged folders or inside one. */
    private fun fits(dragged: List<File>, target: File) = dragged.none { same(it, target) || inside(target, it) }

    private fun same(a: File, b: File) = a.invariantSeparatorsPath == b.invariantSeparatorsPath

    private fun inside(file: File, folder: File) =
        file.invariantSeparatorsPath.startsWith(folder.invariantSeparatorsPath.trimEnd('/') + "/")
}
