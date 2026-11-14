package com.tdm.android.data

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

data class TwitchSession(
    val accessToken: String,
    val userId: String,
    val login: String,
    val clientId: String,
    val expiresInSeconds: Long
)

class AuthStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tdm_auth", Context.MODE_PRIVATE)

    fun saveSession(session: TwitchSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_LOGIN, session.login)
            .putString(KEY_CLIENT_ID, session.clientId)
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val chars = "0123456789abcdef"
        val random = SecureRandom()
        val newId = buildString(32) {
            repeat(32) { append(chars[random.nextInt(chars.length)]) }
        }
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_LOGIN)
            .remove(KEY_CLIENT_ID)
            .apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LOGIN = "login"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
