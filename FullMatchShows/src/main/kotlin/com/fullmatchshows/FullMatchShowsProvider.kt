package com.fullmatchshows

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class FullMatchShowsProvider : MainAPI() {
    override var mainUrl = "https://fullmatchshows.com"
    override var name = "FullMatchShows"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private fun doc(url: String) = app.get(url, timeout = 20).document

    private fun cards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val candidates = document.select(
            "article a[href], .post a[href], .entry-title a[href], .jeg_post_title a[href], .td-module-title a[href]"
        )
        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { a ->
            val href = a.absUrl("href").trimEnd('/')
            val title = a.text().trim().ifBlank { a.attr("title").trim() }
            if (href.isBlank() || title.isBlank() || !href.startsWith(mainUrl) ||
                href == mainUrl || href.contains("/category/") || href.contains("/tag/") ||
                href.contains("/page/") || !seen.add(href)
            ) return@mapNotNull null
            val image = a.selectFirst("img")?.let {
                it.absUrl("src").ifBlank { it.absUrl("data-src") }
            }.orEmpty()
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = image.ifBlank { null }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val results = cards(doc(url))
        return newHomePageResponse(
            listOf(HomePageList("Match replays", results, isHorizontalImages = false)),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return cards(doc("$mainUrl/?s=$encoded"))
    }

    override suspend fun load(url: String): LoadResponse {
        val document = doc(url)
        val title = document.selectFirst("h1.entry-title, h1.post-title, article h1, h1")
            ?.text()?.trim().orEmpty().ifBlank { document.title().trim() }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        // Keep source labels as episode names; each episode points at its source page.
        val episodes = document.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            val label = a.text().trim()
            if (label.isBlank() || href.isBlank() || href == url ||
                href.startsWith(mainUrl) || href.startsWith("javascript:") ||
                href.startsWith("mailto:") || href.startsWith("#")
            ) return@mapNotNull null
            val context = a.parent()?.text().orEmpty()
            val looksLikeStream = Regex(
                "(?i)stream|server|watch|play|1080p|720p|480p|full match|match replay|embed|vid"
            ).containsMatchIn("$label $context")
            if (!looksLikeStream) return@mapNotNull null
            Episode(data = href, name = label, season = 1)
        }.distinctBy { it.data to it.name }.mapIndexed { index, ep ->
            ep.copy(episode = index + 1)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Delegate supported public embed/player pages to CloudStream's extractors.
        // This does not attempt to bypass DRM, logins, or access controls.
        loadExtractor(data, data, subtitleCallback, callback)
        return true
    }
}
