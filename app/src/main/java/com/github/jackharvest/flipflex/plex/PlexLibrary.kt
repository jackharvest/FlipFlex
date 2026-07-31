package com.github.jackharvest.flipflex.plex

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
     * A window of any list the server has given us a path for.
     *
     * Categories hand back a ready-made path with a filter already on it -- for
     * example `/library/sections/2/all?genre=42` -- and re-deriving that from
     * its parts would mean parsing the query Plex just handed us. Paging it is
     * identical to paging a section, so this feeds the same [Page].
     */
    suspend fun pathItems(
        uri: String,
        token: String,
        path: String,
        offset: Int = 0,
        size: Int = PAGE,
    ): Page {
        // The path may or may not already carry a query. Getting this wrong
        // produces a URL with two question marks, which Plex answers with the
        // *unfiltered* library rather than an error -- so a genre browse would
        // silently show everything.
        val join = if (path.contains('?')) "&" else "?"
        return page(
            PlexClient.json(
                "$uri$path${join}X-Plex-Container-Start=$offset&X-Plex-Container-Size=$size",
                token,
            ),
            offset,
        )
    }

    /** One way of slicing a library: a genre, a year, a content rating. */
    data class Category(val title: String, val path: String, val count: Int)

    /**
     * The genres in a library, for the Categories view.
     *
     * Genre only, of the several facets Plex offers (year, decade, rating,
     * collection, director). It is the one people browse by, and each extra
     * facet is another row on a screen that shows seven of them -- a list of
     * facets to choose a facet from is a level of navigation that buys nothing.
     *
     * `fastKey` is preferred over `key` because it is a complete path with the
     * filter already applied. `key` is a bare id on some server versions and a
     * path on others, and the bare id form gives no way to know which facet it
     * belongs to.
     */
    suspend fun genres(uri: String, token: String, sectionKey: String): List<Category> {
        val o = PlexClient.json("$uri/library/sections/$sectionKey/genre", token)
        val arr = o?.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val d = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = d.str("title")
            val fast = d.str("fastKey")
            val raw = d.str("key")
            val path = when {
                fast.isNotEmpty() -> fast
                raw.startsWith("/") -> raw
                raw.isNotEmpty() -> "/library/sections/$sectionKey/all?genre=${PlexClient.enc(raw)}"
                else -> ""
            }
            if (title.isEmpty() || path.isEmpty()) null
            else Category(title, path, d.optInt("size", 0))
        }
    }

    /**
     * One random playable thing from a whole library. What Shuffle needs.
     *
     * Two requests, not one: the first asks for a single item purely to read the
     * server's `totalSize`, and the second fetches one item at a random offset
     * inside it. Fetching the library and picking from it would mean parsing
     * several megabytes of JSON into a 128 MB heap to throw all but one row
     * away.
     *
     * `sort=random` exists on newer servers and is not used, because it is a
     * silent no-op on older ones -- which shuffles you to the first title
     * alphabetically, every time, and looks exactly like a broken feature.
     *
     * `type=4` is what makes this work on television: it enumerates *episodes*
     * across the whole section, so a shuffle is a random episode of a random
     * show rather than a random show you would then have to pick an episode of.
     */
    suspend fun randomInSection(
        uri: String,
        token: String,
        sectionKey: String,
        sectionType: String,
    ): PlexItem? {
        val type = if (sectionType == "show") 4 else 1
        val base = "$uri/library/sections/$sectionKey/all?type=$type"
        val first = page(
            PlexClient.json("$base&X-Plex-Container-Start=0&X-Plex-Container-Size=1", token),
            0,
        )
        val total = first.totalSize
        if (total <= 0) return null
        val pick = (0 until total).random()
        if (pick == 0) return first.items.firstOrNull()
        return page(
            PlexClient.json("$base&X-Plex-Container-Start=$pick&X-Plex-Container-Size=1", token),
            pick,
        ).items.firstOrNull()
    }

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
