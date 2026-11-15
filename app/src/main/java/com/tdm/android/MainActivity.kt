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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.tdm.android.data.AuthStorage
import com.tdm.android.data.DropCampaignUi
import com.tdm.android.data.LiveStreamCandidate
import com.tdm.android.data.MinerUiState
import com.tdm.android.data.TimedDropUi
import com.tdm.android.data.TwitchSession
import com.tdm.android.miner.MinerForegroundService
import com.tdm.android.miner.MinerRepository
import com.tdm.android.network.TwitchAuthApi
import com.tdm.android.ui.TwitchLoginScreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private enum class CampaignFilter(val label: String) {
    ALL("Все"),
    ACTIVE("Активные"),
    UNCLAIMED("Неполученные"),
    CONNECTED("Привязанные"),
    UPCOMING("Скоро")
}

private fun formatMinutesAsClock(totalMinutes: Int): String {
    val safe = totalMinutes.coerceAtLeast(0)
    val h = safe / 60
    val m = safe % 60
    return String.format(Locale.US, "%d:%02d:00", h, m)
}

private fun formatDate(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))
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
    var showSettingsDialog by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val isBatteryOptimized = remember(minerState.isRunning, showSettingsDialog) {
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
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Twitch Drops Miner",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBF94FF)
                            )
                            Text(
                                text = "${session.login} • Забрано: ${minerState.claimedSessionCount} (всего: ${minerState.totalClaimedAllTime})",
                                fontSize = 11.sp,
                                color = Color(0xFFADADB8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { MinerRepository.refreshCampaignsOnce(context, session) },
                                enabled = !minerState.isRefreshing
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = Color(0xFFEFEFF1))
                            }
                            IconButton(onClick = { showSettingsDialog = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color(0xFFEFEFF1))
                            }
                            IconButton(onClick = onLogout) {
                                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Выйти", tint = Color(0xFFFF8A9B))
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
                            text = { Text("Главная", fontSize = 12.sp, maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Дропсы", fontSize = 12.sp, maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Каналы (${minerState.channels.size})", fontSize = 12.sp, maxLines = 1) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("Приоритет", fontSize = 12.sp, maxLines = 1) }
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
                0 -> MainPcStyleTab(
                    minerState = minerState,
                    isBatteryOptimized = isBatteryOptimized,
                    onStart = ::startMinerService,
                    onStop = ::stopMinerService,
                    onDisableBatteryOpt = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(intent) }
                    }
                )

                1 -> InventoryTab(
                    campaigns = minerState.campaigns,
                    priorityGames = minerState.priorityGames,
                    excludedGames = minerState.excludedGames,
                    isRefreshing = minerState.isRefreshing,
                    onTogglePriority = { MinerRepository.togglePriorityGame(it) },
                    onToggleExclude = { MinerRepository.toggleExcludedGame(it) },
                    onManualClaim = { instanceId, benefitName, gameName ->
                        MinerRepository.manualClaimDrop(context, session, instanceId, benefitName, gameName)
                    },
                    onOpenLink = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                )

                2 -> ChannelsTab(
                    minerState = minerState,
                    onRefreshChannels = { MinerRepository.refreshActiveChannels(context, session) },
                    onSwitchChannel = { login -> MinerRepository.switchToChannel(login) }
                )

                3 -> PrioritiesTab(
                    priorityGames = minerState.priorityGames,
                    excludedGames = minerState.excludedGames,
                    onAddPriority = { MinerRepository.addPriorityGame(it) },
                    onMove = { idx, delta -> MinerRepository.movePriorityGame(idx, delta) },
                    onRemovePriority = { MinerRepository.togglePriorityGame(it) },
                    onRemoveExcluded = { MinerRepository.toggleExcludedGame(it) }
                )
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            minerState = minerState,
            isBatteryOptimized = isBatteryOptimized,
            onDismiss = { showSettingsDialog = false },
            onTogglePriorityOnly = { MinerRepository.setPriorityOnly(it) },
            onToggleSkipUnconnected = { MinerRepository.setSkipUnconnected(it) },
            onSyncHashes = { MinerRepository.forceSyncGqlHashes() },
            onDisableBatteryOpt = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                runCatching { context.startActivity(intent) }
            }
        )
    }
}

@Composable
private fun MainPcStyleTab(
    minerState: MinerUiState,
    isBatteryOptimized: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDisableBatteryOpt: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Статус + кнопка Старт/Стоп
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2B2B33)),
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
                            text = minerState.statusText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (minerState.isRunning) Color(0xFF00E676) else Color(0xFFEFEFF1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!minerState.activeGameName.isNullOrBlank()) {
                            val viewersText = if (minerState.activeChannelViewers > 0) {
                                " • ${minerState.activeChannelViewers} зрит."
                            } else ""
                            Text(
                                text = "${minerState.activeGameName}$viewersText",
                                fontSize = 12.sp,
                                color = Color(0xFFBF94FF)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { if (minerState.isRunning) onStop() else onStart() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (minerState.isRunning) Color(0xFFD32F2F) else Color(0xFF9146FF)
                        ),
                        shape = RoundedCornerShape(8.dp)
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

                if (isBatteryOptimized) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onDisableBatteryOpt,
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
                            text = "Отключить контроль батареи для фона",
                            fontSize = 12.sp,
                            color = Color(0xFFFFCA28)
                        )
                    }
                }
            }
        }

        // Блок Campaign Progress + Drop Progress (как на ПК)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2B2B33)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Прогресс кампании",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFADADB8)
                )

                val camp = minerState.activeCampaign
                if (camp != null) {
                    val campPct = camp.campaignProgressFraction * 100f
                    Text(
                        text = "${camp.gameName} — ${camp.name}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEFEFF1),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.1f%% (%d/%d)",
                                campPct,
                                camp.claimedDropsCount,
                                camp.totalDropsCount
                            ),
                            fontSize = 12.sp,
                            color = Color(0xFF00E676)
                        )
                        Text(
                            text = "осталось ${formatMinutesAsClock(camp.campaignRemainingMinutes)}",
                            fontSize = 12.sp,
                            color = Color(0xFFADADB8)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { camp.campaignProgressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF00C853),
                        trackColor = Color(0xFF2C2C35)
                    )
                } else {
                    Text(
                        text = "Нет активной кампании",
                        fontSize = 13.sp,
                        color = Color(0xFF71717A)
                    )
                }

                HorizontalDivider(color = Color(0xFF27272E))

                val drop = minerState.activeDrop
                if (drop != null && drop.requiredMinutes > 0) {
                    Text(
                        text = "Дроп: ${drop.benefitName}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFBF94FF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.1f%% (%d/%d мин)",
                                drop.progressPercent,
                                drop.watchedClamped,
                                drop.requiredMinutes
                            ),
                            fontSize = 12.sp,
                            color = Color(0xFF00E676)
                        )
                        Text(
                            text = "осталось ${formatMinutesAsClock(drop.remainingMinutes)}",
                            fontSize = 12.sp,
                            color = Color(0xFFADADB8)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { drop.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF00C853),
                        trackColor = Color(0xFF2C2C35)
                    )
                } else {
                    Text(
                        text = "Ожидание выбора дропа...",
                        fontSize = 12.sp,
                        color = Color(0xFF71717A)
                    )
                }
            }
        }

        // Окно Output (Логи) как внизу Main на ПК
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141417)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2B2B33)),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                Text(
                    text = "Output / Логи",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFADADB8),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                if (minerState.logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Нажмите «Старт» для запуска майнинга", color = Color(0xFF71717A), fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(minerState.logs.asReversed()) { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFD4D4D8)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelsTab(
    minerState: MinerUiState,
    onRefreshChannels: () -> Unit,
    onSwitchChannel: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = minerState.activeCampaign?.gameName ?: "Каналы",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBF94FF)
                )
                Text(
                    text = if (minerState.activeChannelLogin != null) {
                        "Сейчас смотрим: ${minerState.activeChannelLogin}"
                    } else {
                        "Нажмите Switch для ручного выбора стримера"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFFADADB8)
                )
            }
            OutlinedButton(
                onClick = onRefreshChannels,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Обновить", fontSize = 12.sp)
            }
        }

        // Заголовок таблицы как на ПК
        Surface(
            color = Color(0xFF202026),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Канал", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFADADB8), modifier = Modifier.weight(1.4f))
                Text("Статус", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFADADB8), modifier = Modifier.weight(0.9f))
                Text("Зрит.", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFADADB8), modifier = Modifier.weight(0.7f))
                Text("Switch", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFADADB8), textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
            }
        }

        if (minerState.channels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Список каналов появится после запуска майнера или нажатия «Обновить».",
                    color = Color(0xFFADADB8),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(20.dp)
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(minerState.channels, key = { it.channelId }) { stream ->
                val isCurrent = stream.channelLogin.equals(minerState.activeChannelLogin, ignoreCase = true)
                ChannelRowItem(
                    stream = stream,
                    isCurrent = isCurrent,
                    canSwitch = minerState.isRunning && !isCurrent,
                    onSwitch = { onSwitchChannel(stream.channelLogin) }
                )
            }
        }
    }
}

@Composable
private fun ChannelRowItem(
    stream: LiveStreamCandidate,
    isCurrent: Boolean,
    canSwitch: Boolean,
    onSwitch: () -> Unit
) {
    Surface(
        color = if (isCurrent) Color(0xFF281E3B) else Color(0xFF18181B),
        shape = RoundedCornerShape(6.dp),
        border = if (isCurrent) BorderStroke(1.dp, Color(0xFF9146FF)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1.4f)) {
                Text(
                    text = stream.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) Color(0xFFBF94FF) else Color(0xFFEFEFF1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stream.gameName,
                    fontSize = 11.sp,
                    color = Color(0xFFADADB8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.weight(0.9f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isCurrent) "ACTIVE" else "ONLINE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E676)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF00E676),
                    modifier = Modifier.size(13.dp)
                )
            }

            Text(
                text = stream.viewers.toString(),
                fontSize = 12.sp,
                color = Color(0xFFEFEFF1),
                modifier = Modifier.weight(0.7f)
            )

            Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                if (canSwitch) {
                    IconButton(
                        onClick = onSwitch,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Переключиться",
                            tint = Color(0xFFBF94FF)
                        )
                    }
                } else if (isCurrent) {
                    Text("Текущий", fontSize = 10.sp, color = Color(0xFFBF94FF))
                }
            }
        }
    }
}

@Composable
private fun InventoryTab(
    campaigns: List<DropCampaignUi>,
    priorityGames: List<String>,
    excludedGames: List<String>,
    isRefreshing: Boolean,
    onTogglePriority: (String) -> Unit,
    onToggleExclude: (String) -> Unit,
    onManualClaim: (String, String, String) -> Unit,
    onOpenLink: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(CampaignFilter.ALL) }

    val filtered = remember(campaigns, searchQuery, selectedFilter) {
        campaigns.filter { c ->
            val matchesQuery = searchQuery.isBlank() ||
                c.gameName.contains(searchQuery, ignoreCase = true) ||
                c.name.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                CampaignFilter.ALL -> true
                CampaignFilter.ACTIVE -> c.isActive
                CampaignFilter.UNCLAIMED -> c.hasUnclaimedDrops
                CampaignFilter.CONNECTED -> c.isAccountConnected
                CampaignFilter.UPCOMING -> !c.isActive && c.isUpcoming
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            placeholder = { Text("Поиск по игре или кампании...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить", modifier = Modifier.size(18.dp))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(10.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CampaignFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF9146FF),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        if (isRefreshing && campaigns.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF9146FF))
            }
            return@Column
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ничего не найдено", color = Color(0xFFADADB8))
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.id }) { camp ->
                val isPriority = priorityGames.any { it.equals(camp.gameName, ignoreCase = true) }
                val isExcluded = excludedGames.any { it.equals(camp.gameName, ignoreCase = true) }

                PcStyleInventoryCampaignCard(
                    camp = camp,
                    isPriority = isPriority,
                    isExcluded = isExcluded,
                    onTogglePriority = { onTogglePriority(camp.gameName) },
                    onToggleExclude = { onToggleExclude(camp.gameName) },
                    onManualClaim = onManualClaim,
                    onOpenLink = onOpenLink
                )
            }
        }
    }
}

@Composable
private fun PcStyleInventoryCampaignCard(
    camp: DropCampaignUi,
    isPriority: Boolean,
    isExcluded: Boolean,
    onTogglePriority: () -> Unit,
    onToggleExclude: () -> Unit,
    onManualClaim: (String, String, String) -> Unit,
    onOpenLink: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isExcluded) Color(0xFF1F1619) else Color(0xFF18181B)
        ),
        border = BorderStroke(1.dp, Color(0xFF2D2D36)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Заголовок кампании
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${camp.gameName} — ${camp.name}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isExcluded) Color(0xFFADADB8) else Color(0xFFBF94FF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onTogglePriority, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isPriority) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Приоритет",
                        tint = if (isPriority) Color(0xFFFFCA28) else Color(0xFFADADB8),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onToggleExclude, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = "Исключить",
                        tint = if (isExcluded) Color(0xFFFF8A9B) else Color(0xFF71717A),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Информация: обложка слева + Active / Ends / Allowed channels справа
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (!camp.gameBoxArtUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = camp.gameBoxArtUrl,
                        contentDescription = camp.gameName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 58.dp, height = 78.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF26262C))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (camp.isActive) "Active" else "Upcoming",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (camp.isActive) Color(0xFF00E676) else Color(0xFFADADB8)
                        )
                        if (camp.isActive) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = "До: ${formatDate(camp.endsAtEpochMs)}",
                        fontSize = 12.sp,
                        color = Color(0xFFD4D4D8)
                    )
                    Text(
                        text = if (camp.aclEnabled && camp.allowedChannels.isNotEmpty()) {
                            "Каналы: ${camp.allowedChannels.size} шт."
                        } else {
                            "Каналы: Все"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFFADADB8)
                    )
                    if (!camp.isAccountConnected) {
                        Text(
                            text = "Аккаунт не привязан",
                            fontSize = 11.sp,
                            color = Color(0xFFFFCA28)
                        )
                    }
                }
            }

            if (!camp.isAccountConnected && !camp.accountLinkUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onOpenLink(camp.accountLinkUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Привязать игровой аккаунт", fontSize = 12.sp)
                }
            }

            // Горизонтальная лента плиток дропов (как на скрине 1 ПК-версии)
            if (camp.drops.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(camp.drops, key = { it.id }) { drop ->
                        DropTileCard(
                            drop = drop,
                            onManualClaim = {
                                val inst = drop.dropInstanceId
                                if (!inst.isNullOrBlank()) {
                                    onManualClaim(inst, drop.benefitName, camp.gameName)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DropTileCard(
    drop: TimedDropUi,
    onManualClaim: () -> Unit
) {
    Surface(
        color = Color(0xFF131317),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (drop.isClaimed) Color(0xFF1B5E20) else Color(0xFF2D2D36)
        ),
        modifier = Modifier.width(126.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = drop.benefitName,
                fontSize = 11.sp,
                color = Color(0xFFEFEFF1),
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (!drop.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = drop.imageUrl,
                    contentDescription = drop.benefitName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF26262C))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF26262C))
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (drop.isClaimed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Получен",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else if (drop.canClaim) {
                Button(
                    onClick = onManualClaim,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9146FF)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Забрать", fontSize = 11.sp)
                }
            } else {
                LinearProgressIndicator(
                    progress = { drop.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF9146FF),
                    trackColor = Color(0xFF2C2C35)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1f%% из %dм",
                        drop.progressPercent,
                        drop.requiredMinutes
                    ),
                    fontSize = 10.sp,
                    color = Color(0xFFADADB8),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PrioritiesTab(
    priorityGames: List<String>,
    excludedGames: List<String>,
    onAddPriority: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemovePriority: (String) -> Unit,
    onRemoveExcluded: (String) -> Unit
) {
    var manualGame by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualGame,
                    onValueChange = { manualGame = it },
                    singleLine = true,
                    placeholder = { Text("Добавить игру (точное название)", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onAddPriority(manualGame)
                        manualGame = ""
                    },
                    enabled = manualGame.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9146FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(54.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                }
            }
        }

        item {
            Text(
                text = "Приоритет майнинга (сверху вниз)",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFBF94FF),
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (priorityGames.isEmpty()) {
            item {
                Text(
                    text = "Список пуст. Нажмите ★ в карточке игры во вкладке «Дропсы» или введите название выше.",
                    color = Color(0xFFADADB8),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            itemsIndexed(priorityGames) { index, game ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}. $game",
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp,
                            color = Color(0xFFEFEFF1)
                        )
                        IconButton(onClick = { onMove(index, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Выше")
                        }
                        IconButton(onClick = { onMove(index, 1) }, enabled = index < priorityGames.lastIndex) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Ниже")
                        }
                        IconButton(onClick = { onRemovePriority(game) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Color(0xFFFF8A9B))
                        }
                    }
                }
            }
        }

        if (excludedGames.isNotEmpty()) {
            item {
                Text(
                    text = "Чёрный список (пропускаются)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF8A9B),
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            items(excludedGames) { game ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1619)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = game,
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp,
                            color = Color(0xFFEFEFF1)
                        )
                        IconButton(onClick = { onRemoveExcluded(game) }) {
                            Icon(Icons.Default.Close, contentDescription = "Убрать", tint = Color(0xFFEFEFF1))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    minerState: MinerUiState,
    isBatteryOptimized: Boolean,
    onDismiss: () -> Unit,
    onTogglePriorityOnly: (Boolean) -> Unit,
    onToggleSkipUnconnected: (Boolean) -> Unit,
    onSyncHashes: () -> Unit,
    onDisableBatteryOpt: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF18181B),
        title = { Text("Настройки майнера") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Только приоритетные игры", fontSize = 14.sp)
                        Text(
                            "Не переключаться на другие игры после списка.",
                            fontSize = 11.sp,
                            color = Color(0xFFADADB8)
                        )
                    }
                    Switch(checked = minerState.priorityOnly, onCheckedChange = onTogglePriorityOnly)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Только привязанные аккаунты", fontSize = 14.sp)
                        Text(
                            "Пропускать игры, где аккаунт ещё не привязан к Twitch.",
                            fontSize = 11.sp,
                            color = Color(0xFFADADB8)
                        )
                    }
                    Switch(checked = minerState.skipUnconnected, onCheckedChange = onToggleSkipUnconnected)
                }

                OutlinedButton(
                    onClick = onSyncHashes,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Обновить GQL-хеши с DevilXD")
                }

                if (isBatteryOptimized) {
                    OutlinedButton(
                        onClick = onDisableBatteryOpt,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Разрешить работу без контроля батареи")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        }
    )
}
