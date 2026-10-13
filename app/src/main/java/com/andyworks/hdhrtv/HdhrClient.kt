package com.andyworks.hdhrtv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val number: String,
    val name: String,
    val url: String,
    val hd: Boolean,
)

object HdhrClient {

    /** Ask SiliconDust's cloud which HDHomeRun tuners are on this LAN. Returns e.g. "http://192.168.1.50". */
    suspend fun discoverBaseUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val devices = JSONArray(get("https://api.hdhomerun.com/discover"))
            (0 until devices.length())
                .map { devices.getJSONObject(it) }
                .firstOrNull { it.has("LineupURL") } // tuners have a lineup; the DVR storage entry doesn't
                ?.optString("BaseURL")
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalize)
        }.getOrNull()
    }

    /** ATSC 1.0 channels only: ATSC 3.0 (AC-4 audio) and DRM channels are skipped. */
    suspend fun lineup(baseUrl: String): List<Channel> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("$baseUrl/lineup.json"))
        (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.optInt("ATSC3") != 1 && it.optInt("DRM") != 1 && it.has("URL") }
            .map {
                Channel(
                    number = it.optString("GuideNumber"),
                    name = it.optString("GuideName"),
                    url = it.getString("URL"),
                    hd = it.optInt("HD") == 1,
                )
            }
    }

    /** "192.168.1.50" -> "http://192.168.1.50" */
    fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
