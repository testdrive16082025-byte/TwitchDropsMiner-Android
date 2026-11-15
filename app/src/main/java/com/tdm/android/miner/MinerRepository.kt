package com.tdm.android.miner

import android.content.Context
import com.tdm.android.data.AuthStorage
import com.tdm.android.data.DropCampaignUi
import com.tdm.android.data.LiveStreamCandidate
import com.tdm.android.data.MinerPreferences
import com.tdm.android.data.MinerUiState
import com.tdm.android.data.TwitchSession
import com.tdm.android.network.GqlRegistry
import com.tdm.android.network.TwitchAuthApi
import com.tdm.android.network.TwitchGqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MinerRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MinerUiState())
    val state: StateFlow<MinerUiState> = _state.asStateFlow()

    private var minerJob: Job? = null
    private var prefs: MinerPreferences? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val channelCooldowns = mutableMapOf<String, Long>()

    fun initPreferences(context: Context) {
        if (prefs == null) {
            prefs = MinerPreferences(context.applicationContext)
        }
        val p = prefs ?: return
        _state.update {
            it.copy(
                priorityGames = p.getPriorityGames(),
                excludedGames = p.getExcludedGames(),
                priorityOnly = p.isPriorityOnly(),
                skipUnconnected = p.isSkipUnconnected(),
                totalClaimedAllTime = p.getTotalClaimed()
            )
        }
    }

    fun addLog(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        _state.update { cur ->
            val updated = (cur.logs + line).takeLast(150)
            cur.copy(logs = updated)
        }
    }

    fun addPriorityGame(gameName: String) {
        val trimmed = gameName.trim()
        if (trimmed.isEmpty()) return
        val p = prefs ?: return
        val current = _state.value.priorityGames.toMutableList()
        if (current.none { it.equals(trimmed, ignoreCase = true) }) {
            current.add(trimmed)
            p.savePriorityGames(current)
            _state.update { it.copy(priorityGames = current) }
        }
    }

    fun togglePriorityGame(gameName: String) {
        val p = prefs ?: return
        val current = _state.value.priorityGames.toMutableList()
        if (current.any { it.equals(gameName, ignoreCase = true) }) {
            current.removeAll { it.equals(gameName, ignoreCase = true) }
        } else {
            current.add(gameName)
        }
        p.savePriorityGames(current)
        _state.update { it.copy(priorityGames = current) }
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
        } else {
            current.add(gameName)
        }
        p.saveExcludedGames(current)
        _state.update { it.copy(excludedGames = current) }
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

        val deviceId = AuthStorage(context).getOrCreateDeviceId()
        _state.update { it.copy(isRunning = true, statusText = "Запуск майнера...") }

        minerJob = scope.launch {
            val gql = TwitchGqlClient(session, deviceId, ::addLog)
            try {
                addLog("Майнер запущен (${session.login}).")
                GqlRegistry.syncFromDevilXd(TwitchAuthApi.httpClient, force = false, log = ::addLog)

                var firstLoop = true
                while (isActive) {
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
                        } catch (e: Exception) {
                            addLog("Ошибка получения кампаний: ${e.message}. Повтор через 30с.")
                            _state.update { it.copy(statusText = "Ошибка сети, повтор...") }
                            delay(30_000)
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
                                activeGameName = null,
                                activeCampaignName = null,
                                activeDrop = null
                            )
                        }
                        addLog("Нет доступных активных кампаний. Следующая проверка через 5 мин.")
                        delay(5 * 60_000L)
                        continue
                    }

                    var selectedCampaign: DropCampaignUi? = null
                    var selectedStream: LiveStreamCandidate? = null
                    val now = System.currentTimeMillis()

                    for (camp in orderedCampaigns) {
                        val streams = try {
                            gql.fetchLiveStreamsForCampaign(camp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            addLog("Ошибка поиска каналов (${camp.gameName}): ${e.message}")
                            emptyList()
                        }

                        for (s in streams.take(12)) {
                            val cooldownUntil = channelCooldowns[s.channelId] ?: 0L
                            if (cooldownUntil > now) continue

                            val supported = gql.channelSupportsCampaign(s.channelId, camp.id)
                            if (supported != false) {
                                selectedCampaign = camp
                                selectedStream = s
                                break
                            }
                            addLog("Пропуск ${s.channelLogin}: дропы кампании не активны.")
                        }
                        if (selectedStream != null) break
                    }

                    if (selectedCampaign == null || selectedStream == null) {
                        val firstCamp = orderedCampaigns.first()
                        _state.update {
                            it.copy(
                                statusText = "Нет стримов с дропами",
                                activeChannelLogin = null,
                                activeGameName = firstCamp.gameName,
                                activeCampaignName = firstCamp.name,
                                activeDrop = firstCamp.nextDrop
                            )
                        }
                        addLog("Сейчас нет живых стримов с активными дропами. Пауза 2 мин.")
                        delay(2 * 60_000L)
                        continue
                    }

                    mineStreamSession(gql, selectedCampaign, selectedStream)
                }
            } catch (_: CancellationException) {
                addLog("Майнер остановлен.")
            } catch (e: Exception) {
                addLog("Критическая ошибка майнера: ${e.message}")
            } finally {
                _state.update {
                    it.copy(
                        isRunning = false,
                        statusText = "Остановлен",
                        activeChannelLogin = null,
                        activeChannelViewers = 0
                    )
                }
            }
        }
    }

    fun stopMining() {
        minerJob?.cancel()
        minerJob = null
        _state.update {
            it.copy(
                isRunning = false,
                statusText = "Остановлен",
                activeChannelLogin = null,
                activeChannelViewers = 0
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

        val eligible = campaigns.filter { c ->
            c.isActive &&
                c.hasUnclaimedDrops &&
                c.nextDrop != null &&
                excluded.none { ex -> ex.equals(c.gameName, ignoreCase = true) } &&
                (!skipUnconnected || c.isAccountConnected)
        }
        if (eligible.isEmpty()) return emptyList()

        val ordered = mutableListOf<DropCampaignUi>()
        val priorities = _state.value.priorityGames
        for (game in priorities) {
            ordered.addAll(
                eligible.filter {
                    it !in ordered && it.gameName.equals(game, ignoreCase = true)
                }.sortedByDescending { it.isAccountConnected }
            )
        }

        if (!_state.value.priorityOnly) {
            ordered.addAll(
                eligible.filter { it !in ordered }
                    .sortedByDescending { it.isAccountConnected }
            )
        }

        return ordered
    }

    private suspend fun mineStreamSession(
        gql: TwitchGqlClient,
        campaign: DropCampaignUi,
        stream: LiveStreamCandidate
    ) {
        addLog("Смотрим ${stream.channelLogin} (${campaign.gameName}, зрителей: ${stream.viewers})")
        var playlistUrl = gql.getLowestStreamPlaylistUrl(stream.channelLogin) ?: run {
            addLog("Не удалось получить поток ${stream.channelLogin}, ищу другой канал...")
            channelCooldowns[stream.channelId] = System.currentTimeMillis() + 5 * 60_000L
            delay(10_000)
            return
        }

        var currentDrop = campaign.nextDrop
        _state.update {
            it.copy(
                statusText = "Майнинг: ${stream.channelLogin}",
                activeChannelLogin = stream.channelLogin,
                activeChannelViewers = stream.viewers,
                activeGameName = campaign.gameName,
                activeCampaignName = campaign.name,
                activeDrop = currentDrop
            )
        }

        val seenChunks = mutableSetOf<String>()
        var tick = 0
        var loggedChunksOnce = false
        var lastMinutes = currentDrop?.currentMinutes ?: 0
        var lastProgressChangeMs = System.currentTimeMillis()
        val sessionStartMs = System.currentTimeMillis()

        while (System.currentTimeMillis() - sessionStartMs < 20 * 60_000L) {
            val chunksSent = gql.sendWatchHeartbeat(playlistUrl, seenChunks)
            if (chunksSent <= 0) {
                addLog("Поток ${stream.channelLogin} прервался, переподключение...")
                val refreshed = gql.getLowestStreamPlaylistUrl(stream.channelLogin)
                if (refreshed.isNullOrBlank()) {
                    addLog("Канал ${stream.channelLogin} оффлайн. Смена канала.")
                    return
                }
                playlistUrl = refreshed
                seenChunks.clear()
                loggedChunksOnce = false
            } else if (!loggedChunksOnce) {
                addLog("Просмотр подтверждён (HEAD x$chunksSent чанков).")
                loggedChunksOnce = true
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
                        val nextDropState = activeDropSnap.copy(
                            currentMinutes = updatedMinutes,
                            requiredMinutes = if (progress.requiredMinutes > 0) progress.requiredMinutes else activeDropSnap.requiredMinutes
                        )
                        currentDrop = nextDropState
                        _state.update { it.copy(activeDrop = nextDropState) }
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
                        addLog("Отслеживается дроп «${switched.benefitName}»: ${switched.currentMinutes}/${switched.requiredMinutes} мин")
                    }
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

            delay(20_000)
        }
    }
}
