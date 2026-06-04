package com.bililive.danmaku

import android.app.Application
import com.bililive.danmaku.data.local.SettingsDataStore
import com.bililive.danmaku.data.remote.BiliBiliApi
import com.bililive.danmaku.data.remote.BiliBiliCookieJar
import com.bililive.danmaku.data.remote.BiliBiliLoginManager
import com.bililive.danmaku.data.remote.DanmakuWebSocketClient
import com.bililive.danmaku.data.remote.MockDanmakuDataSource
import com.bililive.danmaku.data.repository.DanmakuRepositoryImpl
import com.bililive.danmaku.domain.repository.DanmakuRepository
import com.bililive.danmaku.domain.repository.SettingsRepository
import com.bililive.danmaku.domain.usecase.ConnectRoomUseCase
import com.bililive.danmaku.domain.usecase.DisconnectUseCase
import com.bililive.danmaku.domain.usecase.ValidateRoomIdUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BiliLiveApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var danmakuRepository: DanmakuRepository
        private set
    lateinit var connectRoomUseCase: ConnectRoomUseCase
        private set
    lateinit var disconnectUseCase: DisconnectUseCase
        private set
    lateinit var validateRoomIdUseCase: ValidateRoomIdUseCase
        private set
    lateinit var loginManager: BiliBiliLoginManager
        private set

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsDataStore(applicationContext)

        // 创建登录管理器
        loginManager = BiliBiliLoginManager(applicationContext)

        // 创建 Cookie 管理器（获取 buvid 等浏览器指纹 + 登录Cookie）
        val cookieJar = BiliBiliCookieJar(applicationContext, loginManager)

        // 创建 B站 API 客户端（带 Cookie）
        val bilibiliApi = BiliBiliApi(cookieJar)

        // 创建 WebSocket 客户端（带 Cookie + Token + 登录信息）
        val webSocketClient = DanmakuWebSocketClient(bilibiliApi, cookieJar, loginManager)
        val mockDataSource = MockDanmakuDataSource()

        danmakuRepository = DanmakuRepositoryImpl(
            webSocketClient = webSocketClient,
            mockDataSource = mockDataSource,
            settingsRepository = settingsRepository,
            scope = appScope
        )

        connectRoomUseCase = ConnectRoomUseCase(danmakuRepository, settingsRepository)
        disconnectUseCase = DisconnectUseCase(danmakuRepository)
        validateRoomIdUseCase = ValidateRoomIdUseCase()
    }
}
