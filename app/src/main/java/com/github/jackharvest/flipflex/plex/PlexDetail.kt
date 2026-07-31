package com.github.jackharvest.flipflex.plex

import org.json.JSONObject

/**
 * Everything the details page shows that a list row does not carry.
 *
 * [PlexLibrary] deliberately reduces every endpoint to [PlexItem], because the
 * browse screens render exactly one thing. The details page is the one screen
 * that wants more: the description, what the file actually is, and which audio
 * and subtitle tracks it has. That is a second fetch of a single item, which is
 * cheap, rather than widening every list request.
 */
object PlexDetail {

    /**
     * One selectable track. [id] is a stream id, not an index -- Plex wants the
     * id back on `/library/parts`, and the two do not agree on a file whose
     * streams were added out of order.
     */
    data class Track(
        val id: String,
        val label: String,
        val selected: Boolean,
    )

    data class Detail(
        val item: PlexItem,
        val summary: String,
        val contentRating: String,
        /** "1080p", "720p", "SD" -- what the file on the server is. */
        val sourceResolution: String,
        val sourceBitrateKbps: Int,
        val videoCodec: String,
        val container: String,
        /**
         * The part id, which is what a subtitle or audio choice is applied to.
         *
         * Empty when the item has no media -- a show or a season. Choosing a
         * track is meaningless there and the details page does not offer it.
         */
        val partId: String,
        val audio: List<Track>,
        val subtitles: List<Track>,
    ) {
        /** "1080p H.264 · 8.2 Mbps", or as much of it as the server told us. */
        fun sourceLine(): String = buildList {
            if (sourceResolution.isNotEmpty()) add(sourceResolution)
            if (videoCodec.isNotEmpty()) add(videoCodec.uppercase())
            if (sourceBitrateKbps > 0) {
                add(
                    if (sourceBitrateKbps >= 1000) {
                        "%.1f Mbps".format(sourceBitrateKbps / 1000f)
                    } else {
                        "$sourceBitrateKbps kbps"
                    }
                )
            }
        }.joinToString(" · ")

        val selectedSubtitle: Track? get() = subtitles.firstOrNull { it.selected }
        val selectedAudio: Track? get() = audio.firstOrNull { it.selected }
    }

    suspend fun fetch(uri: String, token: String, ratingKey: String): Detail? {
        val o = PlexClient.json("$uri/library/metadata/$ratingKey", token) ?: return null
        val md = o.optJSONObject("MediaContainer")?.optJSONArray("Metadata")?.optJSONObject(0)
            ?: return null

        val item = PlexItem.from(md)
        val media = md.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)
        val streams = part?.optJSONArray("Stream")

        val tracks = { type: Int ->
            (0 until (streams?.length() ?: 0)).mapNotNull { i ->
                val s = streams?.optJSONObject(i) ?: return@mapNotNull null
                if (s.optInt("streamType", -1) != type) return@mapNotNull null
                // extendedDisplayTitle is the one that distinguishes two English
                // tracks -- "English (SRT External)" against "English (PGS)".
                // displayTitle alone gives two rows reading "English" and no way
                // to tell which is which.
                val label = listOf("extendedDisplayTitle", "displayTitle", "language")
                    .firstNotNullOfOrNull { k -> s.strOrNull(k) }
                    ?: "Track ${i + 1}"
                Track(
                    id = s.str("id"),
                    label = label,
                    selected = s.optBoolean("selected", false),
                )
            }
        }

        return Detail(
            item = item,
            summary = md.str("summary"),
            contentRating = md.str("contentRating"),
            sourceResolution = resolutionLabel(media),
            sourceBitrateKbps = media?.optInt("bitrate", 0) ?: 0,
            videoCodec = media?.str("videoCodec").orEmpty(),
            container = media?.str("container").orEmpty(),
            partId = part?.str("id").orEmpty(),
            audio = tracks(2),
            subtitles = tracks(3),
        )
    }

    /**
     * Plex reports `videoResolution` as a bare number for HD ("1080", "720")
     * and as the literal string "sd" below that, so it cannot simply have "p"
     * appended -- that would put "sdp" on the screen.
     */
    private fun resolutionLabel(media: JSONObject?): String {
        val raw = media?.str("videoResolution").orEmpty()
        return when {
            raw.isEmpty() -> ""
            raw.equals("sd", ignoreCase = true) -> "SD"
            raw.toIntOrNull() != null -> "${raw}p"
            else -> raw.uppercase()
        }
    }

    /**
     * Choose a subtitle track, or 0 for none.
     *
     * Server-side rather than local, which is what Plex's own clients do: the
     * choice is stored against the part and follows you to a TV. `allParts=1`
     * applies it to every part of a multi-file item, so a two-disc film does not
     * lose subtitles halfway through.
     */
    suspend fun setSubtitle(uri: String, token: String, partId: String, streamId: String) {
        PlexClient.text(
            "$uri/library/parts/$partId?subtitleStreamID=$streamId&allParts=1",
            token,
            method = "PUT",
        )
    }

    /** The same, for the dub-or-original choice. */
    suspend fun setAudio(uri: String, token: String, partId: String, streamId: String) {
        PlexClient.text(
            "$uri/library/parts/$partId?audioStreamID=$streamId&allParts=1",
            token,
            method = "PUT",
        )
    }
}
