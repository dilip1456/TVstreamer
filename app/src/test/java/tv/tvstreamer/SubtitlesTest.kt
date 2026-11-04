package tv.tvstreamer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitlesTest {
    private val movie = OpenSubs.Query(tmdbId = 27205, title = "Inception", year = "2010", lang = "en")
    private val ep = OpenSubs.Query(tmdbId = 1399, title = "Game of Thrones", season = 2, episode = 3, lang = "es")

    @Test fun keyUsesIdsEpisodeAndLanguage() {
        assertEquals("tmdb27205_en", Subtitles.key(movie))
        assertEquals("tmdb1399_s2e3_es", Subtitles.key(ep))
        assertEquals("imdb1375666_en", Subtitles.key(OpenSubs.Query(imdbId = "tt1375666", lang = "EN")))
        assertEquals("t_the_matrix_1999_en", Subtitles.key(OpenSubs.Query(title = "The Matrix!", year = "1999", lang = "en")))
        assertNotEquals(Subtitles.key(ep), Subtitles.key(ep.copy(episode = 4)))
        assertEquals("tmdb1399_s2e3", Subtitles.titleKey(ep))
    }

    @Test fun cachePersistsAndRemembersChoice() {
        val dir = Files.createTempDirectory("subs").toFile()
        val c = Subtitles.Cache(dir, max = 2)
        assertNull(c.get(movie))
        val f = c.put(movie, "srt", "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray())
        assertEquals("tmdb27205_en.srt", f.name)
        // A new Cache over the same dir (= a later session) finds it
        assertEquals(f, Subtitles.Cache(dir).get(movie))
        assertNull(c.get(movie.copy(lang = "fr")))
        c.remember(ep, "es")
        assertEquals("es", Subtitles.Cache(dir).choice(ep.copy(lang = "")))
        assertEquals("", c.choice(movie))
        // LRU cap
        f.setLastModified(1000)
        c.put(ep, "vtt", "WEBVTT".toByteArray())
        c.put(ep.copy(lang = "fr"), "ass", "x".toByteArray())
        assertNull(c.get(movie))
        dir.deleteRecursively()
    }

    @Test fun quotaResetParsing() {
        val body = """{"requests":6,"remaining":-1,"reset_time":"13 hours","reset_time_utc":"2023-05-26T00:00:00.000Z"}"""
        assertEquals(java.time.Instant.parse("2023-05-26T00:00:00Z").toEpochMilli(), Subtitles.Quota.parseReset(body, 0))
        val now = java.time.Instant.parse("2026-11-03T15:00:00Z").toEpochMilli()
        assertEquals(java.time.Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(), Subtitles.Quota.parseReset("nope", now))
        assertTrue(Subtitles.Quota.available(0, now))
        assertFalse(Subtitles.Quota.available(now + 1, now))
    }

    @Test fun summaryMessages() {
        assertTrue(Subtitles.summary(listOf("OpenSubtitles: no API key", "SubDL: no key"), movie).contains("Settings > API keys"))
        assertEquals("Daily subtitle limit used (OpenSubtitles). Try again tomorrow.",
            Subtitles.summary(listOf("OpenSubtitles: daily limit used", "SubDL: no key"), movie))
        assertEquals("SubDL: no English subtitles",
            Subtitles.summary(listOf("OpenSubtitles: no API key", "SubDL: no English subtitles"), movie))
    }

    @Test fun subdlParamsNeverContainKey() {
        val p = SubDl.searchParams(ep)
        assertTrue(p.contains("tmdb_id=1399")); assertTrue(p.contains("type=tv"))
        assertTrue(p.contains("season_number=2")); assertTrue(p.contains("episode_number=3")); assertTrue(p.contains("languages=ES"))
        assertFalse(p.contains("api_key"))
        val t = SubDl.searchParams(OpenSubs.Query(title = "Some Film", year = "2001"))
        assertTrue(t.contains("film_name=Some+Film")); assertTrue(t.contains("year=2001")); assertTrue(t.contains("type=movie"))
    }

    @Test fun subdlParseAndBest() {
        val json = """{"status":true,"subtitles":[
            {"release_name":"pack","lang":"spanish","language":"ES","url":"/subtitle/1-1.zip","season":2,"episode":0,"full_season":true,"hi":false},
            {"release_name":"hi","language":"ES","url":"/subtitle/2-2.zip","season":2,"episode":3,"hi":true},
            {"release_name":"good","language":"ES","url":"/subtitle/3-3.zip","season":2,"episode":3,"hi":false},
            {"release_name":"wrong","language":"ES","url":"/subtitle/4-4.zip","season":2,"episode":5,"hi":false}]}"""
        val hits = SubDl.parse(json)
        assertEquals(4, hits.size)
        assertEquals("good", SubDl.best(hits, ep)!!.name)
        assertTrue(SubDl.parse("""{"status":false,"error":"x"}""").isEmpty())
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun zipPicksRightEpisode() {
        val z = zip("readme.txt" to "x", "Show.S02E01.srt" to "one", "Show.S02E03.WEB.srt" to "three", "Show.2x04.srt" to "four")
        assertEquals("three", String(SubDl.pickFromZip(z, ep)!!.first))
        assertEquals("four", String(SubDl.pickFromZip(z, ep.copy(episode = 4))!!.first))
        assertNull(SubDl.pickFromZip(z, ep.copy(episode = 9)))
        val m = SubDl.pickFromZip(zip("a.srt" to "short", "b.vtt" to "much longer text"), movie)!!
        assertEquals("vtt", m.second)
        assertNull(SubDl.pickFromZip(zip("nfo.txt" to "x"), movie))
    }
}
