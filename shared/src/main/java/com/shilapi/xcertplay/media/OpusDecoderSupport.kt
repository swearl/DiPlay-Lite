package com.shilapi.xcertplay.media

/**
 * Whether this head unit can decode Opus for CarPlay audio playback.
 *
 * Android guarantees an Opus decoder in MediaCodec from Android 5.0 (API 21), but Android 4.4.2
 * (API 19) lacks built-in Opus decoding. Note that Apple Wireless CarPlay protocol strictly mandates
 * Opus (0x70000000) to be advertised in audioFormats (type 100 default); omitting it causes iOS to
 * refuse wireless audio stream negotiation and route audio through iPhone speakers.
 */
object OpusDecoderSupport {
    /** Android 4.4 ships no platform Opus decoder; playback relies on the bundled libopus decoder. */
    fun isAvailable(): Boolean = false
}
