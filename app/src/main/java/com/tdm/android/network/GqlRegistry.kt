package com.tdm.android.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

data class GqlQueryDef(
    val key: String,
    val operationName: String,
    val sha256Hash: String,
    val defaultVariables: JsonObject = buildJsonObject {}
)

object GqlRegistry {
    private const val DEVILXD_CONSTANTS_URL =
        "https://raw.githubusercontent.com/DevilXD/TwitchDropsMiner/master/constants.py"

    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()

    @Volatile
    var androidUserAgent: String =
        "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240905.003) tv.twitch.android.app/21.3.0/2103000"
        private set

    @Volatile
    var hasSyncedOnce: Boolean = false
        private set

    private val queries = linkedMapOf(
        "Inventory" to GqlQueryDef(
            key = "Inventory",
            operationName = "Inventory",
            sha256Hash = "e0765ebaa8e8eeb4043cc6dfeab3eac7f682ef5f724b81367e6e55c7aef2be4c",
            defaultVariables = buildJsonObject { put("fetchRewardCampaigns", false) }
        ),
        "Campaigns" to GqlQueryDef(
            key = "Campaigns",
            operationName = "ViewerDropsDashboard",
            sha256Hash = "5a4da2ab3d5b47c9f9ce864e727b2cb346af1e3ea8b897fe8f704a97ff017619",
            defaultVariables = buildJsonObject { put("fetchRewardCampaigns", false) }
        ),
        "CampaignDetails" to GqlQueryDef(
            key = "CampaignDetails",
            operationName = "DropCampaignDetails",
            sha256Hash = "e7acdecb05429a62f5984bdcb27ee938ae20543579bf73c3ae44e7c822bc4f54"
        ),
        "AvailableDrops" to GqlQueryDef(
            key = "AvailableDrops",
            operationName = "DropsHighlightService_AvailableDrops",
            sha256Hash = "9a62a09bce5b53e26e64a671e530bc599cb6aab1e5ba3cbd5d85966d3940716f"
        ),
        "CurrentDrop" to GqlQueryDef(
            key = "CurrentDrop",
            operationName = "DropCurrentSessionContext",
            sha256Hash = "2e4b3630b91552eb05b76a94b6850eb25fe42263b7cf6d06bee6d156dd247c1c"
        ),
        "ClaimDrop" to GqlQueryDef(
            key = "ClaimDrop",
            operationName = "DropsPage_ClaimDropRewards",
            sha256Hash = "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930"
        ),
        "GameDirectory" to GqlQueryDef(
            key = "GameDirectory",
            operationName = "DirectoryPage_Game",
            sha256Hash = "c7c9d5aad09155c4161d2382092dc44610367f3536aac390105c02b24e3554c6",
            defaultVariables = buildJsonObject {
                put("limit", 30)
                put("slug", "")
                put("imageWidth", 50)
                put("includeIsDJ", false)
                put("sortTypeIsRecency", false)
                put("options", buildJsonObject {
                    put("includeRestricted", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("SUB_ONLY_LIVE")) })
                    put("systemFilters", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("DROPS_ENABLED")) })
                    put("sort", "VIEWER_COUNT")
                })
            }
        ),
        "GetStreamInfo" to GqlQueryDef(
            key = "GetStreamInfo",
            operationName = "VideoPlayerStreamInfoOverlayChannel",
            sha256Hash = "a5f2e34d626a9f4f5c0204f910bab2194948a9502089be558bb6e779a9e1b3d2"
        ),
        "PlaybackAccessToken" to GqlQueryDef(
            key = "PlaybackAccessToken",
            operationName = "PlaybackAccessToken_Template",
            sha256Hash = "0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712",
            defaultVariables = buildJsonObject {
                put("isLive", true)
                put("vodID", "")
                put("isVod", false)
                put("playerType", "site")
            }
        )
    )

    fun get(key: String): GqlQueryDef? = synchronized(queries) { queries[key] }

    suspend fun syncFromDevilXd(client: OkHttpClient, force: Boolean, log: (String) -> Unit): Boolean {
        if (hasSyncedOnce && !force) return true
        return syncMutex.withLock {
            if (hasSyncedOnce && !force) return@withLock true
            withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(DEVILXD_CONSTANTS_URL).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            log("Не удалось загрузить constants.py (HTTP ${resp.code}), использую встроенные хеши.")
                            return@withContext false
                        }
                        val pyText = resp.body?.string().orEmpty()
                        val updatedCount = parsePythonConstants(pyText)
                        hasSyncedOnce = true
                        log("Синхронизация GQL с DevilXD: обновлено $updatedCount запросов.")
                        updatedCount > 0
                    }
                } catch (e: Exception) {
                    log("Ошибка синхронизации constants.py: ${e.message}")
                    false
                }
            }
        }
    }

    private fun parsePythonConstants(text: String): Int {
        val uaRegex = Regex("""Dalvik/[^"'\r\n]+tv\.twitch\.android\.app[^"'\r\n]*""")
        uaRegex.find(text)?.value?.let { androidUserAgent = it }

        val entryHeader = Regex(""""([A-Za-z0-9_]+)"\s*:\s*GQL\w*\(""")
        var count = 0

        for (match in entryHeader.findAll(text)) {
            val key = match.groupValues[1]
            val openParenIdx = match.range.last
            val blockEnd = findBalancedEnd(text, openParenIdx, '(', ')') ?: continue
            val block = text.substring(openParenIdx + 1, blockEnd)

            val strings = extractQuotedStrings(block)
            if (strings.size < 2) continue
            val opName = strings[0]
            val hash = strings[1]
            if (!hash.matches(Regex("^[a-fA-F0-9]{64}$"))) continue

            var varsObj: JsonObject? = null
            val braceStart = block.indexOf('{')
            if (braceStart >= 0) {
                val braceEnd = findBalancedEnd(block, braceStart, '{', '}')
                if (braceEnd != null) {
                    val pyDict = block.substring(braceStart, braceEnd + 1)
                    varsObj = convertPythonDictToJson(pyDict)
                }
            }

            synchronized(queries) {
                val existingVars = queries[key]?.defaultVariables ?: buildJsonObject {}
                queries[key] = GqlQueryDef(
                    key = key,
                    operationName = opName,
                    sha256Hash = hash,
                    defaultVariables = varsObj ?: existingVars
                )
            }
            count++
        }
        return count
    }

    private fun findBalancedEnd(text: String, startIdx: Int, openCh: Char, closeCh: Char): Int? {
        var depth = 0
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (i in startIdx until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if ((inSingle || inDouble) && c == '\\') {
                escaped = true
                continue
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle
                continue
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble
                continue
            }
            if (inSingle || inDouble) continue
            if (c == openCh) depth++
            else if (c == closeCh) {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }

    private fun extractQuotedStrings(block: String): List<String> {
        val result = mutableListOf<String>()
        val strRegex = Regex("""["']([^"']+)["']""")
        for (m in strRegex.findAll(block)) {
            result.add(m.groupValues[1])
            if (result.size >= 2) break
        }
        return result
    }

    private fun convertPythonDictToJson(pyDict: String): JsonObject? {
        return runCatching {
            val cleaned = StringBuilder()
            var i = 0
            var inSingle = false
            var inDouble = false
            var escaped = false

            while (i < pyDict.length) {
                val c = pyDict[i]
                if (escaped) {
                    cleaned.append(c)
                    escaped = false
                    i++
                    continue
                }
                if (inSingle) {
                    if (c == '\\') {
                        cleaned.append(c)
                        escaped = true
                    } else if (c == '\'') {
                        cleaned.append('"')
                        inSingle = false
                    } else if (c == '"') {
                        cleaned.append("\\\"")
                    } else {
                        cleaned.append(c)
                    }
                    i++
                    continue
                }
                if (inDouble) {
                    if (c == '\\') {
                        cleaned.append(c)
                        escaped = true
                    } else if (c == '"') {
                        cleaned.append('"')
                        inDouble = false
                    } else {
                        cleaned.append(c)
                    }
                    i++
                    continue
                }
                if (c == '\'') {
                    cleaned.append('"')
                    inSingle = true
                    i++
                    continue
                }
                if (c == '"') {
                    cleaned.append('"')
                    inDouble = true
                    i++
                    continue
                }
                if (c == '#') {
                    while (i < pyDict.length && pyDict[i] != '\n') i++
                    continue
                }
                if (pyDict.startsWith("...", i)) {
                    cleaned.append("\"\"")
                    i += 3
                    continue
                }
                if (c.isLetter()) {
                    val start = i
                    while (i < pyDict.length && (pyDict[i].isLetterOrDigit() || pyDict[i] == '_')) i++
                    val word = pyDict.substring(start, i)
                    when (word) {
                        "True" -> cleaned.append("true")
                        "False" -> cleaned.append("false")
                        "None" -> cleaned.append("null")
                        else -> cleaned.append(word)
                    }
                    continue
                }
                cleaned.append(c)
                i++
            }

            val noTrailingCommas = cleaned.toString().replace(Regex(""",\s*([}\]])"""), "$1")
            json.parseToJsonElement(noTrailingCommas).jsonObject
        }.getOrNull()
    }
}
