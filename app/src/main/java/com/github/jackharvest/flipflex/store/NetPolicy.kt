package com.github.jackharvest.flipflex.store

/**
 * What FlipFlex is allowed to do with somebody's mobile data allowance.
 *
 * Three values rather than two, because the two-value version could only be
 * wrong in one direction or the other. "Wi-Fi only, on or off" is right for
 * downloads and wrong for streaming: a phone that silently refuses to play
 * anything away from the house looks broken, and a phone that quietly streams
 * an evening of television over LTE costs money. [ASK] is the honest answer to
 * both -- it spends the data, and it says so first.
 *
 * Stored as these strings rather than as an enum's ordinal, so reordering or
 * inserting a value cannot silently change what an existing install means.
 */
object NetPolicy {

    /** Never on mobile data. Downloads wait; playback is refused. */
    const val WIFI_ONLY = "on"

    /** Allowed, after a confirmation. The default for streaming. */
    const val ASK = "warn"

    /** Allowed, with nothing said. */
    const val ANY = "off"

    /**
     * The settings row's trailing value.
     *
     * The row is titled "Wi-Fi only", so these read as answers to that question
     * rather than as three unrelated modes -- "Warn" is "Wi-Fi only, unless you
     * say otherwise".
     */
    fun label(value: String): String = when (value) {
        WIFI_ONLY -> "On"
        ASK -> "Warn"
        else -> "Off"
    }

    /** Cycled by pressing OK on the row, in the order a settings list reads. */
    fun next(value: String): String = when (value) {
        WIFI_ONLY -> ASK
        ASK -> ANY
        else -> WIFI_ONLY
    }
}
