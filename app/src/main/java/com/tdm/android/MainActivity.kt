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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import com.tdm.android.data.CampaignFilter
import com.tdm.android.data.DropCampaignUi
import com.tdm.android.data.LiveStreamCandidate
import com.tdm.android.data.MinerUiState
import com.tdm.android.data.PriorityMode
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

    // Автоматический запуск майнера при входе в приложение
    LaunchedEffect(session.userId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!MinerRepository.state.value.isRunning) {
            val intent = Intent(context, MinerForegroundService::class.java).apply {
                action = MinerForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }
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
                        }
                    }

                    val tabTitles = listOf(
                        "Главная",
                        "Дропсы",
                        "Каналы (${minerState.channels.size})",
                        "Приоритет"
                    )
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF18181B),
                        contentColor = Color(0xFFBF94FF)
                    ) {
                        tabTitles.forEachIndexed { idx, title ->
                            Tab(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        color = if (selectedTab == idx) Color(0xFFBF94FF) else Color(0xFFADADB8)
                                    )
                                }
                            }
                        }
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
                    selectedFilter = minerState.lastDropsFilter,
                    isRefreshing = minerState.isRefreshing,
                    onSelectFilter = { MinerRepository.setLastDropsFilter(it) },
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
                    campaigns = minerState.campaigns,
                    priorityGames = minerState.priorityGames,
                    excludedGames = minerState.excludedGames,
                    priorityMode = minerState.priorityMode,
                    onSelectMode = { MinerRepository.setPriorityMode(it) },
                    onAddPriority = { MinerRepository.addPriorityGame(it) },
                    onAddExcluded = { MinerRepository.addExcludedGame(it) },
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
            },
            onLogout = {
                showSettingsDialog = false
                onLogout()
            }
        )
    }
}

@Composable
private fun MainPcStyleTab(
    minerState: MinerUiState,
    isBatteryOptimized: Boolean,
    onDisableBatteryOpt: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Статус майнера (без кнопки Старт/Стоп — работает автоматически)
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
                    Surface(
                        color = Color(0xFF25252D),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = minerState.priorityMode.title,
                            fontSize = 11.sp,
                            color = Color(0xFFBF94FF),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
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

        // Блок Campaign Progress + Drop Progress
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

        // Окно Output (Логи)
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
                        Text("Запуск автомайнера...", color = Color(0xFF71717A), fontSize = 13.sp)
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
                    text = "Список каналов появится сразу после выбора активной кампании.",
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
    selectedFilter: CampaignFilter,
    isRefreshing: Boolean,
    onSelectFilter: (CampaignFilter) -> Unit,
    onTogglePriority: (String) -> Unit,
    onToggleExclude: (String) -> Unit,
    onManualClaim: (String, String, String) -> Unit,
    onOpenLink: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(campaigns, searchQuery, selectedFilter, priorityGames) {
        campaigns.filter { c ->
            val matchesQuery = searchQuery.isBlank() ||
                c.gameName.contains(searchQuery, ignoreCase = true) ||
                c.name.contains(searchQuery, ignoreCase = true)

            val inPriority = priorityGames.any { it.equals(c.gameName, ignoreCase = true) }
            val matchesFilter = when (selectedFilter) {
                CampaignFilter.ALL -> true
                CampaignFilter.ACTIVE -> inPriority && c.isActive && c.hasUnclaimedDrops
                CampaignFilter.COMPLETED -> inPriority && c.isActive && c.drops.isNotEmpty() && !c.hasUnclaimedDrops
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

        // Ровно 3 фильтра: Все / Активные / Выполнены (выбор запоминается)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CampaignFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onSelectFilter(filter) },
                    label = { Text(filter.label, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
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
            val emptyMsg = when {
                selectedFilter != CampaignFilter.ALL && priorityGames.isEmpty() ->
                    "В вашем списке приоритетов пока нет игр.\nНажмите ★ на нужных играх во вкладке «Все» или добавьте их в «Приоритет»."
                selectedFilter == CampaignFilter.ACTIVE ->
                    "По вашим приоритетным играм сейчас нет активных неполученных дропов."
                selectedFilter == CampaignFilter.COMPLETED ->
                    "По вашим приоритетным играм пока нет полностью выполненных активных кампаний."
                else -> "Ничего не найдено"
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyMsg,
                    color = Color(0xFFADADB8),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
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

@OptIn(ExperimentalLayoutApi::class)
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
    var channelsExpanded by remember(camp.id) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isExcluded) Color(0xFF1F1619) else Color(0xFF18181B)
        ),
        border = BorderStroke(1.dp, Color(0xFF2D2D36)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onOpenLink("https://www.twitch.tv/drops/campaigns?dropID=${camp.id}")
                        }
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

                    if (camp.aclEnabled && camp.allowedChannels.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { channelsExpanded = !channelsExpanded }
                        ) {
                            Text(
                                text = "Участвуют каналы (${camp.allowedChannels.size})",
                                fontSize = 12.sp,
                                color = Color(0xFFBF94FF)
                            )
                            Icon(
                                imageVector = if (channelsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = Color(0xFFBF94FF),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Каналы: Все стримеры категории",
                                fontSize = 12.sp,
                                color = Color(0xFFADADB8)
                            )
                            if (camp.gameSlug.isNotBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Открыть на Twitch",
                                    tint = Color(0xFFBF94FF),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            onOpenLink("https://www.twitch.tv/directory/category/${camp.gameSlug}?filter=drops")
                                        }
                                )
                            }
                        }
                    }

                    if (!camp.isAccountConnected) {
                        Text(
                            text = "Аккаунт не привязан",
                            fontSize = 11.sp,
                            color = Color(0xFFFFCA28)
                        )
                    }
                }
            }

            if (camp.aclEnabled && camp.allowedChannels.isNotEmpty()) {
                val visibleChannels = if (channelsExpanded) {
                    camp.allowedChannels
                } else {
                    camp.allowedChannels.take(4)
                }

                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visibleChannels.forEach { ch ->
                        Surface(
                            color = Color(0xFF25252D),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable {
                                onOpenLink("https://www.twitch.tv/${ch.login}")
                            }
                        ) {
                            Text(
                                text = ch.displayName,
                                fontSize = 11.sp,
                                color = Color(0xFFEFEFF1),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (!channelsExpanded && camp.allowedChannels.size > 4) {
                        Surface(
                            color = Color(0xFF281E3B),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { channelsExpanded = true }
                        ) {
                            Text(
                                text = "+ ещё ${camp.allowedChannels.size - 4}",
                                fontSize = 11.sp,
                                color = Color(0xFFBF94FF),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
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

            if (camp.drops.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(camp.drops, key = { it.id }) { drop ->
                        DropTileCard(
                            drop = drop,
                            onCardClick = {
                                onOpenLink("https://www.twitch.tv/drops/campaigns?dropID=${camp.id}")
                            },
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
    onCardClick: () -> Unit,
    onManualClaim: () -> Unit
) {
    Surface(
        color = Color(0xFF131317),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (drop.isClaimed) Color(0xFF1B5E20) else Color(0xFF2D2D36)
        ),
        modifier = Modifier
            .width(126.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onCardClick)
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
    campaigns: List<DropCampaignUi>,
    priorityGames: List<String>,
    excludedGames: List<String>,
    priorityMode: PriorityMode,
    onSelectMode: (PriorityMode) -> Unit,
    onAddPriority: (String) -> Unit,
    onAddExcluded: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemovePriority: (String) -> Unit,
    onRemoveExcluded: (String) -> Unit
) {
    var gameQuery by remember { mutableStateOf("") }

    val allKnownGames = remember(campaigns) {
        campaigns.map { it.gameName }.distinctBy { it.lowercase() }.sorted()
    }

    val suggestions = remember(gameQuery, allKnownGames, priorityGames, excludedGames) {
        val q = gameQuery.trim()
        if (q.isEmpty()) emptyList()
        else allKnownGames.filter { name ->
            name.contains(q, ignoreCase = true) &&
                priorityGames.none { it.equals(name, ignoreCase = true) } &&
                excludedGames.none { it.equals(name, ignoreCase = true) }
        }.take(8)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                border = BorderStroke(1.dp, Color(0xFF2D2D36)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Режим выбора кампании",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFBF94FF)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    PriorityMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectMode(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = priorityMode == mode,
                                onClick = { onSelectMode(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF9146FF))
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mode.title, fontSize = 13.sp, color = Color(0xFFEFEFF1))
                                Text(mode.subtitle, fontSize = 11.sp, color = Color(0xFFADADB8))
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = gameQuery,
                    onValueChange = { gameQuery = it },
                    singleLine = true,
                    placeholder = { Text("Введите игру для приоритета или исключения...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (gameQuery.isNotEmpty()) {
                            IconButton(onClick = { gameQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                val trimmed = gameQuery.trim()
                val exactInSuggestions = suggestions.any { it.equals(trimmed, ignoreCase = true) }

                if (suggestions.isNotEmpty() || (trimmed.isNotEmpty() && !exactInSuggestions)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF202026)),
                        border = BorderStroke(1.dp, Color(0xFF32323D)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            suggestions.forEach { gameName ->
                                SuggestionRow(
                                    label = gameName,
                                    onAddPriority = {
                                        onAddPriority(gameName)
                                        gameQuery = ""
                                    },
                                    onAddExcluded = {
                                        onAddExcluded(gameName)
                                        gameQuery = ""
                                    }
                                )
                            }
                            if (trimmed.isNotEmpty() && !exactInSuggestions) {
                                SuggestionRow(
                                    label = "Добавить: $trimmed",
                                    onAddPriority = {
                                        onAddPriority(trimmed)
                                        gameQuery = ""
                                    },
                                    onAddExcluded = {
                                        onAddExcluded(trimmed)
                                        gameQuery = ""
                                    }
                                )
                            }
                        }
                    }
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
                    text = "Список приоритетов пуст. Найдите игру в поиске выше или нажмите ★ во вкладке «Дропсы».",
                    color = Color(0xFFADADB8),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
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

        item {
            Text(
                text = "Исключения / Чёрный список (${excludedGames.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF8A9B),
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (excludedGames.isEmpty()) {
            item {
                Text(
                    text = "Исключений пока нет. Нажмите значок глаза в поиске выше или в карточке игры.",
                    color = Color(0xFF71717A),
                    fontSize = 12.sp
                )
            }
        } else {
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
private fun SuggestionRow(
    label: String,
    onAddPriority: () -> Unit,
    onAddExcluded: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFFEFEFF1),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onAddPriority, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "В приоритет",
                tint = Color(0xFFFFCA28),
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onAddExcluded, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = Icons.Default.VisibilityOff,
                contentDescription = "В исключения",
                tint = Color(0xFFFF8A9B),
                modifier = Modifier.size(18.dp)
            )
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
    onDisableBatteryOpt: () -> Unit,
    onLogout: () -> Unit
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

                HorizontalDivider(color = Color(0xFF2B2B33))

                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF8A9B).copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A9B))
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        }
    )
}
