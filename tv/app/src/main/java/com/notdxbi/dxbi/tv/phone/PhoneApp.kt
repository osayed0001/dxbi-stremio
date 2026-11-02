package com.notdxbi.dxbi.tv.phone

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Accent = Color(0xFFE50914)
private val Background = Color(0xFF0B0B0B)

/**
 * The phone app. For now the engine check (phase 1 of the mobile plan): every piece of Stremio the
 * native screens will need, read through [Engine], with the page hidden underneath.
 */
@Composable
fun PhoneApp(engine: Engine, showPage: (Boolean) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Background)) {
        var pageShown by remember { mutableStateOf(false) }
        if (pageShown) {
            // The page on its own, to compare with: Back comes back here
            BackHandler { pageShown = false; showPage(false) }
            return@MaterialTheme
        }
        Box(Modifier.fillMaxSize().background(Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            EngineCheck(engine, onShowPage = { pageShown = true; showPage(true) })
        }
    }
}

@Composable
private fun EngineCheck(engine: Engine, onShowPage: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ready by engine.ready.collectAsState()
    val ctx by engine.state("ctx").collectAsState()
    val board by engine.state("board").collectAsState()
    val continueWatching by engine.state("continue_watching_preview").collectAsState()
    val search by engine.state("search").collectAsState()
    val details by engine.state("meta_details").collectAsState()
    var log by remember { mutableStateOf(listOf<String>()) }
    val started = remember { SystemClock.elapsedRealtime() }
    fun note(text: String) { log = (listOf("${(SystemClock.elapsedRealtime() - started) / 100 / 10.0}s  $text") + log).take(12) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }

    fun call(label: String, op: String, args: JSONObject) = scope.launch {
        try { engine.call(op, args) } catch (e: Exception) { note("$label failed: ${e.message}") }
    }

    fun open(item: JSONObject, videoId: String? = null) {
        selected = item
        val type = item.optString("type")
        val id = item.optString("id")
        val args = JSONObject()
            .put("metaPath", JSONObject().put("resource", "meta").put("type", type).put("id", id).put("extra", JSONArray()))
            .put("streamPath", (videoId ?: if (type == "movie") id else null)?.let {
                JSONObject().put("resource", "stream").put("type", type).put("id", it).put("extra", JSONArray())
            } ?: JSONObject.NULL)
            .put("guessStream", true)
        note("loading ${item.optString("name")}${videoId?.let { " · $it" } ?: ""}")
        call("details", "load", JSONObject().put("field", "meta_details").put("model", "MetaDetails").put("args", args))
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        note("engine ready")
        call("board", "load", JSONObject().put("field", "board").put("model", "CatalogsWithExtra").put("args", JSONObject().put("extra", JSONArray())))
        delay(300)
        call("rows", "loadRange", JSONObject().put("field", "board").put("start", 0).put("end", 20))
    }
    LaunchedEffect(board != null) { if (board != null) note("Home rows arrived") }
    LaunchedEffect(ctx != null) { if (ctx != null) note("account arrived") }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DX.BI", color = Accent, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Text("engine check", color = Color.Gray, modifier = Modifier.weight(1f))
                TextButton(onClick = onShowPage) { Text("Show page") }
            }
            val c = ctx
            Text(
                when {
                    !ready -> "Starting Stremio…"
                    c == null -> "Engine ready, waiting for the account"
                    c.optBoolean("signedIn") -> "Signed in as ${c.optJSONObject("user")?.optString("email")} · ${c.optJSONArray("addons")?.length() ?: 0} addons"
                    else -> "Not signed in"
                },
                color = Color.White,
            )
            log.forEach { Text(it, color = Color.Gray, fontSize = 11.sp) }
        }

        item { Section("Continue watching") }
        item {
            val items = continueWatching?.optJSONArray("items").objects()
            if (items.isEmpty()) Text("—", color = Color.Gray)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { item ->
                    Poster(item, progress = item.optDouble("progress", 0.0).toFloat() / 100f) {
                        open(item, item.optString("videoId").takeIf { it.isNotEmpty() })
                    }
                }
            }
        }

        item { Section("Search") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Films, series…") },
                modifier = Modifier.fillMaxWidth(),
            )
            LaunchedEffect(query) {
                if (query.length < 2) return@LaunchedEffect
                delay(400)
                note("searching \"$query\"")
                val extra = JSONArray().put(JSONArray().put("search").put(query))
                engine.call("load", JSONObject().put("field", "search").put("model", "CatalogsWithExtra").put("args", JSONObject().put("extra", extra)))
                engine.call("loadRange", JSONObject().put("field", "search").put("start", 0).put("end", 10))
            }
        }
        if (query.length >= 2) rows(search, ::open)

        details?.let { d -> item { Details(d, selected, onEpisode = { v -> selected?.let { open(it, v) } }, onPlay = { link ->
            note("play")
            call("play", "play", JSONObject().put("link", link))
        }) } }

        item { Section("Home") }
        rows(board, ::open)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rows(state: JSONObject?, open: (JSONObject) -> Unit) {
    val catalogs = state?.optJSONArray("catalogs").objects()
    items(catalogs) { row ->
        Column {
            Text("${row.optString("name")} · ${row.optString("type")}  (${row.optString("addon")}, ${row.optString("state")})", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(row.optJSONArray("items").objects()) { item -> Poster(item) { open(item) } }
            }
        }
    }
}

@Composable
private fun Details(d: JSONObject, selected: JSONObject?, onEpisode: (String) -> Unit, onPlay: (String) -> Unit) {
    val meta = d.optJSONObject("meta")
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A)).padding(12.dp)) {
        Text(meta?.optString("name") ?: selected?.optString("name") ?: "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("details ${d.optString("state")} · ${meta?.optString("year") ?: ""} · IMDb ${meta?.optString("imdb") ?: "?"}", color = Color.Gray, fontSize = 12.sp)
        meta?.optString("description")?.takeIf { it.isNotEmpty() && it != "null" }?.let {
            Text(it, color = Color.LightGray, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val videos = meta?.optJSONArray("videos").objects()
        if (videos.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("${videos.size} episodes", color = Color.White, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(videos.filter { it.optInt("season") > 0 }.take(40)) { v ->
                    Text(
                        "S${v.optInt("season")}E${v.optInt("episode")}",
                        color = if (v.optBoolean("watched")) Color.Gray else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A)).clickable { onEpisode(v.optString("id")) }.padding(8.dp, 6.dp),
                    )
                }
            }
        }
        val groups = d.optJSONArray("streams").objects()
        Spacer(Modifier.height(8.dp))
        val selectedVideo = d.optJSONObject("selected")?.optJSONObject("streamPath")?.optString("id")
        Text(if (selectedVideo == null) "No episode picked: tap one for its streams" else "Streams for $selectedVideo", color = Color.White, fontSize = 13.sp)
        groups.forEach { g ->
            Text("${g.optString("addon")}: ${g.optString("state")} (${g.optJSONArray("items")?.length() ?: 0})", color = Color.Gray, fontSize = 12.sp)
            g.optJSONArray("items").objects().take(6).forEach { s ->
                val link = s.optString("play").takeIf { it.startsWith("#/player/") }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF242424))
                        .clickable(enabled = link != null) { link?.let(onPlay) }.padding(8.dp),
                ) {
                    Text(s.optString("name").replace('\n', ' '), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(s.optString("description"), color = Color.LightGray, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)

@Composable
private fun Poster(item: JSONObject, progress: Float? = null, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = item.optString("poster").takeIf { it.startsWith("http") },
            contentDescription = item.optString("name"),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF222222)),
        )
        if (progress != null) LinearProgressIndicator(progress = { progress }, color = Accent, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
        Text(item.optString("name"), color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
