package org.handtop.desktop.linux

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element

class KdePlacesTest {
    /** What KDE writes on its first start (shortened): its own places, then the end. */
    private val fresh = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE xbel>
        <xbel xmlns:bookmark="http://www.freedesktop.org/standards/desktop-bookmarks" xmlns:mime="http://www.freedesktop.org/standards/shared-mime-info" xmlns:kdepriv="http://www.kde.org/kdepriv">
         <bookmark href="file:///root">
          <title>Home</title>
          <info>
           <metadata owner="http://freedesktop.org">
            <bookmark:icon name="user-home"/>
           </metadata>
           <metadata owner="http://www.kde.org">
            <ID>1764000000/0</ID>
            <isSystemItem>true</isSystemItem>
           </metadata>
          </info>
         </bookmark>
        </xbel>
    """.trimIndent() + "\n"

    private val phone = KdePlaces.Place("file:///sdcard", "Phone", "phone")
    private val downloads = KdePlaces.Place("file:///sdcard/Download", "Downloads", "folder-download")

    /** (href, title, icon) of every bookmark, in order; also proves the file is well-formed XML. */
    private fun bookmarks(xbel: String): List<Triple<String, String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xbel.byteInputStream())
        val nodes = doc.getElementsByTagName("bookmark")
        return (0 until nodes.length).map { i ->
            val b = nodes.item(i) as Element
            val icon = b.getElementsByTagName("bookmark:icon").item(0) as Element
            Triple(b.getAttribute("href"), b.getElementsByTagName("title").item(0).textContent, icon.getAttribute("name"))
        }
    }

    @Test
    fun placesGoAfterKdesOwn() {
        val out = KdePlaces.add(fresh, listOf(phone, downloads), now = 1764100000)!!
        assertEquals(
            listOf(
                Triple("file:///root", "Home", "user-home"),
                Triple("file:///sdcard", "Phone", "phone"),
                Triple("file:///sdcard/Download", "Downloads", "folder-download"),
            ),
            bookmarks(out),
        )
    }

    @Test
    fun eachPlaceGetsItsOwnId() {
        val out = KdePlaces.add(fresh, listOf(phone, downloads), now = 1764100000)!!
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(out.byteInputStream())
        val ids = doc.getElementsByTagName("ID").let { n -> (0 until n.length).map { n.item(it).textContent } }
        assertEquals(listOf("1764000000/0", "1764100000/0", "1764100000/1"), ids)
    }

    @Test
    fun placesAlreadyThereAreNotAddedAgain() {
        val once = KdePlaces.add(fresh, listOf(phone), now = 1)!!
        assertNull(KdePlaces.add(once, listOf(phone), now = 2))
        assertEquals(3, bookmarks(KdePlaces.add(once, listOf(phone, downloads), now = 2)!!).size)
    }

    @Test
    fun titlesAreEscaped() {
        val out = KdePlaces.add(fresh, listOf(KdePlaces.Place("file:///sdcard/A%26B", "Docs & <more>", "folder")), now = 1)!!
        assertEquals("Docs & <more>", bookmarks(out).last().second)
    }

    @Test
    fun aFileWithoutItsEndIsLeftAlone() {
        assertNull(KdePlaces.add(fresh.substringBefore("</xbel>"), listOf(phone), now = 1))
    }
}
