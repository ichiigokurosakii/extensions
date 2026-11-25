package com.fullmatchshows

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FullMatchShowsProvider : MainAPI() {
    override var mainUrl = "https://fullmatchshows.com"
    override var name = "FullMatchShows"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private suspend fun doc(url: String): Document = app.get(url, timeout = 20).document

    private fun absoluteAssetUrl(element: Element, raw: String): String? {
        return runCatching {
            java.net.URI(element.baseUri()).resolve(raw).toString()
        }.getOrNull()?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
    }

    /**
     * Resolve the real image URL used by WordPress themes and lazy-load plugins.
     * Prefer lazy-load attributes over placeholder src values.
     */
    private fun imageFrom(element: Element?): String? {
        if (element == null) return null

        val attributes = listOf(
            "data-lazy-src",
            "data-src",
            "data-original",
            "data-orig-file",
            "data-url",
            "src",
            "content",
            "href"
        )

        for (attribute in attributes) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank() || raw.startsWith("data:", true) ||
                raw.startsWith("javascript:", true) || raw == "#"
            ) continue

            absoluteAssetUrl(element, raw)?.let { return it }
        }

        val srcSet = element.attr("data-srcset").ifBlank {
            element.attr("srcset")
        }
        if (srcSet.isNotBlank()) {
            val candidates = srcSet.split(",").mapNotNull { candidate ->
                val raw = candidate.trim().substringBefore(" ").trim()
                if (raw.isBlank() || raw.startsWith("data:", true)) null
                else absoluteAssetUrl(element, raw)
            }
            candidates.lastOrNull()?.let { return it }
        }

        // Some themes render catalogue thumbnails as CSS background images.
        val style = element.attr("style")
        val backgroundUrl = Regex(
            """background-image\s*:\s*url\(['"]?(.*?)['"]?\)""",
            RegexOption.IGNORE_CASE
        ).find(style)?.groupValues?.getOrNull(1)
        if (!backgroundUrl.isNullOrBlank()) {
            absoluteAssetUrl(element, backgroundUrl)?.let { return it }
        }

        return null
    }

    /** Read the image explicitly published by the match page's social/share metadata. */
    private fun metadataPoster(document: Document): String? {
        return document.select(
            "meta[property=og:image], meta[property=og:image:secure_url], " +
                "meta[name=twitter:image], meta[property=twitter:image], link[rel=image_src]"
        ).firstNotNullOfOrNull { metadata ->
            val attribute = if (metadata.tagName() == "link") "href" else "content"
            imageFrom(metadata)
                ?: absoluteAssetUrl(metadata, metadata.attr(attribute).trim())
        }
    }

    private fun cardImage(anchor: Element): String? {
        val card = anchor.parents().firstOrNull {
            it.tagName() == "article" ||
                it.classNames().any { name ->
                    name.contains("post", true) ||
                        name.contains("thumbnail", true) ||
                        name.contains("module", true)
                }
        } ?: anchor.parent()

        return imageFrom(anchor.selectFirst("img, source"))
            ?: imageFrom(card?.selectFirst("img, source"))
            ?: imageFrom(card?.selectFirst("[style*=background-image]"))
    }

    private fun cardAnchors(document: Document): List<Element> {
        val candidates = document.select(
            "article a[href], .post a[href], .entry-title a[href], " +
                ".jeg_post_title a[href], .td-module-title a[href]"
        )
        val seen = mutableSetOf<String>()

        return candidates.filter { anchor ->
            val href = anchor.absUrl("href").trimEnd('/')
            val title = anchor.text().trim().ifBlank {
                anchor.attr("title").trim().ifBlank { anchor.attr("aria-label").trim() }
            }

            href.isNotBlank() && title.isNotBlank() &&
                href.startsWith(mainUrl, ignoreCase = true) &&
                href.trimEnd('/') != mainUrl.trimEnd('/') &&
                !href.contains("/category/") && !href.contains("/tag/") &&
                !href.contains("/page/") && seen.add(href)
        }
    }

    private suspend fun cards(document: Document): List<SearchResponse> {
        val anchors = cardAnchors(document)
        val entries = anchors.map { anchor ->
            Triple(
                anchor.absUrl("href").trimEnd('/'),
                anchor.text().trim().ifBlank {
                    anchor.attr("title").trim().ifBlank { anchor.attr("aria-label").trim() }
                },
                cardImage(anchor)
            )
        }

        // Use the catalogue image immediately. If it is missing, fetch the
        // match page's Open Graph/Twitter metadata as a poster fallback.
        val results = mutableListOf<SearchResponse>()
        for ((url, title, cardPoster) in entries) {
            val poster = cardPoster ?: runCatching {
                metadataPoster(app.get(url, timeout = 12).document)
            }.getOrNull()

            results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            })
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val results = cards(doc(url))
        return newHomePageResponse(
            listOf(HomePageList("Match replays", results, isHorizontalImages = true)),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return cards(doc("$mainUrl/?s=$encoded"))
    }

    /**
     * Find the article's actual video/link block first, then fall back to the main
     * article body. Link labels are deliberately not filtered by words: the section's
     * HTML location determines which links are episodes.
     */
    private fun videoLinksArea(document: Document): Element? {
        val explicitSection = document.selectFirst(
            ".video-links, .match-links, .watch-links, .stream-links, " +
                ".video-section, .match-video-links, .match-streams, .player-links"
        ) ?: document.select(
            "[class*=video-link], [class*=match-link], [class*=stream-link]"
        ).firstOrNull { it.select("a[href]").isNotEmpty() }

        if (explicitSection != null && explicitSection.select("a[href]").isNotEmpty()) {
            return explicitSection
        }

        return document.selectFirst(
            ".entry-content, .post-content, .jeg_post_content, .td-post-content, " +
                ".article-content, .post-body, article"
        )
    }

    private fun episodeLinks(document: Document, pageUrl: String): List<Episode> {
        val area = videoLinksArea(document) ?: return emptyList()
        val seen = mutableSetOf<String>()

        return area.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").trim()
            val label = anchor.text().trim().ifBlank {
                anchor.attr("title").trim().ifBlank {
                    anchor.attr("aria-label").trim().ifBlank {
                        anchor.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
                    }
                }
            }

            val isHighlight = href.contains("highlight", ignoreCase = true) ||
                label.contains("highlight", ignoreCase = true)
            val isTelegramPromo = href.contains("t.me/", ignoreCase = true) ||
                href.contains("telegram.me", ignoreCase = true) ||
                label.contains("telegram", ignoreCase = true) ||
                label.contains("join our channel", ignoreCase = true)

            if (href.isBlank() || label.isBlank() ||
                isHighlight || isTelegramPromo ||
                href == pageUrl || href.startsWith("#") ||
                href.startsWith("javascript:", true) ||
                href.startsWith("mailto:", true) ||
                href.startsWith("tel:", true) ||
                href.startsWith(mainUrl, ignoreCase = true) ||
                !href.startsWith("http://", true) && !href.startsWith("https://", true) ||
                !seen.add("$href|$label")
            ) return@mapNotNull null

            newEpisode(href) {
                // Preserve the website's wording, including language and half labels.
                name = label
                season = 1
            }
        }.mapIndexed { index, episode ->
            episode.copy(episode = index + 1)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = doc(url)
        val title = document.selectFirst("h1.entry-title, h1.post-title, article h1, h1")
            ?.text()?.trim().orEmpty().ifBlank { document.title().trim() }
        val poster = metadataPoster(document)
            ?: imageFrom(document.selectFirst("article img, .entry-content img, img"))
        val description = document.selectFirst(
            "meta[name=description], meta[property=og:description]"
        )?.attr("content")?.takeIf { it.isNotBlank() }

        val episodes = episodeLinks(document, url)

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
        return loadExtractor(data, subtitleCallback, callback)
    }
}
