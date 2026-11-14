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

    fun initPreferences(context: Context) {
        if (prefs == null) {
            prefs = MinerPreferences(context.applicationContext)
        }
        val p = prefs ?: return
        _state.update {
            it.copy(
                priorityGames = p.getPriorityGames(),
                priorityOnly = p.isPriorityOnly()
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

    fun setPriorityOnly(enabled: Boolean) {
        val p = prefs ?: return
        p.setPriorityOnly(enabled)
        _state.update { it.copy(priorityOnly = enabled) }
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
                _state.update { it.copy(campaigns = campaigns) }
                addLog("Загружено кампаний: ${campaigns.size} (активных: ${campaigns.count { c -> c.isActive }}).")
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

                while (isActive) {
                    _state.update { it.copy(statusText = "Загрузка кампаний...") }
                    val campaigns = try {
                        gql.fetchCampaigns()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        addLog("Ошибка получения кампаний: ${e.message}. Повтор через 30с.")
                        _state.update { it.copy(statusText = "Ошибка сети, повтор...") }
                        delay(30_000)
                        continue
                    }

                    _state.update { it.copy(campaigns = campaigns) }
                    claimReadyDrops(gql, campaigns)

                    val orderedCampaigns = orderEligibleCampaigns(campaigns)
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

                    for (camp in orderedCampaigns) {
                        val streams = try {
                            gql.fetchLiveStreamsForCampaign(camp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            addLog("Ошибка поиска каналов (${camp.gameName}): ${e.message}")
                            emptyList()
                        }

                        for (s in streams.take(10)) {
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

    private suspend fun claimReadyDrops(gql: TwitchGqlClient, campaigns: List<DropCampaignUi>) {
        for (camp in campaigns) {
            for (drop in camp.drops) {
                val instanceId = drop.dropInstanceId
                if (drop.canClaim && !instanceId.isNullOrBlank()) {
                    try {
                        if (gql.claimDrop(instanceId)) {
                            addLog("ЗАБРАН ДРОП: ${drop.benefitName} (${camp.gameName})")
                        }
                    } catch (e: Exception) {
                        addLog("Не удалось забрать ${drop.benefitName}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun orderEligibleCampaigns(campaigns: List<DropCampaignUi>): List<DropCampaignUi> {
        val eligible = campaigns.filter { c ->
            c.isActive && c.hasUnclaimedDrops && c.nextDrop != null
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

        var tick = 0
        var loggedChunksOnce = false
        val sessionStartMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - sessionStartMs < 20 * 60_000L) {
            val chunksSent = gql.sendWatchHeartbeat(playlistUrl)
            if (chunksSent <= 0) {
                addLog("Поток ${stream.channelLogin} прервался, переподключение...")
                val refreshed = gql.getLowestStreamPlaylistUrl(stream.channelLogin)
                if (refreshed.isNullOrBlank()) {
                    addLog("Канал ${stream.channelLogin} оффлайн. Смена канала.")
                    return
                }
                playlistUrl = refreshed
                loggedChunksOnce = false
            } else if (!loggedChunksOnce) {
                addLog("Просмотр подтверждён (HEAD x$chunksSent чанков).")
                loggedChunksOnce = true
            }

            tick++
            if (tick % 3 == 0 && currentDrop != null) {
                val progress = gql.fetchCurrentDrop(stream.channelId)
                if (progress != null) {
                    val matchedInCampaign = campaign.drops.firstOrNull { it.id == progress.dropId }
                    if (progress.dropId == currentDrop.id) {
                        val updatedMinutes = maxOf(currentDrop.currentMinutes, progress.currentMinutes)
                        val changed = updatedMinutes != currentDrop.currentMinutes
                        currentDrop = currentDrop.copy(
                            currentMinutes = updatedMinutes,
                            requiredMinutes = if (progress.requiredMinutes > 0) progress.requiredMinutes else currentDrop.requiredMinutes
                        )
                        _state.update { it.copy(activeDrop = currentDrop) }
                        if (changed) {
                            addLog("Прогресс «${currentDrop.benefitName}»: ${currentDrop.currentMinutes}/${currentDrop.requiredMinutes} мин")
                        }
                        if (currentDrop.currentMinutes >= currentDrop.requiredMinutes && currentDrop.requiredMinutes > 0) {
                            addLog("Дроп готов к получению! Забираю...")
                            return
                        }
                    } else if (matchedInCampaign != null && !matchedInCampaign.isClaimed) {
                        currentDrop = matchedInCampaign.copy(
                            currentMinutes = maxOf(matchedInCampaign.currentMinutes, progress.currentMinutes)
                        )
                        _state.update { it.copy(activeDrop = currentDrop) }
                        addLog("Отслеживается дроп «${currentDrop.benefitName}»: ${currentDrop.currentMinutes}/${currentDrop.requiredMinutes} мин")
                    }
                }
            }

            if (tick % 6 == 0 && currentDrop != null) {
                val invSnap = gql.fetchDropProgressFromInventory(campaign.id, currentDrop.id)
                if (invSnap != null) {
                    val updatedMinutes = maxOf(currentDrop.currentMinutes, invSnap.currentMinutes)
                    val changed = updatedMinutes != currentDrop.currentMinutes
                    currentDrop = currentDrop.copy(
                        currentMinutes = updatedMinutes,
                        isClaimed = invSnap.isClaimed,
                        dropInstanceId = invSnap.dropInstanceId ?: currentDrop.dropInstanceId
                    )
                    _state.update { it.copy(activeDrop = currentDrop) }
                    if (changed) {
                        addLog("Инвентарь «${currentDrop.benefitName}»: ${currentDrop.currentMinutes}/${currentDrop.requiredMinutes} мин")
                    }
                    if (invSnap.isClaimed || (currentDrop.requiredMinutes > 0 && currentDrop.currentMinutes >= currentDrop.requiredMinutes)) {
                        addLog("Дроп выполнен! Обновляю и забираю...")
                        return
                    }
                }
            }

            delay(20_000)
        }
    }
}
