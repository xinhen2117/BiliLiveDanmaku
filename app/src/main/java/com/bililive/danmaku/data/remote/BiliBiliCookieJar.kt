package com.bililive.danmaku.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
        private const val BILI_DOMAIN = "bilibili.com"
        private const val BILI_SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val cookieStore = BiliBiliCookieStore(
        loginCookieProvider = { loginManager?.getLoginCookieString().orEmpty() }
    )
    private var cookieInitialized = false
    private val initializationMutex = Mutex()
    private val initializationClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /**
     * 初始化 Cookie：通过 SPI API 获取 buvid3
     * 不使用 WebView
     */
    suspend fun initializeCookie() = initializationMutex.withLock {
        if (cookieInitialized) return@withLock
        try {
            val buvidData = fetchBuvidFromApi()
            if (buvidData != null) {
                val (b3, b4) = buvidData
                addCookie("buvid3", b3, BILI_DOMAIN)
                if (b4.isNotEmpty()) {
                    addCookie("buvid4", b4, BILI_DOMAIN)
                }
                Log.d(TAG, "buvid3 获取成功")
                cookieInitialized = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w(TAG, "初始化Cookie失败")
        }
    }

    private suspend fun fetchBuvidFromApi(): Pair<String, String>? {
        return try {
            val request = Request.Builder().url(BILI_SPI_URL)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://live.bilibili.com")
                .build()
            val response = initializationClient.newCall(request).awaitResponseData()
            if (response.code !in 200..299) return null
            val json = org.json.JSONObject(response.body)
            if (json.optInt("code") == 0) {
                val data = json.getJSONObject("data")
                val b3 = data.optString("b_3", "")
                val b4 = data.optString("b_4", "")
                if (b3.isNotEmpty()) Pair(b3, b4) else null
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w(TAG, "获取buvid失败")
            null
        }
    }

    private fun addCookie(name: String, value: String, domain: String) {
        val cookie = Cookie.Builder()
            .domain(domain)
            .path("/")
            .name(name)
            .value(value)
            .secure()
            .build()
        cookieStore.saveFromResponse(BILI_SPI_URL.toHttpUrl(), listOf(cookie))
    }

    fun getBuvid3(): String {
        return cookieStore.browserCookieValue("buvid3", "https://live.bilibili.com/".toHttpUrl())
    }

    fun getUserAgent(): String = USER_AGENT

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookieStore.saveFromResponse(url, cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore.loadForRequest(url)
    }

    fun destroy() {
        cookieStore.clear()
        initializationClient.dispatcher.executorService.shutdown()
        initializationClient.connectionPool.evictAll()
    }
}
