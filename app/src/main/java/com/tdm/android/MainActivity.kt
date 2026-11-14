package com.tdm.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tdm.android.data.AuthStorage
import com.tdm.android.data.DropCampaignUi
import com.tdm.android.data.TwitchSession
import com.tdm.android.miner.MinerForegroundService
import com.tdm.android.miner.MinerRepository
import com.tdm.android.network.TwitchAuthApi
import com.tdm.android.ui.TwitchLoginScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MinerRepository.initPreferences(applicationContext)
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

    when (val state = uiState) {
        AuthUiState.Checking, AuthUiState.Validating -> {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color(0xFF9146FF))
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (state == AuthUiState.Checking) "Проверка сессии..." else "Проверка токена...",
                        color = Color(0xFFEFEFF1)
                    )
                }
            }
        }

        AuthUiState.LoggedOut -> {
            LoginWelcomeView(
                errorMessage = errorMessage,
                onLoginClick = {
                    errorMessage = null
                    uiState = AuthUiState.LoggingInWebView
                },
                onManualTokenClick = { showManualDialog = true }
            )
        }

        is AuthUiState.LoggedIn -> {
            MinerDashboard(
                session = state.session,
                onLogout = {
                    context.startService(
                        Intent(context, MinerForegroundService::class.java).apply {
                            action = MinerForegroundService.ACTION_STOP
                        }
                    )
                    authStorage.clearSession()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    errorMessage = null
                    uiState = AuthUiState.LoggedOut
                }
            )
        }

        AuthUiState.LoggingInWebView -> Unit
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
                        text = "Вставьте токен доступа (без слова OAuth):",
                        fontSize = 13.sp,
                        color = Color(0xFFADADB8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it },
                        singleLine = true,
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
                TextButton(onClick = { showManualDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun LoginWelcomeView(
    errorMessage: String?,
    onLoginClick: () -> Unit,
    onManualTokenClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
            Spacer(modifier = Modifier.height(20.dp))

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A161D)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFF8A9B),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

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
                    Text("Вход не выполнен", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = onLoginClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9146FF))
                    ) {
                        Text("Войти через Twitch", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onManualTokenClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ввести токен вручную")
                    }
                }
            }
        }
    }
}

@Composable
private fun MinerDashboard(
    session: TwitchSession,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val minerState by MinerRepository.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val isBatteryOptimized = remember(minerState.isRunning) {
        val pm = context.getSystemService(PowerManager::class.java)
        pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    LaunchedEffect(session.userId) {
        if (minerState.campaigns.isEmpty() && !minerState.isRunning) {
            MinerRepository.refreshCampaignsOnce(context, session)
        }
    }

    fun startMinerService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val intent = Intent(context, MinerForegroundService::class.java).apply {
            action = MinerForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopMinerService() {
        val intent = Intent(context, MinerForegroundService::class.java).apply {
            action = MinerForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }

    Scaffold(
        containerColor = Color(0xFF0E0E10),
        topBar = {
            Surface(color = Color(0xFF18181B)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Twitch Drops Miner",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBF94FF)
                            )
                            Text(
                                text = "Аккаунт: ${session.login}",
                                fontSize = 12.sp,
                                color = Color(0xFFADADB8)
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { MinerRepository.refreshCampaignsOnce(context, session) },
                                enabled = !minerState.isRefreshing
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Обновить",
                                    tint = Color(0xFFEFEFF1)
                                )
                            }
                            IconButton(onClick = onLogout) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = "Выйти",
                                    tint = Color(0xFFFF8A9B)
                                )
                            }
                        }
                    }

                    // Карточка управления и статуса майнера
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF202026)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = minerState.statusText,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (minerState.isRunning) Color(0xFF00E676) else Color(0xFFEFEFF1),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!minerState.activeGameName.isNullOrBlank()) {
                                        Text(
                                            text = minerState.activeGameName.orEmpty(),
                                            fontSize = 12.sp,
                                            color = Color(0xFFBF94FF),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (minerState.isRunning) stopMinerService() else startMinerService()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (minerState.isRunning) Color(0xFFD32F2F) else Color(0xFF9146FF)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (minerState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (minerState.isRunning) "Стоп" else "Старт")
                                }
                            }

                            val drop = minerState.activeDrop
                            if (drop != null && drop.requiredMinutes > 0) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = drop.benefitName,
                                        fontSize = 12.sp,
                                        color = Color(0xFFEFEFF1),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${drop.currentMinutes} / ${drop.requiredMinutes} мин",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFFBF94FF)
                                    )
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                LinearProgressIndicator(
                                    progress = { drop.progressFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    color = Color(0xFF9146FF),
                                    trackColor = Color(0xFF323239)
                                )
                            }

                            if (isBatteryOptimized) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        runCatching { context.startActivity(intent) }
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BatteryAlert,
                                        contentDescription = null,
                                        tint = Color(0xFFFFCA28),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Отключить контроль батареи для работы в фоне",
                                        fontSize = 12.sp,
                                        color = Color(0xFFFFCA28)
                                    )
                                }
                            }
                        }
                    }

                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF18181B),
                        contentColor = Color(0xFFBF94FF)
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Кампании (${minerState.campaigns.size})", fontSize = 13.sp) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Приоритеты (${minerState.priorityGames.size})", fontSize = 13.sp) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Логи", fontSize = 13.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> CampaignsTab(
                    campaigns = minerState.campaigns,
                    priorityGames = minerState.priorityGames,
                    isRefreshing = minerState.isRefreshing,
                    onTogglePriority = { MinerRepository.togglePriorityGame(it) }
                )

                1 -> PrioritiesTab(
                    priorityGames = minerState.priorityGames,
                    priorityOnly = minerState.priorityOnly,
                    onMove = { idx, delta -> MinerRepository.movePriorityGame(idx, delta) },
                    onRemove = { MinerRepository.togglePriorityGame(it) },
                    onTogglePriorityOnly = { MinerRepository.setPriorityOnly(it) }
                )

                2 -> LogsTab(logs = minerState.logs)
            }
        }
    }
}

@Composable
private fun CampaignsTab(
    campaigns: List<DropCampaignUi>,
    priorityGames: List<String>,
    isRefreshing: Boolean,
    onTogglePriority: (String) -> Unit
) {
    if (isRefreshing && campaigns.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF9146FF))
        }
        return
    }
    if (campaigns.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Кампании не загружены", color = Color(0xFFADADB8))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(campaigns, key = { it.id }) { camp ->
            val isPriority = priorityGames.any { it.equals(camp.gameName, ignoreCase = true) }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = camp.gameName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBF94FF)
                            )
                            Text(
                                text = camp.name,
                                fontSize = 13.sp,
                                color = Color(0xFFEFEFF1),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onTogglePriority(camp.gameName) }) {
                            Icon(
                                imageVector = if (isPriority) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Приоритет",
                                tint = if (isPriority) Color(0xFFFFCA28) else Color(0xFFADADB8)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val badgeColor = if (camp.isActive) Color(0xFF00E676) else Color(0xFFADADB8)
                        Text(
                            text = if (camp.isActive) "АКТИВНА" else "СКОРО",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                        if (!camp.isAccountConnected) {
                            Text("• Не привязан аккаунт", fontSize = 11.sp, color = Color(0xFFFFCA28))
                        }
                        if (camp.aclEnabled) {
                            Text("• ACL каналов: ${camp.allowedChannels.size}", fontSize = 11.sp, color = Color(0xFFADADB8))
                        }
                    }

                    if (camp.drops.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        camp.drops.forEach { drop ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = drop.benefitName,
                                    fontSize = 12.sp,
                                    color = if (drop.isClaimed) Color(0xFF00E676) else Color(0xFFADADB8),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (drop.isClaimed) {
                                        "Получен"
                                    } else {
                                        "${drop.currentMinutes}/${drop.requiredMinutes}м"
                                    },
                                    fontSize = 12.sp,
                                    color = if (drop.isClaimed) Color(0xFF00E676) else Color(0xFFEFEFF1)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrioritiesTab(
    priorityGames: List<String>,
    priorityOnly: Boolean,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onTogglePriorityOnly: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Только приоритетные игры", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Если выключено, после списка приоритетов майнер возьмёт другие активные кампании.",
                        fontSize = 12.sp,
                        color = Color(0xFFADADB8)
                    )
                }
                Switch(
                    checked = priorityOnly,
                    onCheckedChange = onTogglePriorityOnly
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (priorityGames.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Нажмите на звёздочку рядом с игрой во вкладке «Кампании», чтобы добавить её в приоритет.",
                    color = Color(0xFFADADB8),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(priorityGames) { index, game ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}. $game",
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                color = Color(0xFFEFEFF1)
                            )
                            IconButton(
                                onClick = { onMove(index, -1) },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Выше")
                            }
                            IconButton(
                                onClick = { onMove(index, 1) },
                                enabled = index < priorityGames.lastIndex
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Ниже")
                            }
                            IconButton(onClick = { onRemove(game) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Color(0xFFFF8A9B))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsTab(logs: List<String>) {
    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Логи пока пустые", color = Color(0xFFADADB8))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(logs.asReversed()) { line ->
            Text(
                text = line,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFD4D4D8)
            )
        }
    }
}
