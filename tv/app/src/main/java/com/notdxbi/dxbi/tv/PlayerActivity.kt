package com.notdxbi.dxbi.tv

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaSession
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The native player: plays the stream Stremio picked (MKV, HEVC, HDR and surround sound included,
 * as far as the TV's hardware can), with the addons' subtitles. Reports its position back so the
 * watch progress is kept.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controls: Controls
    private var gestures: PlayerGestures? = null
    private var session: MediaSession? = null
    // a download playing from the phone (its id), rather than a stream the page handed over
    private var offlineId: String? = null
    // the subtitles' size (Stremio's setting), put back after the small window's bigger one
    private var subtitleSize = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION
    private lateinit var zoom: PictureZoom
    private var party = false
    private val partyTick = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            PlaybackBus.listener?.onPartyTick(exo.currentPosition, duration, !exo.playWhenReady, exo.playbackState == Player.STATE_BUFFERING, exo.playbackParameters.speed)
            handler.postDelayed(this, 500)
        }
    }
    private val hideToast = Runnable { seekFlash.visibility = View.GONE }

    /** The page's watch party: plays, pauses and seeks with everyone, and says what's happening */
    fun control(json: String) {
        val exo = player ?: return
        val c = JSONObject(json)
        when (c.optString("cmd")) {
            "play" -> exo.play()
            "pause" -> exo.pause()
            "seek" -> exo.seekTo(c.optLong("ms"))
            "rate" -> exo.setPlaybackSpeed(c.optDouble("rate", 1.0).toFloat())
            "close" -> finishWith()
            "party" -> {
                val on = c.optBoolean("on")
                if (on != party) {
                    party = on
                    handler.removeCallbacks(partyTick)
                    if (on) handler.post(partyTick)
                }
                controls.party(on, c.optString("code"), c.optJSONArray("people")?.let { list -> (0 until list.length()).map { list.optString(it) } } ?: emptyList())
            }
            "toast" -> {
                seekFlash.text = c.optString("text")
                seekFlash.visibility = View.VISIBLE
                handler.removeCallbacks(hideToast)
                handler.postDelayed(hideToast, 3500)
            }
        }
    }
    private val sound = SoundMode()
    @Volatile private var subtitleDelayMs = 0L
    private val refreshSubtitles = Runnable { player?.let { it.seekTo(it.currentPosition) } }
    // Up next: what's after this episode, how long the card counts down (0: never), the card's state
    private var nextTitle = ""
    private var nextArt = ""
    private var upNextSeconds = 0
    private var upNextLeft = 0
    private var upNextDismissed = false
    private lateinit var upNextCard: View
    private val upNextCount = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (upNextCard.visibility != View.VISIBLE) return
            if (exo.isPlaying) upNextLeft--
            findViewById<TextView>(R.id.up_next_count).text = upNextLeft.coerceAtLeast(0).toString()
            if (upNextLeft <= 0) finishWith(next = true) else handler.postDelayed(this, 1000)
        }
    }

    private inner class Renderers : DefaultRenderersFactory(this) {
        override fun buildTextRenderers(context: android.content.Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
            val own = ArrayList<Renderer>()
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, own)
            own.mapTo(out) { DelayedText(it) { subtitleDelayMs * 1000 } }
        }

        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false) // (Dialogue boost works on 16-bit sound)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(sound))
                .build()
    }

    private val phone get() = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType != Configuration.UI_MODE_TYPE_TELEVISION &&
        !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    // The phone app on a phone: its own controls, gestures, small window and lock screen controls
    // (dx.bi TV on a phone stays as it always was)
    private val phoneApp get() = BuildConfig.PHONE_UI && phone
    private lateinit var clock: TextView
    private var showClock = true
    private lateinit var seekFlash: TextView
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var lastError: String? = null
    private var lastText: List<String?> = emptyList()

    private val reportProgress = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }
    private val hideFlash = Runnable { seekFlash.visibility = View.GONE }

    // Skip intro / recap / credits: the episode's times (seconds; end -1 = to the end), whether dx.bi
    // skips by itself ("intro": intros and recaps, "all": credits too), and which were skipped already
    private class Segment(val type: String, val start: Double, val end: Double)
    private var segments: List<Segment> = emptyList()
    private var autoSkip = "off"
    private val autoSkipped = mutableSetOf<Segment>()
    private var showing: Segment? = null
    private lateinit var skipButton: TextView
    // A torrent played on the TV itself: how it's going, while the picture waits for it
    private var torrent = false
    private val torrentStatus = object : Runnable {
        override fun run() {
            val exo = player
            val waiting = exo == null || exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE
            val text = if (torrent && waiting) TorrentEngine.status() else null
            if (text != null) {
                seekFlash.text = text
                seekFlash.visibility = View.VISIBLE
            } else if (seekFlash.text.contains("peer") || seekFlash.text.startsWith("Finding")) {
                seekFlash.visibility = View.GONE
            }
            handler.postDelayed(this, 700)
        }
    }
    private val updateClock = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null) {
                val format = android.text.format.DateFormat.getTimeFormat(this@PlayerActivity)
                val left = if (exo.duration > 0) ((exo.duration - exo.currentPosition) / exo.playbackParameters.speed).toLong() else 0L
                val now = System.currentTimeMillis()
                clock.text = format.format(now) + if (left > 0) " · Ends at ${format.format(now + left)}" else ""
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val checkSkip = object : Runnable {
        override fun run() {
            updateSkip()
            updateUpNext()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones turn freely; TVs stay landscape
        if (phone) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        clock = findViewById(R.id.clock)
        seekFlash = findViewById(R.id.seek_flash)
        skipButton = findViewById(R.id.skip_button)
        skipButton.setOnClickListener { doSkip() }

        val stream = JSONObject(intent.getStringExtra(EXTRA_STREAM) ?: "{}")
        segments = stream.optJSONArray("skips")?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type")
                if (type !in setOf("intro", "recap", "credits")) return@mapNotNull null
                Segment(type, o.optDouble("start", 0.0), if (o.isNull("end")) -1.0 else o.optDouble("end", -1.0))
            }
        } ?: emptyList()
        autoSkip = stream.optString("autoSkip", "off")
        showClock = stream.optBoolean("clock", true)
        torrent = stream.optBoolean("torrent", false)
        findViewById<TextView>(R.id.title).text = stream.optString("title")
        findViewById<TextView>(R.id.subtitle).apply {
            text = stream.optString("subtitle")
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        stream.optJSONObject("subtitleStyle")?.let { styleSubtitles(it) }

        val url = stream.optString("url")
        if (url.isEmpty()) {
            finishWith(error = "no stream address")
            return
        }

        // Addons can ask for headers on their links (Stremio's proxyHeaders)
        val headers = mutableMapOf<String, String>()
        stream.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // a torrent's first bytes wait for its details and first pieces to come from peers
            .readTimeout(if (torrent) 150L else 30L, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        // (an addon's own User-Agent replaces ours: Media3 would send both)
        val ownAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        val online = OkHttpDataSource.Factory(http)
            .setUserAgent(if (ownAgent) null else "dxbi-tv/${BuildConfigVersion.name(this)}")
            .setDefaultRequestProperties(headers)
        // a download plays from the phone
        offlineId = stream.optString("offline").ifEmpty { null }
        val dataSource = if (offlineId != null) Downloads.playback(this) else online

        // The TV's own decoders first, falling back to software ones when a format isn't supported
        val renderers = Renderers()
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
        player = exo
        playerView.player = exo
        // headphone buttons, Bluetooth and the small window's controls reach the player
        if (phoneApp) session = MediaSession.Builder(this, exo).setId("dxbi-player").build()
        val accent = try { Color.parseColor(stream.optString("accent")) } catch (_: Exception) { Color.parseColor("#E50914") }
        zoom = PictureZoom(playerView)
        val next = stream.optJSONObject("next")
        nextTitle = next?.optString("title").orEmpty()
        nextArt = next?.optString("thumbnail").orEmpty()
        upNextSeconds = if (next == null) 0 else stream.optString("upNext", "10").toIntOrNull() ?: 0
        upNextCard = findViewById(R.id.up_next)
        val leaveParty = { PlaybackBus.listener?.onPartyLeave(); Unit }
        // Phones: their own controls, laid out like a streaming app's, and worked by touch
        controls = if (phoneApp) PhoneControls(
            this, playerView, exo, sound, zoom, ::shiftSubtitles, accent,
            title = stream.optString("title"),
            subtitle = stream.optString("subtitle"),
            hasNext = next != null,
            showClock = showClock,
            seek = ::seek,
            leaveParty = leaveParty,
            leave = { go -> finishWith(next = go) },
            onLock = { gestures?.lock() },
            onUnlock = { gestures?.unlock() },
            onExternal = { playElsewhere() },
        ) else PlayerControls(this, playerView, exo, sound, zoom, ::shiftSubtitles, accent, clock.takeIf { showClock }, next != null, ::seek, leaveParty) { go -> finishWith(next = go) }
        PlaybackBus.player = this
        // Phones: taps, double taps, slides and pinches (the remote still works the same everywhere)
        if (phoneApp) {
            val touch = PlayerGestures(this, playerView, exo, controls, ::seek, ::flash).also { gestures = it }
            touch.onLockedTap = { (controls as? PhoneControls)?.showUnlock() }
            playerView.setOnTouchListener(touch)
            // messages (±10 s, brightness, speed…) small at the top, off the picture's middle
            seekFlash.layoutParams = (seekFlash.layoutParams as android.widget.FrameLayout.LayoutParams).apply { topMargin = (22 * resources.displayMetrics.density).toInt() }
            seekFlash.textSize = 14f
            (6 * resources.displayMetrics.density).toInt().let { v -> seekFlash.setPadding(v * 2, v, v * 2, v) }
        }
        fitControls()
        findViewById<View>(R.id.up_next_play).setOnClickListener { finishWith(next = true) }
        findViewById<View>(R.id.up_next_cancel).setOnClickListener {
            upNextDismissed = true
            hideUpNext()
        }

        // Subtitles start off, like Netflix (and dx.bi's default): pick one from the subtitles button.
        // Files with several audio tracks (dual-audio releases) start in Stremio's audio language
        // (Settings → Player), when they have it
        val audioLanguage = stream.optString("audioLanguage").ifEmpty { null }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(null)
            .setSelectUndeterminedTextLanguage(false)
            .apply { if (audioLanguage != null) setPreferredAudioLanguage(audioLanguage) }
            .build()

        val subtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
        stream.optJSONArray("subtitles")?.let { list ->
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val subUrl = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                subtitles += MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                    // where it came from, for the phone app's subtitle menu ("stream:3", "addon:7")
                    .setId(if (phoneApp) "${s.optString("source", "addon")}:$i" else null)
                    .setMimeType(subtitleMime(subUrl))
                    .setLanguage(s.optString("lang").ifEmpty { null })
                    .setLabel(s.optString("label").ifEmpty { s.optString("lang") })
                    .setSelectionFlags(0)
                    .build()
            }
        }

        val item = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setSubtitleConfigurations(subtitles)
            .build()
        exo.setMediaItem(item, stream.optLong("startMs", 0L).coerceAtLeast(0L))
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finishWith(ended = true, next = upNextSeconds > 0 && !upNextDismissed)
            }

            // A subtitle switched on mid-line only shows from its next line: a seek to where we are
            // brings the current one up straight away
            override fun onTracksChanged(tracks: Tracks) {
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { i -> g.getTrackFormat(i).id } }
                if (text.isNotEmpty() && text != lastText) exo.seekTo(exo.currentPosition)
                lastText = text
            }

            override fun onPlayerError(error: PlaybackException) {
                lastError = (if (torrent) TorrentEngine.failed() else null) ?: describe(error)
                finishWith(error = lastError)
            }

            // the small window takes the video's shape, and its play button follows the player
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updatePip()
            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
        })
        if (phoneApp) {
            ContextCompat.registerReceiver(this, pipButtons, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
            updatePip()
        }
        exo.prepare()
        exo.playWhenReady = true
        handler.postDelayed(reportProgress, PROGRESS_INTERVAL_MS)
        if (showClock) handler.post(updateClock)
        handler.postDelayed(checkSkip, 500)
        if (torrent) handler.post(torrentStatus)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back leaves the player straight away, like Netflix (the position is kept)
            override fun handleOnBackPressed() {
                if (!controls.closePanel()) finishWith()
            }
        })
    }

    /**
     * With the controls hidden, the remote works like Netflix's: left/right skip 10 seconds, OK
     * pauses or plays. With the controls showing, the arrows move between them as usual.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { exo.playWhenReady = !exo.playWhenReady; controls.show(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); controls.show(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(+1); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-1); return true }
            }
            // OK presses Skip while it's up (and the controls aren't)
            if (showing != null && !controls.visible && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                doSkip()
                return true
            }
            if (controls.panelKey(event.keyCode)) return true
            if (controls.visible) controls.poke()
            // The up next card's buttons take the remote
            if (upNextCard.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
            if (!controls.visible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(+1); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        exo.playWhenReady = !exo.playWhenReady
                        controls.show()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { controls.show(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun segmentEnd(s: Segment, durationMs: Long): Long =
        if (s.end < 0) durationMs else (s.end * 1000).toLong()

    private fun updateSkip() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        val now = exo.currentPosition
        // (not the last second of a part: pressing then would skip nothing)
        // (the up next card takes over from Skip credits)
        val seg = segments.firstOrNull { now >= (it.start * 1000).toLong() && now < segmentEnd(it, duration) - 1000 && !(it.type == "credits" && upNextSeconds > 0) }
        val auto = seg != null && (autoSkip == "all" || (autoSkip == "intro" && seg.type != "credits"))
        if (seg != null && auto && seg !in autoSkipped) {
            autoSkipped += seg
            doSkip(seg)
            return
        }
        if (seg == showing) return
        showing = seg
        if (seg == null) {
            skipButton.visibility = View.GONE
            return
        }
        skipButton.text = when (seg.type) {
            "intro" -> "Skip intro"
            "recap" -> "Skip recap"
            else -> "Skip credits"
        }
        skipButton.visibility = View.VISIBLE
        if (!controls.visible) skipButton.requestFocus()
    }

    private fun updateUpNext() {
        val exo = player ?: return
        if (upNextSeconds <= 0 || exo.duration == C.TIME_UNSET || exo.duration < 120_000) return
        val duration = exo.duration
        val credits = segments.firstOrNull { it.type == "credits" }?.let { (it.start * 1000).toLong() }?.takeIf { it > duration / 2 && it < duration }
        val at = credits ?: (duration - 30_000)
        if (exo.currentPosition < at - 2000) {
            upNextDismissed = false
            hideUpNext()
        } else if (exo.currentPosition >= at && !upNextDismissed && upNextCard.visibility != View.VISIBLE) {
            showUpNext()
        }
    }

    private fun showUpNext() {
        if (controls.visible) controls.hide()
        upNextLeft = upNextSeconds
        findViewById<TextView>(R.id.up_next_title).text = nextTitle
        findViewById<TextView>(R.id.up_next_count).text = upNextLeft.toString()
        upNextCard.visibility = View.VISIBLE
        findViewById<View>(R.id.up_next_play).requestFocus()
        handler.postDelayed(upNextCount, 1000)
        if (nextArt.startsWith("https://")) kotlin.concurrent.thread(name = "dxbi-up-next-art") {
            val art = try { java.net.URL(nextArt).openStream().use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
            if (art != null) runOnUiThread { findViewById<ImageView>(R.id.up_next_art).setImageBitmap(art) }
        }
    }

    private fun hideUpNext() {
        if (upNextCard.visibility != View.VISIBLE) return
        upNextCard.visibility = View.GONE
        handler.removeCallbacks(upNextCount)
        playerView.requestFocus()
    }

    /** Moves the subtitles by [byMs] and returns where they are now */
    private fun shiftSubtitles(byMs: Long): Long {
        if (byMs != 0L) {
            subtitleDelayMs += byMs
            // lines already passed only come back from a seek; once, after the last press
            handler.removeCallbacks(refreshSubtitles)
            handler.postDelayed(refreshSubtitles, 600)
        }
        return subtitleDelayMs
    }

    fun refit() = fitControls()

    private fun fitControls() {
        val config = resources.configuration
        controls.fit(config.screenWidthDp, config.orientation == Configuration.ORIENTATION_PORTRAIT)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controls.isInitialized) fitControls()
    }

    private fun doSkip(seg: Segment? = showing) {
        val exo = player ?: return
        val s = seg ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: return
        exo.seekTo(segmentEnd(s, duration).coerceAtMost(duration))
        showing = null
        skipButton.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun seek(direction: Int) {
        val exo = player ?: return
        if (direction > 0) exo.seekForward() else exo.seekBack()
        seekFlash.text = if (direction > 0) "+10 s   ${clock(exo.currentPosition)}" else "−10 s   ${clock(exo.currentPosition)}"
        seekFlash.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash)
        handler.postDelayed(hideFlash, 900)
    }

    // -----------------------------------------------------------------------------------------------
    // Phones: carrying on in another app (VLC, mpv…) from where this one is

    private val elsewhere = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val r = ExternalPlayer.result(result.data)
        // where the other app got to is where this video's progress stands
        if (r.positionMs > 0) finishWith(ended = r.ended, positionMs = r.positionMs, durationMs = r.durationMs) else finishWith()
    }

    private fun playElsewhere() {
        val exo = player ?: return
        val stream = JSONObject(intent.getStringExtra(EXTRA_STREAM) ?: "{}")
        if (offlineId != null) return flash("Downloads play here only", 1500)
        val chooser = ExternalPlayer.chooser(stream, exo.currentPosition) ?: return
        exo.pause()
        try {
            elsewhere.launch(chooser)
        } catch (_: Exception) {
            ExternalPlayer.noApp(this)
        }
    }

    /** A moment of feedback in the middle of the picture ("" hides it) */
    private fun flash(text: String, forMs: Long) {
        handler.removeCallbacks(hideFlash)
        if (text.isEmpty()) {
            seekFlash.visibility = View.GONE
            return
        }
        seekFlash.text = text
        seekFlash.visibility = View.VISIBLE
        handler.postDelayed(hideFlash, forMs)
    }

    private fun report() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (offlineId != null) Downloads.leftAt(offlineId!!, exo.currentPosition, ended = false)
        else PlaybackBus.listener?.onProgress(exo.currentPosition, duration, !exo.isPlaying)
    }

    /** Closes the player and says how it went ([positionMs]/[durationMs]: where another app left it, instead of this one) */
    private fun finishWith(ended: Boolean = false, error: String? = null, next: Boolean = false, positionMs: Long? = null, durationMs: Long? = null) {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (::controls.isInitialized) controls.release()
        val exo = player
        val duration = durationMs?.takeIf { it > 0 } ?: exo?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (ended) duration else positionMs ?: exo?.currentPosition ?: 0L
        // A download wasn't handed over by the page: its place is kept here instead
        val offline = offlineId
        if (offline != null) Downloads.leftAt(offline, position, ended)
        else PlaybackBus.closed(position, duration, ended, error, next)
        if (torrent) TorrentEngine.close()
        finish()
    }

    // -----------------------------------------------------------------------------------------------
    // Phones: leaving the app while a video plays keeps it playing in a small window

    private val pipButtons = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("do")) {
                "play" -> player?.play()
                "pause" -> player?.pause()
                "back" -> player?.seekBack()
                "forward" -> player?.seekForward()
            }
        }
    }

    private fun pipAction(icon: Int, title: String, what: String, code: Int): RemoteAction {
        val intent = PendingIntent.getBroadcast(this, code, Intent(ACTION_PIP).setPackage(packageName).putExtra("do", what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, icon), title, title, intent)
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val exo = player ?: return null
        val size = exo.videoSize
        // (Android only takes shapes between 2.39:1 and 1:2.39)
        val ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
        val shape = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 1 / 2.39f -> Rational(100, 239)
            else -> ratio
        }
        val playing = exo.isPlaying
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(shape)
            .setActions(listOf(
                pipAction(R.drawable.ic_rewind, "Back 10 seconds", "back", 1),
                if (playing) pipAction(R.drawable.ic_pause, "Pause", "pause", 2) else pipAction(R.drawable.ic_play, "Play", "play", 2),
                pipAction(R.drawable.ic_forward, "Forward 10 seconds", "forward", 3),
            ))
        // Android 12 and later go into the small window by themselves, smoothly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        return builder.build()
    }

    private fun updatePip() {
        if (!phoneApp || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // (before Android 12 the app asks for the small window itself)
        if (phoneApp && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && player?.isPlaying == true) {
            pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) {
            // just the picture in the small window
            if (::controls.isInitialized) controls.hide()
            skipButton.visibility = View.GONE
            seekFlash.visibility = View.GONE
            if (::upNextCard.isInitialized) upNextCard.visibility = View.GONE
            playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.6f)
        } else {
            playerView.subtitleView?.setFractionalTextSize(subtitleSize)
            // The small window was closed (not opened back up): the video's over
            if (lifecycle.currentState == Lifecycle.State.CREATED) finishWith()
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app (Home button) pauses, unless it carries on in the small window; the progress so far is kept
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) player?.pause()
        report()
    }

    override fun onDestroy() {
        if (!closed) finishWith()
        handler.removeCallbacksAndMessages(null)
        if (PlaybackBus.player === this) PlaybackBus.player = null
        if (::controls.isInitialized) controls.release()
        if (::zoom.isInitialized) zoom.release()
        gestures?.release()
        if (phoneApp) runCatching { unregisterReceiver(pipButtons) }
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Stremio's subtitle settings (size, colours, outline, position) instead of the player's white
     * text on black boxes. Colours come as #RRGGBB or #RRGGBBAA.
     */
    private fun styleSubtitles(style: JSONObject) {
        val view = playerView.subtitleView ?: return
        fun color(key: String, fallback: Int): Int {
            val hex = style.optString(key).removePrefix("#")
            val v = hex.toLongOrNull(16) ?: return fallback
            return when (hex.length) {
                6 -> (0xFF000000 or v).toInt()
                8 -> ((v and 0xFF) shl 24 or (v ushr 8)).toInt()
                else -> fallback
            }
        }
        val opacity = (style.optDouble("opacity", 100.0) / 100).coerceIn(0.0, 1.0)
        val text = color("text", Color.WHITE).let { Color.argb((Color.alpha(it) * opacity).toInt(), Color.red(it), Color.green(it), Color.blue(it)) }
        val outline = color("outline", Color.BLACK)
        val typeface = if (style.optBoolean("bold")) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setStyle(CaptionStyleCompat(
            text,
            color("background", Color.TRANSPARENT),
            Color.TRANSPARENT,
            if (Color.alpha(outline) > 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
            outline,
            typeface,
        ))
        // the file's own colours and sizes would win over these otherwise
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        val size = style.optDouble("size", 100.0).coerceIn(50.0, 300.0) / 100
        subtitleSize = (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size).toFloat()
        view.setFractionalTextSize(subtitleSize)
        // Stremio's offset is how far up from the bottom of the picture the lines sit
        val offset = style.optDouble("offset", 5.0).coerceIn(0.0, 40.0) / 100
        view.setBottomPaddingFraction(0f)
        view.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val pad = ((bottom - top) * offset).toInt()
            if (v.paddingBottom != pad) v.post { v.setPadding(0, 0, 0, pad) }
        }
    }

    private fun subtitleMime(url: String): String {
        val path = Uri.parse(url).lastPathSegment?.lowercase() ?: ""
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "the server refused it"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "the connection failed"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            // No software decoders are bundled: DTS and TrueHD audio (common in BluRay rips) only play
            // on TVs that decode them
            "this TV can't decode its video or audio format (DTS and TrueHD audio often can't play: pick a release with AAC, AC3 or DD+ audio)"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "its file format isn't supported"
        else -> error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val EXTRA_STREAM = "stream"
        private const val ACTION_PIP = "com.notdxbi.dxbi.tv.PIP"
        private const val PROGRESS_INTERVAL_MS = 5000L
        private const val SEEK_STEP_MS = 10_000L
    }
}
