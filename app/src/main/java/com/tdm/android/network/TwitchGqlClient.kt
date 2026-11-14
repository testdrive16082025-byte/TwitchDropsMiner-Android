package com.tdm.android.network

import com.tdm.android.data.AllowedChannel
import com.tdm.android.data.DropCampaignUi
import com.tdm.android.data.LiveStreamCandidate
import com.tdm.android.data.TimedDropUi
import com.tdm.android.data.TwitchSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.time.Instant

data class CurrentDropProgress(
    val dropId: String,
    val currentMinutes: Int,
    val requiredMinutes: Int
)

class TwitchGqlClient(
    private val session: TwitchSession,
    private val deviceId: String,
    private val log: (String) -> Unit
) {
    private val client = TwitchAuthApi.httpClient
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildOperationJson(key: String, variables: JsonObject): JsonObject {
        val def = GqlRegistry.get(key)
            ?: throw IllegalStateException("Неизвестный GQL-запрос: $key")
        val mergedVars = buildJsonObject {
            def.defaultVariables.forEach { (k, v) -> put(k, v) }
            variables.forEach { (k, v) -> put(k, v) }
        }
        return buildJsonObject {
            put("operationName", def.operationName)
            put("variables", mergedVars)
            put("extensions", buildJsonObject {
                put("persistedQuery", buildJsonObject {
                    put("version", 1)
                    put("sha256Hash", def.sha256Hash)
                })
            })
        }
    }

    suspend fun gqlSingle(
        key: String,
        variables: JsonObject = buildJsonObject {},
        allowRetry: Boolean = true
    ): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildOperationJson(key, variables).toString()
        val respObj = executeRawGql(payload).asObj()
            ?: throw IllegalStateException("Пустой GQL-ответ для $key")

        val errors = respObj["errors"].asArr()
        if (!errors.isNullOrEmpty()) {
            val messages = errors.mapNotNull { it.asObj()?.get("message").asStr() }
            if (allowRetry && messages.any { it.contains("PersistedQueryNotFound", ignoreCase = true) }) {
                log("GQL $key: PersistedQueryNotFound — обновляю хеши с GitHub DevilXD...")
                GqlRegistry.syncFromDevilXd(client, force = true, log = log)
                return@withContext gqlSingle(key, variables, allowRetry = false)
            }
            if (allowRetry && messages.any {
                    it.contains("service error", ignoreCase = true) ||
                        it.contains("timeout", ignoreCase = true) ||
                        it.contains("deadline", ignoreCase = true)
                }
            ) {
                delay(1500)
                return@withContext gqlSingle(key, variables, allowRetry = false)
            }
            if (respObj["data"].asObj() == null) {
                throw IllegalStateException("GQL ошибка ($key): ${messages.joinToString()}")
            }
        }
        respObj
    }

    suspend fun gqlBatch(
        items: List<Pair<String, JsonObject>>,
        allowRetry: Boolean = true
    ): List<JsonObject> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val arrayPayload = buildJsonArray {
            items.forEach { (k, v) -> add(buildOperationJson(k, v)) }
        }.toString()

        val respArr = executeRawGql(arrayPayload).asArr()
            ?: return@withContext emptyList()

        val hasNotFound = respArr.any { elem ->
            elem.asObj()?.get("errors").asArr()?.any { err ->
                err.asObj()?.get("message").asStr()?.contains("PersistedQueryNotFound", ignoreCase = true) == true
            } == true
        }
        if (allowRetry && hasNotFound) {
            log("Batch GQL: PersistedQueryNotFound — обновляю хеши с GitHub DevilXD...")
            GqlRegistry.syncFromDevilXd(client, force = true, log = log)
            return@withContext gqlBatch(items, allowRetry = false)
        }

        respArr.mapNotNull { it.asObj() }
    }

    private fun executeRawGql(jsonBody: String): JsonElement {
        val request = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("Client-Id", TwitchAuthApi.ANDROID_APP_CLIENT_ID)
            .header("Authorization", "OAuth ${session.accessToken}")
            .header("X-Device-Id", deviceId)
            .header("User-Agent", GqlRegistry.androidUserAgent)
            .header("Accept", "*/*")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} от GQL: ${body.take(180)}")
            }
            return json.parseToJsonElement(body)
        }
    }

    suspend fun fetchCampaigns(): List<DropCampaignUi> {
        val invResp = gqlSingle("Inventory")
        val inventoryObj = invResp["data"].asObj()?.get("currentUser").asObj()?.get("inventory").asObj()

        val progressMap = mutableMapOf<String, MutableMap<String, DropProgressEntry>>()
        inventoryObj?.get("dropCampaignsInProgress").asArr()?.forEach { campEl ->
            val campObj = campEl.asObj() ?: return@forEach
            val campId = campObj["id"].asStr() ?: return@forEach
            val map = progressMap.getOrPut(campId) { mutableMapOf() }
            campObj["timeBasedDrops"].asArr()?.forEach { dropEl ->
                val dropObj = dropEl.asObj() ?: return@forEach
                val dropId = dropObj["id"].asStr() ?: return@forEach
                val selfObj = dropObj["self"].asObj()
                map[dropId] = DropProgressEntry(
                    currentMinutes = selfObj?.get("currentMinutesWatched").asInt() ?: 0,
                    isClaimed = selfObj?.get("isClaimed").asBool() ?: false,
                    dropInstanceId = selfObj?.get("dropInstanceID").asStr()
                )
            }
        }

        val campResp = gqlSingle("Campaigns")
        val rawCampaigns = campResp["data"].asObj()
            ?.get("currentUser").asObj()
            ?.get("dropCampaigns").asArr()
            .orEmpty()

        val baseList = rawCampaigns.mapNotNull { it.asObj() }.filter { obj ->
            val st = obj["status"].asStr().orEmpty()
            st.equals("ACTIVE", ignoreCase = true) || st.equals("UPCOMING", ignoreCase = true)
        }

        val detailsById = mutableMapOf<String, JsonObject>()
        val chunks = baseList.mapNotNull { it["id"].asStr() }.chunked(15)
        for (chunkIds in chunks) {
            val batchReq = chunkIds.map { cid ->
                "CampaignDetails" to buildJsonObject {
                    put("channelLogin", session.userId)
                    put("dropID", cid)
                }
            }
            val batchResp = gqlBatch(batchReq)
            for (respObj in batchResp) {
                val cObj = respObj["data"].asObj()?.get("user").asObj()?.get("dropCampaign").asObj()
                    ?: continue
                val cid = cObj["id"].asStr()
                if (!cid.isNullOrBlank()) {
                    detailsById[cid] = cObj
                }
            }
        }

        val result = mutableListOf<DropCampaignUi>()
        for (base in baseList) {
            val id = base["id"].asStr() ?: continue
            val detail = detailsById[id]
            val gameObj = detail?.get("game").asObj() ?: base["game"].asObj()
            val selfObj = detail?.get("self").asObj() ?: base["self"].asObj()
            val allowObj = detail?.get("allow").asObj()

            val aclEnabled = allowObj?.get("isEnabled").asBool() ?: false
            val aclChannels = allowObj?.get("channels").asArr().orEmpty().mapNotNull { chEl ->
                val ch = chEl.asObj() ?: return@mapNotNull null
                val chId = ch["id"].asStr() ?: return@mapNotNull null
                val login = ch["name"].asStr() ?: return@mapNotNull null
                val disp = ch["displayName"].asStr() ?: login
                AllowedChannel(chId, login, disp)
            }

            val campProgress = progressMap[id].orEmpty()
            val dropsArr = detail?.get("timeBasedDrops").asArr().orEmpty()
            val drops = dropsArr.mapNotNull { dEl ->
                val dObj = dEl.asObj() ?: return@mapNotNull null
                val dropId = dObj["id"].asStr() ?: return@mapNotNull null
                val dropName = dObj["name"].asStr() ?: "Drop"
                val reqMin = dObj["requiredMinutesWatched"].asInt() ?: 0
                val prog = campProgress[dropId]
                val benefitName = dObj["benefitEdges"].asArr()?.firstOrNull()
                    ?.asObj()?.get("benefit").asObj()?.get("name").asStr()
                    ?: dropName

                val curMin = prog?.currentMinutes ?: 0
                val claimed = prog?.isClaimed ?: false
                val instanceId = prog?.dropInstanceId ?: "${session.userId}#$id#$dropId"

                TimedDropUi(
                    id = dropId,
                    name = dropName,
                    benefitName = benefitName,
                    requiredMinutes = reqMin,
                    currentMinutes = curMin,
                    isClaimed = claimed,
                    dropInstanceId = instanceId,
                    startsAtEpochMs = parseIsoTime(dObj["startAt"].asStr()),
                    endsAtEpochMs = parseIsoTime(dObj["endAt"].asStr())
                )
            }

            result.add(
                DropCampaignUi(
                    id = id,
                    name = base["name"].asStr() ?: detail?.get("name").asStr() ?: "Campaign",
                    gameName = gameObj?.get("displayName").asStr()
                        ?: gameObj?.get("name").asStr()
                        ?: "Unknown Game",
                    gameSlug = gameObj?.get("slug").asStr() ?: "",
                    status = base["status"].asStr() ?: "ACTIVE",
                    isAccountConnected = selfObj?.get("isAccountConnected").asBool() ?: true,
                    startsAtEpochMs = parseIsoTime(base["startAt"].asStr()),
                    endsAtEpochMs = parseIsoTime(base["endAt"].asStr()),
                    aclEnabled = aclEnabled,
                    allowedChannels = aclChannels,
                    drops = drops
                )
            )
        }

        return result.sortedWith(
            compareByDescending<DropCampaignUi> { it.isActive }
                .thenByDescending { it.isAccountConnected }
                .thenBy { it.endsAtEpochMs }
        )
    }

    suspend fun claimDrop(dropInstanceId: String): Boolean {
        val vars = buildJsonObject {
            put("input", buildJsonObject {
                put("dropInstanceID", dropInstanceId)
            })
        }
        val resp = gqlSingle("ClaimDrop", vars)
        val status = resp["data"].asObj()
            ?.get("claimDropRewards").asObj()
            ?.get("status").asStr()
        return status == "ELIGIBLE_FOR_ALL" || status == "DROP_INSTANCE_ALREADY_CLAIMED"
    }

    suspend fun fetchLiveStreamsForCampaign(campaign: DropCampaignUi): List<LiveStreamCandidate> {
        if (campaign.aclEnabled && campaign.allowedChannels.isNotEmpty()) {
            val candidates = mutableListOf<LiveStreamCandidate>()
            for (chunk in campaign.allowedChannels.chunked(15)) {
                val batch = chunk.map { ch ->
                    "GetStreamInfo" to buildJsonObject { put("channel", ch.login) }
                }
                val respList = gqlBatch(batch)
                respList.forEachIndexed { idx, respObj ->
                    val userObj = respObj["data"].asObj()?.get("user").asObj() ?: return@forEachIndexed
                    val streamObj = userObj["stream"].asObj() ?: return@forEachIndexed
                    val bSettings = userObj["broadcastSettings"].asObj()
                    val gameName = bSettings?.get("game").asObj()?.get("displayName").asStr().orEmpty()
                    if (gameName.isNotBlank() && !gameName.equals(campaign.gameName, ignoreCase = true)) {
                        return@forEachIndexed
                    }
                    val ch = chunk.getOrNull(idx) ?: return@forEachIndexed
                    candidates.add(
                        LiveStreamCandidate(
                            channelId = userObj["id"].asStr() ?: ch.id,
                            channelLogin = ch.login,
                            displayName = ch.displayName,
                            broadcastId = streamObj["id"].asStr() ?: "",
                            gameName = gameName.ifBlank { campaign.gameName },
                            viewers = streamObj["viewersCount"].asInt() ?: 0,
                            title = bSettings?.get("title").asStr().orEmpty()
                        )
                    )
                }
            }
            return candidates.sortedByDescending { it.viewers }
        }

        if (campaign.gameSlug.isBlank()) return emptyList()
        val vars = buildJsonObject {
            put("limit", 30)
            put("slug", campaign.gameSlug)
        }
        val resp = gqlSingle("GameDirectory", vars)
        val edges = resp["data"].asObj()
            ?.get("game").asObj()
            ?.get("streams").asObj()
            ?.get("edges").asArr()
            .orEmpty()

        return edges.mapNotNull { edgeEl ->
            val node = edgeEl.asObj()?.get("node").asObj() ?: return@mapNotNull null
            val broadcaster = node["broadcaster"].asObj() ?: return@mapNotNull null
            val chId = broadcaster["id"].asStr() ?: return@mapNotNull null
            val login = broadcaster["login"].asStr() ?: return@mapNotNull null
            val disp = broadcaster["displayName"].asStr() ?: login
            val gName = node["game"].asObj()?.get("displayName").asStr() ?: campaign.gameName
            LiveStreamCandidate(
                channelId = chId,
                channelLogin = login,
                displayName = disp,
                broadcastId = node["id"].asStr() ?: "",
                gameName = gName,
                viewers = node["viewersCount"].asInt() ?: 0,
                title = node["title"].asStr().orEmpty()
            )
        }
    }

    suspend fun fetchCurrentDrop(channelId: String): CurrentDropProgress? {
        return try {
            val resp = gqlSingle("CurrentDrop", buildJsonObject { put("channelID", channelId) })
            val cur = resp["data"].asObj()
                ?.get("currentUser").asObj()
                ?.get("dropCurrentSession").asObj() ?: return null
            val dropId = cur["dropID"].asStr() ?: return null
            CurrentDropProgress(
                dropId = dropId,
                currentMinutes = cur["currentMinutesWatched"].asInt() ?: 0,
                requiredMinutes = cur["requiredMinutesWatched"].asInt() ?: 0
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getLowestStreamPlaylistUrl(channelLogin: String): String? = withContext(Dispatchers.IO) {
        val tokenResp = runCatching {
            gqlSingle("PlaybackAccessToken", buildJsonObject { put("login", channelLogin) })
        }.getOrElse {
            executeRawPlaybackTokenFallback(channelLogin)
        }

        val tokenData = tokenResp["data"].asObj()?.get("streamPlaybackAccessToken").asObj()
            ?: return@withContext null
        val value = tokenData["value"].asStr() ?: return@withContext null
        val signature = tokenData["signature"].asStr() ?: return@withContext null

        val encodedValue = URLEncoder.encode(value, "UTF-8")
        val masterUrl =
            "https://usher.ttvnw.net/api/channel/hls/$channelLogin.m3u8" +
                "?sig=$signature&token=$encodedValue&allow_source=true&allow_audio_only=true"

        val masterReq = Request.Builder()
            .url(masterUrl)
            .header("User-Agent", GqlRegistry.androidUserAgent)
            .get()
            .build()

        client.newCall(masterReq).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string().orEmpty()
            val variantUrls = body.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("http") }
                .toList()
            variantUrls.lastOrNull()
        }
    }

    private fun executeRawPlaybackTokenFallback(channelLogin: String): JsonObject {
        val rawPayload = buildJsonObject {
            put("operationName", "PlaybackAccessToken_Template")
            put(
                "query",
                """query PlaybackAccessToken_Template(${'$'}login: String!, ${'$'}isLive: Boolean!, ${'$'}vodID: ID!, ${'$'}isVod: Boolean!, ${'$'}playerType: String!) {
                  streamPlaybackAccessToken(channelName: ${'$'}login, params: {platform: "web", playerBackend: "mediaplayer", playerType: ${'$'}playerType}) @include(if: ${'$'}isLive) {
                    value signature authorization { isForbidden forbiddenReasonCode } __typename
                  }
                  videoPlaybackAccessToken(id: ${'$'}vodID, params: {platform: "web", playerBackend: "mediaplayer", playerType: ${'$'}playerType}) @include(if: ${'$'}isVod) {
                    value signature __typename
                  }
                }""".trimIndent()
            )
            put("variables", buildJsonObject {
                put("isLive", true)
                put("login", channelLogin)
                put("isVod", false)
                put("vodID", "")
                put("playerType", "site")
            })
        }.toString()
        return executeRawGql(rawPayload).asObj() ?: buildJsonObject {}
    }

    suspend fun sendWatchHeartbeat(playlistUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val listReq = Request.Builder()
                .url(playlistUrl)
                .header("Connection", "close")
                .header("User-Agent", GqlRegistry.androidUserAgent)
                .get()
                .build()

            val chunkUrl = client.newCall(listReq).execute().use { resp ->
                if (resp.code >= 400) return@withContext false
                val text = resp.body?.string().orEmpty()
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .lastOrNull()
            } ?: return@withContext false

            val resolvedChunkUrl = if (chunkUrl.startsWith("http")) {
                chunkUrl
            } else {
                playlistUrl.substringBeforeLast('/') + "/" + chunkUrl
            }

            val headReq = Request.Builder()
                .url(resolvedChunkUrl)
                .header("Connection", "close")
                .header("User-Agent", GqlRegistry.androidUserAgent)
                .head()
                .build()

            client.newCall(headReq).execute().use { headResp ->
                headResp.code < 400
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseIsoTime(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
    }

    private data class DropProgressEntry(
        val currentMinutes: Int,
        val isClaimed: Boolean,
        val dropInstanceId: String?
    )

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asStr(): String? {
        val prim = this as? JsonPrimitive ?: return null
        if (!prim.isString && prim.content == "null") return null
        return prim.contentOrNull
    }
    private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
