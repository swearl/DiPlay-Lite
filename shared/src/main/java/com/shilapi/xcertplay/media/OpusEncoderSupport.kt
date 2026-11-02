package com.shilapi.xcertplay.media

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * Android has guaranteed an Opus *decoder* since API 21 but only guarantees an Opus *encoder*
 * from API 29, so an Android 8 or 9 unit plays CarPlay audio yet cannot send the microphone as
 * Opus. The accessory advertises its input formats in the info plist, long before any stream is
 * set up, so the answer has to come from the codec list rather than from a failed encoder.
 */
object OpusEncoderSupport {
    /** The platform has no guaranteed Opus encoder before API 29; only the bundled libopus encoder applies. */
    fun isAvailable(): Boolean = NativeOpusEncoder.isAvailable()
}
