package tv.tvstreamer

/**
 * Hero / detail artwork: a backdrop and a poster must not be the same picture shown twice.
 * When there is no distinct backdrop, the UI uses a muted gradient instead of repeating the poster.
 */
object BackdropArt {
    fun fileName(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').lowercase()

    fun same(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val fa = fileName(a)
        val fb = fileName(b)
        return fa.isNotEmpty() && fa == fb
    }

    /** Backdrop to put behind a poster, or null when it would just be the same art (use a gradient). */
    fun distinct(backdrop: String?, poster: String): String? {
        val b = backdrop?.ifEmpty { null } ?: return null
        return if (same(b, poster)) null else b
    }
}
