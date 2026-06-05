package com.nbks.famichibi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "famichibi_settings")

@Serializable
data class DecorationItem(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1.0f,
    val rotation: Float = 0f
)

@Serializable
data class AgentConfig(
    val id: String,
    val name: String,
    val personality: String = ""
)

@Serializable
data class QuickPhrase(
    val text: String
)

@Serializable
data class ServerConfig(
    val id: String = "default",
    val name: String = "Default",
    val url: String = "http://10.0.2.2:8000",
    val password: String = ""
)

class PreferencesRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        val SERVERS = stringPreferencesKey("servers")
        val ROOM_ID = stringPreferencesKey("room_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ID = stringPreferencesKey("user_id")
        val VRM_PATH = stringPreferencesKey("vrm_path")
        val MY_VRM_PATH = stringPreferencesKey("my_vrm_path")
        val SELECTED_ASSET_VRM = stringPreferencesKey("selected_asset_vrm")
        val DECORATIONS = stringPreferencesKey("decorations")
        val AGENTS = stringPreferencesKey("agents")
        val QUICK_PHRASES = stringPreferencesKey("quick_phrases")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
        val QUIET_ENABLED = stringPreferencesKey("quiet_enabled")
        val TTS_ENABLED = stringPreferencesKey("tts_enabled")
        val VOICE_INPUT_ENABLED = stringPreferencesKey("voice_input_enabled")
        val LAST_BRIEFING_DATE = stringPreferencesKey("last_briefing_date")
        val VOICEVOX_SPEAKER = intPreferencesKey("voicevox_speaker")
        val AI_ENABLED = stringPreferencesKey("ai_enabled")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "http://10.0.2.2:8000" }
    val activeServerId: Flow<String> = context.dataStore.data.map { it[ACTIVE_SERVER_ID] ?: "default" }
    val servers: Flow<List<ServerConfig>> = context.dataStore.data.map {
        val str = it[SERVERS] ?: "[]"
        try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }
    val roomId: Flow<String> = context.dataStore.data.map { it[ROOM_ID] ?: "" }
    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME] ?: "お兄ちゃん" }
    val userId: Flow<String> = context.dataStore.data.map { it[USER_ID] ?: "" }
    val vrmPath: Flow<String> = context.dataStore.data.map { it[VRM_PATH] ?: "" }
    val myVrmPath: Flow<String> = context.dataStore.data.map { it[MY_VRM_PATH] ?: "" }
    val selectedAssetVrm: Flow<String> = context.dataStore.data.map { it[SELECTED_ASSET_VRM] ?: "" }
    val decorations: Flow<List<DecorationItem>> = context.dataStore.data.map {
        val str = it[DECORATIONS] ?: "[]"
        try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }
    val agents: Flow<List<AgentConfig>> = context.dataStore.data.map {
        val str = it[AGENTS] ?: "[]"
        try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }
    val quickPhrases: Flow<List<String>> = context.dataStore.data.map {
        val str = it[QUICK_PHRASES] ?: "[]"
        try { json.decodeFromString<List<String>>(str) } catch (_: Exception) {
            listOf("おはよう", "ただいま", "おかえり", "ありがとう", "がんばって")
        }
    }
    val quietHoursStart: Flow<Int> = context.dataStore.data.map { it[QUIET_HOURS_START] ?: 0 }
    val quietHoursEnd: Flow<Int> = context.dataStore.data.map { it[QUIET_HOURS_END] ?: 7 }
    val quietEnabled: Flow<Boolean> = context.dataStore.data.map { it[QUIET_ENABLED] != "0" }
    val ttsEnabled: Flow<Boolean> = context.dataStore.data.map { it[TTS_ENABLED] != "0" }
    val voiceInputEnabled: Flow<Boolean> = context.dataStore.data.map { it[VOICE_INPUT_ENABLED] != "0" }
    val lastBriefingDate: Flow<String> = context.dataStore.data.map { it[LAST_BRIEFING_DATE] ?: "" }
    val voicevoxSpeaker: Flow<Int> = context.dataStore.data.map { it[VOICEVOX_SPEAKER] ?: 58 }
    val aiEnabled: Flow<Boolean> = context.dataStore.data.map { it[AI_ENABLED] != "0" }

    suspend fun setServerUrl(url: String) { context.dataStore.edit { it[SERVER_URL] = url } }
    suspend fun setActiveServerId(id: String) { context.dataStore.edit { it[ACTIVE_SERVER_ID] = id } }
    suspend fun setServers(list: List<ServerConfig>) { context.dataStore.edit { it[SERVERS] = json.encodeToString(list) } }
    suspend fun setRoomId(id: String) { context.dataStore.edit { it[ROOM_ID] = id } }
    suspend fun setUserName(name: String) { context.dataStore.edit { it[USER_NAME] = name } }
    suspend fun setUserId(id: String) { context.dataStore.edit { it[USER_ID] = id } }
    suspend fun setVrmPath(path: String) { context.dataStore.edit { it[VRM_PATH] = path } }
    suspend fun setMyVrmPath(path: String) { context.dataStore.edit { it[MY_VRM_PATH] = path } }
    suspend fun setSelectedAssetVrm(name: String) { context.dataStore.edit { it[SELECTED_ASSET_VRM] = name } }
    suspend fun setDecorations(items: List<DecorationItem>) { context.dataStore.edit { it[DECORATIONS] = json.encodeToString(items) } }
    suspend fun setAgents(agents: List<AgentConfig>) { context.dataStore.edit { it[AGENTS] = json.encodeToString(agents) } }
    suspend fun setQuickPhrases(phrases: List<String>) { context.dataStore.edit { it[QUICK_PHRASES] = json.encodeToString(phrases) } }
    suspend fun setQuietHours(start: Int, end: Int) {
        context.dataStore.edit { it[QUIET_HOURS_START] = start; it[QUIET_HOURS_END] = end }
    }
    suspend fun setQuietEnabled(enabled: Boolean) {
        context.dataStore.edit { it[QUIET_ENABLED] = if (enabled) "1" else "0" }
    }
    suspend fun setTtsEnabled(enabled: Boolean) { context.dataStore.edit { it[TTS_ENABLED] = if (enabled) "1" else "0" } }
    suspend fun setVoiceInputEnabled(enabled: Boolean) { context.dataStore.edit { it[VOICE_INPUT_ENABLED] = if (enabled) "1" else "0" } }
    suspend fun setLastBriefingDate(date: String) { context.dataStore.edit { it[LAST_BRIEFING_DATE] = date } }
    suspend fun setVoicevoxSpeaker(speaker: Int) { context.dataStore.edit { it[VOICEVOX_SPEAKER] = speaker } }
    suspend fun setAiEnabled(enabled: Boolean) { context.dataStore.edit { it[AI_ENABLED] = if (enabled) "1" else "0" } }
}
