package com.bililive.danmaku.data.remote

import android.content.Context
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * B站 Cookie 管理器 - 纯 HTTP 方式
 * 
 * 不使用 WebView，通过 SPI API 获取 buvid3
 * 登录 Cookie 由 BiliBiliLoginManager 提供
 */
class BiliBiliCookieJar(
    private val context: Context,
    private val loginManager: BiliBiliLoginManager? = null
) : CookieJar {

    companion object {
        private const val TAG = "BiliCookie"
        private const val BILI_DOMAIN = ".bilibili.com"
        private const val BILI_SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private var cookieInitialized = false

    /**
     * 初始化 Cookie：通过 SPI API 获取 buvid3
     * 不使用 WebView
     */
    suspend fun initializeCookie(scope: kotlinx.coroutines.CoroutineScope) {
        if (cookieInitialized) return
        try {
            val buvidData = fetchBuvidFromApi()
            if (buvidData != null) {
                val (b3, b4) = buvidData
                addCookie("buvid3", b3, BILI_DOMAIN)
                if (b4.isNotEmpty()) {
                    addCookie("buvid4", b4, BILI_DOMAIN)
                }
                Log.d(TAG, "buvid3 获取成功: ${b3.take(20)}...")
            }
            cookieInitialized = true
        } catch (e: Exception) {
            Log.w(TAG, "初始化Cookie失败: ${e.message}")
            cookieInitialized = true  // 即使失败也标记为已初始化
        }
    }

    private fun fetchBuvidFromApi(): Pair<String, String>? {
        return try {
            val url = java.net.URL(BILI_SPI_URL)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://live.bilibili.com")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = org.json.JSONObject(body)
            if (json.optInt("code") == 0) {
                val data = json.getJSONObject("data")
                val b3 = data.optString("b_3", "")
                val b4 = data.optString("b_4", "")
                if (b3.isNotEmpty()) Pair(b3, b4) else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "获取buvid失败: ${e.message}")
            null
        }
    }

    private fun addCookie(name: String, value: String, domain: String) {
        val cookie = Cookie.Builder()
            .domain(domain)
            .path("/")
            .name(name)
            .value(value)
            .build()
        cookieStore.getOrPut(domain) { mutableListOf() }.apply {
            removeAll { it.name == name }
            add(cookie)
        }
    }

    /**
     * 获取 Cookie 字符串（用于 WebSocket Handshake）
     */
    fun getCookieString(): String {
        val parts = mutableListOf<String>()

        // 登录 Cookie（SESSDATA 等）
        val loginCookies = loginManager?.getLoginCookieString() ?: ""
        if (loginCookies.isNotEmpty()) {
            parts.add(loginCookies)
        }

        // 浏览器 Cookie（buvid3 等）
        val browserCookies = cookieStore.values.flatten()
            .joinToString("; ") { "${it.name}=${it.value}" }
        if (browserCookies.isNotEmpty()) {
            parts.add(browserCookies)
        }

        return if (parts.isNotEmpty()) parts.joinToString("; ") else ""
    }

    fun getBuvid3(): String {
        return cookieStore.values.flatten()
            .firstOrNull { it.name == "buvid3" }
            ?.value ?: ""
    }

    fun getUserAgent(): String = USER_AGENT

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            cookieStore.getOrPut(cookie.domain) { mutableListOf() }.apply {
                removeAll { it.name == cookie.name }
                add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore.values.flatten().filter { cookie ->
            url.host.endsWith(cookie.domain.removePrefix("."))
        }
    }

    fun destroy() {
        cookieStore.clear()
    }
}
