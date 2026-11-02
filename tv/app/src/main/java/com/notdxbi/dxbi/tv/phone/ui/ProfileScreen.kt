package com.notdxbi.dxbi.tv.phone.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Airing
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------------
// New & Hot: what's airing from the viewer's shows, and the top 10s

@Composable
fun NewHotScreen(stremio: Stremio, onOpen: (Title) -> Unit) {
    val airing by stremio.airing.collectAsState()
    val rows by stremio.rows.collectAsState()
    // (straight to the top 10s when none of their shows has an episode this week)
    var tab by rememberSaveable { mutableStateOf(if (stremio.airing.value.isEmpty()) 1 else 0) }
    val films = rows.firstOrNull { it.type == "movie" && it.state == Load.Ready }
    val series = rows.firstOrNull { it.type == "series" && it.state == Load.Ready }
    val tabs = listOf("Airing This Week", "Top 10 Series", "Top 10 Films")
    Column(Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Text("New & Hot", style = MaterialTheme.typography.headlineMedium, color = Dx.Text, modifier = Modifier.padding(Gutter, 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(tabs) { i, label ->
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(50)).background(if (tab == i) Color.White else Dx.Raised).clickable { tab = i }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (tab == i) Color.Black else Dx.Text, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + LocalBarSpace.current)) {
            when (tab) {
                0 -> if (airing.isEmpty()) item {
                    Text("Nothing from your shows this week. Add series to My List and their new episodes show up here.", color = Dx.Muted, modifier = Modifier.padding(Gutter))
                } else items(airing, key = { it.id }) { a -> AiringItem(a) { onOpen(Title(a.id, "series", a.name)) } }
                else -> {
                    val list = (if (tab == 1) series else films)?.items.orEmpty().take(10)
                    itemsIndexed(list, key = { _, t -> t.id }) { i, t -> TopTen(i + 1, t) { onOpen(t) } }
                }
            }
        }
    }
}

@Composable
private fun AiringItem(a: Airing, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gutter, vertical = 12.dp)) {
        // the date down the side, as Netflix's Coming Soon does
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(a.time)).uppercase()
            val date = java.text.SimpleDateFormat("d", java.util.Locale.getDefault()).format(java.util.Date(a.time))
            Text(day, color = Dx.Muted, style = MaterialTheme.typography.labelSmall)
            Text(date, color = Dx.Text, fontWeight = FontWeight.Black, fontSize = 26.sp)
        }
        Column(Modifier.weight(1f)) {
            Art(a.still ?: a.background, a.name, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(6.dp))
            Spacer(Modifier.height(8.dp))
            Text(a.name, color = Dx.Text, style = MaterialTheme.typography.titleMedium)
            Text("Season ${a.season} · Episode ${a.episode}${if (a.title.isNotEmpty()) " · ${a.title}" else ""}", color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
            Text(
                if (a.out) "Out now" + (if (a.more > 0) " · +${a.more} more this week" else "") else "${a.whenText} · ${a.countdown}",
                color = if (a.out) Dx.Accent else Dx.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TopTen(rank: Int, t: Title, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", color = Dx.Text, fontWeight = FontWeight.Black, fontSize = 44.sp, modifier = Modifier.width(56.dp))
        Art(t.art, t.name, Modifier.width(84.dp).aspectRatio(2f / 3f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.year, t.genres.firstOrNull()).joinToString(" · "), color = Dx.Muted, style = MaterialTheme.typography.bodySmall)
            t.imdb?.let { Spacer(Modifier.height(4.dp)); ImdbBadge(it) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Profile: the account, its lists, and settings

@Composable
fun ProfileScreen(
    stremio: Stremio,
    onOpen: (Title) -> Unit,
    onLibrary: () -> Unit,
    onCustomiseHome: () -> Unit,
    onSettings: () -> Unit,
    onStremioSettings: () -> Unit,
) {
    val account by stremio.account.collectAsState()
    val myList by stremio.myList.collectAsState()
    val continueWatching by stremio.continueWatching.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(bottom = 24.dp + LocalBarSpace.current),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(Dx.Raised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = Dx.Muted, modifier = Modifier.size(46.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(account?.email ?: "Signed in", style = MaterialTheme.typography.titleMedium, color = Dx.Text)
            }
        }
        if (continueWatching.isNotEmpty()) item {
            PosterRow("Continue Watching", continueWatching.map { it.title }, onOpen = onOpen)
        }
        item { PosterRow(myList.firstOrNull()?.name ?: "My List", myList.flatMap { it.items }, onMore = onLibrary, onOpen = onOpen) }
        item {
            Column(Modifier.padding(top = 6.dp)) {
                Line(Icons.Filled.VideoLibrary, "Library", "Everything you've added or watched", onClick = onLibrary)
                Line(Icons.Filled.Tune, "Customise Home", "Order and hide Home's rows", onClick = onCustomiseHome)
                Line(Icons.Filled.Settings, "dx.bi settings", "Rows, reviews, skipping, watch party and more", onClick = onSettings)
                Line(Icons.Filled.Subtitles, "Playback & subtitles", "Languages, subtitles, next episode, streaming server", onClick = onStremioSettings)
                Line(Icons.AutoMirrored.Filled.ExitToApp, "Sign out", account?.email) { stremio.signOut() }
            }
        }
    }
}

@Composable
fun Line(icon: ImageVector, title: String, note: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gutter, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Dx.Muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Dx.Text, style = MaterialTheme.typography.titleSmall)
            note?.let { Text(it, color = Dx.Faint, style = MaterialTheme.typography.bodySmall) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Dx.Faint)
    }
}

// ---------------------------------------------------------------------------------------------------
// Signing in (with a Stremio account)

@Composable
fun SignInScreen(stremio: Stremio) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val field = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Dx.Text, unfocusedBorderColor = Dx.Line, focusedContainerColor = Dx.Raised, unfocusedContainerColor = Dx.Raised,
        focusedLabelColor = Dx.Muted, unfocusedLabelColor = Dx.Faint, cursorColor = Dx.Accent,
    )
    Column(
        Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Logo(52.sp)
        Spacer(Modifier.height(28.dp))
        Text("Sign in", style = MaterialTheme.typography.headlineMedium, color = Dx.Text)
        Text("With your Stremio account: your addons, library and progress come with it.", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, colors = field,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, colors = field,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        problem?.let { Text(it, color = Dx.Accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                busy = true
                problem = null
                scope.launch {
                    problem = stremio.signIn(email.trim(), password)
                    busy = false
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Dx.Accent),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Sign In", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.stremio.com/register"))) }) {
            Text("New to Stremio? Create a free account", color = Dx.Muted)
        }
    }
}
