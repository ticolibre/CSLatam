package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

class AllCalidadProvider : MainAPI() {
    override var mainUrl = "https://allcalidad.re"
    override var name = "AllCalidad"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private val apiUrl = "$mainUrl/api/rest"
    private val imageUrl = "$mainUrl/wp-content/uploads"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p/w500"

    private fun resolveImage(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        path.startsWith("/thumbs/") || path.startsWith("/backdrops/") || path.startsWith("/logos/") -> imageUrl + path
        else -> tmdbImageUrl + path
    }

    override val mainPage = mainPageOf(
        "movies" to "Películas",
        "tvshows" to "Series",
        "animes" to "Animes",
        "movies:26" to "Acción",
        "movies:51" to "Animación",
        "movies:25" to "Aventura",
        "movies:27" to "Ciencia Ficción",
        "movies:216" to "Comedia",
        "movies:135" to "Crimen",
        "movies:156" to "Drama",
        "movies:52" to "Familia",
        "movies:109" to "Fantasía",
        "movies:429" to "Historia",
        "movies:273" to "Misterio",
        "movies:239" to "Romance",
        "movies:295" to "Suspense",
        "movies:447" to "Terror",
        "movies:182" to "Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split(":")
        val postType = parts[0]
        val genreId = parts.getOrNull(1)?.toIntOrNull()

        val url = "$apiUrl/listing?page=$page&post_type=$postType&posts_per_page=24" +
            (genreId?.let { "&genres=$it" } ?: "")

        val listing = runCatching { tryParseJson<ListingResponse>(app.get(url).text) }
            .getOrNull()
        val items = listing?.data?.posts?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val lastPage = listing?.data?.pagination?.lastPage ?: 1

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = page < lastPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/search?query=$encoded&page=1&post_type=movies,tvshows,animes&posts_per_page=24"
        val listing = runCatching { tryParseJson<ListingResponse>(app.get(url).text) }
            .getOrNull()
        return listing?.data?.posts?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun ApiPost.toSearchResult(): SearchResponse? {
        val tvType = when (type) {
            "movies" -> TvType.Movie
            "animes" -> TvType.Anime
            else -> TvType.TvSeries
        }
        val name = title ?: return null
        return newMovieSearchResponse(name, this.toJson(), tvType, fix = false) {
            this.posterUrl = resolveImage(images?.poster)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val post = tryParseJson<ApiPost>(url) ?: return null
        val type = post.type ?: return null
        val tvType = when (type) {
            "movies" -> TvType.Movie
            "animes" -> TvType.Anime
            else -> TvType.TvSeries
        }
        val title = post.title ?: return null
        val poster = resolveImage(post.images?.poster)
        val related = getRelated(post.id, type)

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, tvType, post.id.toString()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = resolveImage(post.images?.backdrop)
                this.plot = post.overview
                this.year = post.releaseDate?.substringBefore("-")?.toIntOrNull()
                addScore(post.rating, 10)
                post.runtime?.toIntOrNull()?.let { addDuration("$it min") }
                this.recommendations = related
            }
        } else {
            val episodes = getEpisodes(post.id)
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = resolveImage(post.images?.backdrop)
                this.plot = post.overview
                this.year = post.releaseDate?.substringBefore("-")?.toIntOrNull()
                addScore(post.rating, 10)
                this.recommendations = related
            }
        }
    }

    private suspend fun getEpisodes(postId: Int): MutableList<Episode> {
        val response = runCatching {
            tryParseJson<EpisodesResponse>(app.get("$apiUrl/episodes?post_id=$postId").text)
        }.getOrNull()?.data ?: return mutableListOf()

        return response.mapNotNull { ep ->
            newEpisode("$mainUrl/episodio/${ep.id}") {
                this.name = "S${ep.seasonNumber}E${ep.episodeNumber}"
                this.season = ep.seasonNumber
                this.episode = ep.episodeNumber
                this.posterUrl = resolveImage(ep.stillPath)
            }
        }.toMutableList()
    }

    private suspend fun getRelated(postId: Int, postType: String): List<SearchResponse> {
        val response = runCatching {
            tryParseJson<RelatedResponse>(
                app.get("$apiUrl/related?post_id=$postId&post_type=$postType&posts_per_page=16").text
            )
        }.getOrNull()?.data ?: return emptyList()

        return response.mapNotNull { it.toSearchResult() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postId = data.substringAfterLast('/').toIntOrNull() ?: return false
        val player = runCatching {
            tryParseJson<PlayerResponse>(app.get("$apiUrl/player?post_id=$postId&_any=1").text)
        }.getOrNull()?.data ?: return false

        player.embeds.forEach { embed ->
            val url = embed.url ?: return@forEach
            if (!url.startsWith("http")) return@forEach
            runCatching {
                loadExtractor(url, mainUrl, subtitleCallback) { link ->
                    CoroutineScope(Dispatchers.IO).launch {
                        callback(
                            newExtractorLink(
                                link.source,
                                "${embed.lang ?: "Server"} · ${embed.quality ?: "HD"} · ${link.name}",
                                link.url,
                                link.type,
                            ) {
                                this.quality = link.quality
                                this.referer = link.referer
                                this.headers = link.headers
                                this.extractorData = link.extractorData
                            }
                        )
                    }
                }
            }
        }

        player.downloads.forEach { dl ->
            val url = dl.url ?: return@forEach
            val label = "${dl.lang ?: "Server"} · ${dl.quality ?: "HD"}"
            if (url.startsWith("magnet:")) {
                CoroutineScope(Dispatchers.IO).launch {
                    callback(
                        newExtractorLink(
                            "Torrent",
                            "$label · ${dl.server ?: "Magnet"}",
                            url,
                            ExtractorLinkType.MAGNET,
                        )
                    )
                }
            } else if (url.startsWith("http")) {
                runCatching {
                    loadExtractor(url, mainUrl, subtitleCallback) { link ->
                        CoroutineScope(Dispatchers.IO).launch {
                            callback(
                                newExtractorLink(
                                    link.source,
                                    "$label · ${link.name}",
                                    link.url,
                                    link.type,
                                ) {
                                    this.quality = link.quality
                                    this.referer = link.referer
                                    this.headers = link.headers
                                }
                            )
                        }
                    }
                }
            }
        }
        return true
    }
}

private data class ListingResponse(
    @JsonProperty("data") val data: ListingData? = null,
)

private data class ListingData(
    @JsonProperty("posts") val posts: List<ApiPost> = emptyList(),
    @JsonProperty("pagination") val pagination: Pagination? = null,
)

private data class Pagination(
    @JsonProperty("current_page") val currentPage: Int = 0,
    @JsonProperty("last_page") val lastPage: Int = 0,
)

private data class ApiPost(
    @JsonProperty("_id") val id: Int = 0,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("images") val images: ApiImages? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("runtime") val runtime: String? = null,
)

private data class ApiImages(
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
)

private data class EpisodesResponse(
    @JsonProperty("data") val data: List<ApiEpisode> = emptyList(),
)

private data class ApiEpisode(
    @JsonProperty("_id") val id: Int = 0,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("season_number") val seasonNumber: Int = 0,
    @JsonProperty("episode_number") val episodeNumber: Int = 0,
)

private data class RelatedResponse(
    @JsonProperty("data") val data: List<ApiPost> = emptyList(),
)

private data class PlayerResponse(
    @JsonProperty("data") val data: PlayerData? = null,
)

private data class PlayerData(
    @JsonProperty("embeds") val embeds: List<PlayerEmbed> = emptyList(),
    @JsonProperty("downloads") val downloads: List<PlayerDownload> = emptyList(),
)

private data class PlayerEmbed(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("url") val url: String? = null,
)

private data class PlayerDownload(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("quality") val quality: String? = null,
)
