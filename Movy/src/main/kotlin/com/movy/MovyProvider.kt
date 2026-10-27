package com.movy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class MovyProvider : MainAPI() {
    override var mainUrl = "https://www.movy.sx"
    override var name = "Movy"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Keep the three confirmed catalogue routes; avoid speculative routes that can return empty pages.
    override val mainPage = listOf(
        MainPageData("Movies", "$mainUrl/browse/movie", horizontalImages = true),
        MainPageData("TV Shows", "$mainUrl/browse/tv", horizontalImages = true),
        MainPageData("Anime", "$mainUrl/browse/anime", horizontalImages = true)
    )

    private suspend fun getDocument(url: String): Document =
        app.get(url, timeout = 25).document

    private fun absolute(document: Document, raw: String): String {
        if (raw.isBlank()) return ""
        return try { document.location().let { java.net.URL(java.net.URL(it), raw).toString() } }
        catch (_: Exception) { raw }
    }

    private fun posterOf(element: Element, document: Document): String? {
        // Posters can be lazy-loaded or wrapped by Next.js image optimization.
        val candidates = mutableListOf<Element>()
        var current: Element? = element
        repeat(6) {
            current?.let { candidates.add(it) }
            current = current?.parent()
        }
        fun imageUrl(img: Element): String? {
            listOf("data-poster", "data-src", "data-lazy-src", "data-original", "data-image", "src").forEach { key ->
                val value = img.attr(key).trim()
                if (value.isNotBlank() && !value.startsWith("data:image") && !value.startsWith("blob:")) return value
            }
            listOf("data-srcset", "srcset").forEach { key ->
                val value = img.attr(key).split(",").firstOrNull()?.trim()?.substringBefore(" ")?.trim().orEmpty()
                if (value.isNotBlank() && !value.startsWith("data:image")) return value
            }
            return null
        }
        val raw = candidates.firstNotNullOfOrNull { wrapper ->
            wrapper.select("img, picture source").firstNotNullOfOrNull(::imageUrl)
                ?: wrapper.attr("data-poster").takeIf(String::isNotBlank)
                ?: wrapper.attr("data-image").takeIf(String::isNotBlank)
                ?: wrapper.attr("data-src").takeIf(String::isNotBlank)
                ?: Regex("""(?i)background-image\s*:\s*url\(['"]?([^)'"\s]+)""")
                    .find(wrapper.attr("style"))?.groupValues?.getOrNull(1)
        }
        return raw?.let { absolute(document, it) }
    }

    private fun titleOf(element: Element): String {
        val heading = element.selectFirst("h1, h2, h3, .title, .name, .film-name, .movie-title")
            ?.text()?.trim()?.takeIf(String::isNotBlank)
        val imageAlt = element.selectFirst("img")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
        val attribute = element.attr("title").trim().ifBlank { element.attr("aria-label").trim() }
        val text = element.text().trim()
        val generic = setOf("play", "watch", "watch now", "more info", "more information", "image", "movy", "movy watch anything", "watch anything")
        val local = sequenceOf(heading, imageAlt, attribute, text)
            .filterNotNull()
            .firstOrNull {
                val value = it.lowercase()
                it.isNotBlank() && value !in generic &&
                    !value.startsWith("play ") && !value.startsWith("watch ") &&
                    !value.contains("more info") && !value.contains("more information")
            }
        if (!local.isNullOrBlank()) return local
        return element.parent()?.let { parent ->
            parent.selectFirst("h1, h2, h3, .title, .name, .film-name, .movie-title")
                ?.text()?.trim().orEmpty().ifBlank { parent.attr("title").trim() }
        }.orEmpty()
    }

    private fun isInternalContentUrl(url: String): Boolean {
        val path = try { java.net.URI(url).path.lowercase() } catch (_: Exception) { "" }
        return path.contains("/movie/") || path.contains("/show/") ||
            path.contains("/tv/") || path.contains("/anime/") ||
            path.contains("/series/") || path.contains("/watch/") ||
            path.contains("/title/")
    }

    private fun cardElements(document: Document): List<Element> {
        // Use actual content anchors as the source of truth instead of relying on
        // old theme-specific card class names. Movy is a Next.js app and its
        // class names can change independently of the route structure.
        val found = linkedMapOf<String, Element>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (isInternalContentUrl(href)) found.putIfAbsent(href.trimEnd('/'), anchor)
        }
        return found.values.toList()
    }

    private fun searchResponses(document: Document, forceAnime: Boolean = false): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        return cardElements(document).mapNotNull { anchor ->
            val rawUrl = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trimEnd('/')
            val isMovie = rawUrl.contains("/movie/")
            val rawPath = try { java.net.URI(rawUrl).path.orEmpty() } catch (_: Exception) { "" }
            // Movy's anime catalogue points anime series to ordinary /tv/{tmdbId}
            // pages. Carry the catalogue type in a harmless query marker so load()
            // can use anime metadata without changing normal TV-series behavior.
            val isAnime = !isMovie && (rawUrl.contains("/anime/") ||
                (forceAnime && Regex("^/tv/\\d+(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(rawPath)))
            val parent = anchor.parent()
            val title = titleOf(anchor).ifBlank { parent?.let(::titleOf).orEmpty() }
            if (title.isBlank() || rawUrl.isBlank()) return@mapNotNull null
            // Carry anime identity and catalogue title with the selected item.
            // Detect the category from its request URL, not only its display name.
            val url = if (isAnime && forceAnime) {
                val marker = "csType=anime&csTitle=" + URLEncoder.encode(title, "UTF-8")
                rawUrl + (if (rawUrl.contains("?")) "&" else "?") + marker
            } else rawUrl
            if (!seen.add(url)) return@mapNotNull null
            val poster = posterOf(anchor, document) ?: parent?.let { posterOf(it, document) }
            if (isMovie) newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            } else newTvSeriesSearchResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = when {
            page <= 1 -> base
            base.contains("?") -> "$base&page=$page"
            else -> "$base?page=$page"
        }
        val document = getDocument(url)
        val results = searchResponses(document, forceAnime = base.contains("/browse/anime", ignoreCase = true) || request.name.equals("Anime", ignoreCase = true))
        val sectionName = request.name
        return newHomePageResponse(
            listOf(HomePageList(sectionName, results, isHorizontalImages = true)),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = getDocument("$mainUrl/search?keyword=$encoded")
        val results = searchResponses(document)
        if (results.isNotEmpty()) return results
        return searchResponses(getDocument("$mainUrl/?s=$encoded"))
    }

    private fun pageTitle(document: Document): String {
        val candidates = listOf(
            document.selectFirst("h1, .heading-name, .film-name, .movie-title, .detail-title")?.text()?.trim(),
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim(),
            document.title().substringBefore(" - ").trim()
        )
        val generic = setOf("movy", "movy watch anything", "watch anything", "watch movies online")
        candidates.firstOrNull { !it.isNullOrBlank() && it.lowercase() !in generic }?.let { return it!! }
        val slug = try { java.net.URI(document.location()).path.trimEnd('/').substringAfterLast('/') } catch (_: Exception) { "" }
        return slug.replace(Regex("[-_]+"), " ").replaceFirstChar { it.uppercase() }.ifBlank { "Untitled" }
    }

    private fun pagePoster(document: Document): String? {
        val meta = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.takeIf(String::isNotBlank)
        val images = document.select("img, picture source").mapNotNull { img ->
            val url = listOf("data-poster", "data-src", "data-lazy-src", "data-original", "data-image", "src")
                .asSequence().map { img.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:image") && !it.startsWith("blob:") }
                ?: listOf("data-srcset", "srcset").asSequence()
                    .map { img.attr(it).split(",").firstOrNull()?.trim()?.substringBefore(" ")?.trim().orEmpty() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:image") }
            url?.let { Pair(it, (img.attr("alt") + " " + img.attr("class") + " " + it).lowercase()) }
        }
        val preferred = images.firstOrNull { (_, hint) ->
            hint.contains("poster") || hint.contains("tmdb") || hint.contains("image.tmdb.org") ||
                hint.contains("_next/image") || hint.contains("cover")
        }?.first
        val background = document.select("[style*=background-image]").firstNotNullOfOrNull { el ->
            Regex("""(?i)background-image\s*:\s*url\(['"]?([^)'"\s]+)""")
                .find(el.attr("style"))?.groupValues?.getOrNull(1)
        }
        return (meta ?: preferred ?: images.firstOrNull()?.first ?: background)?.let { absolute(document, it) }
    }

    private fun pagePlot(document: Document): String? =
        document.selectFirst("meta[name=description]")?.attr("content")?.takeIf(String::isNotBlank)
            ?: document.selectFirst(".description, .film-description, .content, .overview")?.text()?.trim()
                ?.takeIf(String::isNotBlank)

    private fun sourcesFrom(document: Document, pageUrl: String): List<Pair<String, String>> {
        val output = linkedMapOf<String, String>()
        val sourcePattern = Regex("(?i)(?:source|server|watch|play|stream)\\s*[:：-]?\\s*([A-Za-z0-9 _.-]{0,40})")
        document.select("a[href], iframe[src], source[src], [data-src], [data-url], [data-link]").forEach { el ->
            val raw = when (el.tagName()) {
                "iframe", "source" -> el.attr("src")
                else -> listOf("href", "data-src", "data-url", "data-link").firstNotNullOfOrNull { el.attr(it).takeIf(String::isNotBlank) }.orEmpty()
            }
            val url = absolute(document, raw)
            if (!url.startsWith("http") || url == pageUrl || url.contains("movy.sx/") && !url.contains("/embed/")) return@forEach
            val label = el.text().trim().ifBlank {
                el.attr("title").ifBlank { el.attr("aria-label") }
            }.ifBlank {
                val context = el.parent()?.text().orEmpty()
                sourcePattern.find(context)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }.ifBlank { "Source ${output.size + 1}" }
            if (url !in output) output[url] = label.take(80)
        }

        // Some players keep embed URLs in JavaScript configuration rather than DOM attributes.
        val scriptUrlPattern = Regex("""(?i)(?:file|src|source|url|embedUrl|iframeUrl)\s*[:=]\s*["'](https?://[^"'\\s<>]+)["']""")
        document.select("script").forEach { script ->
            scriptUrlPattern.findAll(script.data() + script.html()).forEach { match ->
                val url = match.groupValues.getOrNull(1).orEmpty().trimEnd(',', ';')
                if (url.startsWith("http") && url !in output && !url.contains("movy.sx/")) {
                    output[url] = "Source ${output.size + 1}"
                }
            }
        }
        return output.map { it.key to it.value }
    }

    private suspend fun metadataEpisodes(seriesUrl: String, poster: String?, isAnime: Boolean = false): List<com.lagradost.cloudstream3.Episode> {
        val path = try { java.net.URI(seriesUrl).path.orEmpty() } catch (_: Exception) { return emptyList() }
        val seriesId = Regex("^/tv/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.getOrNull(1) ?: return emptyList()
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/"
        )
        return try {
            val details = JSONObject(app.get("https://db.wecollege.net/3/tv/$seriesId?language=en-US", headers = headers, timeout = 12).text)
            val seasons = details.optJSONArray("seasons") ?: return emptyList()
            val result = mutableListOf<com.lagradost.cloudstream3.Episode>()
            for (i in 0 until seasons.length()) {
                val seasonObject = seasons.optJSONObject(i) ?: continue
                val seasonNumber = seasonObject.optInt("season_number", -1)
                if (seasonNumber <= 0) continue
                val seasonData = try {
                    JSONObject(app.get(
                        "https://db.wecollege.net/3/tv/$seriesId/season/$seasonNumber?language=en-US",
                        headers = headers,
                        timeout = 12
                    ).text)
                } catch (_: Exception) {
                    continue
                }
                val episodeArray = seasonData.optJSONArray("episodes") ?: continue
                for (j in 0 until episodeArray.length()) {
                    val episodeData = episodeArray.optJSONObject(j) ?: continue
                    val episodeNumber = episodeData.optInt("episode_number", 0)
                    if (episodeNumber <= 0) continue
                    val episodeName = episodeData.optString("name").takeIf { it.isNotBlank() && it != "null" }
                        ?: "Episode $episodeNumber"
                    val episodeUrl = "$mainUrl/tv/$seriesId/$seasonNumber/$episodeNumber" +
                        if (isAnime) "?csType=anime" else ""
                    result.add(newEpisode(episodeUrl) {
                        name = "S$seasonNumber E$episodeNumber - $episodeName"
                        season = seasonNumber
                        episode = episodeNumber
                        posterUrl = poster
                        description = episodeData.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                    })
                }
            }
            result.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun numericEpisodeRoute(url: String): Triple<String, Int, Int>? {
        val path = try { java.net.URI(url).path.orEmpty() } catch (_: Exception) { return null }
        val parts = path.trim('/').split('/')
        if (parts.size < 4 || parts[0].lowercase() !in setOf("tv", "show", "series")) return null
        val id = parts[1].takeIf { it.all(Char::isDigit) } ?: return null
        val season = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val episode = parts[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Triple(id, season, episode)
    }

    private fun episodeElements(document: Document): List<Element> {
        val selectors = listOf(
            ".episodes-list a[href]", ".ss-list a[href]", ".episode-list a[href]",
            ".ep-item a[href]", ".episode-item a[href]", "[data-id] a[href]",
            "[class*=episode] a[href]", "[id*=episode] a[href]",
            "[class*=season] a[href]", "[data-testid*=episode] a[href]",
            "a[href*='/episode']", "a[href*='/watch/']"
        )
        val result = linkedMapOf<String, Element>()
        fun addIfEpisode(a: Element) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val label = (a.text() + " " + a.attr("title") + " " + a.attr("aria-label") +
                " " + a.className() + " " + a.parent()?.className().orEmpty())
            val route = numericEpisodeRoute(href)
            val currentPath = try { java.net.URI(document.location()).path.orEmpty().trim('/') } catch (_: Exception) { "" }
            val currentParts = currentPath.split('/')
            val sameSeries = route != null && currentParts.size >= 2 &&
                currentParts[0].lowercase() in setOf("tv", "show", "series") &&
                currentParts[1] == route.first
            val episodeLike = sameSeries ||
                Regex("(?i)(?:/episode(?:[-_/]|$)|episode\\s*[-:#]?\\s*\\d+|\\bep\\.?\\s*\\d+|\\bs\\d{1,2}e\\d{1,3}\\b)")
                    .containsMatchIn("$href $label") ||
                a.parents().take(4).any { p ->
                    p.className().contains("episode", true) ||
                        p.className().contains("ep-item", true) ||
                        p.id().contains("episode", true)
                }
            if (href.startsWith("http") && href.trimEnd('/') != document.location().trimEnd('/') &&
                episodeLike && isInternalContentUrl(href)
            ) result.putIfAbsent(href.trimEnd('/'), a)
        }

        selectors.forEach { selector -> document.select(selector).forEach(::addIfEpisode) }
        // Movy encodes TV episodes as /tv/{seriesId}/{season}/{episode}.
        document.select("a[href]").forEach(::addIfEpisode)

        document.select("script#__NEXT_DATA__, script[type=application/json]").forEach { script ->
            val state = script.data().ifBlank { script.html() }
            val pathPattern = Regex("""(?i)["']((?:https?:\\/\\/[^"']+)?/?(?:tv|show|series)/\d+/\d+/\d+[^"'\s]*)["']""")
            pathPattern.findAll(state).forEach { match ->
                val raw = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                val href = absolute(document, raw)
                val route = numericEpisodeRoute(href)
                if (route != null) {
                    val synthetic = Element("a").attr("href", href).text("Episode " + route.third)
                    result.putIfAbsent(href.trimEnd('/'), synthetic)
                }
            }
        }
        return result.values.sortedWith(compareBy<Element>(
            { numericEpisodeRoute(it.absUrl("href").ifBlank { it.attr("href") })?.second ?: 1 },
            { numericEpisodeRoute(it.absUrl("href").ifBlank { it.attr("href") })?.third ?: Int.MAX_VALUE }
        ))
    }

    private fun queryValue(url: String, key: String): String? {
        val query = try { java.net.URI(url).rawQuery.orEmpty() } catch (_: Exception) { "" }
        return query.split("&").firstNotNullOfOrNull { part ->
            val name = java.net.URLDecoder.decode(part.substringBefore("="), "UTF-8")
            if (name.equals(key, ignoreCase = true)) java.net.URLDecoder.decode(part.substringAfter("=", ""), "UTF-8") else null
        }
    }

    private fun isAnimeUrl(url: String): Boolean =
        queryValue(url, "csType")?.equals("anime", ignoreCase = true) == true ||
            try { java.net.URI(url).path.orEmpty().let { it.startsWith("/anime/") || it == "/anime" } } catch (_: Exception) { false }

    private suspend fun animeMetadata(url: String): JSONObject? {
        val path = try { java.net.URI(url).path.orEmpty() } catch (_: Exception) { return null }
        val seriesId = Regex("^/(?:tv|anime)/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.getOrNull(1) ?: return null
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/"
        )
        return try {
            JSONObject(app.get(
                "https://db.wecollege.net/3/tv/$seriesId?language=en-US",
                headers = headers,
                timeout = 12
            ).text).takeIf { it.optInt("id", 0) == seriesId.toInt() }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val isAnime = isAnimeUrl(url)
        val animeInfo = if (isAnime) animeMetadata(url) else null
        val fallbackTitle = pageTitle(document)
        val title = animeInfo?.optString("name")?.takeIf { it.isNotBlank() && it != "null" } ?: fallbackTitle
        val metadataPoster = animeInfo?.optString("poster_path")?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
        val poster = metadataPoster ?: pagePoster(document)
        val metadataPlot = animeInfo?.optString("overview")?.takeIf { it.isNotBlank() && it != "null" }
        val rawPlot = pagePlot(document)
        val plot = metadataPlot ?: rawPlot?.takeUnless {
            it.contains("Movy", ignoreCase = true) && it.contains("Watch Free", ignoreCase = true)
        }
        val tags = document.select(".genres a, .film-genre a, a[href*='/genre/']").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val isMovie = url.contains("/movie/") || document.selectFirst("meta[property='og:type']")?.attr("content") == "video.movie"
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        // Movy's TV detail page is a client-rendered shell; its episode links may
        // not exist in the HTML returned to CloudStream. Build the episode list
        // from the same TMDB-compatible metadata API used by Movy's resolver.
        val apiEpisodes = if (url.contains("/tv/") && !url.contains("/anime/")) {
            metadataEpisodes(url, poster, isAnime = isAnime)
        } else emptyList()
        val episodeLinks = if (apiEpisodes.isEmpty()) episodeElements(document) else emptyList()
        val episodes = if (apiEpisodes.isNotEmpty()) {
            apiEpisodes
        } else if (episodeLinks.isNotEmpty()) {
            episodeLinks.mapIndexed { index, element ->
                val href = element.absUrl("href").ifBlank { element.attr("href") }
                val route = numericEpisodeRoute(href)
                val textLabel = element.text().trim()
                val label = when {
                    textLabel.isBlank() -> "Episode ${route?.third ?: index + 1}"
                    textLabel.length > 100 -> textLabel.take(100)
                    textLabel.equals("play", true) || textLabel.equals("watch", true) ->
                        "Episode ${route?.third ?: index + 1}"
                    else -> textLabel
                }
                val episodeNumber = route?.third
                    ?: Regex("(?i)(?:episode|ep|e)\\s*0*(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                newEpisode(href) {
                    name = label
                    season = route?.second
                        ?: Regex("(?i)(?:season|s)\\s*0*(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: 1
                    episode = episodeNumber
                    posterUrl = poster
                }
            }
        } else {
            // Do not fabricate a fake "Movy Watch Anything" episode when a series
            // page's episode list has not been detected.
            emptyList()
        }
        return newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)

        // Movy's own backend returns encrypted multi-server source lists; resolve these first.
        val resolved = try { MovyResolver.resolve(data, document) } catch (_: Exception) { emptyList() }
        var found = false
        resolved.forEach { (url, label) ->
            try {
                val handled = loadExtractor(url, subtitleCallback) { link ->
                    callback(link)
                    found = true
                }
                if (!handled) {
                    // Resolver payloads are already direct media URLs. Some CDN
                    // URLs are signed/extensionless, so do not discard them just
                    // because their path lacks .m3u8 or .mp4.
                    val linkType = when {
                        url.contains(".mp4", true) -> ExtractorLinkType.VIDEO
                        url.contains(".mpd", true) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.M3U8
                    }
                    callback(
                        newExtractorLink(
                            source = "Movy",
                            name = label,
                            url = url,
                            type = linkType
                        ) {
                            referer = "$mainUrl/"
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                                "Origin" to mainUrl
                            )
                        }
                    )
                    found = true
                } else found = true
            } catch (_: Exception) { }
        }

        // Fall back to ordinary embed extraction if the site backend yielded nothing.
        val sources = sourcesFrom(document, data)
        if (sources.isEmpty()) {
            if (found) return true
            return loadExtractor(data, subtitleCallback, callback)
        }
        sources.forEach { (url, label) ->
            try {
                val handled = loadExtractor(url, subtitleCallback) { link ->
                    callback(link)
                    found = true
                }
                if (!handled) {
                    // Keep direct video links available when no extractor is registered.
                    if (url.matches(Regex("(?i)^https?://.*\\.(m3u8|mp4|mpd)(?:\\?.*)?$"))) {
                        callback(
                            newExtractorLink(
                                source = "Movy",
                                name = label,
                                url = url,
                                type = if (url.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                            )
                        )
                        found = true
                    }
                } else found = true
            } catch (_: Exception) { }
        }
        return found
    }
}
