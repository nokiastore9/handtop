package org.handtop.desktop.wayland

import org.junit.Assert.assertEquals
import org.junit.Test

class DragDataTest {

    @Test
    fun filesBecomeFileUrisOnePerLine() =
        assertEquals(
            "file:///root/Documents/Welcome.txt\r\nfile:///sdcard/Download/a.pdf\r\n",
            UriList.encode(listOf("/root/Documents/Welcome.txt", "/sdcard/Download/a.pdf")),
        )

    @Test
    fun spacesAndNonAsciiNamesArePercentEncoded() =
        // UTF-8 bytes, as GLib and Qt write them.
        assertEquals(
            "file:///root/My%20Notes/%D0%9F%D0%BB%D0%B0%D0%BD%20%231.txt\r\n",
            UriList.encode(listOf("/root/My Notes/План #1.txt")),
        )

    @Test
    fun aUriListGivesBackTheLinuxPaths() =
        assertEquals(
            listOf("/root/My Notes/План #1.txt", "/sdcard/a.pdf", "/tmp/x"),
            UriList.parse(
                "# a comment\r\n" +
                    "file:///root/My%20Notes/%D0%9F%D0%BB%D0%B0%D0%BD%20%231.txt\r\n" +
                    "file://localhost/sdcard/a.pdf\n" +
                    "\r\n" +
                    "https://example.com/page\r\n" + // not a file
                    "file://otherhost/etc/passwd\r\n" + // another machine
                    "file:///tmp/x",
            ),
        )

    @Test
    fun encodeAndParseRoundTrip() {
        val paths = listOf("/root/a b/c%d.txt", "/root/ü/é", "/root/[x]&y=1;z")
        assertEquals(paths, UriList.parse(UriList.encode(paths)))
    }

    @Test
    fun theTargetsPreferredActionWinsWhenBothSidesAllowIt() =
        assertEquals(
            DndActions.MOVE,
            DndActions.choose(source = DndActions.COPY or DndActions.MOVE, target = DndActions.COPY or DndActions.MOVE, preferred = DndActions.MOVE),
        )

    @Test
    fun otherwiseTheFirstCommonOfCopyMoveAsk() {
        // The target prefers move, but the source only copies.
        assertEquals(DndActions.COPY, DndActions.choose(source = DndActions.COPY, target = DndActions.COPY or DndActions.MOVE, preferred = DndActions.MOVE))
        assertEquals(DndActions.MOVE, DndActions.choose(source = DndActions.MOVE or DndActions.ASK, target = DndActions.MOVE or DndActions.ASK, preferred = 0))
        assertEquals(DndActions.ASK, DndActions.choose(source = DndActions.ASK or DndActions.COPY, target = DndActions.ASK, preferred = DndActions.ASK))
    }

    @Test
    fun nothingInCommonIsNone() =
        assertEquals(DndActions.NONE, DndActions.choose(source = DndActions.COPY, target = DndActions.MOVE, preferred = DndActions.MOVE))
}
