package com.github.jackharvest.flipflex.plex

/**
 * What we ask the transcoder for, as a handful of named presets.
 *
 * Presets rather than two free settings, because resolution and bitrate are not
 * independent in any useful way -- 640x480 at 320 kbps and 240x180 at 3000 kbps
 * are both nonsense, and a settings screen that lets you build them is a
 * settings screen that lets you make playback worse by accident. PocketFlex
 * carries them separately and that is the one place its settings screen is
 * harder to use than it needs to be.
 *
 * The sizes are chosen against the panel, which is 240x320 at density 160 --
 * so a 16:9 picture is 240x135 portrait and 320x180 in the player's landscape.
 * [STANDARD] already oversamples that; everything above it exists for the case
 * where the server is on the same Wi-Fi and there is no reason not to.
 */
object Quality {

    data class Preset(
        val id: String,
        val label: String,
        val resolution: String,
        val bitrate: Int,
    ) {
        /** "320x240 · 800 kbps", for a settings row's trailing column. */
        val summary: String get() = "$resolution · $bitrate kbps"
    }

    val PRESETS = listOf(
        Preset("low", "Low", "240x180", 320),
        Preset("standard", "Standard", "320x240", 800),
        Preset("high", "High", "480x360", 1500),
        Preset("max", "Maximum", "640x480", 3000),
    )

    /**
     * Streaming defaults to Standard: it is the pair Phase 2 was proven on, and
     * the constraint it respects is the server's transcoder and the radio
     * rather than the SoC -- the MT6739 decodes 1600x960 in hardware.
     */
    const val DEFAULT_STREAM = "standard"

    /**
     * Downloads default one step lower, on purpose. The whole point of a
     * download is to fit a lot of them, and a file watched on a 2.4" panel with
     * no network is not the one to spend storage on. PocketFlex reached the same
     * conclusion from the other direction and gave downloads their own setting.
     */
    const val DEFAULT_DOWNLOAD = "low"

    /** Never null: an unknown id -- a pref written by an older build -- is Standard. */
    fun byId(id: String?): Preset =
        PRESETS.firstOrNull { it.id == id } ?: PRESETS.first { it.id == DEFAULT_STREAM }
}
