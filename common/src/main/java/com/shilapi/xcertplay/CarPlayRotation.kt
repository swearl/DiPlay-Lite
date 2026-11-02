package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * Optional, for head units whose screen turns: CarPlay gets a square canvas holding a landscape and a
 * portrait screen as view areas, so a turn redraws CarPlay in the other area instead of reconnecting.
 * A square is heavier to encode and decode; the smoother picture caps it at 1920.
 */
object CarPlayRotation {
    private const val TAG = "DiPlay-Rotation"
    private const val PREFS = "diplay_carplay_rotation"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PICTURE = "picture"
    private val SIDES = listOf(2560, 2304, 2048, 1920, 1600, 1280)

    /** How large a square to ask for; sharper is up to the screen's long side. */
    enum class Picture(val maxSide: Int?) { SMOOTHER(1920), SHARPER(null) }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun picture(context: Context): Picture =
        Picture.entries.firstOrNull { it.name == prefs(context).getString(KEY_PICTURE, null) } ?: Picture.SMOOTHER

    fun setPicture(context: Context, picture: Picture) = prefs(context).edit().putString(KEY_PICTURE, picture.name).apply()

    /**
     * The largest square the selected decoder takes, or null to retain the plain canvas.
     * The API 21+ MediaCodecList capability probe is unavailable on 4.4, so the canvas is kept.
     */
    fun squareSide(
        @Suppress("UNUSED_PARAMETER") longSide: Int,
        @Suppress("UNUSED_PARAMETER") picture: Picture,
        @Suppress("UNUSED_PARAMETER") hevc: Boolean,
        @Suppress("UNUSED_PARAMETER") preferSoftwareHevcDecoder: Boolean = false,
        @Suppress("UNUSED_PARAMETER") configures: (decoder: String, mime: String, side: Int) -> Boolean = ::configures,
    ): Int? = null

    /**
     * The landscape and portrait areas, each width to height, of a [side] square for a screen that turns.
     * CarPlay's window is [windowWidth] x [windowHeight] now, on a screen of [screenWidth] x [screenHeight]
     * in the same orientation. System bars keep their screen edges when the screen turns (a head unit's
     * navigation bar stays at the bottom), so the other window loses the same width and height to them,
     * not the swapped amounts: with a 120 px navigation bar, a 2560x1440 screen gives 2560x1320 and
     * 1440x2440, not 1320x2560. Without a known screen size, the other window is the turned one.
     */
    internal fun turningAreas(
        side: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val (landscape, portrait) = turnedWindows(windowWidth, windowHeight, screenWidth, screenHeight)
        fun even(value: Long) = value.toInt() and 1.inv()
        return (side to even(side.toLong() * landscape.second / landscape.first)) to
            (even(side.toLong() * portrait.first / portrait.second) to side)
    }

    /**
     * CarPlay's full window on a landscape and on a portrait screen, from the window now and the screen in
     * the same orientation; see [turningAreas] for why the bars keep their edges.
     */
    internal fun turnedWindows(
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val known = screenWidth >= windowWidth && screenHeight >= windowHeight && windowWidth > 0 && windowHeight > 0
        val barsWidth = if (known) screenWidth - windowWidth else 0
        val barsHeight = if (known) screenHeight - windowHeight else 0
        val other = if (known) (screenHeight - barsWidth) to (screenWidth - barsHeight) else windowHeight to windowWidth
        val now = windowWidth to windowHeight
        return if (windowWidth >= windowHeight) now to other else other to now
    }

    /** Whether [decoder] accepts a [side] x [side] stream when asked to configure for it. */
    private fun configures(decoder: String, mime: String, side: Int): Boolean {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(decoder)
            codec.configure(MediaFormat.createVideoFormat(mime, side, side), null, null, 0)
            true
        } catch (error: Exception) {
            false
        } finally {
            runCatching { codec?.release() }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
