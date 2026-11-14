package com.tdm.android.network

import com.tdm.android.data.TwitchSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
private data class ValidateResponse(
    val client_id: String = "",
    val login: String? = null,
    val user_id: String? = null,
    val expires_in: Long = 0
)

object TwitchAuthApi {
    const val ANDROID_APP_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"

    const val OAUTH_AUTHORIZE_URL =
        "https://www.twitch.tv/login" +
            "?client_id=$ANDROID_APP_CLIENT_ID" +
            "&redirect_params=client_id%3D$ANDROID_APP_CLIENT_ID" +
            "%26redirect_uri%3Dhttps%253A%252F%252Fwww.twitch.tv%252F" +
            "%26response_type%3Dtoken"

    private const val VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"

    private val json = Json { ignoreUnknownKeys = true }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun validateToken(rawToken: String): Result<TwitchSession> = withContext(Dispatchers.IO) {
        val token = rawToken.trim().removePrefix("OAuth ").removePrefix("Bearer ")
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Пустой токен"))
        }

        try {
            val request = Request.Builder()
                .url(VALIDATE_URL)
                .header("Authorization", "OAuth $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) {
                    return@withContext Result.failure(
                        IllegalStateException("Токен недействителен или истёк (401)")
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Ошибка проверки токена: HTTP ${response.code}")
                    )
                }

                val parsed = json.decodeFromString<ValidateResponse>(body)
                val userId = parsed.user_id
                val login = parsed.login
                if (userId.isNullOrBlank() || login.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Токен не содержит user_id или login")
                    )
                }

                Result.success(
                    TwitchSession(
                        accessToken = token,
                        userId = userId,
                        login = login,
                        clientId = parsed.client_id,
                        expiresInSeconds = parsed.expires_in
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
