package io.github.jackharvest.flipflex.plex

import org.json.JSONObject

/**
 * One row, whatever it came from.
 *
 * A single flat type for sections, movies, shows, seasons and episodes rather
 * than a sealed hierarchy. That is a deliberate concession to the screen: this
 * device renders one thing, a list of rows with a title and a subtitle, and
 * every Plex type collapses into that. A type hierarchy would buy correctness
 * the UI has no way to express.
 */
data class PlexItem(
    val ratingKey: String,
    /** The API path for this item's children, when it has any. */
    val key: String,
    val type: String,
    val title: String,
    /** Show name for an episode, nothing for a movie. */
    val grandparentTitle: String = "",
    val parentTitle: String = "",
    /** Season of an episode, show of a season. Needed by "Go to season". */
    val parentRatingKey: String = "",
    /** Show of an episode, two levels up. Needed by "Go to show". */
    val grandparentRatingKey: String = "",
    val index: Int = -1,
    val parentIndex: Int = -1,
    val year: Int = -1,
    val durationMs: Long = 0,
    val viewOffsetMs: Long = 0,
    val viewCount: Int = 0,
    val leafCount: Int = 0,
    val viewedLeafCount: Int = 0,
    /** Only set for library sections, where `key` is a bare number. */
    val sectionKey: String = "",
) {

    val isPlayable: Boolean get() = type == "movie" || type == "episode" || type == "clip"
    val isContainer: Boolean get() = type == "show" || type == "season" || type == "collection"

    val watched: Boolean get() = viewCount > 0
    val inProgress: Boolean get() = viewOffsetMs > 0 && viewOffsetMs < durationMs

    /** 0f..1f, or 0f when there is nothing to show. */
    val progress: Float
        get() = if (durationMs > 0) (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * The second line of the row. What belongs there depends entirely on type,
     * and getting it wrong is the difference between a list you can scan and a
     * list of identical-looking strings -- an episode list where every row says
     * the show name is useless.
     */
    fun subtitle(): String = when (type) {
        "episode" -> buildString {
            if (parentIndex >= 0 && index >= 0) append("S$parentIndex · E$index")
            if (grandparentTitle.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append(grandparentTitle)
            }
        }
        "season" -> if (leafCount > 0) "$leafCount episodes" else ""
        "show" -> buildString {
            if (year > 0) append(year)
            if (leafCount > 0) {
                if (isNotEmpty()) append("  ·  ")
                append("$leafCount ep")
            }
        }
        "movie" -> if (year > 0) year.toString() else ""
        else -> ""
    }

    /** "43m left" for something part-watched, else a plain runtime. */
    fun timeLabel(): String {
        if (durationMs <= 0) return ""
        val remaining = if (inProgress) durationMs - viewOffsetMs else durationMs
        val minutes = (remaining / 60_000).toInt()
        val label = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
        return if (inProgress) "$label left" else label
    }

    companion object {
        fun from(o: JSONObject): PlexItem {
            // Sections come back as Directory entries whose "key" is a bare
            // section number; everything else has a real path. Normalising here
            // keeps the ambiguity out of every call site.
            // str(), never optString(): Plex sends explicit nulls for the
            // parent/grandparent fields on anything that has no parent, and
            // optString renders JSONObject.NULL as the string "null". See
            // Json.kt -- a row reading "null" is the mild version; a
            // grandparentRatingKey of "null" builds a URL that 404s.
            val rawKey = o.str("key")
            val isSection = rawKey.isNotEmpty() && rawKey.toIntOrNull() != null

            return PlexItem(
                ratingKey = o.str("ratingKey"),
                key = rawKey,
                type = o.str("type"),
                title = o.str("title", "Untitled"),
                grandparentTitle = o.str("grandparentTitle"),
                parentTitle = o.str("parentTitle"),
                parentRatingKey = o.str("parentRatingKey"),
                grandparentRatingKey = o.str("grandparentRatingKey"),
                index = o.optInt("index", -1),
                parentIndex = o.optInt("parentIndex", -1),
                year = o.optInt("year", -1),
                durationMs = o.optLong("duration", 0L),
                viewOffsetMs = o.optLong("viewOffset", 0L),
                viewCount = o.optInt("viewCount", 0),
                leafCount = o.optInt("leafCount", 0),
                viewedLeafCount = o.optInt("viewedLeafCount", 0),
                sectionKey = if (isSection) rawKey else "",
            )
        }
    }
}
