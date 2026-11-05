package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPage as mainPageItem
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Collections

/** Payload stored in the CloudStream item URL for every catalogue entry. */
data class CatalogoGeneralData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("isTv") val isTv: Boolean = false,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
)

/** Stremio/Nuvio addon response for `catalog/{type}/{id}.json`. */
data class TicoCatalogResponse(
    @JsonProperty("metas") val metas: List<TicoMeta>? = null,
)

/** Stremio/Nuvio addon response for `meta/{type}/{id}.json`. */
data class TicoMetaResponse(
    @JsonProperty("meta") val meta: TicoMeta? = null,
)

data class TicoMeta(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseInfo") val releaseInfo: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("videos") val videos: List<TicoVideo>? = null,
    @JsonProperty("app_extras") val appExtras: TicoAppExtras? = null,
) {
    val isTv get() = type == "series"
    val year get() = releaseInfo?.take(4)?.toIntOrNull()
    val rating get() = appExtras?.ratings?.tmdb?.rating?.toDoubleOrNull()
}

data class TicoVideo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("released") val released: String? = null,
)

data class TicoAppExtras(
    @JsonProperty("ratings") val ratings: TicoRatings? = null,
)

data class TicoRatings(
    @JsonProperty("tmdb") val tmdb: TicoTmdbRating? = null,
)

data class TicoTmdbRating(
    @JsonProperty("rating") val rating: String? = null,
)

data class GeneralCrossMetaData(
    @JsonProperty("isSuccess") val isSuccess: Boolean,
    @JsonProperty("sources") val sources: List<GeneralCrossSource>? = null,
)

data class GeneralCrossSource(
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("dataUrl") val dataUrl: String,
)

/**
 * Catálogo General, now backed by the static Nuvio/Stremio addon published at
 * [BASE_URL] instead of TMDB at runtime.
 *
 * Home rows come from `catalog/{type}/{id}.json`, detail pages from
 * `meta/{type}/{id}.json`, and search is a local index built from every
 * catalogue file (cached for a few hours).
 *
 * Playback is still delegated: on load, other installed providers with the
 * same language are searched for the title and, if found, their dataUrls are
 * wrapped in [GeneralCrossMetaData] so `loadLinks` can forward playback to
 * them. Movies get a Play button; series get the matched provider's episode
 * list. If nothing matches, the detail page is metadata-only.
 */
class CatalogoGeneralProvider : MainAPI() {
    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/ticolibre/NuvioFeed/main"
    }

    override var name = "Catálogo General"
    override var mainUrl = BASE_URL
    override var lang = "mx"
    override val providerType = ProviderType.MetaProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val hasMainPage = true

    /** Mirrors manifest.json -> catalogs[]. "type/id" is used as the row payload. */
    private val catalogPaths = listOf(
        "movie/trending-movies" to "Trending Movies",
        "series/trending-series" to "Trending Series",
        "series/crunchyroll" to "Popular on Crunchyroll",
        "movie/a24" to "A24 Films",
        "movie/searchlight" to "Searchlight Pictures",
        "movie/focus" to "Focus Features",
        "movie/neon" to "NEON Films",
        "movie/blumhouse" to "Blumhouse Films",
        "movie/shudder" to "Shudder Originals",
        "movie/cannes" to "Cannes: Palme d'Or",
        "movie/sundance" to "Sundance: Grand Jury Winners",
        "movie/best-picture-winners" to "Oscars: Best Picture Winners",
        "movie/best-picture-nominees" to "Oscars: Best Picture Nominees",
        "movie/ghibli" to "Studio Ghibli",
        "movie/globes-drama" to "Golden Globes: Best Drama",
        "movie/bafta-best-film" to "BAFTA: Best Film",
        "movie/spirit-best-feature" to "Independent Spirit: Best Feature",
        "series/mappa" to "MAPPA",
        "movie/sxsw-narrative" to "SXSW: Grand Jury Winners",
        "movie/top-2020s" to "Top 2020s: Most Voted",
        "movie/top-2010s" to "Top 2010s: Most Voted",
        "movie/top-2000s" to "Top 2000s: Most Voted",
        "movie/top-1990s" to "Top 90s: Most Voted",
        "movie/top-1980s" to "Top 80s: Most Voted",
        "series/top-2020s-series" to "Top 2020s Series",
        "series/top-2010s-series" to "Top 2010s Series",
        "series/top-2000s-series" to "Top 2000s Series",
        "series/top-1990s-series" to "Top 90s Series",
        "series/top-1980s-series" to "Top 80s Series",
        "series/top-anime-series" to "Top Anime Series",
        "movie/top-horror-1990s-now" to "Top Horror Films: 1990-Now",
        "movie/top-drama-1990s-now" to "Top Drama Films: 1990-Now",
        "movie/top-docs-2000s-now" to "Top Documentaries: 2000-Now",
        "movie/top-animated-2000s-now" to "Top Animated Films: 2000-Now",
    )

    override val mainPage = catalogPaths.map { (path, label) ->
        mainPageItem(path, label, false)
    }

    private val catalogCacheMs = 30L * 60 * 1000
    private data class CatalogCacheEntry(val timestamp: Long, val items: List<TicoMeta>)
    private val catalogCache = Collections.synchronizedMap(HashMap<String, CatalogCacheEntry>())

    private val metaCacheMs = 60L * 60 * 1000
    private data class MetaCacheEntry(val timestamp: Long, val meta: TicoMeta?)
    private val metaCache = Collections.synchronizedMap(HashMap<String, MetaCacheEntry>())

    private val indexCacheMs = 6L * 60 * 60 * 1000
    private data class IndexCacheEntry(val timestamp: Long, val items: List<TicoMeta>)
    @Volatile private var indexCache: IndexCacheEntry? = null
    private val indexFetchTimeoutMs = 20_000L

    private val providerSearchTimeoutMs = 5_000L
    private val findMatchesDeadlineMs = 6_000L
    private val maxMatchesForFallback = 4
    private val maxSearchResults = 60

    private val searchCacheMs = 10L * 60 * 1000
    private data class SearchCacheEntry(val timestamp: Long, val matches: List<SearchMatch>)
    private val searchCache = Collections.synchronizedMap(HashMap<String, SearchCacheEntry>())

    private class SearchMatch(val providerName: String, val loaded: LoadResponse)

    // ---------------------------------------------------------------- network

    private suspend fun fetchCatalog(path: String): List<TicoMeta> {
        val now = System.currentTimeMillis()
        catalogCache[path]?.let {
            if (now - it.timestamp < catalogCacheMs) return it.items
        }
        return try {
            val body = app.get("$BASE_URL/catalog/$path.json").text
            val items = parseJson<TicoCatalogResponse>(body).metas.orEmpty()
            if (items.isNotEmpty()) catalogCache[path] = CatalogCacheEntry(now, items)
            items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            catalogCache[path]?.items ?: emptyList()
        }
    }

    private suspend fun fetchMeta(type: String, id: String): TicoMeta? {
        val key = "$type/$id"
        val now = System.currentTimeMillis()
        metaCache[key]?.let {
            if (now - it.timestamp < metaCacheMs) return it.meta
        }
        val meta = try {
            val body = app.get("$BASE_URL/meta/$key.json").text
            parseJson<TicoMetaResponse>(body).meta
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
        metaCache[key] = MetaCacheEntry(now, meta)
        return meta
    }

    /**
     * Local search index built from every catalogue file. First search in a
     * session downloads all of them (the addon has no search resource); the
     * result is cached for [indexCacheMs].
     */
    private suspend fun buildIndex(): List<TicoMeta> {
        val now = System.currentTimeMillis()
        indexCache?.let {
            if (now - it.timestamp < indexCacheMs) return it.items
        }
        val items = catalogPaths.amap { (path, _) ->
            withTimeoutOrNull(indexFetchTimeoutMs) { fetchCatalog(path) } ?: emptyList()
        }.flatten().distinctBy { "${it.type}:${it.id}" }

        if (items.isNotEmpty()) indexCache = IndexCacheEntry(now, items)
        return items
    }

    // ------------------------------------------------------------ home/search

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(HomePageList(request.name, emptyList(), false), hasNext = false)
        }
        val results = fetchCatalog(request.data).mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(HomePageList(request.name, results, false), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return emptyList<SearchResponse>().toNewSearchResponseList()
        val needle = normalize(query)
        if (needle.isBlank()) return null

        val results = buildIndex()
            .asSequence()
            .filter { normalize(it.name.orEmpty()).contains(needle) }
            .sortedWith(
                compareByDescending<TicoMeta> { it.rating ?: 0.0 }
                    .thenBy { it.name.orEmpty() }
            )
            .take(maxSearchResults)
            .mapNotNull { it.toSearchResponse() }
            .toList()

        return results.toNewSearchResponseList()
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<CatalogoGeneralData>(url) ?: return null
        val id = data.id ?: return null
        val type = if (data.isTv) "series" else "movie"

        val meta = fetchMeta(type, id)
        val title = meta?.name?.takeIf { it.isNotBlank() }
            ?: data.title?.takeIf { it.isNotBlank() }
            ?: return null
        val year = meta?.year ?: data.year
        val poster = meta?.poster
        val backdrop = meta?.background
        val plot = meta?.description
        val tags = meta?.genres
        val score = Score.from10(meta?.rating)

        val matches = findMatches(title, filterName(title), year, data.isTv)

        if (matches.isEmpty()) {
            return if (data.isTv) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes = emptyList()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, dataUrl = "") {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.tags = tags
                }
            }
        }

        val first = matches.first()

        return when (val loaded = first.loaded) {
            is MovieLoadResponse -> {
                val movieMatches = matches.filter { it.loaded is MovieLoadResponse }
                val crossData = GeneralCrossMetaData(
                    true,
                    movieMatches.map {
                        GeneralCrossSource(it.providerName, (it.loaded as MovieLoadResponse).dataUrl)
                    }
                ).toJson()
                newMovieLoadResponse(title, url, TvType.Movie, dataUrl = crossData) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.tags = tags
                }
            }

            is TvSeriesLoadResponse -> {
                val seriesMatches = matches.filter { it.loaded is TvSeriesLoadResponse }
                val wrappedEpisodes = loaded.episodes.map { ep ->
                    val sources = mutableListOf(GeneralCrossSource(first.providerName, ep.data))
                    seriesMatches.forEach { other ->
                        if (other.providerName == first.providerName) return@forEach
                        val otherLoaded = other.loaded as TvSeriesLoadResponse
                        otherLoaded.episodes.firstOrNull {
                            it.season == ep.season && it.episode == ep.episode
                        }?.let { sources.add(GeneralCrossSource(other.providerName, it.data)) }
                    }
                    val wrapped = GeneralCrossMetaData(true, sources).toJson()
                    @Suppress("DEPRECATION_ERROR")
                    Episode(
                        data = wrapped,
                        name = ep.name,
                        season = ep.season,
                        episode = ep.episode,
                        posterUrl = ep.posterUrl,
                        score = ep.score,
                        description = ep.description,
                        date = ep.date,
                        runTime = ep.runTime,
                    )
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes = wrappedEpisodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.tags = tags
                }
            }

            else -> null
        }
    }

    // -------------------------------------------------------------- playback

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            linksFound = true
            callback(link)
        }
        tryParseJson<GeneralCrossMetaData>(data)?.let { metaData ->
            if (!metaData.isSuccess) return false
            metaData.sources?.amap { source ->
                getApiFromNameNull(source.apiName)?.let {
                    try {
                        it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            return linksFound
        }
        tryParseJson<GeneralCrossSource>(data)?.let { source ->
            getApiFromNameNull(source.apiName)?.let {
                try {
                    it.loadLinks(source.dataUrl, isCasting, subtitleCallback, wrappedCallback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return linksFound
        }
        return false
    }

    // --------------------------------------------------------------- matching

    private val validApis
        get() = apis.filter {
            it.lang == this.lang &&
                it::class != this::class &&
                it.providerType != ProviderType.MetaProvider
        }

    private fun SearchResponse.typeOk(isTv: Boolean): Boolean {
        val t = this.type
        return if (isTv) {
            t?.let { it != TvType.Movie && it != TvType.AnimeMovie } ?: (this is TvSeriesSearchResponse)
        } else {
            t?.let { it == TvType.Movie || it == TvType.AnimeMovie } ?: (this is MovieSearchResponse)
        }
    }

    private fun SearchResponse.yearOk(searchYear: Int?): Boolean {
        val y = when (this) {
            is MovieSearchResponse -> this.year
            is TvSeriesSearchResponse -> this.year
            else -> null
        } ?: return true
        return searchYear == null || y == searchYear
    }

    private suspend fun searchProvider(
        api: com.lagradost.cloudstream3.MainAPI,
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): SearchMatch? {
        return try {
            withTimeout(providerSearchTimeoutMs) {
                val searchResult = api.search(title, 1) ?: return@withTimeout null
                val candidates = searchResult.items.filter { it.typeOk(isTv) }
                if (candidates.isEmpty()) return@withTimeout null

                val matched = candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true) && it.yearOk(searchYear)
                } ?: candidates.firstOrNull {
                    comparableName(it.name).equals(matchName, ignoreCase = true)
                } ?: return@withTimeout null

                val loaded = api.load(matched.url) ?: return@withTimeout null
                SearchMatch(api.name, loaded)
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun findMatches(
        title: String,
        matchName: String,
        searchYear: Int?,
        isTv: Boolean,
    ): List<SearchMatch> {
        val key = "$isTv|$matchName|$searchYear"
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.timestamp < searchCacheMs) return it.matches }

        var complete = true
        val matches = withTimeoutOrNull(findMatchesDeadlineMs) {
            validApis.amap { api ->
                searchProvider(api, title, matchName, searchYear, isTv)
            }.filterNotNull()
        } ?: run {
            complete = false
            emptyList()
        }

        val result = matches.take(maxMatchesForFallback)

        if (complete) {
            searchCache[key] = SearchCacheEntry(now, result)
            if (searchCache.size > 250) {
                searchCache.clear()
            }
        }
        return result
    }

    // ---------------------------------------------------------------- helpers

    private fun filterName(name: String): String {
        return Regex("""[^a-zA-Z0-9-]""").replace(name, "")
    }

    private fun comparableName(name: String): String {
        return filterName(Regex("""\s*\(?\d{4}\)?\s*$""").replace(name, ""))
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun TicoMeta.toSearchResponse(): SearchResponse? {
        val display = name?.takeIf { it.isNotBlank() } ?: return null
        val mediaId = id?.takeIf { it.isNotBlank() } ?: return null
        val itemYear = year
        val payload = CatalogoGeneralData(mediaId, isTv, display, itemYear).toJson()
        val posterUrl = poster

        return if (isTv) {
            newTvSeriesSearchResponse(display, payload, TvType.TvSeries, fix = false) {
                this.posterUrl = posterUrl
                this.year = itemYear
            }
        } else {
            newMovieSearchResponse(display, payload, TvType.Movie, fix = false) {
                this.posterUrl = posterUrl
                this.year = itemYear
            }
        }
    }
}
