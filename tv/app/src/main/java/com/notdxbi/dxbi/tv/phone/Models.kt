package com.notdxbi.dxbi.tv.phone

import org.json.JSONArray
import org.json.JSONObject

// What the native screens show, read from the JSON dxbi-engine.js sends (see its views)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

/** A string that's really there: null for missing, JSON null and "" */
fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

data class Title(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val wide: Boolean = false,
    val year: String? = null,
    val runtime: String? = null,
    val imdb: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val inLibrary: Boolean = false,
    val trailer: String? = null,
) {
    /** Art for a card: the poster, or for titles without one the backdrop */
    val art: String? get() = poster ?: background ?: if (id.startsWith("tt")) "https://images.metahub.space/poster/small/$id/img" else null
    val backdrop: String? get() = background ?: if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else poster

    companion object {
        fun from(o: JSONObject) = Title(
            id = o.optString("id"),
            type = o.optString("type"),
            name = o.optString("name"),
            poster = o.text("poster"),
            background = o.text("background"),
            logo = o.text("logo"),
            wide = o.optString("shape") == "landscape",
            year = o.text("year"),
            runtime = o.text("runtime"),
            imdb = o.text("imdb"),
            genres = o.optJSONArray("genres").strings(),
            description = o.text("description"),
            inLibrary = o.optBoolean("inLibrary"),
            trailer = o.text("trailer"),
        )
    }
}

enum class Load { None, Loading, Ready, Err }

private fun load(o: JSONObject, key: String = "state") = when (o.optString(key)) {
    "Ready" -> Load.Ready
    "Loading" -> Load.Loading
    "Err" -> Load.Err
    else -> Load.None
}

data class Row(val index: Int, val key: String, val name: String, val type: String, val addon: String, val discover: String?, val state: Load, val items: List<Title>) {
    companion object {
        fun from(o: JSONObject) = Row(
            index = o.optInt("index"),
            key = o.optString("key"),
            name = o.optString("name"),
            type = o.optString("type"),
            addon = o.optString("addon"),
            discover = o.text("discover"),
            state = load(o),
            items = o.optJSONArray("items").objects().map(Title::from),
        )

        fun list(state: JSONObject?) = state?.optJSONArray("catalogs").objects().map(::from)
    }
}

data class Continue(val title: Title, val progress: Float, val videoId: String?, val newEpisodes: Int) {
    companion object {
        fun list(state: JSONObject?) = state?.optJSONArray("items").objects().map { o ->
            Continue(Title.from(o), (o.optDouble("progress", 0.0) / 100.0).toFloat().coerceIn(0f, 1f), o.text("videoId"), o.optInt("notifications"))
        }
    }
}

data class Video(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val thumbnail: String?,
    val released: String?,
    val overview: String?,
    val watched: Boolean,
    val progress: Float?,
    val upcoming: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Video(
            id = o.optString("id"),
            title = o.optString("title"),
            season = if (o.isNull("season")) null else o.optInt("season"),
            episode = if (o.isNull("episode")) null else o.optInt("episode"),
            thumbnail = o.text("thumbnail"),
            released = o.text("released"),
            overview = o.text("overview"),
            watched = o.optBoolean("watched"),
            progress = if (o.isNull("progress")) null else (o.optDouble("progress", 0.0) / 100.0).toFloat(),
            upcoming = o.optBoolean("upcoming"),
        )
    }
}

data class Stream(
    val name: String,
    val description: String,
    val url: String?,
    val torrent: Boolean,
    val trailer: Boolean,
    val externalUrl: String?,
    val filename: String?,
    val headers: Map<String, String>,
    val play: String?,
) {
    companion object {
        fun from(o: JSONObject) = Stream(
            name = o.optString("name"),
            description = o.optString("description"),
            url = o.text("url"),
            torrent = o.text("infoHash") != null,
            trailer = o.text("ytId") != null,
            externalUrl = o.text("externalUrl"),
            filename = o.text("filename"),
            headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } } ?: emptyMap(),
            play = o.text("play"),
        )
    }
}

data class StreamGroup(val addon: String, val state: Load, val streams: List<Stream>)

data class Details(
    val state: Load,
    val metaId: String?,
    val videoId: String?,
    val title: Title?,
    val cast: List<String>,
    val directors: List<String>,
    val videos: List<Video>,
    val resumeVideoId: String?,
    val resumeMs: Long,
    val durationMs: Long,
    val streams: List<StreamGroup>,
) {
    val seasons: List<Int> get() = videos.mapNotNull { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))

    companion object {
        fun from(o: JSONObject?): Details? {
            if (o == null) return null
            val meta = o.optJSONObject("meta")
            val selected = o.optJSONObject("selected")
            val resume = o.optJSONObject("resume")
            return Details(
                state = load(o),
                metaId = selected?.optJSONObject("metaPath")?.text("id"),
                videoId = selected?.optJSONObject("streamPath")?.text("id"),
                title = meta?.let(Title::from),
                cast = meta?.optJSONArray("cast").strings(),
                directors = meta?.optJSONArray("directors").strings(),
                videos = meta?.optJSONArray("videos").objects().map(Video::from),
                resumeVideoId = resume?.text("videoId"),
                resumeMs = resume?.optLong("timeOffset") ?: 0,
                durationMs = resume?.optLong("duration") ?: 0,
                streams = o.optJSONArray("streams").objects().map { g ->
                    StreamGroup(g.optString("addon"), load(g), g.optJSONArray("items").objects().map(Stream::from))
                },
            )
        }
    }
}

data class Discover(val request: JSONObject?, val catalogs: List<String>, val genres: List<String>, val state: Load, val items: List<Title>, val nextPage: Boolean) {
    val genre: String? get() = request?.optJSONObject("path")?.optJSONArray("extra")?.let { extra ->
        (0 until extra.length()).mapNotNull { extra.optJSONArray(it) }.firstOrNull { it.optString(0) == "genre" }?.optString(1)
    }

    companion object {
        fun from(o: JSONObject?) = o?.let {
            Discover(
                it.optJSONObject("request"),
                it.optJSONArray("catalogs").objects().map { c -> c.optString("name") },
                it.optJSONArray("genres").strings(),
                load(it),
                it.optJSONArray("items").objects().map(Title::from),
                it.optBoolean("nextPage"),
            )
        }
    }
}

data class Account(val signedIn: Boolean, val email: String?, val avatar: String?, val addons: List<String>) {
    companion object {
        fun from(o: JSONObject?) = o?.let {
            val user = it.optJSONObject("user")
            Account(it.optBoolean("signedIn"), user?.text("email"), user?.text("avatar"), it.optJSONArray("addons").objects().map { a -> a.optString("name") })
        }
    }
}

/** dx.bi's own Home rows (window.__dxbi.phone) */
data class ListRow(val name: String, val items: List<Title>)

data class Airing(
    val id: String, val name: String, val background: String?, val still: String?, val season: Int, val episode: Int,
    val title: String, val more: Int, val time: Long, val whenText: String, val countdown: String, val out: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Airing(
            o.optString("id"), o.optString("name"), o.text("background"), o.text("still"), o.optInt("season"), o.optInt("episode"),
            o.optString("title"), o.optInt("more"), o.optLong("time"), o.optString("when"), o.optString("countdown"), o.optBoolean("out"),
        )
    }
}

data class Setting(val key: String, val type: String, val label: String, val description: String, val group: String, val options: List<Pair<Any?, String>>, val value: Any?) {
    companion object {
        fun from(o: JSONObject) = Setting(
            o.optString("key"), o.optString("type"), o.optString("label"), o.optString("description"), o.optString("group"),
            o.optJSONArray("options").objects().map { (if (it.isNull("value")) null else it.opt("value")) to it.optString("label") },
            if (o.isNull("value")) null else o.opt("value"),
        )
    }
}

data class Viewer(val id: String, val name: String, val colour: String?, val kids: Boolean)

data class HomeLayout(val order: List<String>, val hidden: Set<String>, val names: Map<String, String>) {
    companion object {
        val NONE = HomeLayout(emptyList(), emptySet(), emptyMap())
        fun from(o: JSONObject?) = o?.let {
            val names = it.optJSONObject("names")
            HomeLayout(it.optJSONArray("order").strings(), it.optJSONArray("hidden").strings().toSet(), names?.keys()?.asSequence()?.associateWith { k -> names.optString(k) } ?: emptyMap())
        } ?: NONE
    }

    /** Home's rows as the viewer arranged them: their order first, anything new after, hidden ones left out */
    fun arrange(rows: List<Row>): List<Row> {
        val visible = rows.filter { it.key !in hidden }.map { r -> names[r.key]?.let { r.copy(name = it) } ?: r }
        if (order.isEmpty()) return visible
        val place = order.withIndex().associate { (i, k) -> k to i }
        return visible.sortedBy { place[it.key] ?: (order.size + it.index) }
    }
}
