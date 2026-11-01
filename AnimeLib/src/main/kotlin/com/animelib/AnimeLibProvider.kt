package com.animelib

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
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

    private fun JSONObject.title(): String =
        optString("rus_name").ifBlank { optString("eng_name") }.ifBlank { optString("name") }

    private fun JSONObject.poster(): String? =
        optJSONObject("cover")?.let { c ->
            c.optString("default").ifBlank { c.optString("thumbnail") }.ifBlank { null }
        }

    private fun JSONObject.tvType(): TvType = when (optJSONObject("type")?.optString("label")) {
        "Фильм" -> TvType.AnimeMovie
        "OVA", "ONA", "Спешл", "Спешиал" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun toSearch(o: JSONObject): SearchResponse =
        newAnimeSearchResponse(o.title(), "$mainUrl/ru/anime/${o.optString("slug_url")}", o.tvType()) {
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
            if (any.optString("type") == "text") sb.append(any.optString("text"))
            any.optJSONArray("content")?.let { sb.append(proseToText(it)) }
            if (any.optString("type") == "paragraph") sb.append("\n")
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
                e.optString("number").toDoubleOrNull()?.toInt() ?: (i + 1),
            )
        }

        fun build(kind: String) = episodes.map { (id, e, num) ->
            newEpisode("$id|$kind") {
                this.name = e.optString("name").ifBlank { null }
                this.episode = num
                this.season = e.optString("season").toIntOrNull()
            }
        }

        val genres = d.optJSONArray("genres")?.let { g ->
            (0 until g.length()).map { g.getJSONObject(it).optString("name") }
        }
        val year = d.optString("releaseDate").take(4).toIntOrNull()
        val rating = d.optJSONObject("rating")?.optString("average")?.toDoubleOrNull()
        val statusLabel = d.optJSONObject("status")?.optString("label")

        return newAnimeLoadResponse(d.title(), url, d.tvType()) {
            posterUrl = d.poster()
            posterHeaders = this@AnimeLibProvider.posterHeaders
            engName = d.optString("eng_name").ifBlank { null }
            japName = d.optString("name").ifBlank { null }
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

    // ---------- Links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = data.substringBefore("|")
        val wantSub = data.substringAfter("|", "dub") == "sub"
        val players = apiGet("/episodes/$episodeId").getJSONObject("data").optJSONArray("players")
            ?: return false

        data class Src(val team: String, val src: String)

        val sources = (0 until players.length()).map { players.getJSONObject(it) }
            .filter { it.optString("player") == "Kodik" && it.optString("src").isNotBlank() }
            .filter {
                val isSub = it.optJSONObject("translation_type")?.optString("label")
                    ?.contains("Субтитр") == true
                isSub == wantSub
            }
            .map { Src(it.optJSONObject("team")?.optString("name") ?: "Kodik", it.optString("src")) }
            .distinctBy { it.src }

        var found = false
        sources.amap { s ->
            runCatching {
                val (quality, link) = resolveKodik(s.src) ?: return@runCatching
                found = true
                callback(
                    newExtractorLink(
                        source = s.team,
                        name = s.team,
                        url = link,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "https://kodikplayer.com/"
                        this.quality = quality
                    }
                )
            }
        }
        return found
    }

    /** Returns best quality HLS link of a Kodik player page. */
    private suspend fun resolveKodik(src: String): Pair<Int, String>? {
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
        val best = links.keys().asSequence()
            .mapNotNull { k -> k.toIntOrNull()?.let { it to k } }
            .maxByOrNull { it.first } ?: return null
        val raw = links.getJSONArray(best.second).getJSONObject(0).optString("src")
        val url = if (raw.contains("//")) raw else decodeKodik(raw)
        return best.first to (if (url.startsWith("//")) "https:$url" else url)
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
