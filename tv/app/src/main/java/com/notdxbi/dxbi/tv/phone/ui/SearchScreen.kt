package com.notdxbi.dxbi.tv.phone.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(stremio: Stremio, initial: String, focus: Boolean, onOpen: (Title) -> Unit, onPlay: (Title) -> Unit) {
    var query by rememberSaveable { mutableStateOf(initial) }
    val results by stremio.search.collectAsState()
    val billboard by stremio.billboard.collectAsState()
    val rows by stremio.rows.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focuser = remember { FocusRequester() }

    LaunchedEffect(Unit) { if (focus && initial.isEmpty()) runCatching { focuser.requestFocus() } }
    LaunchedEffect(query) {
        if (query.trim().length < 2) return@LaunchedEffect
        delay(350)
        stremio.search(query.trim())
    }

    Column(Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.padding(Gutter, 10.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(6.dp)).background(Dx.Raised).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Dx.Muted)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search films, series, people", color = Dx.Faint, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Dx.Text),
                    cursorBrush = SolidColor(Dx.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focuser),
                )
            }
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Dx.Muted) }
        }

        if (query.trim().length < 2) {
            // Nothing typed yet: what's popular, as a list to start from
            val suggestions = (billboard + rows.filter { it.type == "movie" || it.type == "series" }.flatMap { it.items }).distinctBy { it.id }.take(30)
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + LocalBarSpace.current)) {
                item { Text("Recommended", style = MaterialTheme.typography.titleMedium, color = Dx.Text, modifier = Modifier.padding(Gutter, 8.dp)) }
                items(suggestions, key = { it.id }) { t -> Suggestion(t, onOpen = { onOpen(t) }, onPlay = { onPlay(t) }) }
            }
        } else {
            // the best of each row first (films, series…), rather than every film before any series
            val found = (0 until (results.maxOfOrNull { it.items.size } ?: 0))
                .flatMap { i -> results.mapNotNull { it.items.getOrNull(i) } }
                .distinctBy { "${it.type}:${it.id}" }
            val searching = results.isEmpty() || results.any { it.state == Load.Loading || it.state == Load.None }
            if (found.isEmpty() && !searching) {
                Text("Nothing found for \"$query\".", color = Dx.Muted, modifier = Modifier.padding(Gutter))
            } else {
                PosterGrid(found, loading = searching, header = "Films & Series", onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun Suggestion(t: Title, onOpen: () -> Unit, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = Gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(t.backdrop, t.name, Modifier.width(140.dp).aspectRatio(16f / 9f), RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Text(t.name, color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onPlay) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.Transparent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Dx.Text)
            }
        }
    }
}

/** Posters three to a row, with more fetched as the end comes near */
@Composable
fun PosterGrid(items: List<Title>, loading: Boolean, header: String? = null, onEnd: (() -> Unit)? = null, onOpen: (Title) -> Unit) {
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= grid.layoutInfo.totalItemsCount - 6 } == true } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && onEnd != null && items.isNotEmpty()) onEnd() }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(Gutter, 4.dp, Gutter, 24.dp + LocalBarSpace.current),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        header?.let { item(span = { GridItemSpan(maxLineSpan) }) { Text(it, style = MaterialTheme.typography.titleMedium, color = Dx.Text, modifier = Modifier.padding(vertical = 6.dp)) } }
        items(items, key = { "${it.type}:${it.id}" }) { t ->
            Art(t.art, t.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clickable { onOpen(t) })
        }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dx.Accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
    }
}

/** See All for a Home row, or Categories: one catalog, a genre to narrow it down */
@Composable
fun DiscoverScreen(stremio: Stremio, title: String, onBack: () -> Unit, onOpen: (Title) -> Unit) {
    val discover by stremio.discover.collectAsState()
    var genres by remember { mutableStateOf(false) }
    val d = discover
    Column(Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = Gutter), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Dx.Text) }
            Text(title, style = MaterialTheme.typography.titleLarge, color = Dx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (d != null && d.genres.isNotEmpty()) Box(Modifier.padding(horizontal = Gutter, vertical = 4.dp)) {
            Pill(selected = d.genre != null, onClick = { genres = true }) {
                Text(d.genre ?: "All genres", color = Dx.Text, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Dx.Text, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = genres, onDismissRequest = { genres = false }) {
                DropdownMenuItem(text = { Text("All genres") }, onClick = { genres = false; stremio.discoverGenre(null) })
                d.genres.forEach { g -> DropdownMenuItem(text = { Text(g) }, onClick = { genres = false; stremio.discoverGenre(g) }) }
            }
        }
        Spacer(Modifier.height(6.dp))
        PosterGrid(
            d?.items.orEmpty(),
            loading = d == null || d.state == Load.Loading,
            onEnd = { if (d?.nextPage == true && d.state == Load.Ready) stremio.discoverMore() },
            onOpen = onOpen,
        )
    }
}
