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
data class HostConfig(
    val id: String,
    val name: String,
    val url: String
)

@Serializable
data class ServerMembership(
    val serverId: String,
    val hostId: String,
    val serverName: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    val joinedAt: String = "",
    val role: String? = null
)

@Serializable
data class QuickPhrase(
    val text: String
)

class PreferencesRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val HOSTS = stringPreferencesKey("hosts")
        val SERVER_MEMBERSHIPS = stringPreferencesKey("server_memberships")
        val ACTIVE_HOST_ID = stringPreferencesKey("active_host_id")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        val ACTIVE_CHANNEL_ID = stringPreferencesKey("active_channel_id")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val VRM_PATH = stringPreferencesKey("vrm_path")
        val MY_VRM_PATH = stringPreferencesKey("my_vrm_path")
        val SELECTED_ASSET_VRM = stringPreferencesKey("selected_asset_vrm")
        val DECORATIONS = stringPreferencesKey("decorations")
        val QUICK_PHRASES = stringPreferencesKey("quick_phrases")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
        val QUIET_ENABLED = stringPreferencesKey("quiet_enabled")
        val TTS_ENABLED = stringPreferencesKey("tts_enabled")
        val VOICE_INPUT_ENABLED = stringPreferencesKey("voice_input_enabled")
        val VOICEVOX_SPEAKER = intPreferencesKey("voicevox_speaker")
        val LAST_BRIEFING_DATE = stringPreferencesKey("last_briefing_date")
    }

    val hosts: Flow<List<HostConfig>> = context.dataStore.data.map {
        val str = it[HOSTS] ?: "[]"
        try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }
    val serverMemberships: Flow<List<ServerMembership>> = context.dataStore.data.map {
        val str = it[SERVER_MEMBERSHIPS] ?: "[]"
        try { json.decodeFromString(str) } catch (_: Exception) { emptyList() }
    }
    val userId: Flow<String> = context.dataStore.data.map { it[USER_ID] ?: "" }
    val activeHostId: Flow<String> = context.dataStore.data.map { it[ACTIVE_HOST_ID] ?: "" }
    val activeServerId: Flow<String> = context.dataStore.data.map { it[ACTIVE_SERVER_ID] ?: "" }
    val activeChannelId: Flow<String> = context.dataStore.data.map { it[ACTIVE_CHANNEL_ID] ?: "" }
    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME] ?: "" }
    val vrmPath: Flow<String> = context.dataStore.data.map { it[VRM_PATH] ?: "" }
    val myVrmPath: Flow<String> = context.dataStore.data.map { it[MY_VRM_PATH] ?: "" }
    val selectedAssetVrm: Flow<String> = context.dataStore.data.map { it[SELECTED_ASSET_VRM] ?: "" }
    val decorations: Flow<List<DecorationItem>> = context.dataStore.data.map {
        val str = it[DECORATIONS] ?: "[]"
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
    val voicevoxSpeaker: Flow<Int> = context.dataStore.data.map { it[VOICEVOX_SPEAKER] ?: 58 }
    val lastBriefingDate: Flow<String> = context.dataStore.data.map { it[LAST_BRIEFING_DATE] ?: "" }

    suspend fun setHosts(list: List<HostConfig>) { context.dataStore.edit { it[HOSTS] = json.encodeToString(list) } }
    suspend fun setServerMemberships(list: List<ServerMembership>) { context.dataStore.edit { it[SERVER_MEMBERSHIPS] = json.encodeToString(list) } }
    suspend fun setUserId(id: String) { context.dataStore.edit { it[USER_ID] = id } }
    suspend fun setActiveHostId(id: String) { context.dataStore.edit { it[ACTIVE_HOST_ID] = id } }
    suspend fun setActiveServerId(id: String) { context.dataStore.edit { it[ACTIVE_SERVER_ID] = id } }
    suspend fun setActiveChannelId(id: String) { context.dataStore.edit { it[ACTIVE_CHANNEL_ID] = id } }
    suspend fun setUserName(name: String) { context.dataStore.edit { it[USER_NAME] = name } }
    suspend fun setVrmPath(path: String) { context.dataStore.edit { it[VRM_PATH] = path } }
    suspend fun setMyVrmPath(path: String) { context.dataStore.edit { it[MY_VRM_PATH] = path } }
    suspend fun setSelectedAssetVrm(name: String) { context.dataStore.edit { it[SELECTED_ASSET_VRM] = name } }
    suspend fun setDecorations(items: List<DecorationItem>) { context.dataStore.edit { it[DECORATIONS] = json.encodeToString(items) } }
    suspend fun setQuickPhrases(phrases: List<String>) { context.dataStore.edit { it[QUICK_PHRASES] = json.encodeToString(phrases) } }
    suspend fun setQuietHours(start: Int, end: Int) {
        context.dataStore.edit { it[QUIET_HOURS_START] = start; it[QUIET_HOURS_END] = end }
    }
    suspend fun setQuietEnabled(enabled: Boolean) {
        context.dataStore.edit { it[QUIET_ENABLED] = if (enabled) "1" else "0" }
    }
    suspend fun setTtsEnabled(enabled: Boolean) { context.dataStore.edit { it[TTS_ENABLED] = if (enabled) "1" else "0" } }
    suspend fun setVoiceInputEnabled(enabled: Boolean) { context.dataStore.edit { it[VOICE_INPUT_ENABLED] = if (enabled) "1" else "0" } }
    suspend fun setVoicevoxSpeaker(speaker: Int) { context.dataStore.edit { it[VOICEVOX_SPEAKER] = speaker } }
    suspend fun setLastBriefingDate(date: String) { context.dataStore.edit { it[LAST_BRIEFING_DATE] = date } }
}
