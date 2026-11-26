package org.handtop.desktop.linux

/**
 * Places in KDE's file manager and file dialogs (Dolphin, Kate, Okular…): the bookmarks of
 * ~/.local/share/user-places.xbel. KDE writes that file with its own places (Home, Trash…) on
 * its first start; HandTop only adds to a file KDE made, never creates one, or KDE would take
 * it as its own and leave its places out.
 */
object KdePlaces {

    class Place(val uri: String, val title: String, val icon: String)

    /**
     * [xbel] with [places] added at the end, each with an id as KDE makes them ("seconds/n",
     * [now] in seconds). Null when every place is there already, or the file has no end
     * (being written, or not KDE's): then it stays as it is.
     */
    fun add(xbel: String, places: List<Place>, now: Long): String? {
        val end = xbel.lastIndexOf("</xbel>")
        if (end < 0) return null
        val fresh = places.filter { "href=\"${escape(it.uri)}\"" !in xbel }
        if (fresh.isEmpty()) return null
        val added = fresh.mapIndexed { n, place ->
            """
            | <bookmark href="${escape(place.uri)}">
            |  <title>${escape(place.title)}</title>
            |  <info>
            |   <metadata owner="http://freedesktop.org">
            |    <bookmark:icon name="${escape(place.icon)}"/>
            |   </metadata>
            |   <metadata owner="http://www.kde.org">
            |    <ID>$now/$n</ID>
            |   </metadata>
            |  </info>
            | </bookmark>
            |""".trimMargin()
        }.joinToString("")
        return xbel.substring(0, end) + added + xbel.substring(end)
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
