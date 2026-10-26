package com.movy

import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Movy's player backend returns encrypted source lists from several nodes.
 * This resolves those lists before CloudStream's normal extractor fallback.
 */
internal object MovyResolver {
    private const val API = "https://api.wecollege.net"
    private const val REFERER = "https://www.movy.sx/"
    private val nodes = listOf(
        "miami", "boise", "vegas", "reno", "oakland", "seattle", "phoenix",
        "atlanta", "portland", "austin", "dallas", "tampa", "orlando",
        "munich", "berlin", "paris", "delhi", "cancun"
    )
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to REFERER,
        "Origin" to "https://www.movy.sx",
        "Accept" to "application/json, text/plain, */*"
    )

    suspend fun resolve(pageUrl: String, doc: Document): List<Pair<String, String>> {
        val html = doc.html()
        val tmdbId = findTmdbId(pageUrl, html, doc) ?: return emptyList()
        val isMovie = pageUrl.contains("/movie/") ||
            doc.selectFirst("meta[property='og:type']")?.attr("content") == "video.movie"
        val mediaType = if (isMovie) "movie" else "tv"
        val titleRaw = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: doc.selectFirst("h1, .heading-name, .film-name")?.text()
            ?: doc.title()
        val title = titleRaw.substringBefore(" | ").substringBefore(" - Episode")
            .substringBefore(" - Season").substringBefore(" Season ").trim()
        if (title.isBlank()) return emptyList()
        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(
            doc.selectFirst("meta[property='og:title']")?.attr("content").orEmpty() + " " +
                doc.selectFirst("meta[name=description]")?.attr("content").orEmpty() + " " + html.take(30000)
        )?.groupValues?.get(1).orEmpty()
        val imdbId = Regex("tt\\d{7,9}").find(html)?.value.orEmpty()
        val seasonEpisode = findSeasonEpisode(pageUrl, html, doc)
        val seed = try {
            val seedDoc = app.get("$API/seed?mediaId=$tmdbId", headers = headers, timeout = 15).text
            JSONObject(seedDoc).optString("seed").takeIf { it.isNotBlank() } ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        val params = linkedMapOf(
            "title" to title,
            "mediaType" to mediaType,
            "year" to year,
            "tmdbId" to tmdbId.toString(),
            "imdbId" to imdbId,
            "episodeId" to (seasonEpisode.second.coerceAtLeast(1)).toString(),
            "seasonId" to (seasonEpisode.first.coerceAtLeast(1)).toString(),
            "enc" to "2",
            "seed" to seed
        )
        val totalSeasons = Regex("(?i)number_of_seasons[\\\"']?\\s*[:=]\\s*[\\\"']?(\\d+)")
            .find(html)?.groupValues?.getOrNull(1)
        if (!totalSeasons.isNullOrBlank()) params["totalSeasons"] = totalSeasons

        val results = linkedMapOf<String, String>()
        // Resolve nodes sequentially without an additional coroutine dependency.
        for (node in nodes) {
            try {
                val query = params.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }
                val response = app.get("$API/$node/sources?$query", headers = headers, timeout = 12).text.trim()
                val plain = decrypt(response, seed, tmdbId) ?: continue
                val json = JSONObject(plain)
                val sources = json.optJSONArray("sources")
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val item = sources.optJSONObject(i) ?: continue
                        val url = item.optString("url").ifBlank { item.optString("file") }.trim()
                        if (!url.startsWith("http")) continue
                        val quality = item.optString("quality").ifBlank { "Auto" }
                        results.putIfAbsent(url, "Movy · $quality")
                    }
                }
                val audio = json.optJSONArray("audio")
                if (audio != null) {
                    for (i in 0 until audio.length()) {
                        val item = audio.optJSONObject(i) ?: continue
                        val url = item.optString("url").ifBlank { item.optString("file") }.trim()
                        if (url.startsWith("http")) {
                            results.putIfAbsent(url, "Movy · " + item.optString("lang", "Auto"))
                        }
                    }
                }
            } catch (_: Exception) {
                // A failed node should not prevent trying the remaining nodes.
            }
        }
        return results.map { it.key to it.value }
    }

    private fun findTmdbId(pageUrl: String, html: String, doc: Document): Int? {
        val explicitPatterns = listOf(
            Regex("(?i)(?:tmdb[_-]?id|tmdbId|themoviedb[_-]?id)[\\\"'\\s:=]+(\\d{1,9})"),
            Regex("(?i)[?&](?:tmdbId|tmdb_id|id)=(\\d{1,9})"),
            Regex("(?i)data-(?:tmdb-id|tmdbid|id)=[\\\"'](\\d{1,9})[\\\"']")
        )
        for (pattern in explicitPatterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        doc.select("[data-tmdb-id], [data-tmdbid], [data-id]").forEach { element ->
            listOf("data-tmdb-id", "data-tmdbid", "data-id").forEach { key ->
                element.attr(key).toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        }
        val path = try { java.net.URI(pageUrl).path.orEmpty() } catch (_: Exception) { pageUrl }
        Regex("-(\\d{2,9})/?$").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun findSeasonEpisode(pageUrl: String, html: String, doc: Document): Pair<Int, Int> {
        val combined = pageUrl + " " + doc.title() + " " +
            doc.selectFirst("h1, h2, .heading-name")?.text().orEmpty() + " " + html.take(50000)
        val season = Regex("(?i)(?:season|\\bs)\\s*[-:#]?\\s*0*(\\d{1,2})")
            .find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val episode = Regex("(?i)(?:episode|\\bep\\.?|\\be)\\s*[-:#]?\\s*0*(\\d{1,3})")
            .find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        return season to episode
    }

    private fun decrypt(encoded: String, seed: String, tmdbId: Int): String? {
        return try {
            val normalized = encoded.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val bytes = Base64.getDecoder().decode(padded)
            val stream = keystream(seed, tmdbId, bytes.size)
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor stream[i].toInt()).toByte()
            if (bytes.size < 4 || bytes[0] != 109.toByte() || bytes[1] != 118.toByte() ||
                bytes[2] != 109.toByte() || bytes[3] != 49.toByte()) return null
            String(bytes, 4, bytes.size - 4, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun mixHash(input: Int): Int {
        var x = input
        x = x xor (x ushr 16)
        x *= 2246822507L.toInt()
        x = x xor (x ushr 13)
        x *= 3266489909L.toInt()
        x = x xor (x ushr 16)
        return x
    }

    private fun fnvSeed(seed: String): Int {
        var value = 2166136261L.toInt()
        seed.toByteArray(Charsets.UTF_8).forEach { byte ->
            value = (value xor (byte.toInt() and 0xff)) * 16777619
        }
        return mixHash(value)
    }

    private fun keystream(seed: String, tmdbId: Int, size: Int): ByteArray {
        val slots = IntArray(61)
        val present = BooleanArray(61)
        var i = mixHash(fnvSeed(seed) xor mixHash(tmdbId xor 2654435769L.toInt()))
        repeat(8) { r ->
            val n = Integer.remainderUnsigned(i, 61)
            i = Integer.rotateLeft(i + 2654435769L.toInt(), 7 + (r and 7))
            slots[n] = i xor mixHash(i)
            present[n] = true
            i = mixHash(i + n)
        }
        var acc = mixHash(i xor 2779096485L.toInt())
        val out = ByteArray(size)
        var offset = 0
        var wordOffset = 0
        while (offset < size) {
            val r = Integer.remainderUnsigned(acc, 61)
            val neg = if (present[r]) -1 else 0
            val u = slots[r]
            val d = 2654435769L.toInt() * (wordOffset + 1)
            val v = acc xor (u xor d)
            val g = v or (acc and (u xor d) and neg)
            val rotated = Integer.rotateLeft(g + acc, r and 31) xor Integer.rotateLeft(acc, (r * 7) and 31)
            acc = mixHash(rotated + 2654435769L.toInt())
            slots[r] = acc
            present[r] = true
            repeat(4) { k ->
                if (offset < size) out[offset++] = (acc ushr (8 * k)).toByte()
            }
            wordOffset++
        }
        return out
    }
}
