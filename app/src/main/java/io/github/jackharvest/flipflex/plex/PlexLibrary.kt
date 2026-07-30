package io.github.jackharvest.flipflex.plex

import org.json.JSONObject

/**
 * Browsing. Everything returns a list of [PlexItem], because the UI renders
 * exactly one thing.
 *
 * Every list here is fetched with an explicit container window rather than in
 * full. A Plex library of a few thousand items answers `/all` with several
 * megabytes of JSON, and this handset has a 128 MB heap growth limit -- parsing
 * that into a JSONObject is a real out-of-memory risk, not a theoretical one.
 */
object PlexLibrary {

    /** How much of a long list we pull at once. */
    const val PAGE = 60

    private fun metadata(o: JSONObject?): List<PlexItem> {
        val container = o?.optJSONObject("MediaContainer") ?: return emptyList()
        // Sections arrive as "Directory", content as "Metadata". Checking both
        // means callers do not have to know which endpoint returns which.
        val arr = container.optJSONArray("Metadata")
            ?: container.optJSONArray("Directory")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { PlexItem.from(it) }
        }
    }

    suspend fun sections(uri: String, token: String): List<PlexItem> =
        metadata(PlexClient.json("$uri/library/sections", token))
            // Photo and music sections would need a completely different player
            // and browse model. Filtering them here keeps them off a home screen
            // that cannot do anything with them yet.
            .filter { it.type == "movie" || it.type == "show" }

    /** Everything in a section, alphabetically, one page at a time. */
    suspend fun sectionItems(
        uri: String,
        token: String,
        sectionKey: String,
        start: Int = 0,
        size: Int = PAGE,
    ): List<PlexItem> = metadata(
        PlexClient.json(
            "$uri/library/sections/$sectionKey/all?sort=titleSort" +
                "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$size",
            token,
        )
    )

    /**
     * Continue Watching, across every library.
     *
     * This is the screen that justifies the whole app on a flip phone: it is
     * the shortest path from opening the lid to playing the thing you were
     * already watching, with no browsing at all.
     */
    suspend fun onDeck(uri: String, token: String, size: Int = 20): List<PlexItem> =
        metadata(
            PlexClient.json(
                "$uri/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=$size",
                token,
            )
        )

    suspend fun recentlyAdded(uri: String, token: String, size: Int = 20): List<PlexItem> =
        metadata(
            PlexClient.json(
                "$uri/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=$size",
                token,
            )
        )

    /** Seasons of a show, or episodes of a season. */
    suspend fun children(uri: String, token: String, ratingKey: String): List<PlexItem> =
        metadata(PlexClient.json("$uri/library/metadata/$ratingKey/children", token))

    /** Every episode of a show, flattened. What "Shuffle All" needs. */
    suspend fun allLeaves(uri: String, token: String, ratingKey: String): List<PlexItem> =
        metadata(PlexClient.json("$uri/library/metadata/$ratingKey/allLeaves", token))

    suspend fun item(uri: String, token: String, ratingKey: String): PlexItem? =
        metadata(PlexClient.json("$uri/library/metadata/$ratingKey", token)).firstOrNull()

    /**
     * Mark watched / unwatched.
     *
     * These are GETs, which looks wrong and is not -- Plex's scrobble and
     * unscrobble endpoints genuinely take GET, and sending PUT gets a 404.
     */
    suspend fun markWatched(uri: String, token: String, ratingKey: String) {
        PlexClient.text("$uri/:/scrobble?key=$ratingKey&identifier=com.plexapp.plugins.library", token)
    }

    suspend fun markUnwatched(uri: String, token: String, ratingKey: String) {
        PlexClient.text("$uri/:/unscrobble?key=$ratingKey&identifier=com.plexapp.plugins.library", token)
    }
}
