package com.tdm.android.data

import android.content.Context
import android.content.SharedPreferences

enum class PriorityMode(val title: String, val subtitle: String) {
    LIST_ORDER("По списку", "Строго по порядку вашего списка приоритетов"),
    ENDING_SOONEST("Быстрее сгорают", "Сначала кампании с ближайшим окончанием"),
    LOWEST_AVAILABILITY("Редкие каналы", "Сначала кампании, доступные у 1–нескольких стримеров")
}

enum class CampaignFilter(val label: String) {
    ALL("Все"),
    ACTIVE("Активные"),
    COMPLETED("Выполнены")
}

data class AllowedChannel(
    val id: String,
    val login: String,
    val displayName: String
)

data class TimedDropUi(
    val id: String,
    val name: String,
    val benefitId: String,
    val benefitName: String,
    val imageUrl: String?,
    val requiredMinutes: Int,
    val currentMinutes: Int,
    val isClaimed: Boolean,
    val dropInstanceId: String?,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long
) {
    val canClaim: Boolean
        get() = !isClaimed && requiredMinutes > 0 && currentMinutes >= requiredMinutes && !dropInstanceId.isNullOrBlank()

    val watchedClamped: Int
        get() = if (requiredMinutes <= 0) 0 else currentMinutes.coerceIn(0, requiredMinutes)

    val remainingMinutes: Int
        get() = if (requiredMinutes <= 0) 0 else (requiredMinutes - watchedClamped).coerceAtLeast(0)

    val progressFraction: Float
        get() = if (requiredMinutes <= 0) 0f else (watchedClamped.toFloat() / requiredMinutes.toFloat()).coerceIn(0f, 1f)

    val progressPercent: Float
        get() = progressFraction * 100f
}

data class DropCampaignUi(
    val id: String,
    val name: String,
    val gameName: String,
    val gameSlug: String,
    val gameBoxArtUrl: String?,
    val accountLinkUrl: String?,
    val status: String,
    val isAccountConnected: Boolean,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val aclEnabled: Boolean,
    val allowedChannels: List<AllowedChannel>,
    val drops: List<TimedDropUi>
) {
    val isActive: Boolean
        get() {
            val now = System.currentTimeMillis()
            return status.equals("ACTIVE", ignoreCase = true) && now in startsAtEpochMs..endsAtEpochMs
        }

    val isUpcoming: Boolean
        get() = status.equals("UPCOMING", ignoreCase = true) || System.currentTimeMillis() < startsAtEpochMs

    val hasUnclaimedDrops: Boolean
        get() = drops.any { !it.isClaimed && it.requiredMinutes > 0 }

    val claimedDropsCount: Int
        get() = drops.count { it.isClaimed }

    val totalDropsCount: Int
        get() = drops.size

    val rarityScore: Int
        get() = if (aclEnabled && allowedChannels.isNotEmpty()) allowedChannels.size else Int.MAX_VALUE

    val campaignProgressFraction: Float
        get() {
            val totalReq = drops.sumOf { it.requiredMinutes.coerceAtLeast(0) }
            if (totalReq <= 0) return 0f
            val totalWatched = drops.sumOf {
                if (it.isClaimed) it.requiredMinutes.coerceAtLeast(0) else it.watchedClamped
            }
            return (totalWatched.toFloat() / totalReq.toFloat()).coerceIn(0f, 1f)
        }

    val campaignRemainingMinutes: Int
        get() = drops.sumOf { if (it.isClaimed) 0 else it.remainingMinutes }

    val nextDrop: TimedDropUi?
        get() {
            val now = System.currentTimeMillis()
            return drops.firstOrNull {
                !it.isClaimed &&
                    it.requiredMinutes > 0 &&
                    (it.startsAtEpochMs == 0L || now >= it.startsAtEpochMs) &&
                    (it.endsAtEpochMs == 0L || now <= it.endsAtEpochMs)
            } ?: drops.firstOrNull { !it.isClaimed && it.requiredMinutes > 0 }
        }
}

data class LiveStreamCandidate(
    val channelId: String,
    val channelLogin: String,
    val displayName: String,
    val broadcastId: String,
    val gameName: String,
    val viewers: Int,
    val title: String
)

data class MinerUiState(
    val isRunning: Boolean = false,
    val statusText: String = "Подготовка...",
    val activeChannelLogin: String? = null,
    val activeChannelViewers: Int = 0,
    val activeGameName: String? = null,
    val activeCampaign: DropCampaignUi? = null,
    val activeDrop: TimedDropUi? = null,
    val channels: List<LiveStreamCandidate> = emptyList(),
    val campaigns: List<DropCampaignUi> = emptyList(),
    val priorityGames: List<String> = emptyList(),
    val excludedGames: List<String> = emptyList(),
    val priorityMode: PriorityMode = PriorityMode.LIST_ORDER,
    val priorityOnly: Boolean = false,
    val skipUnconnected: Boolean = false,
    val lastDropsFilter: CampaignFilter = CampaignFilter.ALL,
    val claimedSessionCount: Int = 0,
    val totalClaimedAllTime: Int = 0,
    val lastClaimedSeq: Long = 0L,
    val lastClaimedTitle: String = "",
    val lastClaimedBody: String = "",
    val lastCampaignsFetchMs: Long = 0L,
    val logs: List<String> = emptyList(),
    val isRefreshing: Boolean = false
)

class MinerPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tdm_settings", Context.MODE_PRIVATE)

    fun getPriorityGames(): List<String> = readList(KEY_PRIORITY_GAMES)

    fun savePriorityGames(games: List<String>) = saveList(KEY_PRIORITY_GAMES, games)

    fun getExcludedGames(): List<String> = readList(KEY_EXCLUDED_GAMES)

    fun saveExcludedGames(games: List<String>) = saveList(KEY_EXCLUDED_GAMES, games)

    fun getPriorityMode(): PriorityMode {
        val raw = prefs.getString(KEY_PRIORITY_MODE, PriorityMode.LIST_ORDER.name)
        return runCatching { PriorityMode.valueOf(raw ?: PriorityMode.LIST_ORDER.name) }
            .getOrDefault(PriorityMode.LIST_ORDER)
    }

    fun setPriorityMode(mode: PriorityMode) {
        prefs.edit().putString(KEY_PRIORITY_MODE, mode.name).apply()
    }

    fun getLastDropsFilter(): CampaignFilter {
        val raw = prefs.getString(KEY_LAST_DROPS_FILTER, CampaignFilter.ALL.name)
        return runCatching { CampaignFilter.valueOf(raw ?: CampaignFilter.ALL.name) }
            .getOrDefault(CampaignFilter.ALL)
    }

    fun setLastDropsFilter(filter: CampaignFilter) {
        prefs.edit().putString(KEY_LAST_DROPS_FILTER, filter.name).apply()
    }

    fun isPriorityOnly(): Boolean = prefs.getBoolean(KEY_PRIORITY_ONLY, false)

    fun setPriorityOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRIORITY_ONLY, enabled).apply()
    }

    fun isSkipUnconnected(): Boolean = prefs.getBoolean(KEY_SKIP_UNCONNECTED, false)

    fun setSkipUnconnected(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_UNCONNECTED, enabled).apply()
    }

    fun getTotalClaimed(): Int = prefs.getInt(KEY_TOTAL_CLAIMED, 0)

    fun incrementTotalClaimed(): Int {
        val next = getTotalClaimed() + 1
        prefs.edit().putInt(KEY_TOTAL_CLAIMED, next).apply()
        return next
    }

    private fun readList(key: String): List<String> {
        val raw = prefs.getString(key, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun saveList(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY_PRIORITY_GAMES = "priority_games"
        private const val KEY_EXCLUDED_GAMES = "excluded_games"
        private const val KEY_PRIORITY_MODE = "priority_mode"
        private const val KEY_LAST_DROPS_FILTER = "last_drops_filter"
        private const val KEY_PRIORITY_ONLY = "priority_only"
        private const val KEY_SKIP_UNCONNECTED = "skip_unconnected"
        private const val KEY_TOTAL_CLAIMED = "total_claimed_all_time"
    }
}
