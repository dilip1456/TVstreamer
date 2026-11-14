package tv.tvstreamer

/**
 * Display name for the hero and the full-screen details page: the movie / series title only,
 * never an episode name or an "S01E01" marker.
 */
object HeroTitle {
    private val SXE = Regex("""(?i)\s*[-–:|•]?\s*\bS\d{1,2}\s*[ .:_-]?\s*E(?:p)?\s*\d{1,3}\b.*$""")
    private val XFORM = Regex("""(?i)\s*[-–:|•]?\s*\b\d{1,2}\s*x\s*\d{1,3}\b.*$""")
    private val EPWORD = Regex("""(?i)\s*[-–:|•]?\s*\b(?:episode|episodio|épisode|folge)\s*[-_.:#]?\s*\d{1,4}\b.*$""")
    private val EDGES = Regex("""^[\s|:\-–•*#]+|[\s|:\-–•*#]+$""")

    /**
     * @param raw portal name (or already-cleaned name)
     * @param metaTitle TMDB / OMDb title when we have one; ignored when it still looks like an episode
     * @param episode true when the item is an episode, so a leftover "Show - Pilot" drops the episode title
     */
    fun display(raw: String, metaTitle: String? = null, episode: Boolean = false): String {
        val meta = metaTitle?.trim().orEmpty()
        val base = if (meta.isNotEmpty() && !looksLikeEpisode(meta)) meta else Names.title(raw)
        var s = stripMarkers(base)
        if (episode || looksLikeEpisode(raw) || looksLikeEpisode(base)) {
            val dash = s.split(Regex("""\s+[-–]\s+"""), limit = 2)
            if (dash.size == 2 && dash[0].length >= 2) s = dash[0]
        }
        s = EDGES.replace(s, "").replace(Regex("\\s{2,}"), " ")
        return s.ifBlank { Names.title(raw).ifBlank { raw.trim() } }
    }

    fun looksLikeEpisode(s: String): Boolean = Tags.hasEpisodeMarker(s)

    private fun stripMarkers(s: String): String {
        var t = SXE.replace(s, "")
        t = XFORM.replace(t, "")
        t = EPWORD.replace(t, "")
        return t
    }
}
