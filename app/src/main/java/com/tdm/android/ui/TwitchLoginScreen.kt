package com.tdm.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tdm.android.network.TwitchAuthApi
import java.util.concurrent.atomic.AtomicBoolean

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TwitchLoginScreen(
    onTokenCaptured: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit
) {
    var loadProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(TwitchAuthApi.OAUTH_AUTHORIZE_URL) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var desktopMode by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var defaultMobileUa by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    val handled = remember { AtomicBoolean(false) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && canGoBack && wv.canGoBack()) {
            wv.goBack()
        } else {
            onCancel()
        }
    }

    fun checkUrl(url: String?): Boolean {
        val parsed = parseAuthUrl(url) ?: return false
        when (parsed) {
            is AuthRedirectResult.Token -> {
                if (handled.compareAndSet(false, true)) {
                    onTokenCaptured(parsed.value)
                }
                return true
            }
            is AuthRedirectResult.Error -> {
                if (handled.compareAndSet(false, true)) {
                    onError(parsed.message)
                }
                return true
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Вход в Twitch", fontSize = 17.sp)
                            Text(
                                text = currentUrl,
                                fontSize = 11.sp,
                                color = Color(0xFFADADB8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val wv = webViewRef ?: return@IconButton
                                desktopMode = !desktopMode
                                wv.settings.userAgentString =
                                    if (desktopMode) DESKTOP_USER_AGENT else defaultMobileUa
                                pageError = null
                                wv.loadUrl(TwitchAuthApi.OAUTH_AUTHORIZE_URL)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = "ПК-версия",
                                tint = if (desktopMode) Color(0xFFBF94FF) else Color(0xFFEFEFF1)
                            )
                        }
                        IconButton(
                            onClick = {
                                pageError = null
                                webViewRef?.reload()
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF18181B),
                        titleContentColor = Color(0xFFEFEFF1),
                        navigationIconContentColor = Color(0xFFEFEFF1),
                        actionIconContentColor = Color(0xFFEFEFF1)
                    )
                )
                if (loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color(0xFF9146FF)
                    )
                }
                if (pageError != null) {
                    Surface(color = Color(0xFF3A161D), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = pageError.orEmpty(),
                            color = Color(0xFFFF8A9B),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF0E0E10)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#0E0E10"))

                        defaultMobileUa = settings.userAgentString.replace("; wv", "")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString = defaultMobileUa

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString()
                                if (checkUrl(url)) return true
                                if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (!url.isNullOrBlank()) currentUrl = url
                                canGoBack = view?.canGoBack() == true
                                pageError = null
                                checkUrl(url)
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView?,
                                url: String?,
                                isReload: Boolean
                            ) {
                                if (!url.isNullOrBlank()) currentUrl = url
                                canGoBack = view?.canGoBack() == true
                                checkUrl(url)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) currentUrl = url
                                canGoBack = view?.canGoBack() == true
                                checkUrl(url)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    pageError = "Ошибка загрузки: ${error?.description ?: "неизвестно"}"
                                }
                            }
                        }

                        webViewRef = this
                        loadUrl(TwitchAuthApi.OAUTH_AUTHORIZE_URL)
                    }
                },
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.destroy()
                }
            )
        }
    }
}

private sealed interface AuthRedirectResult {
    data class Token(val value: String) : AuthRedirectResult
    data class Error(val message: String) : AuthRedirectResult
}

private fun parseAuthUrl(url: String?): AuthRedirectResult? {
    if (url.isNullOrBlank()) return null
    val uri = Uri.parse(url)
    val host = uri.host ?: return null
    if (!host.endsWith("twitch.tv")) return null

    val fragment = uri.fragment
    if (!fragment.isNullOrBlank()) {
        val params = fragment.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) part.substring(0, idx) to Uri.decode(part.substring(idx + 1))
            else null
        }.toMap()

        val token = params["access_token"]
        if (!token.isNullOrBlank()) {
            return AuthRedirectResult.Token(token)
        }
        val error = params["error_description"] ?: params["error"]
        if (!error.isNullOrBlank()) {
            return AuthRedirectResult.Error("Авторизация отклонена: $error")
        }
    }

    val queryToken = uri.getQueryParameter("access_token")
    if (!queryToken.isNullOrBlank()) {
        return AuthRedirectResult.Token(queryToken)
    }

    return null
}
