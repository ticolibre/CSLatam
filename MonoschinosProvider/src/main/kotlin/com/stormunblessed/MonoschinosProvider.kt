package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MonoschinosProvider : MainAPI() {
    override var mainUrl = "https://monoschinos.st"
    override var name = "Monoschinos"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "" to "Últimos capítulos",
        "animes" to "Catálogo",
    )

    private fun Element.cardLink(): Element? = selectFirst("a.card-wrap")

    private fun Element.cardTitle(): String? =
        selectFirst("h3.card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.cardPoster(): String? =
        selectFirst("img.card-img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = cardLink() ?: return null
        val title = cardTitle() ?: return null
        return newAnimeSearchResponse(title, fixUrl(link.attr("href")), TvType.Anime) {
            this.posterUrl = fixUrlNull(cardPoster())
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "") {
            val doc = app.get(mainUrl, timeout = 120).document
            val latest = doc.select("article").mapNotNull { article ->
                val link = article.cardLink() ?: return@mapNotNull null
                val title = article.cardTitle() ?: return@mapNotNull null
                val episode = Regex("""EP\s*(\d+)""").find(article.text())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                newAnimeSearchResponse(title, fixUrl(link.attr("href")), TvType.Anime) {
                    this.posterUrl = fixUrlNull(article.cardPoster())
                    addDubStatus(DubStatus.Subbed, episode)
                }
            }
            return newHomePageResponse(
                list = HomePageList(request.name, latest, isHorizontalImages = true),
                hasNext = false
            )
        }

        val url = if (page <= 1) "$mainUrl/animes" else "$mainUrl/animes?p=$page"
        val doc = app.get(url, timeout = 120).document
        val home = doc.select("article").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a[rel=next]") != null
        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/buscar?q=$query", timeout = 120).document
        return doc.select("article").mapNotNull { it.toSearchResult() }
    }

    data class CapList(
        @JsonProperty("eps") val eps: List<Ep> = emptyList(),
    )

    data class Ep(
        @JsonProperty("num") val num: Int? = null,
    )

    override suspend fun load(url: String): LoadResponse? {
        // The "latest episodes" rows link to /ver/... pages; resolve the series first.
        var target = url
        if (target.contains("/ver/")) {
            val episodeDoc = app.get(target, timeout = 120).document
            val animeHref = episodeDoc.selectFirst("a[href*='/anime/']")?.attr("href")
            if (animeHref.isNullOrBlank()) return null
            target = fixUrl(animeHref)
        }

        val resp = app.get(target, timeout = 120)
        val doc = resp.document
        val cookies = resp.cookies

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val info = doc.select("dl dt").mapNotNull { dt ->
            val key = dt.text().trim()
            val value = dt.nextElementSibling()?.text()?.trim() ?: return@mapNotNull null
            key to value
        }.toMap()

        val typeText = info["Tipo"] ?: ""
        val tvType = when {
            typeText.contains("Pelicula", true) -> TvType.AnimeMovie
            typeText.contains("OVA", true) || typeText.contains("Especial", true) -> TvType.OVA
            else -> TvType.Anime
        }

        val tags = doc.select("a[href*='/genero/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val statusText = info["Estado"] ?: ""
        val status = when {
            statusText.contains("Finalizado", true) -> ShowStatus.Completed
            statusText.contains("Estreno", true) -> ShowStatus.Ongoing
            else -> null
        }

        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val caplistUrl = doc.selectFirst("section.caplist")?.attr("data-ajax") ?: ""

        val episodes = if (caplistUrl.isNotBlank() && token.isNotBlank()) {
            try {
                val capJson = app.post(caplistUrl, data = mapOf("_token" to token), cookies = cookies).parsed<CapList>()
                capJson.eps.mapNotNull { ep ->
                    val epNum = ep.num ?: return@mapNotNull null
                    val epUrl = target.replace("-sub-espanol", "").replace("/anime/", "/ver/") + "-episodio-$epNum"
                    newEpisode(epUrl) {
                        this.episode = epNum
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return newAnimeLoadResponse(title, target, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = tags
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("button.play-video").amap {
            val url = base64Decode(it.attr("data-player"))
            loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }
        return true
    }
}
