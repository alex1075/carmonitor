package uk.co.ashunt.carmonitor

import android.content.Context
import uk.co.ashunt.carmonitor.core.MiniJson
import uk.co.ashunt.carmonitor.core.ObdbCatalog
import uk.co.ashunt.carmonitor.core.SignalSet
import uk.co.ashunt.carmonitor.core.VehicleRef
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the OBDb catalogue and signalsets from GitHub at runtime and caches them in app
 * storage, so logging works offline once a vehicle is chosen. Call off the main thread.
 * Unauthenticated GitHub API = 60 requests/hour; a full catalogue refresh is ~8.
 */
class ObdbClient(ctx: Context) {
    private val dir = File(ctx.filesDir, "obdb").apply { mkdirs() }
    private val catalogFile = File(dir, "catalog.json")

    fun cachedSignalset(repo: String) = File(dir, "$repo.json")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "CarMonitor-Android")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub rate limit hit, try again in an hour")
            if (code != 200) throw IOException("HTTP $code for $url")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** All vehicle repos, from cache unless [refresh] or the cache is missing. */
    fun catalogue(refresh: Boolean = false): List<VehicleRef> {
        if (!refresh && catalogFile.exists()) {
            val l = MiniJson.parse(catalogFile.readText()) as? List<*> ?: emptyList<Any>()
            return l.mapNotNull { ObdbCatalog.parseRepoName(it.toString()) }
        }
        val all = mutableListOf<VehicleRef>()
        for (page in 1..20) {
            val json = get(ObdbCatalog.reposPageUrl(page))
            val rawCount = (MiniJson.parse(json) as? List<*>)?.size ?: 0
            all += ObdbCatalog.parseReposPage(json)
            if (rawCount < 100) break          // last page
        }
        if (all.isEmpty()) throw IOException("empty OBDb catalogue")
        catalogFile.writeText(MiniJson.write(all.map { it.repo }))
        return all
    }

    /**
     * Download the signalset for [repo] matching [year] (year-range override or default.json),
     * validate it parses, cache it. Returns the chosen file name.
     */
    fun download(repo: String, year: Int?): String {
        val files = try { ObdbCatalog.parseDirListing(get(ObdbCatalog.dirUrl(repo))) } catch (e: IOException) { listOf("default.json") }
        val file = ObdbCatalog.chooseFile(files, year)
        val json = get(ObdbCatalog.rawUrl(repo, file))
        SignalSet.parse(repo, json)                      // throws if malformed
        cachedSignalset(repo).writeText(json)
        return file
    }
}
