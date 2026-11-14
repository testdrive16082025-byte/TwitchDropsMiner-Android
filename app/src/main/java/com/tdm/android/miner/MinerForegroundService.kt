package com.tdm.android.miner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tdm.android.MainActivity
import com.tdm.android.R
import com.tdm.android.data.AuthStorage
import com.tdm.android.data.MinerUiState
import com.tdm.android.data.TwitchSession
import com.tdm.android.network.TwitchAuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MinerForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserveJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MinerRepository.stopMining()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotif = buildNotification(MinerRepository.state.value)
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotif, fgType)
        acquireWakeLock()

        val session = readSessionFromPrefs()
        if (session == null) {
            MinerRepository.addLog("Не найдена сессия для фонового запуска.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        MinerRepository.startMining(applicationContext, session)

        if (stateObserveJob == null) {
            stateObserveJob = serviceScope.launch {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                MinerRepository.state.collect { uiState ->
                    nm.notify(NOTIFICATION_ID, buildNotification(uiState))
                    if (!uiState.isRunning && MinerRepository.state.value.statusText == "Остановлен") {
                        // Do not auto-stop during initial transition
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stateObserveJob?.cancel()
        MinerRepository.stopMining()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun readSessionFromPrefs(): TwitchSession? {
        val prefs = getSharedPreferences("tdm_auth", Context.MODE_PRIVATE)
        val token = prefs.getString("access_token", null) ?: return null
        val userId = prefs.getString("user_id", null) ?: return null
        val login = prefs.getString("login", null) ?: return null
        val clientId = prefs.getString("client_id", TwitchAuthApi.ANDROID_APP_CLIENT_ID)
            ?: TwitchAuthApi.ANDROID_APP_CLIENT_ID
        return TwitchSession(token, userId, login, clientId, 0L)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TwitchDropsMiner::WakeLock").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновый майнинг дропов",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает статус и прогресс текущего Twitch Drop"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(state: MinerUiState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MinerForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (!state.activeGameName.isNullOrBlank()) {
            "${state.activeGameName} • ${state.activeChannelLogin ?: ""}"
        } else {
            "Twitch Drops Miner"
        }

        val drop = state.activeDrop
        val text = if (drop != null && drop.requiredMinutes > 0) {
            "${drop.benefitName}: ${drop.currentMinutes}/${drop.requiredMinutes} мин"
        } else {
            state.statusText
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "Остановить", stopPending)
            .build()
    }

    companion object {
        const val ACTION_START = "com.tdm.android.action.START"
        const val ACTION_STOP = "com.tdm.android.action.STOP"
        private const val CHANNEL_ID = "tdm_miner_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
