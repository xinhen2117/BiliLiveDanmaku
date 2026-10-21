package com.bililive.danmaku.data.repository

import com.bililive.danmaku.data.remote.*
import com.bililive.danmaku.domain.model.AppSettings
import com.bililive.danmaku.domain.model.ConnectionStatus
import com.bililive.danmaku.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuRepositoryImplTest {
    // Interface proxy keeps unrelated settings API additions out of a connection wiring test.
    private fun settings(state: MutableStateFlow<AppSettings>): SettingsRepository = Proxy.newProxyInstance(
        SettingsRepository::class.java.classLoader, arrayOf(SettingsRepository::class.java)
    ) { _, method, _ ->
        check(method.name == "observeSettings")
        state
    } as SettingsRepository

    @Test
    fun `stored false autoReconnect reaches transport and stops discovery retries`() = runTest {
        var attempts = 0
        val source = object : DanmakuConnectionSource {
            override suspend fun resolve(roomId: Long): DanmakuConnection { attempts++; throw IOException("test") }
        }
        val client = DanmakuWebSocketClient(source, dispatcher = StandardTestDispatcher(testScheduler))
        val state = MutableStateFlow(AppSettings(mockMode = false, autoReconnect = false))
        val repository = DanmakuRepositoryImpl(client, MockDanmakuDataSource(), settings(state), backgroundScope)
        repository.connect(1); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(1, attempts)
        assertEquals(ConnectionStatus.Failed, repository.observeStatus().first())
        repository.disconnect(); client.destroy()
    }

    @Test
    fun `stored true autoReconnect retries but disconnect stops it`() = runTest {
        var attempts = 0
        val source = object : DanmakuConnectionSource {
            override suspend fun resolve(roomId: Long): DanmakuConnection { attempts++; throw IOException("test") }
        }
        val client = DanmakuWebSocketClient(source, dispatcher = StandardTestDispatcher(testScheduler))
        val state = MutableStateFlow(AppSettings(mockMode = false, autoReconnect = true))
        val repository = DanmakuRepositoryImpl(client, MockDanmakuDataSource(), settings(state), backgroundScope)
        repository.connect(1); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(2, attempts)
        assertEquals(ConnectionStatus.Reconnecting, repository.observeStatus().first())
        repository.disconnect(); advanceTimeBy(30_000); runCurrent()
        assertEquals(2, attempts)
        assertEquals(ConnectionStatus.Disconnected, repository.observeStatus().first())
        client.destroy()
    }
}
