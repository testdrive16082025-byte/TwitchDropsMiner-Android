package com.tdm.android.data

import android.content.Context
import android.content.SharedPreferences

data class AllowedChannel(
    val id: String,
    val login: String,
    val displayName: String
)

data class TimedDropUi(
    val id: String,
    val name: String,
    val benefitName: String,
    val requiredMinutes: Int,
    val currentMinutes: Int,
    val isClaimed: Boolean,
    val dropInstanceId: String?,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long
) {
    val canClaim: Boolean
        get() = !isClaimed && requiredMinutes > 0 && currentMinutes >= requiredMinutes && !dropInstanceId.isNullOrBlank()

    val isCompleted: Boolean
        get() = isClaimed

    val progressFraction: Float
        get() = if (requiredMinutes <= 0) 0f else (currentMinutes.toFloat() / requiredMinutes.toFloat()).coerceIn(0f, 1f)
}

data class DropCampaignUi(
    val id: String,
    val name: String,
    val gameName: String,
    val gameSlug: String,
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

    val hasUnclaimedDrops: Boolean
        get() = drops.any { !it.isClaimed && it.requiredMinutes > 0 }

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
    val statusText: String = "Остановлен",
    val activeChannelLogin: String? = null,
    val activeChannelViewers: Int = 0,
    val activeGameName: String? = null,
    val activeCampaignName: String? = null,
    val activeDrop: TimedDropUi? = null,
    val campaigns: List<DropCampaignUi> = emptyList(),
    val priorityGames: List<String> = emptyList(),
    val priorityOnly: Boolean = false,
    val logs: List<String> = emptyList(),
    val isRefreshing: Boolean = false
)

class MinerPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tdm_settings", Context.MODE_PRIVATE)

    fun getPriorityGames(): List<String> {
        val raw = prefs.getString(KEY_PRIORITY_GAMES, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun savePriorityGames(games: List<String>) {
        prefs.edit().putString(KEY_PRIORITY_GAMES, games.joinToString("\n")).apply()
    }

    fun isPriorityOnly(): Boolean = prefs.getBoolean(KEY_PRIORITY_ONLY, false)

    fun setPriorityOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRIORITY_ONLY, enabled).apply()
    }

    companion object {
        private const val KEY_PRIORITY_GAMES = "priority_games"
        private const val KEY_PRIORITY_ONLY = "priority_only"
    }
}
