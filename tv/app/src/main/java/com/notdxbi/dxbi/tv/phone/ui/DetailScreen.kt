package com.notdxbi.dxbi.tv.phone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.phone.Details
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stream
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.Video

/**
 * A title: art, what it is, Play, My List, and for a series its seasons and episodes. Streams come up
 * in a sheet when something is played.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(stremio: Stremio, type: String, id: String, preview: Title?, play: Boolean, onBack: () -> Unit, onSearch: (String) -> Unit) {
    val all by stremio.details.collectAsState()
    // what's shown is this title's (the model can still hold the last title's for a moment)
    val details = all?.takeIf { it.metaId == id }
    val title = details?.title ?: preview ?: Title(id, type, "")
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf(false) }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(type, id) { stremio.open(type, id) }

    /** What Play starts: the episode they're in the middle of, else the first one */
    fun nextVideo(d: Details): Video? {
        if (type != "series") return null
        return d.videos.firstOrNull { it.id == d.resumeVideoId } ?: d.videos.firstOrNull { (it.season ?: 0) > 0 && !it.upcoming } ?: d.videos.firstOrNull()
    }

    fun playVideo(videoId: String?) {
        if (videoId != null && videoId != details?.videoId) stremio.open(type, id, videoId)
        sheet = true
    }

    var autoPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(play, details?.state) {
        if (play && !autoPlayed && details?.state == Load.Ready) {
            autoPlayed = true
            playVideo(nextVideo(details)?.id)
        }
    }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                    AsyncImage(model = title.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Dx.Background)))
                    TitleArt(title, Modifier.align(Alignment.BottomStart).padding(start = Gutter, bottom = 8.dp).fillMaxWidth(0.55f).heightIn(max = 70.dp), textSize = 0)
                }
            }
            item { Header(title, details, onPlay = { details?.let { playVideo(nextVideo(it)?.id) } ?: run { sheet = true } }) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    val inList = details?.title?.inLibrary == true
                    Action(if (inList) Icons.Filled.Check else Icons.Filled.Add, "My List", enabled = details?.state == Load.Ready) { stremio.setInList(!inList) }
                    title.trailer?.let { yt ->
                        Action(Icons.Filled.Theaters, "Trailer") {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$yt")))
                        }
                    }
                    Action(Icons.Filled.Share, "Share") {
                        val link = if (id.startsWith("tt")) "https://www.imdb.com/title/$id" else title.name
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${title.name} $link"), null))
                    }
                }
            }
            if (details == null || details.state == Load.Loading) item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dx.Accent) }
            }
            if (details != null && type == "series" && details.videos.isNotEmpty()) {
                val seasons = details.seasons
                val current = season ?: nextVideo(details)?.season ?: seasons.firstOrNull()
                item { SeasonPicker(seasons, current) { season = it } }
                items(details.videos.filter { it.season == current }, key = { it.id }) { v ->
                    Episode(v, resumeHere = v.id == details.resumeVideoId) { playVideo(v.id) }
                }
            }
            if (details != null && details.cast.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = Gutter, vertical = 16.dp)) {
                    Text("Cast", style = MaterialTheme.typography.titleSmall, color = Dx.Text)
                    Spacer(Modifier.height(8.dp))
                    // a person's name searches for their other titles
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            details.cast.take(8).chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { name -> Pill(selected = false, onClick = { onSearch(name) }) { Text(name, color = Dx.Text, style = MaterialTheme.typography.bodySmall) } }
                                }
                            }
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
    }

    if (sheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state, containerColor = Dx.Surface) {
            Streams(details, type, onPlay = { s ->
                sheet = false
                stremio.play(s)
            })
        }
    }
}

@Composable
private fun Header(t: Title, d: Details?, onPlay: () -> Unit) {
    Column(Modifier.padding(horizontal = Gutter)) {
        if (t.name.isNotEmpty()) Text(t.name, style = MaterialTheme.typography.headlineMedium, color = Dx.Text)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bits = listOfNotNull(t.year, t.runtime?.let(::shortRuntime))
            bits.forEachIndexed { i, b ->
                if (i > 0) Spacer(Modifier.width(10.dp))
                Text(b, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            t.imdb?.let { Spacer(Modifier.width(10.dp)); ImdbBadge(it) }
        }
        if (t.genres.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(t.genres.joinToString(" · "), color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        val resume = d?.resumeVideoId?.let { rid -> d.videos.firstOrNull { it.id == rid } }
        val resumeMovie = t.type != "series" && (d?.resumeMs ?: 0) > 0
        Button(
            onClick = onPlay,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    resume?.season != null -> "Resume S${resume.season}:E${resume.episode}"
                    resumeMovie -> "Resume"
                    else -> "Play"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (resumeMovie && d != null && d.durationMs > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressLine(d.resumeMs.toFloat() / d.durationMs, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text("${((d.durationMs - d.resumeMs) / 60000).coerceAtLeast(1)}m left", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        t.description?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Dx.Text, style = MaterialTheme.typography.bodyMedium)
        }
        if (d != null && d.directors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Director: ${d.directors.joinToString(", ")}", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "107 min" as "1h 47m" */
fun shortRuntime(runtime: String): String {
    val mins = Regex("""\d+""").find(runtime)?.value?.toIntOrNull() ?: return runtime
    if (!runtime.contains("min") && runtime.contains("h")) return runtime
    return if (mins < 60) "${mins}m" else "${mins / 60}h ${if (mins % 60 > 0) "${mins % 60}m" else ""}".trim()
}

@Composable
private fun Action(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) Dx.Text else Dx.Faint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SeasonPicker(seasons: List<Int>, current: Int?, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = Gutter, vertical = 12.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Dx.Raised).clickable(enabled = seasons.size > 1) { open = true }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(seasonName(current), color = Dx.Text, style = MaterialTheme.typography.labelLarge)
            if (seasons.size > 1) Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Dx.Text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            seasons.forEach { s -> DropdownMenuItem(text = { Text(seasonName(s)) }, onClick = { open = false; onPick(s) }) }
        }
    }
}

private fun seasonName(s: Int?) = when (s) {
    null -> "Episodes"
    0 -> "Specials"
    else -> "Season $s"
}

@Composable
private fun Episode(v: Video, resumeHere: Boolean, onPlay: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = !v.upcoming, onClick = onPlay).padding(horizontal = Gutter, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9f)) {
                Art(v.thumbnail, "", Modifier.fillMaxSize(), RoundedCornerShape(4.dp))
                if (!v.upcoming) Box(Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                val progress = v.progress ?: if (v.watched) 1f else null
                if (progress != null && progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${v.episode ?: ""}. ${v.title.ifEmpty { "Episode ${v.episode ?: ""}" }}", color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val note = when {
                    v.upcoming -> v.released?.take(10)?.let { "Coming $it" } ?: "Coming soon"
                    resumeHere -> "Continue watching"
                    v.watched -> "Watched"
                    else -> v.released?.take(4)
                }
                note?.let { Text(it, color = if (resumeHere) Dx.Accent else Dx.Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        v.overview?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Streams

/** What a stream's name and description say about it, the way addons write them */
private class StreamInfo(s: Stream) {
    private val all = "${s.name}\n${s.description}"
    val quality = Regex("""\b(2160p|4k|1440p|1080p|720p|576p|480p|360p)\b""", RegexOption.IGNORE_CASE).find(all)?.value?.uppercase()?.replace("2160P", "4K")
    val size = Regex("""💾\s*([\d.,]+\s*[KMGT]B)""").find(all)?.groupValues?.get(1)
    val seeds = Regex("""👤\s*(\d+)""").find(all)?.groupValues?.get(1)
    val hdr = Regex("""\b(HDR10\+?|HDR|DV|Dolby ?Vision)\b""", RegexOption.IGNORE_CASE).find(all)?.value
    val provider = s.name.lineSequence().firstOrNull()?.replace(Regex("""\[[^]]*]"""), "")?.trim().orEmpty()
    val badge = Regex("""\[([^]]+)]""").find(s.name)?.groupValues?.get(1)
    val ready = badge?.let { it.endsWith("+") || it.contains("⚡") } == true
    val pending = badge?.contains("download", ignoreCase = true) == true
    val file = s.filename ?: s.description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
}

@Composable
private fun Streams(d: Details?, type: String, onPlay: (Stream) -> Unit) {
    val groups = d?.streams.orEmpty()
    val episode = d?.videos?.firstOrNull { it.id == d.videoId }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = Gutter, vertical = 4.dp)) {
                Text(d?.title?.name ?: "", style = MaterialTheme.typography.titleLarge, color = Dx.Text)
                if (type == "series" && episode != null) Text("S${episode.season} E${episode.episode} · ${episode.title}", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }
        }
        val loading = d == null || d.videoId == null && type == "series" || groups.isEmpty() || groups.any { it.state == Load.Loading }
        val streams = groups.flatMap { g -> g.streams.map { g.addon to it } }
        if (streams.isEmpty() && !loading) item {
            Text("No streams found. Your addons may not have this one yet.", color = Dx.Muted, modifier = Modifier.padding(Gutter))
        }
        items(streams, key = { (addon, s) -> "$addon|${s.play ?: s.url ?: s.name}" }) { (_, s) -> StreamCard(s) { onPlay(s) } }
        if (loading) item {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Dx.Accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Finding streams…", color = Dx.Muted)
            }
        }
        item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
    }
}

@Composable
private fun StreamCard(s: Stream, onPlay: () -> Unit) {
    val info = remember(s) { StreamInfo(s) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Dx.Raised)
            .clickable(enabled = s.play != null, onClick = onPlay).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                info.quality?.let { Tag(it, strong = true) }
                info.hdr?.let { Tag(it.uppercase()) }
                when {
                    info.ready -> Tag("INSTANT", colour = Color(0xFF2E7D32))
                    info.pending -> Tag("NOT CACHED", colour = Color(0xFF8D6E00))
                    s.torrent -> Tag("TORRENT")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(info.file, color = Dx.Text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(info.provider.ifEmpty { null }, info.size, info.seeds?.let { "$it seeds" }).joinToString(" · "),
                color = Dx.Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Dx.Text)
    }
}

@Composable
private fun Tag(text: String, strong: Boolean = false, colour: Color? = null) {
    Text(
        text,
        color = if (strong) Color.Black else Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(colour ?: if (strong) Color.White else Color.White.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
