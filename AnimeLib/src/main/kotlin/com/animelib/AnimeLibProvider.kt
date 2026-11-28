package com.animelib

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeyClass
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

class AnimeLibProvider : MainAPI() {
    override var mainUrl = "https://animelib.org"
    override var name = "AnimeLib"
    override var lang = "ru"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val api = "https://api.cdnlibs.org/api"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val apiHeaders = mapOf(
        "User-Agent" to ua,
        "Site-Id" to "5",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json",
    )
    private val posterHeaders = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")

    companion object {
        /** Season number AnimeLib uses for specials and recaps */
        private const val SPECIALS_SEASON = 10000

        /** Main page row that is built by [currentSeason] instead of a catalog filter */
        private const val CURRENT_SEASON = "current_season"
    }

    override val mainPage = mainPageOf(
        "sort_by=last_episode_at&sort_type=desc" to "Нові серії",
        "" to "Популярне",
        "status[]=1" to "Онгоінги",
        CURRENT_SEASON to "Сезон: ${seasonOf(Calendar.getInstance()).label}",
        "sort_by=rate_avg&sort_type=desc" to "Найвищий рейтинг",
        "sort_by=created_at&sort_type=desc" to "Нещодавно додані",
    )

    // ---------- HTTP ----------

    private suspend fun apiGet(path: String): JSONObject {
        var last = 0
        for (attempt in 0 until 5) {
            val r = app.get("$api$path", headers = apiHeaders)
            last = r.code
            if (r.code == 429) {
                Thread.sleep(1500L * (attempt + 1)); continue
            }
            if (r.code in 200..299) return JSONObject(r.text)
            break
        }
        throw ErrorLoadingException("AnimeLib API: HTTP $last")
    }

    // ---------- Parsing helpers ----------

    /** optString() turns JSON null into the string "null"; treat it as empty instead. */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONObject.title(): String =
        str("rus_name").ifBlank { str("eng_name") }.ifBlank { str("name") }

    private fun JSONObject.poster(): String? =
        optJSONObject("cover")?.let { c ->
            c.str("default").ifBlank { c.str("thumbnail") }.ifBlank { null }
        }

    private fun JSONObject.tvType(): TvType = when (optJSONObject("type")?.str("label")) {
        "Фильм" -> TvType.AnimeMovie
        "OVA", "ONA", "Спешл", "Спешиал" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun toSearch(o: JSONObject): SearchResponse =
        newAnimeSearchResponse(o.title(), "$mainUrl/ru/anime/${o.str("slug_url")}", o.tvType()) {
            posterUrl = o.poster()
            posterHeaders = this@AnimeLibProvider.posterHeaders
        }

    private fun slugFromUrl(url: String): String =
        url.substringAfter("/anime/").substringBefore("?").substringBefore("/")

    /** Summary comes as a ProseMirror document (or a plain string). */
    private fun proseToText(any: Any?): String = when (any) {
        is String -> any
        is JSONObject -> {
            val sb = StringBuilder()
            if (any.str("type") == "text") sb.append(any.str("text"))
            any.optJSONArray("content")?.let { sb.append(proseToText(it)) }
            if (any.str("type") == "paragraph") sb.append("\n")
            sb.toString()
        }
        is JSONArray -> (0 until any.length()).joinToString("") { proseToText(any.opt(it)) }
        else -> ""
    }.trim()

    // ---------- Main page / search ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == CURRENT_SEASON) {
            // Built in one go, so there is no second page
            val list = if (page == 1) currentSeason() else emptyList()
            return newHomePageResponse(request.name, list, false)
        }
        val q = if (request.data.isBlank()) "" else "&${request.data}"
        val json = apiGet("/anime?page=$page$q")
        val items = json.optJSONArray("data") ?: JSONArray()
        val list = (0 until items.length()).map { toSearch(items.getJSONObject(it)) }
        val hasNext = json.optJSONObject("meta")?.optBoolean("has_next_page", list.size >= 60)
            ?: (list.size >= 60)
        return newHomePageResponse(request.name, list, hasNext)
    }

    // ---------- Current season ----------
    // The API has no season filter (seasons[] is ignored), only a year range. So take the most
    // popular titles of the year, in AnimeLib's own order, and keep the ones that premiered
    // this season. (rating.votes is always 0 in catalog lists, so it can't rank them.)

    private data class AnimeSeason(val label: String, val start: String)

    private fun seasonOf(now: Calendar): AnimeSeason {
        val year = now.get(Calendar.YEAR)
        val (name, startMonth) = when (now.get(Calendar.MONTH)) {
            in 0..2 -> "зима" to 1
            in 3..5 -> "весна" to 4
            in 6..8 -> "літо" to 7
            else -> "осінь" to 10
        }
        // Some premieres air in the last weeks of the previous month
        val start = Calendar.getInstance().apply {
            clear()
            set(year, startMonth - 1, 1)
            add(Calendar.DAY_OF_MONTH, -14)
        }
        return AnimeSeason("$name $year", isoDate(start))
    }

    private fun isoDate(c: Calendar) = String.format(
        Locale.ROOT, "%04d-%02d-%02d",
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
    )

    private suspend fun currentSeason(): List<SearchResponse> {
        val now = Calendar.getInstance()
        val season = seasonOf(now)
        val today = isoDate(now)
        val years = "year_min=${season.start.take(4)}&year_max=${now.get(Calendar.YEAR)}"
        // 4 pages = the year's 240 most popular titles, which covers the season's notable ones
        return (1..4).toList().amap { page ->
            runCatching {
                val items = apiGet("/anime?page=$page&$years&fields[]=releaseDate")
                    .optJSONArray("data") ?: JSONArray()
                (0 until items.length()).map { items.getJSONObject(it) }
            }.getOrDefault(emptyList())
        }.flatten()
            .filter { it.str("releaseDate").take(10) in season.start..today }
            .distinctBy { it.str("slug_url") }
            .map { toSearch(it) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val items = apiGet("/anime?q=$q&page=1").optJSONArray("data") ?: return emptyList()
        return (0 until items.length()).map { toSearch(items.getJSONObject(it)) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------- Title page ----------

    override suspend fun load(url: String): LoadResponse {
        val slug = slugFromUrl(url)
        val fields = listOf(
            "summary", "genres", "releaseDate", "rate_avg", "rate", "episodes_count", "otherNames",
            "time", "publisher",
        ).joinToString("&") { "fields[]=$it" }

        // Independent requests, run in parallel. Only the title itself is required.
        var details: JSONObject? = null
        var epsJson = JSONArray()
        var related = emptyList<SearchResponse>()
        var similar = emptyList<SearchResponse>()
        listOf(
            suspend { details = apiGet("/anime/$slug?$fields").getJSONObject("data") },
            suspend { epsJson = apiGet("/episodes?anime_id=$slug").optJSONArray("data") ?: JSONArray() },
            suspend { related = mediaList("/anime/$slug/relations") },
            suspend { similar = mediaList("/anime/$slug/similar") },
        ).amap { runCatching { it() } }
        val d = details ?: apiGet("/anime/$slug?$fields").getJSONObject("data")

        val episodes = (0 until epsJson.length()).map { i ->
            val e = epsJson.getJSONObject(i)
            Triple(
                e.optLong("id"),
                e,
                e.str("number").toDoubleOrNull()?.toInt() ?: (i + 1),
            )
        }

        val genres = d.optJSONArray("genres")?.let { g ->
            (0 until g.length()).map { g.getJSONObject(it).str("name") }
        }.orEmpty()
        val studios = d.optJSONArray("publisher")?.let { p ->
            (0 until p.length()).map { p.getJSONObject(it).str("name") }.filter { it.isNotBlank() }
        }.orEmpty()
        val year = d.str("releaseDate").take(4).toIntOrNull()
        val rating = d.optJSONObject("rating")?.str("average")?.toDoubleOrNull()
        val statusLabel = d.optJSONObject("status")?.str("label")
        // Many titles have anilist_id = null, but Shikimori ids are MAL ids.
        val anilistId = d.str("anilist_id").toIntOrNull()
        val shikiMalId = Regex("""/animes/\D*(\d+)""").find(d.str("shikimori_href"))
            ?.groupValues?.get(1)?.toIntOrNull()
        val anilist = anilistInfo(anilistId, shikiMalId)

        fun build(kind: String) = episodes.map { (id, e, num) ->
            val season = e.str("season").toIntOrNull()
            // Crunchyroll thumbnails / titles from AniList, for regular episodes only
            val stream = anilist?.episodes?.get(num)?.takeIf { season != SPECIALS_SEASON }
            newEpisode("$id|$kind") {
                this.name = e.str("name").ifBlank { null }
                    ?: stream?.first
                    // Recaps and specials without a name: say what they are
                    ?: e.optJSONObject("status")?.takeIf { it.str("id") != "default" }?.str("label")
                this.episode = num
                this.season = season
                this.posterUrl = stream?.second
            }
        }

        return newAnimeLoadResponse(d.title(), url, d.tvType()) {
            posterUrl = d.poster()
            posterHeaders = this@AnimeLibProvider.posterHeaders
            backgroundPosterUrl = anilist?.background
            // Lets CloudStream's AniList / MAL sync track progress for this title.
            addAniListId(anilistId ?: anilist?.id)
            addMalId(anilist?.malId ?: shikiMalId)
            engName = d.str("eng_name").ifBlank { null }
            japName = d.str("name").ifBlank { null }
            plot = proseToText(d.opt("summary")).ifBlank { null }
            tags = (genres + studios).ifEmpty { null }
            this.year = year
            duration = d.optJSONObject("time")?.optInt("value")?.takeIf { it > 0 }
            contentRating = d.optJSONObject("ageRestriction")?.str("label")
                ?.takeIf { it.isNotBlank() && it != "Нет" }
            // Sequels / prequels first, then titles similar by genre
            recommendations = (related + similar).distinctBy { it.url }.ifEmpty { null }
            anilist?.let { a ->
                actors = a.actors.ifEmpty { null }
                nextAiring = a.nextAiring
                a.trailer?.let { addTrailer(it) }
            }
            showStatus = when (statusLabel) {
                "Онгоинг" -> ShowStatus.Ongoing
                "Завершён" -> ShowStatus.Completed
                else -> null
            }
            rating?.let { this.score = Score.from10(it) }
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, build("dub"))
                addEpisodes(DubStatus.Subbed, build("sub"))
            }
            // AnimeLib puts specials and recaps into season 10000
            if (episodes.any { (_, e, _) -> e.str("season") == SPECIALS_SEASON.toString() }) {
                seasonNames = listOf(SeasonData(SPECIALS_SEASON, "Спешли", null))
            }
        }
    }

    /** Anime entries of a /relations or /similar response (manga and ranobe are skipped). */
    private suspend fun mediaList(path: String): List<SearchResponse> {
        val items = apiGet(path).optJSONArray("data") ?: return emptyList()
        return (0 until items.length())
            .mapNotNull { items.optJSONObject(it)?.optJSONObject("media") }
            .filter { it.str("model") == "anime" && it.str("slug_url").isNotBlank() }
            .map { toSearch(it) }
    }

    private data class AniListInfo(
        val id: Int?,
        val malId: Int?,
        val background: String?,
        val trailer: String?,
        val nextAiring: NextAiring?,
        val actors: List<ActorData>,
        /** Episode number -> (title, thumbnail), from Crunchyroll via AniList */
        val episodes: Map<Int, Pair<String?, String?>>,
    )

    /**
     * Extra info from AniList, looked up by AniList id or, failing that, by MAL id: the ids
     * for tracker sync, a wide background (AnimeLib covers need a Referer header, which some
     * CloudStream views don't send), trailer, next episode, characters and episode thumbnails.
     */
    private suspend fun anilistInfo(id: Int?, malId: Int?): AniListInfo? = runCatching {
        val (arg, value) = when {
            id != null -> "id" to id
            malId != null -> "idMal" to malId
            else -> return null
        }
        val query = "query(\$v:Int){Media($arg:\$v,type:ANIME){id idMal bannerImage " +
            "coverImage{extraLarge} trailer{id site} nextAiringEpisode{episode airingAt} " +
            "characters(sort:[ROLE,RELEVANCE],perPage:25){edges{role node{name{full} " +
            "image{medium}} voiceActors(language:JAPANESE){name{full} image{medium}}}} " +
            "streamingEpisodes{title thumbnail}}}"
        val body = JSONObject().put("query", query)
            .put("variables", JSONObject().put("v", value)).toString()
        val text = app.post(
            "https://graphql.anilist.co",
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf("Accept" to "application/json"),
        ).text
        val m = JSONObject(text).getJSONObject("data").getJSONObject("Media")

        val trailer = m.optJSONObject("trailer")
            ?.takeIf { it.str("site") == "youtube" && it.str("id").isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=${it.str("id")}" }

        val next = m.optJSONObject("nextAiringEpisode")?.let {
            NextAiring(it.optInt("episode"), it.optLong("airingAt"), null)
        }?.takeIf { it.episode > 0 && it.unixTime > 0 }

        val edges = m.optJSONObject("characters")?.optJSONArray("edges") ?: JSONArray()
        val actors = (0 until edges.length()).mapNotNull { i ->
            val edge = edges.getJSONObject(i)
            val node = edge.optJSONObject("node") ?: return@mapNotNull null
            val name = node.optJSONObject("name")?.str("full")?.ifBlank { null }
                ?: return@mapNotNull null
            val va = edge.optJSONArray("voiceActors")?.optJSONObject(0)?.let { v ->
                v.optJSONObject("name")?.str("full")?.ifBlank { null }?.let {
                    Actor(it, v.optJSONObject("image")?.str("medium")?.ifBlank { null })
                }
            }
            ActorData(
                Actor(name, node.optJSONObject("image")?.str("medium")?.ifBlank { null }),
                role = when (edge.str("role")) {
                    "MAIN" -> ActorRole.Main
                    "SUPPORTING" -> ActorRole.Supporting
                    else -> ActorRole.Background
                },
                voiceActor = va,
            )
        }

        // Titles look like "Episode 3 - Name"; map them by that number
        val streams = m.optJSONArray("streamingEpisodes") ?: JSONArray()
        val episodes = (0 until streams.length()).mapNotNull { i ->
            val s = streams.getJSONObject(i)
            val match = Regex("""^Episode\s+(\d+)\s*[-–:]?\s*(.*)$""").find(s.str("title"))
                ?: return@mapNotNull null
            match.groupValues[1].toInt() to Pair(
                match.groupValues[2].trim().ifBlank { null },
                s.str("thumbnail").takeIf { it.startsWith("http") },
            )
        }.toMap()

        AniListInfo(
            id = m.str("id").toIntOrNull(),
            malId = m.str("idMal").toIntOrNull(),
            background = m.str("bannerImage").takeIf { it.startsWith("http") }
                ?: m.optJSONObject("coverImage")?.str("extraLarge")?.takeIf { it.startsWith("http") },
            trailer = trailer,
            nextAiring = next,
            actors = actors,
            episodes = episodes,
        )
    }.getOrNull()

    // ---------- Links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.substringBefore("|").substringAfterLast("/").trim()
        val wantSub = data.substringAfter("|", "dub") == "sub"
        val players = apiGet("/episodes/$episodeId").getJSONObject("data").optJSONArray("players")
            ?: return false

        data class Src(val team: String, val src: String, val isSub: Boolean)

        val all = (0 until players.length()).map { players.getJSONObject(it) }
            .filter { it.str("player") == "Kodik" && it.str("src").isNotBlank() }
            .map {
                val isSub = it.optJSONObject("translation_type")?.str("label")
                    ?.contains("Субтитр") == true
                Src(it.optJSONObject("team")?.str("name") ?: "Kodik", it.str("src"), isSub)
            }
            .distinctBy { it.src }
        // Some episodes (often specials) only exist as subs or only as dubs: rather than
        // showing nothing, offer the other kind, marked in the source name.
        val sources = all.filter { it.isSub == wantSub }.ifEmpty {
            all.map { it.copy(team = it.team + if (it.isSub) " (субтитри)" else " (озвучка)") }
        }

        // Resolve in parallel, but hand the links over in a fixed order: every link has the
        // same priority, so the player starts the first one it got. Recently used dubs go first.
        val resolved = sources.amap { s ->
            s to runCatching { resolveKodik(s.src) }.getOrNull().orEmpty()
        }.filter { it.second.isNotEmpty() }

        val recent = recentDubs()
        val ordered = resolved.sortedBy { (s, _) ->
            recent.indexOf(s.team).let { if (it < 0) Int.MAX_VALUE else it }
        }
        ordered.forEach { (s, qualities) ->
            if (isCasting) {
                // Cast receivers can't use our interceptor: give plain per-quality links.
                qualities.forEach { (quality, link) ->
                    callback(kodikLink(s.team, link, quality))
                }
            } else {
                // One entry per dub team; qualities are switched inside the player.
                callback(kodikLink(s.team, masterUrl(qualities), Qualities.Unknown.value))
            }
        }
        return ordered.isNotEmpty()
    }

    // ---------- Remembered dub ----------
    // The player asks for the video interceptor only for the link it actually plays, so that
    // is where we learn which dub was picked. Kept as a most-recently-used list, so each title
    // gets the dub last used among the ones it has.

    private val recentDubsKey = "animelib_recent_dubs"

    private fun recentDubs(): List<String> =
        runCatching { getKeyClass(recentDubsKey, String::class.java) }.getOrNull()
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun rememberDub(team: String) = runCatching {
        val updated = listOf(team) + recentDubs().filter { it != team }
        setKey(recentDubsKey, updated.take(30).joinToString("\n"))
    }

    private suspend fun kodikLink(team: String, url: String, quality: Int) =
        newExtractorLink(
            // source must equal the provider name so the player asks us for the interceptor
            source = name,
            name = team,
            url = url,
            type = ExtractorLinkType.M3U8,
        ) {
            this.referer = "https://kodikplayer.com/"
            this.quality = quality
        }

    // ---------- Virtual HLS master playlist ----------
    // Kodik serves every quality as a separate media playlist. We pack them into a fake URL
    // and answer it from an OkHttp interceptor with a master playlist, so ExoPlayer sees one
    // stream with several video tracks and the user can pick the quality in the player.

    private val masterHost = "animelib-master.invalid"

    private fun masterUrl(qualities: List<Pair<Int, String>>): String {
        val json = JSONArray().apply {
            qualities.forEach { (q, u) -> put(JSONObject().put("q", q).put("u", u)) }
        }.toString()
        val enc = Base64.encodeToString(
            json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "https://$masterHost/master.m3u8?d=$enc"
    }

    private fun buildMaster(encoded: String): String {
        val arr = JSONArray(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val q = o.getInt("q")
            val width = q * 16 / 9
            val bandwidth = when {
                q >= 1080 -> 5_000_000
                q >= 720 -> 2_800_000
                q >= 480 -> 1_400_000
                else -> 800_000
            }
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=${width}x$q,NAME=\"${q}p\"\n")
            sb.append(o.getString("u")).append('\n')
        }
        return sb.toString()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        rememberDub(extractorLink.name)
        return Interceptor { chain ->
            val req = chain.request()
            val d = req.url.queryParameter("d")
            if (req.url.host != masterHost || d == null) return@Interceptor chain.proceed(req)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(buildMaster(d).toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                .build()
        }
    }

    /** Returns all (quality, HLS link) pairs of a Kodik player page, best first. */
    private suspend fun resolveKodik(src: String): List<Pair<Int, String>>? {
        val pageUrl = if (src.startsWith("//")) "https:$src" else src
        val host = pageUrl.substringAfter("://").substringBefore("/")
        val html = app.get(pageUrl, headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")).text

        fun jsVar(n: String) = Regex("""var $n = "(.*?)";""").find(html)?.groupValues?.get(1)
        fun vInfo(n: String) = Regex("""vInfo\.$n = '(.*?)'""").find(html)?.groupValues?.get(1)

        val form = mapOf(
            "d" to (jsVar("domain") ?: return null),
            "d_sign" to (jsVar("d_sign") ?: return null),
            "pd" to (jsVar("pd") ?: return null),
            "pd_sign" to (jsVar("pd_sign") ?: return null),
            "ref" to (jsVar("ref") ?: ""),
            "ref_sign" to (jsVar("ref_sign") ?: ""),
            "bad_user" to "false",
            "cdn_is_working" to "true",
            "type" to (vInfo("type") ?: return null),
            "hash" to (vInfo("hash") ?: return null),
            "id" to (vInfo("id") ?: return null),
        )
        val resp = app.post(
            "https://$host/ftor",
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to pageUrl,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            data = form,
        ).text
        val links = JSONObject(resp).optJSONObject("links") ?: return null
        return links.keys().asSequence()
            .mapNotNull { k -> k.toIntOrNull()?.let { it to k } }
            .sortedByDescending { it.first }
            .mapNotNull { (quality, key) ->
                val raw = links.optJSONArray(key)?.optJSONObject(0)?.str("src")
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = if (raw.contains("//")) raw else decodeKodik(raw)
                quality to (if (url.startsWith("//")) "https:$url" else url)
            }
            .toList()
    }

    /** Kodik obfuscation: Caesar +18 over letters, then base64. */
    private fun decodeKodik(s: String): String {
        val shifted = s.map { c ->
            when (c) {
                in 'A'..'Z' -> { val n = c.code + 18; (if (n <= 'Z'.code) n else n - 26).toChar() }
                in 'a'..'z' -> { val n = c.code + 18; (if (n <= 'z'.code) n else n - 26).toChar() }
                else -> c
            }
        }.joinToString("")
        val padded = shifted + "=".repeat((4 - shifted.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT))
    }
}
