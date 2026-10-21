package com.bililive.danmaku.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** B站登录管理器。登录请求必须属于当前尝试，才可以写入账号状态。 */
class BiliBiliLoginManager internal constructor(
    private val prefs: SharedPreferences,
    private val client: Call.Factory,
    private val ioDispatcher: CoroutineDispatcher,
    private val logWarning: (String) -> Unit = {}
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE),
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
        Dispatchers.IO,
        { Log.w(TAG, it) }
    )
    companion object {
        private const val TAG = "BiliLogin"
        private const val PREF_NAME = "bili_login"
        private const val KEY_SESSDATA = "sessdata"
        private const val KEY_BILI_JCT = "bili_jct"
        private const val KEY_DEDE_USER_ID = "dede_user_id"
        private const val KEY_DEDE_USER_ID_MD5 = "dede_user_id_md5"
        private const val KEY_USERNAME = "username"
        private const val KEY_UID = "uid"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val attempts = LoginAttemptGuard()
    private val profileScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    data class LoginState(val isLoggedIn: Boolean = false, val username: String = "", val uid: Long = 0)
    data class QRCodeData(val url: String, val qrcodeKey: String)
    private data class UserInfo(val username: String = "", val uid: Long = 0)

    fun getLoginState(): LoginState = attempts.read {
        LoginState(
            isLoggedIn = !prefs.getString(KEY_SESSDATA, "").isNullOrEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            uid = prefs.getLong(KEY_UID, 0)
        )
    }

    suspend fun sendSmsCode(phone: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val captcha = getCaptcha()
            val body = FormBody.Builder().add("cid", "86").add("tel", phone)
            if (!captcha?.optString("token").isNullOrEmpty()) {
                for (key in listOf("token", "challenge", "validate", "seccode")) {
                    body.add(key, captcha?.optString(key).orEmpty())
                }
            }
            val response = client.newCall(passportRequest("sms/send").post(body.build()).build())
                .awaitResponseData()
            val json = response.json()
            val code = json.optInt("code", -1)
            if (code == 0) Result.success(Unit)
            else Result.failure(Exception(json.optString("message", "发送失败: $code")))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure(Exception("网络请求失败，请稍后重试"))
        }
    }

    suspend fun loginWithSms(phone: String, code: String): Result<Unit> = withContext(ioDispatcher) {
        val attempt = attempts.begin(LoginAttemptGuard.Kind.SMS)
        try {
            val body = FormBody.Builder().add("cid", "86").add("tel", phone).add("code", code).build()
            val response = attempts.execute(attempt, client.newCall(
                passportRequest("login/sms").post(body).build()
            ))
            val json = response.json()
            val resultCode = json.optInt("code", -1)
            if (resultCode == 0) {
                completeLogin(attempt, response.cookies())
                Result.success(Unit)
            } else {
                Result.failure(Exception(json.optString("message", "登录失败: $resultCode")))
            }
        } catch (e: CancellationException) {
            attempts.cancel(attempt)
            throw e
        } catch (_: Exception) {
            Result.failure(Exception("登录失败，请检查网络或重试"))
        }
    }

    private suspend fun getCaptcha(): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("https://passport.bilibili.com/x/passport-login/captcha")
                .header("User-Agent", UA)
                .build()
            val json = client.newCall(request).awaitResponseData().json()
            if (json.optInt("code", -1) == 0) json.optJSONObject("data") else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun loginWithCookieString(cookieString: String): Boolean {
        val cookies = parseCookies(cookieString.split(";"))
        val sessdata = cookies["SESSDATA"].orEmpty()
        if (sessdata.isEmpty()) return false
        val attempt = attempts.begin(LoginAttemptGuard.Kind.COOKIE)
        return try {
            attempts.withCurrent(attempt) { saveLogin(cookies, UserInfo()) }
            profileScope.launch {
                try {
                    val info = fetchUserInfo(attempt, sessdata)
                    attempts.withCurrent(attempt, currentCoroutineContext()) {
                        prefs.edit().putString(KEY_USERNAME, info.username).putLong(KEY_UID, info.uid).apply()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    logWarning("保存用户信息失败")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun generateQRCode(): Result<QRCodeData> = withContext(ioDispatcher) {
        val attempt = attempts.begin(LoginAttemptGuard.Kind.QR)
        try {
            val response = attempts.execute(attempt, client.newCall(passportRequest("qrcode/generate").build()))
            val json = response.json()
            if (json.optInt("code", -1) != 0) {
                attempts.cancel(attempt)
                Result.failure(Exception(json.optString("message", "生成二维码失败")))
            } else {
                val data = json.getJSONObject("data")
                val qrCode = QRCodeData(data.getString("url"), data.getString("qrcode_key"))
                require(qrCode.url.isNotBlank() && qrCode.qrcodeKey.isNotBlank())
                attempts.bindQrKey(attempt, qrCode.qrcodeKey, currentCoroutineContext())
                Result.success(qrCode)
            }
        } catch (e: CancellationException) {
            attempts.cancel(attempt)
            throw e
        } catch (_: Exception) {
            attempts.cancel(attempt)
            Result.failure(Exception("生成二维码失败，请检查网络或重试"))
        }
    }

    /** 0: 登录成功，1: 未扫码，2: 待确认，3: 已过期。旧二维码不可恢复登录。 */
    suspend fun pollQRCodeStatus(qrcodeKey: String): Result<Int> = withContext(ioDispatcher) {
        val attempt = attempts.qrAttempt(qrcodeKey)
        try {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https").host("passport.bilibili.com")
                .addPathSegments("x/passport-login/web/qrcode/poll")
                .addQueryParameter("qrcode_key", qrcodeKey)
                .build()
            val request = Request.Builder().url(url).header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com").build()
            val response = attempts.execute(attempt, client.newCall(request))
            val json = response.json()
            if (json.optInt("code", -1) != 0) {
                Result.failure(Exception("二维码状态请求失败"))
            } else {
                when (json.optJSONObject("data")?.optInt("code", -1)) {
                    0 -> {
                        completeLogin(attempt, response.cookies())
                        Result.success(0)
                    }
                    86101 -> Result.success(1)
                    86090 -> Result.success(2)
                    86038 -> {
                        attempts.cancel(attempt)
                        Result.success(3)
                    }
                    else -> Result.failure(Exception("未知二维码状态"))
                }
            }
        } catch (e: CancellationException) {
            attempts.cancel(attempt)
            throw e
        } catch (_: Exception) {
            Result.failure(Exception("二维码状态请求失败，请稍后重试"))
        }
    }

    /** Call synchronously before changing QR UI state; do not wait for recomposition cancellation. */
    fun cancelQrLogin() = attempts.cancelQr()

    /** Abandon interactive QR/SMS login without interrupting an already imported account. */
    fun cancelPendingLogin() = attempts.cancelInteractive()

    private suspend fun completeLogin(attempt: LoginAttemptGuard.Attempt, cookies: Map<String, String>) {
        val sessdata = cookies["SESSDATA"].orEmpty()
        require(sessdata.isNotEmpty()) { "登录成功但未获取到Cookie" }
        // 先完成可能被取消的网络请求，再一次性提交；中途退出不留下半完成的登录。
        val info = fetchUserInfo(attempt, sessdata)
        attempts.withCurrent(attempt, currentCoroutineContext()) { saveLogin(cookies, info) }
    }

    private suspend fun fetchUserInfo(attempt: LoginAttemptGuard.Attempt, sessdata: String): UserInfo {
        return try {
            val request = Request.Builder().url("https://api.bilibili.com/x/web-interface/nav")
                .header("User-Agent", UA).header("Cookie", "SESSDATA=$sessdata").build()
            val json = attempts.execute(attempt, client.newCall(request)).json()
            if (json.optInt("code", -1) == 0) {
                val data = json.getJSONObject("data")
                UserInfo(data.optString("uname", ""), data.optLong("mid", data.optLong("uid", 0)))
            } else UserInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A profile failure is non-fatal; do not log response bodies, session values or names.
            logWarning("获取用户信息失败")
            UserInfo()
        }
    }

    /** Must only run inside attempts.withCurrent, including the profile reset on account change. */
    private fun saveLogin(cookies: Map<String, String>, info: UserInfo) {
        prefs.edit()
            .putString(KEY_SESSDATA, cookies["SESSDATA"].orEmpty())
            .putString(KEY_BILI_JCT, cookies["bili_jct"].orEmpty())
            .putString(KEY_DEDE_USER_ID, cookies["DedeUserID"].orEmpty())
            .putString(KEY_DEDE_USER_ID_MD5, cookies["DedeUserID__ckMd5"].orEmpty())
            .putString(KEY_USERNAME, info.username)
            .putLong(KEY_UID, info.uid)
            .apply()
    }

    fun getLoginCookieString(): String = attempts.read {
        val snapshot = prefs.all
        listOf(
            "SESSDATA" to KEY_SESSDATA, "bili_jct" to KEY_BILI_JCT,
            "DedeUserID" to KEY_DEDE_USER_ID, "DedeUserID__ckMd5" to KEY_DEDE_USER_ID_MD5
        ).mapNotNull { (name, key) ->
            (snapshot[key] as? String)?.takeIf(String::isNotEmpty)?.let { "$name=$it" }
        }.joinToString("; ")
    }

    fun getSessdata(): String = attempts.read { prefs.getString(KEY_SESSDATA, "").orEmpty() }
    fun isLoggedIn(): Boolean = getSessdata().isNotEmpty()
    fun logout() = attempts.logout { prefs.edit().clear().apply() }

    private fun passportRequest(path: String): Request.Builder = Request.Builder()
        .url("https://passport.bilibili.com/x/passport-login/web/$path")
        .header("User-Agent", UA).header("Referer", "https://www.bilibili.com")

    private fun HttpResponseData.json(): JSONObject {
        check(code in 200..299) { "HTTP request failed" }
        return JSONObject(body)
    }

    private fun HttpResponseData.cookies(): Map<String, String> =
        parseCookies(headers.values("Set-Cookie").map { it.substringBefore(';') })

    private fun parseCookies(parts: List<String>): Map<String, String> = parts.mapNotNull { part ->
        val pair = part.trim().split("=", limit = 2)
        if (pair.size == 2) pair[0].trim() to pair[1].trim() else null
    }.toMap()
}
