package io.github.jackharvest.flipflex.plex

import org.json.JSONObject

/**
 * Browsing. Everything returns [PlexItem], because the UI renders exactly one
 * thing: a list of rows with a title and a subtitle.
 *
 * Every list is fetched with an explicit container window rather than in full. A
 * library of a few thousand items answers `/all` with several megabytes of JSON,
 * and this handset has a 128 MB heap growth limit -- parsing that into a
 * JSONObject is a real out-of-memory risk, not a theoretical one.
 */
object PlexLibrary {

    /** How much of a long list we pull at once. */
    const val PAGE = 60

    /**
     * One window of a longer list.
     *
     * [totalSize] is what the first version was missing, and it was a real bug:
     * a library was fetched with a container size of 60 and then treated as
     * complete, so a TV library simply stopped somewhere in the Bs and there was
     * no way to reach anything after it. Carrying the server's own total is what
     * lets the caller know there is more.
     */
    data class Page(
        val items: List<PlexItem>,
        val totalSize: Int,
        val offset: Int,
    ) {
        val hasMore: Boolean get() = offset + items.size < totalSize
        val nextOffset: Int get() = offset + items.size
    }

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

    private fun page(o: JSONObject?, offset: Int): Page {
        val items = metadata(o)
        val c = o?.optJSONObject("MediaContainer")
        // totalSize is only present on a windowed request; size is the count in
        // this response. Falling back to offset+size makes an un-windowed reply
        // look complete, which is the correct reading of it.
        val total = c?.optInt("totalSize", -1)?.takeIf { it >= 0 } ?: (offset + items.size)
        return Page(items, total, offset)
    }

    suspend fun sections(uri: String, token: String): List<PlexItem> =
        metadata(PlexClient.json("$uri/library/sections", token))
            // Photo and music sections need a different player and browse model.
            // Filtering them here keeps them off a home screen that cannot do
            // anything with them yet.
            .filter { it.type == "movie" || it.type == "show" }

    /** A window of a section, alphabetically. */
    suspend fun sectionItems(
        uri: String,
        token: String,
        sectionKey: String,
        offset: Int = 0,
        size: Int = PAGE,
    ): Page = page(
        PlexClient.json(
            "$uri/library/sections/$sectionKey/all?sort=titleSort" +
                "&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$size",
            token,
        ),
        offset,
    )

    /**
     * Where each letter starts, so a long library can be jumped rather than
     * scrolled.
     *
     * Returns the letter and the number of titles under it. Plex includes `#`
     * for anything that does not sort under a letter.
     */
    suspend fun firstCharacters(uri: String, token: String, sectionKey: String): List<Pair<String, Int>> {
        val o = PlexClient.json("$uri/library/sections/$sectionKey/firstCharacter", token)
        val arr = o?.optJSONObject("MediaContainer")?.optJSONArray("Directory")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = d.str("title")
            if (title.isEmpty()) null else title to d.optInt("size", 0)
        }
    }

    /** The titles filed under one letter. */
    suspend fun byFirstCharacter(
        uri: String,
        token: String,
        sectionKey: String,
        letter: String,
        offset: Int = 0,
        size: Int = PAGE,
    ): Page = page(
        PlexClient.json(
            "$uri/library/sections/$sectionKey/firstCharacter/${PlexClient.enc(letter)}" +
                "?X-Plex-Container-Start=$offset&X-Plex-Container-Size=$size",
            token,
        ),
        offset,
    )

    /**
     * Continue Watching, across every library.
     *
     * This is the screen that justifies the whole app on a flip phone: the
     * shortest path from opening the lid to playing the thing you were already
     * watching, with no browsing at all.
     */
    suspend fun onDeck(uri: String, token: String, size: Int = 20): List<PlexItem> =
        metadata(
            PlexClient.json(
                "$uri/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=$size",
                token,
            )
        )

    /** Continue Watching, but only from one library. */
    suspend fun onDeckInSection(uri: String, token: String, sectionKey: String, size: Int = 20): List<PlexItem> =
        metadata(
            PlexClient.json(
                "$uri/library/sections/$sectionKey/onDeck" +
                    "?X-Plex-Container-Start=0&X-Plex-Container-Size=$size",
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

    suspend fun recentlyAddedInSection(uri: String, token: String, sectionKey: String, size: Int = 20): List<PlexItem> =
        metadata(
            PlexClient.json(
                "$uri/library/sections/$sectionKey/recentlyAdded" +
                    "?X-Plex-Container-Start=0&X-Plex-Container-Size=$size",
                token,
            )
        )

    /**
     * Episodes released recently, as opposed to recently *added* to the server.
     *
     * A show ripped in bulk floods "recently added" with a decade of back
     * catalogue; `newest` is ordered by air date and is the one that answers
     * "what is new on my shows".
     */
    suspend fun recentlyReleased(uri: String, token: String, sectionKey: String, size: Int = 20): List<PlexItem> =
        metadata(
            PlexClient.json(
                "$uri/library/sections/$sectionKey/newest" +
                    "?X-Plex-Container-Start=0&X-Plex-Container-Size=$size",
                token,
            )
        )

    /** Recently watched, for a "pick it back up" row. */
    suspend fun recentlyViewed(uri: String, token: String, sectionKey: String, size: Int = 20): List<PlexItem> =
        metadata(
            PlexClient.json(
                "$uri/library/sections/$sectionKey/recentlyViewed" +
                    "?X-Plex-Container-Start=0&X-Plex-Container-Size=$size",
                token,
            )
        )

    /** Seasons of a show, or episodes of a season. */
    suspend fun children(uri: String, token: String, ratingKey: String): List<PlexItem> =
        metadata(PlexClient.json("$uri/library/metadata/$ratingKey/children", token))

    /** Every episode of a show, flattened. What "Shuffle all" needs. */
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
