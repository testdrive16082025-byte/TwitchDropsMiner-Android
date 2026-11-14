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

                    val targetCampaign = pickTargetCampaign(campaigns)
                    if (targetCampaign == null) {
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

                    val streams = try {
                        gql.fetchLiveStreamsForCampaign(targetCampaign)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        addLog("Ошибка поиска каналов для ${targetCampaign.gameName}: ${e.message}")
                        emptyList()
                    }

                    val candidate = streams.firstOrNull()
                    if (candidate == null) {
                        _state.update {
                            it.copy(
                                statusText = "Нет онлайн-стримов: ${targetCampaign.gameName}",
                                activeChannelLogin = null,
                                activeGameName = targetCampaign.gameName,
                                activeCampaignName = targetCampaign.name,
                                activeDrop = targetCampaign.nextDrop
                            )
                        }
                        addLog("Для «${targetCampaign.gameName}» нет живых стримов. Пауза 2 мин.")
                        delay(2 * 60_000L)
                        continue
                    }

                    mineStreamSession(gql, targetCampaign, candidate)
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

    private fun pickTargetCampaign(campaigns: List<DropCampaignUi>): DropCampaignUi? {
        val eligible = campaigns.filter { c ->
            c.isActive && c.hasUnclaimedDrops && c.nextDrop != null
        }
        if (eligible.isEmpty()) return null

        val priorities = _state.value.priorityGames
        for (game in priorities) {
            val match = eligible.firstOrNull {
                it.isAccountConnected && it.gameName.equals(game, ignoreCase = true)
            } ?: eligible.firstOrNull {
                it.gameName.equals(game, ignoreCase = true)
            }
            if (match != null) return match
        }

        if (_state.value.priorityOnly) return null

        return eligible.firstOrNull { it.isAccountConnected } ?: eligible.firstOrNull()
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
        val sessionStartMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - sessionStartMs < 20 * 60_000L) {
            val ok = gql.sendWatchHeartbeat(playlistUrl)
            if (!ok) {
                addLog("Поток ${stream.channelLogin} прервался, переподключение...")
                val refreshed = gql.getLowestStreamPlaylistUrl(stream.channelLogin)
                if (refreshed.isNullOrBlank()) {
                    addLog("Канал ${stream.channelLogin} оффлайн. Смена канала.")
                    return
                }
                playlistUrl = refreshed
            }

            tick++
            if (tick % 3 == 0) {
                val progress = gql.fetchCurrentDrop(stream.channelId)
                if (progress != null && currentDrop != null) {
                    if (progress.dropId == currentDrop.id) {
                        currentDrop = currentDrop.copy(
                            currentMinutes = progress.currentMinutes,
                            requiredMinutes = if (progress.requiredMinutes > 0) progress.requiredMinutes else currentDrop.requiredMinutes
                        )
                        _state.update { it.copy(activeDrop = currentDrop) }
                        addLog("Прогресс «${currentDrop.benefitName}»: ${currentDrop.currentMinutes}/${currentDrop.requiredMinutes} мин")
                        if (currentDrop.currentMinutes >= currentDrop.requiredMinutes && currentDrop.requiredMinutes > 0) {
                            addLog("Дроп готов к получению! Обновляю инвентарь и забираю...")
                            return
                        }
                    } else {
                        addLog("Переключение на следующий дроп кампании...")
                        return
                    }
                }
            }

            delay(20_000)
        }
    }
}
