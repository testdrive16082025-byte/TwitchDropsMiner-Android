package com.tdm.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tdm.android.network.TwitchAuthApi
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TwitchLoginScreen(
    onTokenCaptured: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit
) {
    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
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
                    title = { Text("Вход в Twitch") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF18181B),
                        titleContentColor = Color(0xFFEFEFF1),
                        navigationIconContentColor = Color(0xFFEFEFF1)
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
            }
        },
        containerColor = Color(0xFF0E0E10)
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")

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
                            return checkUrl(request?.url?.toString())
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            canGoBack = view?.canGoBack() == true
                            checkUrl(url)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean
                        ) {
                            canGoBack = view?.canGoBack() == true
                            checkUrl(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() == true
                            checkUrl(url)
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
