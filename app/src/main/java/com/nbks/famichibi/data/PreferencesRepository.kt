package com.nbks.famichibi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

class PreferencesRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ROOM_ID = stringPreferencesKey("room_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ID = stringPreferencesKey("user_id")
        val VRM_PATH = stringPreferencesKey("vrm_path")
        val MY_VRM_PATH = stringPreferencesKey("my_vrm_path")
        val DECORATIONS = stringPreferencesKey("decorations")
        val AGENTS = stringPreferencesKey("agents")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "http://10.0.2.2:8000" }
    val roomId: Flow<String> = context.dataStore.data.map { it[ROOM_ID] ?: "" }
    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME] ?: "お兄ちゃん" }
    val userId: Flow<String> = context.dataStore.data.map { it[USER_ID] ?: "" }
    val vrmPath: Flow<String> = context.dataStore.data.map { it[VRM_PATH] ?: "" }
    val myVrmPath: Flow<String> = context.dataStore.data.map { it[MY_VRM_PATH] ?: "" }
    val decorations: Flow<List<DecorationItem>> = context.dataStore.data.map {
        val str = it[DECORATIONS] ?: "[]"
        try {
            json.decodeFromString(str)
        } catch (_: Exception) {
            emptyList()
        }
    }
    val agents: Flow<List<AgentConfig>> = context.dataStore.data.map {
        val str = it[AGENTS] ?: "[]"
        try {
            json.decodeFromString(str)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url }
    }

    suspend fun setRoomId(id: String) {
        context.dataStore.edit { it[ROOM_ID] = id }
    }

    suspend fun setUserName(name: String) {
        context.dataStore.edit { it[USER_NAME] = name }
    }

    suspend fun setUserId(id: String) {
        context.dataStore.edit { it[USER_ID] = id }
    }

    suspend fun setVrmPath(path: String) {
        context.dataStore.edit { it[VRM_PATH] = path }
    }

    suspend fun setMyVrmPath(path: String) {
        context.dataStore.edit { it[MY_VRM_PATH] = path }
    }

    suspend fun setDecorations(items: List<DecorationItem>) {
        context.dataStore.edit { it[DECORATIONS] = json.encodeToString(items) }
    }

    suspend fun setAgents(agents: List<AgentConfig>) {
        context.dataStore.edit { it[AGENTS] = json.encodeToString(agents) }
    }
}
