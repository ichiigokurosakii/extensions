package com.movy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        MainPageData("TV Shows", "$mainUrl/browse/show", horizontalImages = true),
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
        // Movy uses lazy-loaded and Next.js image elements; inspect the link and
        // a few nearby wrappers because the poster is not always inside the anchor.
        val candidates = mutableListOf<Element>()
        var current: Element? = element
        repeat(5) {
            current?.let { candidates.add(it) }
            current = current?.parent()
        }
        val image = candidates.firstNotNullOfOrNull { it.selectFirst("img") }
        val raw = image?.let {
            listOf("data-src", "data-lazy-src", "data-original", "data-image", "src")
                .firstNotNullOfOrNull { key -> it.attr(key).takeIf(String::isNotBlank) }
                ?.takeIf { value -> !value.startsWith("data:image") }
                ?: it.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")
        }?.takeIf(String::isNotBlank) ?: candidates.firstNotNullOfOrNull { candidate ->
            candidate.attr("data-poster").takeIf(String::isNotBlank)
                ?: candidate.attr("data-image").takeIf(String::isNotBlank)
                ?: Regex("""(?i)background-image\s*:\s*url\(['"]?([^)'"]+)""")
                    .find(candidate.attr("style"))?.groupValues?.getOrNull(1)
        }
        return raw?.takeIf(String::isNotBlank)?.let { absolute(document, it) }
    }

    private fun titleOf(element: Element): String {
        val heading = element.selectFirst("h1, h2, h3, .title, .name, .film-name, .movie-title")
            ?.text()?.trim()?.takeIf(String::isNotBlank)
        val imageAlt = element.selectFirst("img")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
        val attribute = element.attr("title").trim().ifBlank { element.attr("aria-label").trim() }
        val text = element.text().trim()
        val generic = setOf("play", "watch", "watch now", "more info", "more information", "image")
        val local = sequenceOf(heading, imageAlt, attribute, text)
            .filterNotNull()
            .firstOrNull { it.isNotBlank() && it.lowercase() !in generic }
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
            path.contains("/watch/") || path.contains("/title/")
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

    private fun searchResponses(document: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        return cardElements(document).mapNotNull { anchor ->
            val url = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trimEnd('/')
            val parent = anchor.parent()
            val title = titleOf(anchor).ifBlank { parent?.let(::titleOf).orEmpty() }
            if (title.isBlank() || url.isBlank() || !seen.add(url)) return@mapNotNull null
            val poster = posterOf(anchor, document) ?: parent?.let { posterOf(it, document) }
            val isMovie = url.contains("/movie/")
            val isAnime = url.contains("/anime/")
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
        val results = searchResponses(document)
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

    private fun pageTitle(document: Document): String =
        document.selectFirst("h1, .heading-name, .film-name, .movie-title, .detail-title")
            ?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" - ").trim()

    private fun pagePoster(document: Document): String? {
        val raw = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?: document.select("img").firstNotNullOfOrNull { image ->
                listOf("data-src", "data-lazy-src", "data-original", "data-image", "src")
                    .firstNotNullOfOrNull { key -> image.attr(key).takeIf(String::isNotBlank) }
                    ?.takeIf { value ->
                        !value.startsWith("data:image") &&
                            (value.contains("image.tmdb.org") || value.contains("poster", true) || value.contains("wsrv.nl"))
                    }
            }
        return raw?.let { absolute(document, it) }
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

    private fun episodeElements(document: Document): List<Element> {
        val selectors = listOf(
            ".episodes-list a[href]", ".ss-list a[href]", ".episode-list a[href]",
            ".ep-item a[href]", ".episode-item a[href]", "[data-id] a[href]",
            "a[href*='/episode/']", "a[href*='/watch/']"
        )
        val result = linkedMapOf<String, Element>()
        selectors.forEach { selector ->
            document.select(selector).forEach { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                if (href.startsWith("http") && href != document.location() &&
                    (href.contains("/episode/") || href.contains("/watch/") ||
                        a.parent()?.classNames()?.any { it.contains("ep") } == true)
                ) result.putIfAbsent(href, a)
            }
        }
        return result.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val title = pageTitle(document)
        val poster = pagePoster(document)
        val plot = pagePlot(document)
        val tags = document.select(".genres a, .film-genre a, a[href*='/genre/']").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val isMovie = url.contains("/movie/") || document.selectFirst("meta[property='og:type']")?.attr("content") == "video.movie"
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        val episodeLinks = episodeElements(document)
        val episodes = if (episodeLinks.isNotEmpty()) {
            episodeLinks.mapIndexed { index, element ->
                val href = element.absUrl("href").ifBlank { element.attr("href") }
                val label = element.text().trim().ifBlank { "Episode ${index + 1}" }
                val episodeNumber = Regex("(?i)(?:episode|ep|e)\\s*0*(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                newEpisode(href) {
                    name = label
                    season = Regex("(?i)(?:season|s)\\s*0*(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    episode = episodeNumber
                    posterUrl = poster
                }
            }
        } else {
            // A detail page without an episode list is still exposed as one playable entry.
            listOf(newEpisode(url) {
                name = title
                season = 1
                episode = 1
                posterUrl = poster
            })
        }
        return newTvSeriesLoadResponse(title, url, if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries, episodes) {
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
