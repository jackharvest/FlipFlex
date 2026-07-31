package com.github.jackharvest.flipflex.plex

/**
 * Search, which PocketFlex deliberately does not have.
 *
 * That was the right call there: a Miyoo Mini has a D-pad and an A button, and
 * typing "Peaky Blinders" one letter at a time off an on-screen grid is worse
 * than scrolling to it. This handset is the opposite case -- it has a numeric
 * keypad and a real system IME with T9 and prediction, which is the one input
 * advantage a flip phone has over a games console.
 *
 * `/hubs/search` rather than the older `/search`, because it comes back already
 * grouped -- shows, episodes, films, each in its own hub with a heading -- and
 * those groups map directly onto the captioned rows the browse list already
 * draws. The flat endpoint would need us to re-derive the grouping from item
 * types, and would lose the server's own ordering within each group.
 */
object PlexSearch {

    /** One group of results, named by the server. */
    data class Hub(val title: String, val type: String, val items: List<PlexItem>)

    /**
     * Per hub, not overall. Ten films and ten shows is a list worth paging;
     * asking for a hundred of each fills a 128 MB heap with rows nobody will
     * scroll to on a screen that shows seven at a time.
     */
    private const val LIMIT = 12

    suspend fun search(uri: String, token: String, query: String): List<Hub> {
        if (query.isBlank()) return emptyList()
        val o = PlexClient.json(
            "$uri/hubs/search?query=${PlexClient.enc(query)}&limit=$LIMIT",
            token,
        ) ?: return emptyList()

        val hubs = o.optJSONObject("MediaContainer")?.optJSONArray("Hub") ?: return emptyList()
        return (0 until hubs.length()).mapNotNull { i ->
            val h = hubs.optJSONObject(i) ?: return@mapNotNull null
            val type = h.str("type")
            // Actors, directors and tags come back as hubs too. They are real
            // results, but choosing one has nowhere to go -- this app has no
            // person screen -- so a row that cannot be opened would be worse
            // than not offering it.
            if (type !in PLAYABLE_HUBS) return@mapNotNull null
            val arr = h.optJSONArray("Metadata") ?: return@mapNotNull null
            val items = (0 until arr.length()).mapNotNull { j ->
                arr.optJSONObject(j)?.let { PlexItem.from(it) }
            }
            if (items.isEmpty()) null
            else Hub(h.str("title", type.replaceFirstChar { it.uppercase() }), type, items)
        }
    }

    private val PLAYABLE_HUBS = setOf("movie", "show", "season", "episode", "collection", "clip")
}
