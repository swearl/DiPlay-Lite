package com.shilapi.xcertplay

import android.content.Context
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.RemoteControlClient
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    /**
     * Issue #290: the media focus gain. MAY_DUCK is the default: a permanent request on
     * STREAM_MUSIC made one head unit treat DiPlay as the sole owner of the music stream
     * (its own players denied output). But another unit's vendor stack answers MAY_DUCK
     * with a permanent duck to silence — the exclusive switch restores GAIN for those
     * units. Read per session in start(), so it takes effect on the next connection.
     */
    private var mediaFocusExclusive = false
    private fun mediaFocusGain(): Int = if (mediaFocusExclusive) AudioManager.AUDIOFOCUS_GAIN
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    private var legacyClient: RemoteControlClient? = null
    private var legacyButtons: PendingIntent? = null
    private var legacyButtonComponent: ComponentName? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusOwner: Any? = null
    private var focusEventRevision = 0L
    private var focusHeld = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        updateWheelKeyReceiver(context)
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked()
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // The position goes in the playback state; the legacy RemoteControlClient path
                // has no metadata channel.
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        if (focusHeld) return
        @Suppress("DEPRECATION")
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val result = run {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(legacyFocusListener ?: return, AudioManager.STREAM_MUSIC, mediaFocusGain())
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) forwardGrantedFocusLocked()
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && focusOwner == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        mediaFocusExclusive = AirPlayPersistence.loadMediaFocusExclusive(context)
        val owner = Any().also { focusOwner = it }
        focusEventRevision = 0L
        @Suppress("DEPRECATION")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            onFocusChanged(expectedController, owner, change)
        }
        legacyFocusListener = listener
        val result = run {
            @Suppress("DEPRECATION")
            audio?.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, mediaFocusGain())
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusHeld = granted
        if (granted) forwardGrantedFocusLocked()
        run {
            val component = ComponentName(context, LegacyMediaKeyReceiver::class.java)
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(component)
            val buttons = PendingIntent.getBroadcast(context, 0, intent, 0)
            legacyButtonComponent = component
            legacyButtons = buttons
            try {
                // registerMediaButtonEventReceiver(PendingIntent) needs API 21+; the
                // ComponentName variant exists since API 8 and works on API 17/18.
                @Suppress("DEPRECATION")
                audio?.registerMediaButtonEventReceiver(component)
            } catch (error: Throwable) {
                Log.w(TAG, "registerMediaButtonEventReceiver failed", error)
            }
            try {
                legacyClient = RemoteControlClient(buttons).also {
                    it.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or
                        RemoteControlClient.FLAG_KEY_MEDIA_NEXT or RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS)
                    audio?.registerRemoteControlClient(it)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "registerRemoteControlClient failed", error)
            }
        }
    }

    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        // Immediate grants do not promise a later focus callback. Defer dispatch until the caller
        // releases the media-key monitor. A newer real focus event invalidates this observation,
        // as do a controller or request replacement while the queued work waits.
        mainHandler.post { onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision) }
    }

    private fun onFocusChanged(expectedController: CarPlayController, owner: Any, change: Int,
        grantedRevision: Long? = null) {
        val current = synchronized(this) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)) false
            else {
                focusEventRevision += 1
                // Only permanent loss moves media keys elsewhere; transient losses come back.
                if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
                else if (change == AudioManager.AUDIOFOCUS_GAIN) focusHeld = true
                true
            }
        }
        if (!current) return
        // Resolve the matching sink and invoke it outside the media-key monitor. An abandoned
        // request must never mute a newer controller, and these owners must not nest locks.
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) background.sink.onMediaAudioFocusChanged(change)
    }

    private fun releaseLocked() {
        focusOwner = null
        artworkOwner = null
        artworkQueue.clear()
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        @Suppress("DEPRECATION")
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            legacyClient?.let { audio?.unregisterRemoteControlClient(it) }
        } catch (error: Throwable) {
            Log.w(TAG, "unregisterRemoteControlClient failed", error)
        }
        try {
            legacyButtonComponent?.let {
                @Suppress("DEPRECATION")
                audio?.unregisterMediaButtonEventReceiver(it)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "unregisterMediaButtonEventReceiver failed", error)
        }
        legacyClient = null
        legacyButtons = null
        legacyButtonComponent = null
        legacyFocusListener?.let {
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(it)
        }
        legacyFocusListener = null
        focusHeld = false
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        try {
            legacyClient?.setPlaybackState(if (playing) RemoteControlClient.PLAYSTATE_PLAYING else RemoteControlClient.PLAYSTATE_PAUSED)
        } catch (error: Throwable) {
            Log.w(TAG, "legacy setPlaybackState failed", error)
        }
}

    private fun send(index: Int, source: String) {
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
    }

    fun onLegacyMediaButton(intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return
        send(index, KeyEvent.keyCodeToString(event.keyCode))
    }

    /**
     * Issue #151 & #179: entry point for [WheelMediaKeyReceiver] and foreground window dispatch.
     * When CarPlay is connected, steering-wheel media keys should drive CarPlay directly.
     */
    @Synchronized
    fun handleWheelMediaKey(keyCode: Int): Boolean {
        if (controller == null) return false
        val index = CarPlayMediaButton.forKeyCode(keyCode) ?: return false
        send(index, KeyEvent.keyCodeToString(keyCode))
        return true
    }

    /**
     * Issue #151: enables the high-priority [WheelMediaKeyReceiver] only on ECARX/GKUI
     * head units, where the car's mediacenter swallows the wheel media keys. Everywhere
     * else the receiver stays disabled (manifest default) and media-key routing is
     * unchanged.
     */
    private fun updateWheelKeyReceiver(context: Context) {
        val pm = context.packageManager
        val component = android.content.ComponentName(context, WheelMediaKeyReceiver::class.java)
        val wantEnabled = isEcarxGkui(pm)
        val enabledState = pm.getComponentEnabledSetting(component)
        if (wantEnabled && enabledState != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(
                component,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun isEcarxGkui(pm: android.content.pm.PackageManager): Boolean {
        if (runCatching { pm.getPackageInfo("ecarx.xsf.mediacenter", 0) }.isSuccess) return true
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        return manufacturer.contains("ecarx", ignoreCase = true) ||
            brand.contains("ecarx", ignoreCase = true)
    }

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    @com.shilapi.xcertplay.compat.RequiresApi(21)
    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? {
        @Suppress("DEPRECATION")
        val drawable = context.resources.getDrawable(R.drawable.art_now_playing_placeholder) ?: return null
        val width = MAX_ARTWORK_DIMENSION
        val height = MAX_ARTWORK_DIMENSION
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return squareArtwork(decoded).also { square -> if (square !== decoded) decoded.recycle() }
    }

    /**
     * Fits [source] inside a transparent square of at most [MAX_ARTWORK_DIMENSION]. Car clusters draw
     * art in a square box and stretch it, so 16:9 video thumbnails looked squashed.
     */
    internal fun squareArtwork(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (source.width == source.height && largest <= MAX_ARTWORK_DIMENSION) return source
        val side = minOf(largest, MAX_ARTWORK_DIMENSION)
        val scale = side.toFloat() / largest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (side - width) / 2
        val top = (side - height) / 2
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { square ->
            Canvas(square).drawBitmap(
                source,
                null,
                Rect(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

class LegacyMediaKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CarPlayMediaKeys.onLegacyMediaButton(intent)
}

