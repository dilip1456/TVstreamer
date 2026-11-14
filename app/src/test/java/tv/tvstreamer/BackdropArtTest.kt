package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test

class BackdropArtTest {
    @Test fun distinctBackdropIsKept() {
        val poster = "https://image.tmdb.org/t/p/w500/poster.jpg"
        val back = "https://image.tmdb.org/t/p/w1280/backdrop.jpg"
        assertEquals(back, BackdropArt.distinct(back, poster))
        assertFalse(BackdropArt.same(back, poster))
    }

    @Test fun sameUrlIsRejected() {
        val img = "http://portal/cover.png"
        assertTrue(BackdropArt.same(img, img))
        assertNull(BackdropArt.distinct(img, img))
        assertNull(BackdropArt.distinct(null, img))
        assertNull(BackdropArt.distinct("", img))
        // No poster to clash with: the backdrop can be used
        assertEquals(img, BackdropArt.distinct(img, ""))
    }

    @Test fun sameFileDifferentSizeIsRejected() {
        val poster = "https://image.tmdb.org/t/p/w500/abc123.jpg"
        val back = "https://image.tmdb.org/t/p/original/abc123.jpg?x=1"
        assertTrue(BackdropArt.same(poster, back))
        assertNull(BackdropArt.distinct(back, poster))
    }
}
