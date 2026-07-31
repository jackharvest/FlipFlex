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
        /**
         * "eac3", "aac", "dca" -- the audio the file already carries.
         *
         * Read for the details page's before-and-after, and worth having for a
         * reason particular to this device: an 8-channel E-AC-3 source comes
         * back as 2-channel stereo on *every* path we can ask for, so the audio
         * is very often the biggest difference between the file and what the
         * handset will hear. A page that showed the video conversion and said
         * nothing about the audio was showing half the answer.
         */
        val audioCodec: String,
        val audioChannels: Int,
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

        /**
         * "E-AC3 5.1", or as much of it as the server said.
         *
         * The channel count is spelled the way a film's box does -- 5.1, 7.1,
         * Stereo, Mono -- rather than as a bare number, because "6" next to a
         * codec name reads as a version number.
         */
        fun audioLine(): String = listOf(
            audioCodec.uppercase().replace("EAC3", "E-AC3"),
            channelLabel(audioChannels),
        ).filter { it.isNotEmpty() }.joinToString(" ")
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
            audioCodec = media?.str("audioCodec").orEmpty(),
            audioChannels = media?.optInt("audioChannels", 0) ?: 0,
            container = media?.str("container").orEmpty(),
            partId = part?.str("id").orEmpty(),
            audio = tracks(2),
            subtitles = tracks(3),
        )
    }

    /** How a person says a channel count. Empty when the server did not say. */
    fun channelLabel(channels: Int): String = when {
        channels <= 0 -> ""
        channels == 1 -> "Mono"
        channels == 2 -> "Stereo"
        // 6 is 5.1 and 8 is 7.1: five speakers plus a subwoofer, seven plus one.
        // Odd counts are rare enough to print honestly rather than guess at.
        channels % 2 == 0 -> "${channels - 1}.1"
        else -> "${channels}ch"
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
