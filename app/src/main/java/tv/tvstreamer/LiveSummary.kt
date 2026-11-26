package tv.tvstreamer

/**
 * Text for the Live TV page header (1.99): one quiet summary line and the Languages button label.
 * Plain functions so the wording can be unit-tested.
 */
object LiveSummary {
    /** "1,234 channels · English, Hindi · Only hidden": mode name first when it is not the default. */
    fun line(mode: String, count: Int, langs: Set<String>?, view: LiveModes.View): String = listOfNotNull(
        mode.takeIf { it != LiveModes.DEFAULT },
        if (count == 1) "1 channel" else "%,d channels".format(count),
        langs?.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(", ")?.let { if (it.length > 40) "${langs.size} languages" else it },
        view.takeIf { it != LiveModes.View.ACTIVE }?.label
    ).joinToString("  ·  ")

    /** Languages button: "Languages" when all are listed, the names when one or two, else "3 languages". */
    fun langButton(langs: Set<String>?): String = when {
        langs.isNullOrEmpty() -> "Languages"
        langs.size <= 2 -> langs.sorted().joinToString(", ")
        else -> "${langs.size} languages"
    }
}

fun liveSummary(mode: String, count: Int, langs: Set<String>?, view: LiveModes.View) = LiveSummary.line(mode, count, langs, view)
