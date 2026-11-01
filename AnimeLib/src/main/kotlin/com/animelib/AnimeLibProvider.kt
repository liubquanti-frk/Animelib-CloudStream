package com.animelib

import android.util.Base64
import com.lagradost.cloudstream3.*
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

    override val mainPage = mainPageOf(
        "" to "Популярне",
        "sort_by=last_episode_at&sort_type=desc" to "Нові серії",
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
        val q = if (request.data.isBlank()) "" else "&${request.data}"
        val json = apiGet("/anime?page=$page$q")
        val items = json.optJSONArray("data") ?: JSONArray()
        val list = (0 until items.length()).map { toSearch(items.getJSONObject(it)) }
        val hasNext = json.optJSONObject("meta")?.optBoolean("has_next_page", list.size >= 60)
            ?: (list.size >= 60)
        return newHomePageResponse(request.name, list, hasNext)
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
            "summary", "genres", "releaseDate", "rate_avg", "rate", "episodes_count", "otherNames"
        ).joinToString("&") { "fields[]=$it" }
        val d = apiGet("/anime/$slug?$fields").getJSONObject("data")

        val epsJson = apiGet("/episodes?anime_id=$slug").optJSONArray("data") ?: JSONArray()
        val episodes = (0 until epsJson.length()).map { i ->
            val e = epsJson.getJSONObject(i)
            Triple(
                e.optLong("id"),
                e,
                e.str("number").toDoubleOrNull()?.toInt() ?: (i + 1),
            )
        }

        fun build(kind: String) = episodes.map { (id, e, num) ->
            newEpisode("$id|$kind") {
                this.name = e.str("name").ifBlank { null }
                this.episode = num
                this.season = e.str("season").toIntOrNull()
            }
        }

        val genres = d.optJSONArray("genres")?.let { g ->
            (0 until g.length()).map { g.getJSONObject(it).str("name") }
        }
        val year = d.str("releaseDate").take(4).toIntOrNull()
        val rating = d.optJSONObject("rating")?.str("average")?.toDoubleOrNull()
        val statusLabel = d.optJSONObject("status")?.str("label")
        val background = d.str("anilist_id").toIntOrNull()?.let { anilistBackground(it) }

        return newAnimeLoadResponse(d.title(), url, d.tvType()) {
            posterUrl = d.poster()
            posterHeaders = this@AnimeLibProvider.posterHeaders
            backgroundPosterUrl = background
            engName = d.str("eng_name").ifBlank { null }
            japName = d.str("name").ifBlank { null }
            plot = proseToText(d.opt("summary")).ifBlank { null }
            tags = genres
            this.year = year
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
        }
    }

    /**
     * Wide background from AniList. AnimeLib covers need a Referer header, which some
     * CloudStream views (e.g. the home hero banner) don't send, so they fail with 403.
     */
    private suspend fun anilistBackground(id: Int): String? = runCatching {
        val body = """{"query":"query(${'$'}id:Int){Media(id:${'$'}id){bannerImage coverImage{extraLarge}}}","variables":{"id":$id}}"""
        val text = app.post(
            "https://graphql.anilist.co",
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf("Accept" to "application/json"),
        ).text
        val m = JSONObject(text).getJSONObject("data").getJSONObject("Media")
        m.str("bannerImage").takeIf { it.startsWith("http") }
            ?: m.optJSONObject("coverImage")?.str("extraLarge")?.takeIf { it.startsWith("http") }
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

        data class Src(val team: String, val src: String)

        val sources = (0 until players.length()).map { players.getJSONObject(it) }
            .filter { it.str("player") == "Kodik" && it.str("src").isNotBlank() }
            .filter {
                val isSub = it.optJSONObject("translation_type")?.str("label")
                    ?.contains("Субтитр") == true
                isSub == wantSub
            }
            .map { Src(it.optJSONObject("team")?.str("name") ?: "Kodik", it.str("src")) }
            .distinctBy { it.src }

        var found = false
        sources.amap { s ->
            runCatching {
                val qualities = resolveKodik(s.src) ?: return@runCatching
                if (qualities.isEmpty()) return@runCatching
                found = true
                if (isCasting) {
                    // Cast receivers can't use our interceptor: give plain per-quality links.
                    qualities.forEach { (quality, link) ->
                        callback(kodikLink(s.team, link, quality))
                    }
                } else {
                    // One entry per dub team; qualities are switched inside the player.
                    callback(kodikLink(s.team, masterUrl(qualities), qualities.first().first))
                }
            }
        }
        return found
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

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor =
        Interceptor { chain ->
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
