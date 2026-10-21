package com.bililive.danmaku.ui.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bililive.danmaku.data.remote.BiliBiliLoginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B站登录页面
 *
 * 方式1: 扫码登录（推荐）- 用B站App扫码授权
 * 方式2: 短信验证码登录
 * 方式3: Cookie导入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    loginManager: BiliBiliLoginManager,
    onLoginSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loginState by remember { mutableStateOf(loginManager.getLoginState()) }
    var showSmsLogin by remember { mutableStateOf(false) }
    var showCookieInput by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    DisposableEffect(loginManager) {
        onDispose { loginManager.cancelPendingLogin() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("B站登录") },
                navigationIcon = {
                    IconButton(onClick = {
                        loginManager.cancelPendingLogin()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (loginState.isLoggedIn) {
                        TextButton(onClick = {
                            loginManager.logout()
                            loginState = loginManager.getLoginState()
                            statusMessage = "已退出登录"
                            isError = false
                        }) {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ===== 登录状态 =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (loginState.isLoggedIn)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (loginState.isLoggedIn) Icons.Default.Person else Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = if (loginState.isLoggedIn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (loginState.isLoggedIn) loginState.username else "未登录",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    if (loginState.isLoggedIn && loginState.uid > 0) {
                        Text("UID: ${loginState.uid}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (!loginState.isLoggedIn) {
                // ===== 实验性功能提示 =====
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(EXPERIMENTAL_LOGIN_NOTICE,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ===== 方式1: 扫码登录（推荐） =====
                var showQRLogin by remember { mutableStateOf(false) }
                
                Button(
                    onClick = {
                        loginManager.cancelPendingLogin()
                        showQRLogin = !showQRLogin
                        showSmsLogin = false
                        showCookieInput = false
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("扫码登录（推荐）", fontSize = 16.sp)
                }
                Text(
                    "用B站App扫码授权，无需手动复制Cookie",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (showQRLogin) {
                    Spacer(modifier = Modifier.height(12.dp))
                    QRCodeLoginSection(
                        loginManager = loginManager,
                        onLoginSuccess = {
                            loginState = loginManager.getLoginState()
                            showQRLogin = false
                            onLoginSuccess()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                // ===== 方式2: 短信验证码 =====
                OutlinedButton(
                    onClick = {
                        loginManager.cancelPendingLogin()
                        showQRLogin = false
                        showSmsLogin = !showSmsLogin
                        showCookieInput = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sms, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("短信验证码登录")
                }

                if (showSmsLogin) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SmsLoginSection(
                        loginManager = loginManager,
                        onLoginSuccess = {
                            loginState = loginManager.getLoginState()
                            showSmsLogin = false
                            onLoginSuccess()
                        },
                        onError = { msg -> statusMessage = msg; isError = true }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                // ===== 方式3: Cookie导入 =====
                OutlinedButton(
                    onClick = {
                        loginManager.cancelPendingLogin()
                        showQRLogin = false
                        showCookieInput = !showCookieInput
                        showSmsLogin = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导入Cookie")
                }

                if (showCookieInput) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CookieImportSection(
                        onImport = { cookieStr ->
                            try {
                                if (loginManager.loginWithCookieString(cookieStr)) {
                                    loginState = loginManager.getLoginState()
                                    statusMessage = "✅ Cookie导入成功"
                                    isError = false
                                } else {
                                    statusMessage = "❌ Cookie中缺少SESSDATA"
                                    isError = true
                                }
                            } catch (e: Exception) {
                                statusMessage = "❌ 错误: ${e.message}"
                                isError = true
                            }
                        }
                    )
                }
            }

            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(statusMessage, fontSize = 14.sp,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * 短信验证码登录
 */
@Composable
fun SmsLoginSection(
    loginManager: BiliBiliLoginManager,
    onLoginSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    LaunchedEffect(countdown) {
        if (countdown > 0) { delay(1000); countdown-- }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("短信验证码登录", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                label = { Text("手机号") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSending && !isLoggingIn
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (phone.length != 11) { onError("请输入11位手机号"); return@Button }
                    isSending = true
                    scope.launch {
                        try {
                            val result = loginManager.sendSmsCode(phone)
                            isSending = false
                            result.fold(
                                onSuccess = { codeSent = true; countdown = 60 },
                                onFailure = { onError("发送失败: ${it.message}") }
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            isSending = false
                            onError("异常: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = phone.length == 11 && !isSending && countdown == 0
            ) {
                if (isSending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp)); Text("发送中...")
                } else if (countdown > 0) {
                    Text("重新发送 (${countdown}s)")
                } else {
                    Text("获取验证码")
                }
            }

            if (codeSent) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = smsCode,
                    onValueChange = { smsCode = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("验证码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoggingIn
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (smsCode.length < 4) { onError("请输入验证码"); return@Button }
                        isLoggingIn = true
                        scope.launch {
                            try {
                                val result = loginManager.loginWithSms(phone, smsCode)
                                isLoggingIn = false
                                result.fold(
                                    onSuccess = { onLoginSuccess() },
                                    onFailure = { onError("登录失败: ${it.message}") }
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                isLoggingIn = false
                                onError("异常: ${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = smsCode.length >= 4 && !isLoggingIn
                ) {
                    if (isLoggingIn) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp)); Text("登录中...")
                    } else {
                        Text("登录")
                    }
                }
            }
        }
    }
}

/**
 * Cookie导入
 */
@Composable
fun CookieImportSection(onImport: (String) -> Unit) {
    var cookieText by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("从浏览器导入Cookie", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "1. 电脑浏览器登录 bilibili.com\n2. F12 → Application → Cookies\n3. 复制所有Cookie内容粘贴到下面",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = cookieText,
                onValueChange = { cookieText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("SESSDATA=xxx; bili_jct=xxx; ...") },
                minLines = 3, maxLines = 5
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { if (cookieText.isNotBlank()) onImport(cookieText) },
                modifier = Modifier.fillMaxWidth(),
                enabled = cookieText.isNotBlank()
            ) { Text("导入") }
        }
    }
}

/**
 * 扫码登录
 * 使用B站二维码API，用户用B站App扫码授权
 */
@Composable
fun QRCodeLoginSection(
    loginManager: BiliBiliLoginManager,
    onLoginSuccess: () -> Unit
) {
    var qrCodeBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var qrcodeKey by remember { mutableStateOf("") }
    var qrStatus by remember { mutableStateOf(QrLoginStatus.LOADING) }

    DisposableEffect(loginManager) {
        onDispose { loginManager.cancelQrLogin() }
    }

    // 仅向B站申请登录链接；二维码图片在本地生成，不上传链接或缓存到磁盘。
    LaunchedEffect(loginManager, generation) {
        val currentGeneration = generation
        qrcodeKey = ""
        qrCodeBitmap = null
        qrStatus = QrLoginStatus.LOADING
        try {
            val data = loginManager.generateQRCode().getOrThrow()
            val bitmap = withContext(Dispatchers.Default) {
                val qrCode = LoginQrCodeEncoder.encode(data.url)
                Bitmap.createBitmap(
                    qrCode.pixels, qrCode.width, qrCode.height, Bitmap.Config.ARGB_8888
                ).asImageBitmap()
            }
            if (generation != currentGeneration) return@LaunchedEffect
            qrCodeBitmap = bitmap
            qrcodeKey = data.qrcodeKey
            qrStatus = QrLoginStatus.READY
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (generation != currentGeneration) return@LaunchedEffect
            loginManager.cancelQrLogin()
            qrStatus = QrLoginStatus.GENERATION_FAILED
        }
    }

    // 轮询二维码状态
    LaunchedEffect(loginManager, generation, qrcodeKey) {
        val currentGeneration = generation
        val currentKey = qrcodeKey
        if (currentKey.isEmpty() || !qrStatus.canPoll) return@LaunchedEffect
        pollQrLogin(
            request = { loginManager.pollQRCodeStatus(currentKey) },
            isCurrent = {
                generation == currentGeneration && qrcodeKey == currentKey && qrStatus.canPoll
            }
        ) { status ->
            qrStatus = status
            if (status == QrLoginStatus.SUCCESS) {
                onLoginSuccess()
            } else if (status.canRefresh) {
                // 失败/过期后停止轮询并失效化旧挑战；只由用户刷新启动新的尝试。
                loginManager.cancelQrLogin()
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("扫码登录", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "1. 打开B站App\n2. 点击右上角扫一扫\n3. 扫描下方二维码\n4. 在App中确认登录",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 二维码显示区域
            if (qrStatus == QrLoginStatus.LOADING) {
                CircularProgressIndicator(modifier = Modifier.size(120.dp))
            } else if (qrStatus.isError) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            } else if (qrStatus == QrLoginStatus.EXPIRED) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (qrCodeBitmap != null) {
                Image(
                    bitmap = qrCodeBitmap!!,
                    contentDescription = "登录二维码",
                    modifier = Modifier.size(200.dp),
                    filterQuality = FilterQuality.None
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            // 状态提示
            Text(
                text = qrStatus.message,
                fontSize = 14.sp,
                color = when {
                    qrStatus == QrLoginStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                    qrStatus == QrLoginStatus.SCANNED -> MaterialTheme.colorScheme.tertiary
                    qrStatus.canRefresh -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )

            // 生成失败、轮询失败、未知状态和过期都提供可见的重试入口。
            if (qrStatus.canRefresh) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!qrStatus.canRefresh) return@Button
                        // 先同步失效化旧挑战并取消请求，再等待重组启动新的生成任务。
                        loginManager.cancelQrLogin()
                        qrcodeKey = ""
                        qrCodeBitmap = null
                        qrStatus = QrLoginStatus.LOADING
                        generation++
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("刷新二维码")
                }
            }
        }
    }
}
