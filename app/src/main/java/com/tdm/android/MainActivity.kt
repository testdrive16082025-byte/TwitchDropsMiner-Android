package com.tdm.android

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tdm.android.data.AuthStorage
import com.tdm.android.data.TwitchSession
import com.tdm.android.network.TwitchAuthApi
import com.tdm.android.ui.TwitchLoginScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TwitchMinerTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun TwitchMinerTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF9146FF),
        secondary = Color(0xFFBF94FF),
        background = Color(0xFF0E0E10),
        surface = Color(0xFF18181B),
        onPrimary = Color.White,
        onBackground = Color(0xFFEFEFF1),
        onSurface = Color(0xFFEFEFF1)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private sealed interface AuthUiState {
    data object Checking : AuthUiState
    data object LoggedOut : AuthUiState
    data object LoggingInWebView : AuthUiState
    data object Validating : AuthUiState
    data class LoggedIn(val session: TwitchSession) : AuthUiState
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val authStorage = remember { AuthStorage(context) }
    val scope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf<AuthUiState>(AuthUiState.Checking) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }

    fun verifyAndSaveToken(token: String) {
        scope.launch {
            uiState = AuthUiState.Validating
            errorMessage = null
            TwitchAuthApi.validateToken(token)
                .onSuccess { session ->
                    authStorage.saveSession(session)
                    authStorage.getOrCreateDeviceId()
                    uiState = AuthUiState.LoggedIn(session)
                }
                .onFailure { err ->
                    authStorage.clearSession()
                    errorMessage = err.message ?: "Не удалось проверить токен"
                    uiState = AuthUiState.LoggedOut
                }
        }
    }

    LaunchedEffect(Unit) {
        val savedToken = authStorage.getAccessToken()
        if (savedToken.isNullOrBlank()) {
            uiState = AuthUiState.LoggedOut
        } else {
            TwitchAuthApi.validateToken(savedToken)
                .onSuccess { session ->
                    authStorage.saveSession(session)
                    uiState = AuthUiState.LoggedIn(session)
                }
                .onFailure {
                    authStorage.clearSession()
                    errorMessage = "Сохранённая сессия истекла, войдите снова."
                    uiState = AuthUiState.LoggedOut
                }
        }
    }

    if (uiState == AuthUiState.LoggingInWebView) {
        TwitchLoginScreen(
            onTokenCaptured = { token -> verifyAndSaveToken(token) },
            onError = { msg ->
                errorMessage = msg
                uiState = AuthUiState.LoggedOut
            },
            onCancel = { uiState = AuthUiState.LoggedOut }
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Twitch Drops Miner",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFBF94FF)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Этап 2: Авторизация",
                fontSize = 14.sp,
                color = Color(0xFFADADB8)
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A161D)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = Color(0xFFFF8A9B),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            when (val state = uiState) {
                AuthUiState.Checking, AuthUiState.Validating -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color(0xFF9146FF))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (state == AuthUiState.Checking) {
                                    "Проверка сохранённой сессии..."
                                } else {
                                    "Проверка токена Twitch..."
                                },
                                color = Color(0xFFEFEFF1)
                            )
                        }
                    }
                }

                AuthUiState.LoggedOut -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color(0xFF9146FF),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Вход не выполнен",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Войдите в аккаунт Twitch (поддерживаются логин, пароль и 2FA). Токен перехватится автоматически.",
                                fontSize = 13.sp,
                                color = Color(0xFFADADB8)
                            )
                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    errorMessage = null
                                    uiState = AuthUiState.LoggingInWebView
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF9146FF)
                                )
                            ) {
                                Text("Войти через Twitch", fontWeight = FontWeight.SemiBold)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = { showManualDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ввести токен вручную")
                            }
                        }
                    }
                }

                is AuthUiState.LoggedIn -> {
                    val session = state.session
                    val daysLeft = session.expiresInSeconds / 86400
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Авторизация успешна",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            InfoRow("Аккаунт", session.login)
                            InfoRow("User ID", session.userId)
                            InfoRow("Срок токена", if (daysLeft > 0) "~$daysLeft дн." else "Активен")

                            if (session.clientId != TwitchAuthApi.ANDROID_APP_CLIENT_ID) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Внимание: токен не от ANDROID_APP клиента. Для стабильного майнинга лучше входить через кнопку в приложении.",
                                    fontSize = 12.sp,
                                    color = Color(0xFFFFCA28)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                            OutlinedButton(
                                onClick = {
                                    authStorage.clearSession()
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                    errorMessage = null
                                    uiState = AuthUiState.LoggedOut
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Выйти из аккаунта")
                            }
                        }
                    }
                }

                AuthUiState.LoggingInWebView -> Unit
            }
        }
    }

    if (showManualDialog) {
        var manualToken by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            containerColor = Color(0xFF18181B),
            title = { Text("Ручной ввод auth-token") },
            text = {
                Column {
                    Text(
                        text = "Вставьте токен доступа (30 символов, без слова OAuth):",
                        fontSize = 13.sp,
                        color = Color(0xFFADADB8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it },
                        singleLine = true,
                        placeholder = { Text("xxxxxxxxxxxx...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showManualDialog = false
                        verifyAndSaveToken(manualToken)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9146FF))
                ) {
                    Text("Проверить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFFADADB8), fontSize = 14.sp)
        Text(text = value, color = Color(0xFFEFEFF1), fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}
