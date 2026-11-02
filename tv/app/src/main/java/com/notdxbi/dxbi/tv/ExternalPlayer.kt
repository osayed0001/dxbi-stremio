package com.notdxbi.dxbi.tv

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import org.json.JSONObject

/**
 * Plays a stream in another app (VLC, mpv, MX Player, Just Player…), with what they understand of
 * its title, headers, start position and subtitles. When it closes, the position it reports goes
 * back to Stremio like the built-in player's does, so Continue Watching stays right.
 */
object ExternalPlayer {

    @Volatile private var torrent = false

    fun open(activity: MainActivity, json: String, launcher: ActivityResultLauncher<Intent>) {
        val stream = JSONObject(json)
        val url = stream.optString("url")
        if (url.isEmpty()) {
            PlaybackBus.closed(0, 0, ended = false, error = "no stream address")
            return
        }
        torrent = stream.optBoolean("torrent")
        val title = listOf(stream.optString("title"), stream.optString("subtitle")).filter { it.isNotEmpty() }.joinToString(" · ")
        val startMs = stream.optLong("startMs", 0L)
        val headers = stream.optJSONObject("headers")?.let { h -> h.keys().asSequence().flatMap { sequenceOf(it, h.optString(it)) }.toList() } ?: emptyList()
        val subs = stream.optJSONArray("subtitles")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } } ?: emptyList()

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            // MX Player, mpv and Just Player
            .putExtra("title", title)
            .putExtra("position", startMs.toInt())
            .putExtra("return_result", true)
            .putExtra("secure_uri", true)
            // VLC
            .putExtra("from_start", startMs <= 0)
        if (headers.isNotEmpty()) view.putExtra("headers", headers.toTypedArray())
        if (subs.isNotEmpty()) {
            view.putExtra("subs", subs.map { Uri.parse(it.optString("url")) }.toTypedArray<Parcelable>())
            view.putExtra("subs.name", subs.map { it.optString("label") }.toTypedArray())
            view.putExtra("subtitles_location", subs.first().optString("url"))
        }
        try {
            launcher.launch(Intent.createChooser(view, "Play with"))
        } catch (_: Exception) {
            Toast.makeText(activity, "No app on this phone can play videos from a link. VLC or mpv can.", Toast.LENGTH_LONG).show()
            finished(null)
        }
    }

    /** The other app closed: where it got to (each app says it its own way), or nothing */
    fun finished(result: Intent?) {
        val extras = result?.extras
        fun long(vararg keys: String): Long = keys.firstNotNullOfOrNull { k -> extras?.get(k)?.let { (it as? Number)?.toLong() } } ?: 0L
        val position = long("position", "extra_position")
        val duration = long("duration", "extra_duration")
        val ended = extras?.getString("end_by") == "playback_completion" || (duration > 0 && position >= duration - 1000)
        if (torrent) TorrentEngine.close()
        torrent = false
        PlaybackBus.closed(position, duration, ended, error = null)
    }
}
