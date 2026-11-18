package com.tdm.android.miner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.tdm.android.data.AuthStorage
import com.tdm.android.data.CampaignFilter
import com.tdm.android.data.DropCampaignUi
import com.tdm.android.data.LiveStreamCandidate
import com.tdm.android.data.MinerPreferences
import com.tdm.android.data.MinerUiState
import com.tdm.android.data.PriorityMode
import com.tdm.android.data.TwitchSession
import com.tdm.android.network.GqlRegistry
import com.tdm.android.network.TwitchAuthApi
import com.tdm.android.network.TwitchAuthExpiredException
import com.tdm.android.network.TwitchGqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

object MinerRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MinerUiState())
    val state: StateFlow<MinerUiState> = _state.asStateFlow()

    private var minerJob: Job? = null
    private var prefs: MinerPreferences? = null
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val channelCooldowns = ConcurrentHashMap<String, Long>()

    @Volatile
    private var wakeSignal: CompletableDeferred<Unit>? = null

    @Volatile
    private var preferredChannelLogin: String? = null

    @Volatile
    private var isNetworkAvailable: Boolean = true

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun initPreferences(context: Context) {
        GqlRegistry.initCache(context)
        if (prefs == null) {
            prefs = MinerPreferences(context.applicationContext)
        }
        val p = prefs ?: return
        _state.update {
            it.copy(
                priorityGames = p.getPriorityGames(),
                excludedGames = p.getExcludedGames(),
                priorityMode = p.getPriorityMode(),
                priorityOnly = p.isPriorityOnly(),
                skipUnconnected = p.isSkipUnconnected(),
                lastDropsFilter = p.getLastDropsFilter(),
                totalClaimedAllTime = p.getTotalClaimed()
            )
        }
    }

    fun registerNetworkCallback(context: Context) {
        if (networkCallback != null) return
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            isNetworkAvailable = true
            return
        }

        runCatching {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            val activeCaps = cm.getNetworkCapabilities(cm.activeNetwork)
            isNetworkAvailable = activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = !isNetworkAvailable
                    isNetworkAvailable = true
                    if (wasOffline) {
                        addLog("Интернет восстановлен, продолжаю работу.")
                        wakeSignal?.complete(Unit)
                    }
                }

                override fun onLost(network: Network) {
                    runCatching {
                        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                        isNetworkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        if (!isNetworkAvailable) {
                            addLog("Интернет потерян, ожидание сети...")
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }.onFailure {
            isNetworkAvailable = true
        }
    }

    fun unregisterNetworkCallback(context: Context) {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            cm.unregisterNetworkCallback(cb)
        }
    }

    fun addLog(message: String) {
        val line = "[${LocalTime.now().format(timeFormat)}] $message"
        _state.update { cur ->
            val updated = (cur.logs + line).takeLast(150)
            cur.copy(logs = updated)
        }
    }

    fun clearSessionState() {
        stopMining()
        channelCooldowns.clear()
        _state.update { cur ->
            cur.copy(
                isRunning = false,
                statusText = "Подготовка...",
                activeChannelLogin = null,
                activeChannelViewers = 0,
                activeGameName = null,
                activeCampaign = null,
                activeDrop = null,
                channels = emptyList(),
                campaigns = emptyList(),
                sessionExpired = false,
                claimedSessionCount = 0,
                lastCampaignsFetchMs = 0L,
                logs = emptyList(),
                isRefreshing = false
            )
        }
    }

    fun switchToChannel(channelLogin: String) {
        if (!_state.value.isRunning) return
        preferredChannelLogin = channelLogin
        addLog("Переключаюсь на канал $channelLogin...")
        wakeSignal?.complete(Unit)
    }

    fun refreshActiveChannels(context: Context, session: TwitchSession) {
        val camp = _state.value.activeCampaign ?: orderEligibleCampaigns(_state.value.campaigns).firstOrNull() ?: return
        scope.launch {
            try {
                val deviceId = AuthStorage(context).getOrCreateDeviceId()
                val gql = TwitchGqlClient(session, deviceId, ::addLog)
                val streams = gql.fetchLiveStreamsForCampaign(camp)
                _state.update { it.copy(channels = streams) }
                addLog("Список каналов обновлён (${camp.gameName}): ${streams.size} онлайн.")
            } catch (e: TwitchAuthExpiredException) {
                handleAuthExpired(e.message)
            } catch (e: Exception) {
                addLog("Ошибка обновления каналов: ${e.message}")
            }
        }
    }

    fun addPriorityGame(gameName: String) {
        val trimmed = gameName.trim()
        if (trimmed.isEmpty()) return
        val p = prefs ?: return
        val excluded = _state.value.excludedGames.toMutableList()
        if (excluded.removeAll { it.equals(trimmed, ignoreCase = true) }) {
            p.saveExcludedGames(excluded)
        }
        val current = _state.value.priorityGames.toMutableList()
        if (current.none { it.equals(trimmed, ignoreCase = true) }) {
            current.add(trimmed)
            p.savePriorityGames(current)
        }
        _state.update { it.copy(priorityGames = current, excludedGames = excluded) }
    }

    fun addExcludedGame(gameName: String) {
        val trimmed = gameName.trim()
        if (trimmed.isEmpty()) return
        val p = prefs ?: return
        val priority = _state.value.priorityGames.toMutableList()
        if (priority.removeAll { it.equals(trimmed, ignoreCase = true) }) {
            p.savePriorityGames(priority)
        }
        val current = _state.value.excludedGames.toMutableList()
        if (current.none { it.equals(trimmed, ignoreCase = true) }) {
            current.add(trimmed)
            p.saveExcludedGames(current)
        }
        _state.update { it.copy(priorityGames = priority, excludedGames = current) }
    }

    fun togglePriorityGame(gameName: String) {
        val p = prefs ?: return
        val current = _state.value.priorityGames.toMutableList()
        if (current.any { it.equals(gameName, ignoreCase = true) }) {
            current.removeAll { it.equals(gameName, ignoreCase = true) }
            p.savePriorityGames(current)
            _state.update { it.copy(priorityGames = current) }
        } else {
            addPriorityGame(gameName)
        }
    }

    fun movePriorityGame(index: Int, delta: Int) {
        val p = prefs ?: return
        val current = _state.value.priorityGames.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val item = current.removeAt(index)
        current.add(target, item)
        p.savePriorityGames(current)
        _state.update { it.copy(priorityGames = current) }
    }

    fun toggleExcludedGame(gameName: String) {
        val p = prefs ?: return
        val current = _state.value.excludedGames.toMutableList()
        if (current.any { it.equals(gameName, ignoreCase = true) }) {
            current.removeAll { it.equals(gameName, ignoreCase = true) }
            p.saveExcludedGames(current)
            _state.update { it.copy(excludedGames = current) }
        } else {
            addExcludedGame(gameName)
        }
    }

    fun setPriorityMode(mode: PriorityMode) {
        val p = prefs ?: return
        p.setPriorityMode(mode)
        _state.update { it.copy(priorityMode = mode) }
        addLog("Режим выбора: ${mode.title}")
    }

    fun setLastDropsFilter(filter: CampaignFilter) {
        val p = prefs ?: return
        p.setLastDropsFilter(filter)
        _state.update { it.copy(lastDropsFilter = filter) }
    }

    fun setPriorityOnly(enabled: Boolean) {
        val p = prefs ?: return
        p.setPriorityOnly(enabled)
        _state.update { it.copy(priorityOnly = enabled) }
    }

    fun setSkipUnconnected(enabled: Boolean) {
        val p = prefs ?: return
        p.setSkipUnconnected(enabled)
        _state.update { it.copy(skipUnconnected = enabled) }
    }

    fun forceSyncGqlHashes() {
        scope.launch {
            GqlRegistry.syncFromDevilXd(TwitchAuthApi.httpClient, force = true, log = ::addLog)
        }
    }

    fun manualClaimDrop(
        context: Context,
        session: TwitchSession,
        dropInstanceId: String,
        benefitName: String,
        gameName: String
    ) {
        initPreferences(context)
        scope.launch {
            try {
                val deviceId = AuthStorage(context).getOrCreateDeviceId()
                val gql = TwitchGqlClient(session, deviceId, ::addLog)
                if (gql.claimDrop(dropInstanceId)) {
                    recordClaimedDrop(benefitName, gameName)
                    val refreshed = gql.fetchCampaigns()
                    _state.update {
                        it.copy(campaigns = refreshed, lastCampaignsFetchMs = System.currentTimeMillis())
                    }
                } else {
                    addLog("Twitch пока не подтвердил получение $benefitName.")
                }
            } catch (e: TwitchAuthExpiredException) {
                handleAuthExpired(e.message)
            } catch (e: Exception) {
                addLog("Ошибка при получении $benefitName: ${e.message}")
            }
        }
    }

    fun refreshCampaignsOnce(context: Context, session: TwitchSession) {
        initPreferences(context)
        scope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val deviceId = AuthStorage(context).getOrCreateDeviceId()
                val gql = TwitchGqlClient(session, deviceId, ::addLog)
                GqlRegistry.syncFromDevilXd(TwitchAuthApi.httpClient, force = false, log = ::addLog)
                val campaigns = gql.fetchCampaigns()
                _state.update {
                    it.copy(
                        campaigns = campaigns,
                        lastCampaignsFetchMs = System.currentTimeMillis()
                    )
                }
                addLog("Загружено кампаний: ${campaigns.size} (активных с дропами: ${campaigns.count { c -> c.isActive && c.hasUnclaimedDrops }}).")
            } catch (e: TwitchAuthExpiredException) {
                handleAuthExpired(e.message)
            } catch (e: Exception) {
                addLog("Ошибка загрузки кампаний: ${e.message}")
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun startMining(context: Context, session: TwitchSession) {
        initPreferences(context)
        if (minerJob?.isActive == true) return

        registerNetworkCallback(context)
        val deviceId = AuthStorage(context).getOrCreateDeviceId()
        _state.update {
            it.copy(
                isRunning = true,
                sessionExpired = false,
                statusText = "Запуск майнера..."
            )
        }

        minerJob = scope.launch {
            val gql = TwitchGqlClient(session, deviceId, ::addLog)
            try {
                addLog("Майнер запущен (${session.login}).")
                GqlRegistry.syncFromDevilXd(TwitchAuthApi.httpClient, force = false, log = ::addLog)

                var firstLoop = true
                while (isActive) {
                    val requestedLogin = preferredChannelLogin
                    if (!requestedLogin.isNullOrBlank()) {
                        preferredChannelLogin = null
                        val curCamp = _state.value.activeCampaign
                        val cachedStream = _state.value.channels.firstOrNull {
                            it.channelLogin.equals(requestedLogin, ignoreCase = true)
                        }
                        if (curCamp != null && cachedStream != null && curCamp.isActive && curCamp.hasUnclaimedDrops) {
                            val supported = gql.channelSupportsCampaign(cachedStream.channelId, curCamp.id)
                            if (supported == false) {
                                addLog("Канал $requestedLogin сейчас не раздаёт дропы этой кампании.")
                                channelCooldowns[cachedStream.channelId] = System.currentTimeMillis() + 5 * 60_000L
                            } else {
                                mineStreamSession(gql, curCamp, cachedStream, isManualChannel = true)
                                continue
                            }
                        }
                    }

                    if (!isNetworkAvailable) {
                        _state.update { it.copy(statusText = "Ожидание интернета...") }
                        waitInterruptibly(60_000L)
                        continue
                    }

                    val cacheAgeMs = System.currentTimeMillis() - _state.value.lastCampaignsFetchMs
                    val campaigns = if (firstLoop && _state.value.campaigns.isNotEmpty() && cacheAgeMs < 120_000L) {
                        addLog("Использую свежий список кампаний.")
                        _state.value.campaigns
                    } else {
                        _state.update { it.copy(statusText = "Загрузка кампаний...") }
                        try {
                            val loaded = gql.fetchCampaigns()
                            _state.update {
                                it.copy(campaigns = loaded, lastCampaignsFetchMs = System.currentTimeMillis())
                            }
                            loaded
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: TwitchAuthExpiredException) {
                            throw e
                        } catch (e: Exception) {
                            addLog("Ошибка получения кампаний: ${e.message}. Повтор через 30с.")
                            _state.update { it.copy(statusText = "Ошибка сети, повтор...") }
                            waitInterruptibly(30_000L)
                            continue
                        }
                    }
                    firstLoop = false

                    val claimedAny = claimReadyDrops(gql, campaigns)
                    if (claimedAny) {
                        val updatedAfterClaim = runCatching { gql.fetchCampaigns() }.getOrNull()
                        if (updatedAfterClaim != null) {
                            _state.update {
                                it.copy(campaigns = updatedAfterClaim, lastCampaignsFetchMs = System.currentTimeMillis())
                            }
                        }
                    }

                    val orderedCampaigns = orderEligibleCampaigns(_state.value.campaigns)
                    if (orderedCampaigns.isEmpty()) {
                        _state.update {
                            it.copy(
                                statusText = "Нет подходящих активных кампаний",
                                activeChannelLogin = null,
                                activeChannelViewers = 0,
                                activeGameName = null,
                                activeCampaign = null,
                                activeDrop = null,
                                channels = emptyList()
                            )
                        }
                        addLog("Нет доступных активных кампаний. Следующая проверка через 5 мин.")
                        waitInterruptibly(5 * 60_000L)
                        continue
                    }

                    var selectedCampaign: DropCampaignUi? = null
                    var selectedStream: LiveStreamCandidate? = null
                    var foundStreams: List<LiveStreamCandidate> = emptyList()
                    val now = System.currentTimeMillis()

                    for (camp in orderedCampaigns) {
                        val streams = try {
                            gql.fetchLiveStreamsForCampaign(camp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: TwitchAuthExpiredException) {
                            throw e
                        } catch (e: Exception) {
                            addLog("Ошибка поиска каналов (${camp.gameName}): ${e.message}")
                            emptyList()
                        }

                        if (streams.isNotEmpty() && foundStreams.isEmpty()) {
                            foundStreams = streams
                        }

                        val candidateStreams = streams
                            .filter { (channelCooldowns[it.channelId] ?: 0L) <= now }
                            .take(12)

                        if (candidateStreams.isEmpty()) continue

                        val supportMap = gql.checkChannelsSupportCampaignBatch(
                            channelIds = candidateStreams.map { it.channelId },
                            campaignId = camp.id
                        )

                        for (s in candidateStreams) {
                            val supported = supportMap[s.channelId]
                            if (supported != false) {
                                selectedCampaign = camp
                                selectedStream = s
                                foundStreams = streams
                                break
                            }
                            addLog("Пропуск ${s.channelLogin}: дропы кампании не активны.")
                        }
                        if (selectedStream != null) break
                    }

                    _state.update { it.copy(channels = foundStreams) }

                    if (selectedCampaign == null || selectedStream == null) {
                        val firstCamp = orderedCampaigns.first()
                        _state.update {
                            it.copy(
                                statusText = "Нет стримов с дропами",
                                activeChannelLogin = null,
                                activeChannelViewers = 0,
                                activeGameName = firstCamp.gameName,
                                activeCampaign = firstCamp,
                                activeDrop = firstCamp.nextDrop
                            )
                        }
                        addLog("Сейчас нет живых стримов с активными дропами. Пауза 2 мин.")
                        waitInterruptibly(2 * 60_000L)
                        continue
                    }

                    mineStreamSession(gql, selectedCampaign, selectedStream, isManualChannel = false)
                }
            } catch (_: CancellationException) {
                addLog("Майнер остановлен.")
            } catch (e: TwitchAuthExpiredException) {
                handleAuthExpired(e.message)
            } catch (e: Exception) {
                addLog("Критическая ошибка майнера: ${e.message}")
            } finally {
                wakeSignal = null
                _state.update { cur ->
                    cur.copy(
                        isRunning = false,
                        statusText = if (cur.sessionExpired) "Сессия истекла — войдите снова" else "Остановлен",
                        activeChannelLogin = null,
                        activeChannelViewers = 0
                    )
                }
            }
        }
    }

    fun stopMining() {
        wakeSignal?.complete(Unit)
        wakeSignal = null
        preferredChannelLogin = null
        minerJob?.cancel()
        minerJob = null
        _state.update { cur ->
            cur.copy(
                isRunning = false,
                statusText = if (cur.sessionExpired) "Сессия истекла — войдите снова" else "Остановлен",
                activeChannelLogin = null,
                activeChannelViewers = 0
            )
        }
    }

    private fun handleAuthExpired(msg: String?) {
        addLog("Авторизация истекла: ${msg ?: "требуется повторный вход"}")
        _state.update {
            it.copy(
                sessionExpired = true,
                statusText = "Сессия истекла — войдите снова"
            )
        }
    }

    private suspend fun waitInterruptibly(durationMs: Long) {
        val signal = CompletableDeferred<Unit>()
        wakeSignal = signal
        withTimeoutOrNull(durationMs) { signal.await() }
        if (wakeSignal === signal) wakeSignal = null
    }

    private fun syncDropProgressIntoState(
        campaignId: String,
        dropId: String,
        newMinutes: Int,
        newRequired: Int,
        isClaimed: Boolean,
        dropInstanceId: String?
    ) {
        _state.update { state ->
            var updatedActiveCamp = state.activeCampaign
            var updatedActiveDrop = state.activeDrop

            val updatedCampaigns = state.campaigns.map { camp ->
                if (camp.id != campaignId) camp
                else {
                    val newDrops = camp.drops.map { d ->
                        if (d.id != dropId) d
                        else d.copy(
                            currentMinutes = maxOf(d.currentMinutes, newMinutes),
                            requiredMinutes = if (newRequired > 0) newRequired else d.requiredMinutes,
                            isClaimed = d.isClaimed || isClaimed,
                            dropInstanceId = dropInstanceId ?: d.dropInstanceId
                        ).also { updated ->
                            if (updatedActiveDrop?.id == updated.id) {
                                updatedActiveDrop = updated
                            }
                        }
                    }
                    val updatedCamp = camp.copy(drops = newDrops)
                    if (updatedActiveCamp?.id == updatedCamp.id) {
                        updatedActiveCamp = updatedCamp
                    }
                    updatedCamp
                }
            }

            state.copy(
                campaigns = updatedCampaigns,
                activeCampaign = updatedActiveCamp,
                activeDrop = updatedActiveDrop
            )
        }
    }

    private fun recordClaimedDrop(benefitName: String, gameName: String) {
        val total = prefs?.incrementTotalClaimed() ?: (_state.value.totalClaimedAllTime + 1)
        _state.update {
            it.copy(
                claimedSessionCount = it.claimedSessionCount + 1,
                totalClaimedAllTime = total,
                lastClaimedSeq = it.lastClaimedSeq + 1,
                lastClaimedTitle = "Дроп получен: $gameName",
                lastClaimedBody = benefitName
            )
        }
        addLog("ЗАБРАН ДРОП: $benefitName ($gameName)")
    }

    private suspend fun claimReadyDrops(gql: TwitchGqlClient, campaigns: List<DropCampaignUi>): Boolean {
        var claimedAny = false
        for (camp in campaigns) {
            for (drop in camp.drops) {
                val instanceId = drop.dropInstanceId
                if (drop.canClaim && !instanceId.isNullOrBlank()) {
                    try {
                        if (gql.claimDrop(instanceId)) {
                            recordClaimedDrop(drop.benefitName, camp.gameName)
                            claimedAny = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TwitchAuthExpiredException) {
                        throw e
                    } catch (e: Exception) {
                        addLog("Не удалось забрать ${drop.benefitName}: ${e.message}")
                    }
                }
            }
        }
        return claimedAny
    }

    private fun orderEligibleCampaigns(campaigns: List<DropCampaignUi>): List<DropCampaignUi> {
        val excluded = _state.value.excludedGames
        val skipUnconnected = _state.value.skipUnconnected
        val priorities = _state.value.priorityGames
        val mode = _state.value.priorityMode

        val eligible = campaigns.filter { c ->
            c.isActive &&
                c.hasUnclaimedDrops &&
                c.nextDrop != null &&
                excluded.none { ex -> ex.equals(c.gameName, ignoreCase = true) } &&
                (!skipUnconnected || c.isAccountConnected)
        }
        if (eligible.isEmpty()) return emptyList()

        val inPriority = eligible.filter { c ->
            priorities.any { p -> p.equals(c.gameName, ignoreCase = true) }
        }
        val others = if (_state.value.priorityOnly) {
            emptyList()
        } else {
            eligible.filter { c -> c !in inPriority }
        }

        val sortedPriority = when (mode) {
            PriorityMode.LIST_ORDER -> {
                inPriority.sortedWith(
                    compareBy<DropCampaignUi> { c ->
                        priorities.indexOfFirst { p -> p.equals(c.gameName, ignoreCase = true) }
                    }
                        .thenByDescending { it.isAccountConnected }
                        .thenBy { it.endsAtEpochMs }
                )
            }
            PriorityMode.ENDING_SOONEST -> {
                inPriority.sortedWith(
                    compareBy<DropCampaignUi> { it.endsAtEpochMs }
                        .thenByDescending { it.isAccountConnected }
                )
            }
            PriorityMode.LOWEST_AVAILABILITY -> {
                inPriority.sortedWith(
                    compareBy<DropCampaignUi> { it.rarityScore }
                        .thenBy { it.endsAtEpochMs }
                        .thenByDescending { it.isAccountConnected }
                )
            }
        }

        val sortedOthers = when (mode) {
            PriorityMode.LIST_ORDER -> {
                others.sortedWith(
                    compareByDescending<DropCampaignUi> { it.isAccountConnected }
                        .thenBy { it.endsAtEpochMs }
                )
            }
            PriorityMode.ENDING_SOONEST -> {
                others.sortedWith(
                    compareBy<DropCampaignUi> { it.endsAtEpochMs }
                        .thenByDescending { it.isAccountConnected }
                )
            }
            PriorityMode.LOWEST_AVAILABILITY -> {
                others.sortedWith(
                    compareBy<DropCampaignUi> { it.rarityScore }
                        .thenBy { it.endsAtEpochMs }
                        .thenByDescending { it.isAccountConnected }
                )
            }
        }

        return sortedPriority + sortedOthers
    }

    private suspend fun mineStreamSession(
        gql: TwitchGqlClient,
        initialCampaign: DropCampaignUi,
        stream: LiveStreamCandidate,
        isManualChannel: Boolean
    ) {
        var campaign = initialCampaign
        addLog("Смотрим ${stream.channelLogin} (${campaign.gameName}, зрителей: ${stream.viewers})")
        var playlistUrl = gql.getLowestStreamPlaylistUrl(stream.channelLogin) ?: run {
            addLog("Не удалось получить поток ${stream.channelLogin}, ищу другой канал...")
            channelCooldowns[stream.channelId] = System.currentTimeMillis() + 5 * 60_000L
            waitInterruptibly(5_000)
            return
        }

        var currentDrop = campaign.nextDrop
        _state.update {
            it.copy(
                statusText = "Майнинг: ${stream.channelLogin}",
                activeChannelLogin = stream.channelLogin,
                activeChannelViewers = stream.viewers,
                activeGameName = campaign.gameName,
                activeCampaign = campaign,
                activeDrop = currentDrop
            )
        }

        val seenChunks = mutableSetOf<String>()
        var tick = 0
        var loggedChunksOnce = false
        var consecutiveHeartbeatFails = 0
        var lastMinutes = currentDrop?.currentMinutes ?: 0
        var lastProgressChangeMs = System.currentTimeMillis()
        var sessionCheckStartMs = System.currentTimeMillis()

        while (true) {
            if (!preferredChannelLogin.isNullOrBlank()) return

            if (!isNetworkAvailable) {
                _state.update { it.copy(statusText = "Ожидание интернета...") }
                waitInterruptibly(15_000L)
                continue
            }

            val chunksSent = gql.sendWatchHeartbeat(playlistUrl, seenChunks)
            if (chunksSent == -2) {
                addLog("Плейлист ${stream.channelLogin} истёк, обновляю поток...")
                val refreshed = gql.getLowestStreamPlaylistUrl(stream.channelLogin)
                if (refreshed.isNullOrBlank()) {
                    addLog("Канал ${stream.channelLogin} оффлайн. Смена канала.")
                    return
                }
                playlistUrl = refreshed
                seenChunks.clear()
                consecutiveHeartbeatFails = 0
                loggedChunksOnce = false
            } else if (chunksSent <= 0) {
                consecutiveHeartbeatFails++
                if (consecutiveHeartbeatFails >= 2) {
                    addLog("Поток ${stream.channelLogin} прервался, переподключение...")
                    val refreshed = gql.getLowestStreamPlaylistUrl(stream.channelLogin)
                    if (refreshed.isNullOrBlank()) {
                        addLog("Канал ${stream.channelLogin} оффлайн. Смена канала.")
                        return
                    }
                    playlistUrl = refreshed
                    seenChunks.clear()
                    consecutiveHeartbeatFails = 0
                    loggedChunksOnce = false
                }
            } else {
                consecutiveHeartbeatFails = 0
                _state.update {
                    if (it.statusText != "Майнинг: ${stream.channelLogin}") {
                        it.copy(statusText = "Майнинг: ${stream.channelLogin}")
                    } else it
                }
                if (!loggedChunksOnce) {
                    addLog("Просмотр подтверждён (HEAD x$chunksSent чанков).")
                    loggedChunksOnce = true
                }
            }

            tick++
            val activeDropSnap = currentDrop
            if (tick % 3 == 0 && activeDropSnap != null) {
                val progress = gql.fetchCurrentDrop(stream.channelId)
                if (progress != null) {
                    val matchedInCampaign = campaign.drops.firstOrNull { it.id == progress.dropId }
                    if (progress.dropId == activeDropSnap.id) {
                        val updatedMinutes = maxOf(activeDropSnap.currentMinutes, progress.currentMinutes)
                        if (updatedMinutes > lastMinutes) {
                            lastMinutes = updatedMinutes
                            lastProgressChangeMs = System.currentTimeMillis()
                            addLog("Прогресс «${activeDropSnap.benefitName}»: $updatedMinutes/${activeDropSnap.requiredMinutes} мин")
                        }
                        val nextReq = if (progress.requiredMinutes > 0) progress.requiredMinutes else activeDropSnap.requiredMinutes
                        val nextDropState = activeDropSnap.copy(
                            currentMinutes = updatedMinutes,
                            requiredMinutes = nextReq
                        )
                        currentDrop = nextDropState
                        _state.update { it.copy(activeDrop = nextDropState) }
                        syncDropProgressIntoState(campaign.id, activeDropSnap.id, updatedMinutes, nextReq, false, null)

                        if (nextDropState.requiredMinutes > 0 && nextDropState.currentMinutes >= nextDropState.requiredMinutes) {
                            addLog("Дроп готов к получению! Забираю...")
                            return
                        }
                    } else if (matchedInCampaign != null && !matchedInCampaign.isClaimed) {
                        val updatedMinutes = maxOf(matchedInCampaign.currentMinutes, progress.currentMinutes)
                        lastMinutes = updatedMinutes
                        lastProgressChangeMs = System.currentTimeMillis()
                        val switched = matchedInCampaign.copy(currentMinutes = updatedMinutes)
                        currentDrop = switched
                        _state.update { it.copy(activeDrop = switched) }
                        syncDropProgressIntoState(campaign.id, switched.id, updatedMinutes, switched.requiredMinutes, false, null)
                        addLog("Отслеживается дроп «${switched.benefitName}»: ${switched.currentMinutes}/${switched.requiredMinutes} мин")
                    }
                }
            }

            if (tick % 4 == 0) {
                val stillSupported = gql.channelSupportsCampaign(stream.channelId, campaign.id)
                if (stillSupported == false) {
                    addLog("Канал ${stream.channelLogin} больше не раздаёт дропы этой кампании — меняю канал.")
                    channelCooldowns[stream.channelId] = System.currentTimeMillis() + 5 * 60_000L
                    return
                }
            }

            val dropForInv = currentDrop
            if (tick % 6 == 0 && dropForInv != null) {
                val invSnap = gql.fetchDropProgressFromInventory(campaign.id, dropForInv.id)
                if (invSnap != null) {
                    val updatedMinutes = maxOf(dropForInv.currentMinutes, invSnap.currentMinutes)
                    if (updatedMinutes > lastMinutes) {
                        lastMinutes = updatedMinutes
                        lastProgressChangeMs = System.currentTimeMillis()
                        addLog("Инвентарь «${dropForInv.benefitName}»: $updatedMinutes/${dropForInv.requiredMinutes} мин")
                    }
                    val nextDropState = dropForInv.copy(
                        currentMinutes = updatedMinutes,
                        isClaimed = invSnap.isClaimed,
                        dropInstanceId = invSnap.dropInstanceId ?: dropForInv.dropInstanceId
                    )
                    currentDrop = nextDropState
                    _state.update { it.copy(activeDrop = nextDropState) }
                    syncDropProgressIntoState(
                        campaign.id,
                        dropForInv.id,
                        updatedMinutes,
                        dropForInv.requiredMinutes,
                        invSnap.isClaimed,
                        invSnap.dropInstanceId
                    )
                    if (invSnap.isClaimed || (nextDropState.requiredMinutes > 0 && nextDropState.currentMinutes >= nextDropState.requiredMinutes)) {
                        addLog("Дроп выполнен! Обновляю и забираю...")
                        return
                    }
                }
            }

            if (tick >= 18 && System.currentTimeMillis() - lastProgressChangeMs >= 6 * 60_000L) {
                addLog("Прогресс на ${stream.channelLogin} не растёт 6 мин — меняю стримера.")
                channelCooldowns[stream.channelId] = System.currentTimeMillis() + 15 * 60_000L
                return
            }

            if (System.currentTimeMillis() - sessionCheckStartMs >= 20 * 60_000L) {
                sessionCheckStartMs = System.currentTimeMillis()
                val refreshed = runCatching { gql.fetchCampaigns() }.getOrElse { err ->
                    if (err is CancellationException || err is TwitchAuthExpiredException) throw err
                    null
                }
                if (refreshed != null) {
                    _state.update {
                        it.copy(campaigns = refreshed, lastCampaignsFetchMs = System.currentTimeMillis())
                    }
                    if (claimReadyDrops(gql, refreshed)) {
                        return
                    }
                    val updatedCamp = refreshed.firstOrNull { it.id == campaign.id }
                    if (updatedCamp == null || !updatedCamp.isActive || !updatedCamp.hasUnclaimedDrops) {
                        addLog("Текущая кампания завершена, ищу следующую.")
                        return
                    }
                    if (!isManualChannel) {
                        val best = orderEligibleCampaigns(refreshed).firstOrNull()
                        if (best != null && best.id != campaign.id) {
                            addLog("Появилась более приоритетная кампания: ${best.gameName}")
                            return
                        }
                    }
                    campaign = updatedCamp
                    _state.update { it.copy(activeCampaign = updatedCamp) }
                }
            }

            waitInterruptibly(20_000L)
        }
    }
}
