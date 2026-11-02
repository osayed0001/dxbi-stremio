package com.notdxbi.dxbi.tv.phone

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notdxbi.dxbi.tv.phone.ui.DetailScreen
import com.notdxbi.dxbi.tv.phone.ui.DiscoverScreen
import com.notdxbi.dxbi.tv.phone.ui.Dx
import com.notdxbi.dxbi.tv.phone.ui.DxTheme
import com.notdxbi.dxbi.tv.phone.ui.HomeScreen
import com.notdxbi.dxbi.tv.phone.ui.MyScreen
import com.notdxbi.dxbi.tv.phone.ui.NewHotScreen
import com.notdxbi.dxbi.tv.phone.ui.ProfilesScreen
import com.notdxbi.dxbi.tv.phone.ui.SearchScreen
import com.notdxbi.dxbi.tv.phone.ui.SettingsScreen
import com.notdxbi.dxbi.tv.phone.ui.SignInScreen
import com.notdxbi.dxbi.tv.phone.ui.ViewerBadge

private enum class Tab(val route: String, val label: String, val on: ImageVector, val off: ImageVector) {
    Home("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    NewHot("newhot", "New & Hot", Icons.Filled.Whatshot, Icons.Outlined.Whatshot),
    Search("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    My("my", "My dx.bi", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
}

/**
 * dx.bi on a phone: native screens over Stremio's page, which keeps doing Stremio's work underneath
 * ([Engine]). [showPage] uncovers the page itself, for what has no native screen yet.
 */
@Composable
fun PhoneApp(engine: Engine, showPage: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stremio = remember { Stremio(context.applicationContext, engine, scope) }
    DxTheme {
        var pageShown by remember { mutableStateOf(false) }
        fun openPage(hash: String, dxMethod: String?) {
            stremio.goTo(hash, dxMethod)
            pageShown = true
            showPage(true)
        }
        if (pageShown) {
            // Back leaves the page for the native screens again (and the page goes back to Home, its lightest screen)
            BackHandler {
                pageShown = false
                showPage(false)
                stremio.goTo("#/")
            }
            return@DxTheme
        }

        val account by stremio.account.collectAsState()
        val ready by stremio.ready.collectAsState()
        val a = account
        when {
            a == null && !ready -> Starting()
            a != null && !a.signedIn -> SignInScreen(stremio)
            else -> Screens(stremio, ::openPage)
        }
    }
}

@Composable
private fun Starting() {
    Box(Modifier.fillMaxSize().background(Dx.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DX.BI", color = Dx.Accent, fontWeight = FontWeight.Black, fontSize = 44.sp)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Dx.Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

/** Titles handed from one screen to the next, so details can show the art while it loads */
private val previews = mutableMapOf<String, Title>()

private fun NavHostController.openTitle(t: Title, play: Boolean = false) {
    previews["${t.type}:${t.id}"] = t
    navigate("title/${Uri.encode(t.type)}/${Uri.encode(t.id)}?play=$play")
}

private fun NavHostController.switchTo(tab: Tab, route: String = tab.route) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = route == tab.route
    }
}

@Composable
private fun Screens(stremio: Stremio, openPage: (String, String?) -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val tab = Tab.entries.firstOrNull { route.substringBefore('?') == it.route }
    val viewer by stremio.viewer.collectAsState()

    Scaffold(
        containerColor = Dx.Background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        bottomBar = {
            if (tab != null) NavigationBar(containerColor = Color(0xF0111111), tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { nav.switchTo(t) },
                        icon = {
                            if (t == Tab.My) ViewerBadge(viewer?.name ?: "Me", viewer?.colour, 24.dp)
                            else Icon(if (t == tab) t.on else t.off, contentDescription = t.label)
                        },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Dx.Text, selectedTextColor = Dx.Text,
                            unselectedIconColor = Dx.Faint, unselectedTextColor = Dx.Faint,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            composable(Tab.Home.route) {
                HomeScreen(
                    stremio,
                    onOpen = { nav.openTitle(it) },
                    onPlay = { nav.openTitle(it, play = true) },
                    onResume = { nav.openTitle(it.title, play = true) },
                    onSearch = { nav.switchTo(Tab.Search, "search?focus=true") },
                    onProfile = { nav.navigate("profiles") },
                    onSeeAll = { link -> nav.navigate("discover?link=${Uri.encode(link)}") },
                    onCategories = { type -> nav.navigate("discover?type=$type") },
                )
            }
            composable(Tab.NewHot.route) { NewHotScreen(stremio, onOpen = { nav.openTitle(it) }) }
            composable(
                "search?q={q}&focus={focus}",
                arguments = listOf(navArgument("q") { defaultValue = "" }, navArgument("focus") { type = NavType.BoolType; defaultValue = false }),
            ) { e ->
                SearchScreen(
                    stremio,
                    initial = e.arguments?.getString("q").orEmpty(),
                    focus = e.arguments?.getBoolean("focus") == true,
                    onOpen = { nav.openTitle(it) },
                    onPlay = { nav.openTitle(it, play = true) },
                )
            }
            composable(Tab.My.route) {
                MyScreen(
                    stremio,
                    onOpen = { nav.openTitle(it) },
                    onProfiles = { nav.navigate("profiles") },
                    onSettings = { nav.navigate("settings") },
                    onPage = openPage,
                )
            }
            composable(
                "title/{type}/{id}?play={play}",
                arguments = listOf(navArgument("type") {}, navArgument("id") {}, navArgument("play") { type = NavType.BoolType; defaultValue = false }),
            ) { e ->
                val type = e.arguments?.getString("type").orEmpty()
                val id = e.arguments?.getString("id").orEmpty()
                DetailScreen(
                    stremio,
                    type = type,
                    id = id,
                    preview = previews["$type:$id"],
                    play = e.arguments?.getBoolean("play") == true,
                    onBack = { nav.popBackStack() },
                    onSearch = { name -> nav.navigate("search?q=${Uri.encode(name)}") },
                )
            }
            composable(
                "discover?link={link}&type={type}",
                arguments = listOf(navArgument("link") { defaultValue = "" }, navArgument("type") { defaultValue = "" }),
            ) { e ->
                val link = e.arguments?.getString("link").orEmpty()
                val type = e.arguments?.getString("type").orEmpty()
                val rows by stremio.rows.collectAsState()
                androidx.compose.runtime.LaunchedEffect(link, type) {
                    if (link.isNotEmpty()) stremio.discover(link)
                    else stremio.discover("https://v3-cinemeta.strem.io/manifest.json", type.ifEmpty { "movie" }, "top")
                }
                val title = rows.firstOrNull { it.discover == link }?.name
                    ?: if (type == "series") "Series" else if (type.isNotEmpty()) "Films" else "Browse"
                DiscoverScreen(stremio, title, onBack = { nav.popBackStack() }, onOpen = { nav.openTitle(it) })
            }
            composable("profiles") { ProfilesScreen(stremio, onDone = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(stremio, onBack = { nav.popBackStack() }) }
        }
    }
}
