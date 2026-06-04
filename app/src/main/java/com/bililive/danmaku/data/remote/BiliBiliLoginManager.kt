package com.bililive.danmaku.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * B站登录管理器
 *
 * 方式1: 短信验证码登录（使用OkHttp，兼容性好）
 * 方式2: Cookie导入
 */
class BiliBiliLoginManager(private val context: Context) {

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

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // OkHttp 客户端 - 比 HttpURLConnection 更稳定
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val JSON_MEDIA = "application/x-www-form-urlencoded".toMediaType()

    data class LoginState(val isLoggedIn: Boolean = false, val username: String = "", val uid: Long = 0)

    fun getLoginState(): LoginState {
        val sessdata = prefs.getString(KEY_SESSDATA, "") ?: ""
        return LoginState(
            isLoggedIn = sessdata.isNotEmpty(),
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            uid = prefs.getLong(KEY_UID, 0)
        )
    }

    /**
     * 发送短信验证码
     */
    suspend fun sendSmsCode(phone: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 获取GEETEST验证码
                val captcha = getCaptcha()
                val token = captcha?.optString("token") ?: ""
                val challenge = captcha?.optString("challenge") ?: ""
                val validate = captcha?.optString("validate") ?: ""
                val seccode = captcha?.optString("seccode") ?: ""

                // 2. 发送验证码
                val body = buildString {
                    append("cid=86&tel=$phone")
                    if (token.isNotEmpty()) {
                        append("&token=$token&challenge=$challenge&validate=$validate&seccode=$seccode")
                    }
                }

                val request = Request.Builder()
                    .url("https://passport.bilibili.com/x/passport-login/web/sms/send")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                response.close()

                val json = JSONObject(responseBody)
                val code = json.optInt("code", -1)
                Log.d(TAG, "发送验证码: code=$code msg=${json.optString("message")}")

                if (code == 0) Result.success(Unit)
                else Result.failure(Exception(json.optString("message", "错误码: $code")))
            } catch (e: Throwable) {
                Log.e(TAG, "发送验证码异常", e)
                Result.failure(Exception("网络错误: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /**
     * 短信验证码登录
     */
    suspend fun loginWithSms(phone: String, code: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val body = "cid=86&tel=$phone&code=$code"

                val request = Request.Builder()
                    .url("https://passport.bilibili.com/x/passport-login/web/login/sms")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com")
                    .build()

                val response = client.newCall(request).execute()

                // 提取Set-Cookie
                val cookies = mutableMapOf<String, String>()
                response.headers("Set-Cookie").forEach { raw ->
                    val parts = raw.split(";")[0].split("=", limit = 2)
                    if (parts.size == 2) cookies[parts[0].trim()] = parts[1].trim()
                }

                val responseBody = response.body?.string() ?: ""
                response.close()

                val json = JSONObject(responseBody)
                val respCode = json.optInt("code", -1)
                Log.d(TAG, "登录结果: code=$respCode cookies=${cookies.keys}")

                if (respCode == 0) {
                    val sessdata = cookies["SESSDATA"] ?: ""
                    if (sessdata.isNotEmpty()) {
                        saveLoginCookies(sessdata, cookies["bili_jct"] ?: "",
                            cookies["DedeUserID"] ?: "", cookies["DedeUserID__ckMd5"] ?: "")
                        fetchUserInfo(sessdata)
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("登录成功但未获取到Cookie"))
                    }
                } else {
                    Result.failure(Exception(json.optString("message", "登录失败: $respCode")))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "登录异常", e)
                Result.failure(Exception("网络错误: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /**
     * 获取GEETEST验证码
     */
    private fun getCaptcha(): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("https://passport.bilibili.com/x/passport-login/captcha")
                .header("User-Agent", UA)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            val json = JSONObject(body)
            if (json.optInt("code") == 0) json.optJSONObject("data") else null
        } catch (e: Exception) {
            Log.w(TAG, "获取验证码失败: ${e.message}")
            null
        }
    }

    /**
     * Cookie导入
     */
    fun loginWithCookieString(cookieString: String): Boolean {
        return try {
            val cookies = mutableMapOf<String, String>()
            cookieString.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2) cookies[kv[0].trim()] = kv[1].trim()
            }
            val sessdata = cookies["SESSDATA"] ?: ""
            if (sessdata.isEmpty()) return false

            saveLoginCookies(sessdata, cookies["bili_jct"] ?: "",
                cookies["DedeUserID"] ?: "", cookies["DedeUserID__ckMd5"] ?: "")

            CoroutineScope(Dispatchers.IO).launch { fetchUserInfo(sessdata) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cookie导入失败", e)
            false
        }
    }

    // ===== 二维码登录 =====

    data class QRCodeData(val url: String, val qrcodeKey: String)

    /**
     * 生成二维码
     * B站API: https://passport.bilibili.com/x/passport-login/web/qrcode/generate
     */
    suspend fun generateQRCode(): Result<QRCodeData> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                response.close()

                val json = JSONObject(body)
                val code = json.optInt("code", -1)
                Log.d(TAG, "生成二维码: code=$code")

                if (code == 0) {
                    val data = json.getJSONObject("data")
                    val url = data.getString("url")
                    val qrcodeKey = data.getString("qrcode_key")
                    Result.success(QRCodeData(url, qrcodeKey))
                } else {
                    Result.failure(Exception(json.optString("message", "生成二维码失败")))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "生成二维码异常", e)
                Result.failure(Exception("网络错误: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /**
     * 轮询二维码状态
     * B站API: https://passport.bilibili.com/x/passport-login/web/qrcode/poll
     *
     * 返回值:
     * - code=0, data.code=0: 登录成功，Cookie在Set-Cookie中
     * - code=0, data.code=86101: 未扫码
     * - code=0, data.code=86090: 已扫码，等待确认
     * - code=0, data.code=86038: 二维码已过期
     */
    suspend fun pollQRCodeStatus(qrcodeKey: String): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com")
                    .build()

                val response = client.newCall(request).execute()

                // 提取Set-Cookie（登录成功时会返回）
                val cookies = mutableMapOf<String, String>()
                response.headers("Set-Cookie").forEach { raw ->
                    val parts = raw.split(";")[0].split("=", limit = 2)
                    if (parts.size == 2) cookies[parts[0].trim()] = parts[1].trim()
                }

                val responseBody = response.body?.string() ?: ""
                response.close()

                val json = JSONObject(responseBody)
                val code = json.optInt("code", -1)
                val data = json.optJSONObject("data")
                val dataCode = data?.optInt("code", -1) ?: -1
                Log.d(TAG, "二维码轮询: code=$code dataCode=$dataCode cookies=${cookies.keys}")

                if (code == 0 && dataCode == 0) {
                    // 登录成功
                    val sessdata = cookies["SESSDATA"] ?: ""
                    if (sessdata.isNotEmpty()) {
                        saveLoginCookies(sessdata, cookies["bili_jct"] ?: "",
                            cookies["DedeUserID"] ?: "", cookies["DedeUserID__ckMd5"] ?: "")
                        fetchUserInfo(sessdata)
                        Result.success(0) // 登录成功
                    } else {
                        Result.failure(Exception("登录成功但未获取到Cookie"))
                    }
                } else if (dataCode == 86101) {
                    Result.success(1) // 未扫码
                } else if (dataCode == 86090) {
                    Result.success(2) // 已扫码，等待确认
                } else if (dataCode == 86038) {
                    Result.success(3) // 二维码已过期
                } else {
                    Result.failure(Exception("未知状态: $dataCode"))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "轮询二维码异常", e)
                Result.failure(Exception("网络错误: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    private suspend fun fetchUserInfo(sessdata: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/nav")
                    .header("User-Agent", UA)
                    .header("Cookie", "SESSDATA=$sessdata")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                response.close()

                val json = JSONObject(body)
                if (json.optInt("code") == 0) {
                    val data = json.getJSONObject("data")
                    val uname = data.optString("uname", "")
                    val uid = data.optLong("uid", 0)
                    Log.d(TAG, "用户: $uname (uid=$uid)")
                    prefs.edit().putString(KEY_USERNAME, uname).putLong(KEY_UID, uid).apply()
                } else {
                    Log.w(TAG, "获取用户信息: code=${json.optInt("code")}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取用户信息失败: ${e.message}")
            }
        }
    }

    private fun saveLoginCookies(sessdata: String, biliJct: String, dedeUserId: String, dedeUserIdMd5: String) {
        prefs.edit()
            .putString(KEY_SESSDATA, sessdata)
            .putString(KEY_BILI_JCT, biliJct)
            .putString(KEY_DEDE_USER_ID, dedeUserId)
            .putString(KEY_DEDE_USER_ID_MD5, dedeUserIdMd5)
            .apply()
    }

    fun getLoginCookieString(): String {
        val parts = mutableListOf<String>()
        prefs.getString(KEY_SESSDATA, "")?.takeIf { it.isNotEmpty() }?.let { parts.add("SESSDATA=$it") }
        prefs.getString(KEY_BILI_JCT, "")?.takeIf { it.isNotEmpty() }?.let { parts.add("bili_jct=$it") }
        prefs.getString(KEY_DEDE_USER_ID, "")?.takeIf { it.isNotEmpty() }?.let { parts.add("DedeUserID=$it") }
        prefs.getString(KEY_DEDE_USER_ID_MD5, "")?.takeIf { it.isNotEmpty() }?.let { parts.add("DedeUserID__ckMd5=$it") }
        return if (parts.isNotEmpty()) parts.joinToString("; ") else ""
    }

    fun getSessdata(): String = prefs.getString(KEY_SESSDATA, "") ?: ""
    fun isLoggedIn(): Boolean = (prefs.getString(KEY_SESSDATA, "") ?: "").isNotEmpty()

    fun logout() {
        prefs.edit().clear().apply()
    }
}
