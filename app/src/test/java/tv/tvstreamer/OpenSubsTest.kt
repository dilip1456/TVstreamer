package tv.tvstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsTest {
    @Test fun movieByTmdbId() {
        assertEquals("languages=en&tmdb_id=603", OpenSubs.searchParams(OpenSubs.Query(tmdbId = 603, imdbId = "tt0133093", lang = "EN")))
    }

    @Test fun movieByImdbIdWithoutPrefix() {
        assertEquals("imdb_id=133093&languages=fr", OpenSubs.searchParams(OpenSubs.Query(imdbId = "tt0133093", lang = "fr")))
    }

    @Test fun episodeUsesParentIdSeasonAndEpisode() {
        assertEquals("episode_number=5&languages=en&parent_tmdb_id=1396&season_number=2",
            OpenSubs.searchParams(OpenSubs.Query(tmdbId = 1396, season = 2, episode = 5)))
    }

    @Test fun titleFallbackWithYear() {
        assertEquals("languages=en&query=the+matrix&year=1999", OpenSubs.searchParams(OpenSubs.Query(title = "The Matrix", year = "1999")))
    }

    private val sample = """{"total_count":3,"data":[
      {"id":"1","attributes":{"language":"en","download_count":50,"hearing_impaired":true,"machine_translated":false,
        "feature_details":{"season_number":2,"episode_number":5},"files":[{"file_id":11,"file_name":"a.srt"}]}},
      {"id":"2","attributes":{"language":"en","download_count":900,"hearing_impaired":false,"ai_translated":true,
        "feature_details":{"season_number":2,"episode_number":5},"files":[{"file_id":22,"file_name":"b.srt"}]}},
      {"id":"3","attributes":{"language":"en","download_count":10,"hearing_impaired":false,
        "feature_details":{"season_number":2,"episode_number":5},"files":[{"file_id":33,"file_name":"c.vtt"}]}},
      {"id":"4","attributes":{"language":"en","download_count":5000,
        "feature_details":{"season_number":2,"episode_number":6},"files":[{"file_id":44,"file_name":"wrong.srt"}]}},
      {"id":"5","attributes":{"language":"en","files":[]}}
    ]}"""

    @Test fun parsesAndPicksHumanNonHiRightEpisode() {
        val hits = OpenSubs.parseSearch(sample)
        assertEquals(listOf(11L, 22L, 33L, 44L), hits.map { it.fileId })
        assertEquals(33L, OpenSubs.best(hits, OpenSubs.Query(tmdbId = 1, season = 2, episode = 5))?.fileId)
        assertNull(OpenSubs.best(emptyList(), OpenSubs.Query()))
    }

    @Test fun friendlyErrors() {
        assertTrue(OpenSubs.errorFor(401, "").contains("API key"))
        assertTrue(OpenSubs.errorFor(406, """{"reset_time":"5 hours"}""").contains("5 hours"))
        assertTrue(OpenSubs.errorFor(503, "").contains("not responding"))
    }
}
