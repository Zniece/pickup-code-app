package com.pickupcode.app.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object AppPreferences {

    private val KEY_CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
    private val KEY_ENABLE_FOOD = booleanPreferencesKey("enable_food")
    private val KEY_ENABLE_PARCEL = booleanPreferencesKey("enable_parcel")
    private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
    private val KEY_API_KEY = stringPreferencesKey("api_key")
    private val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
    private val KEY_API_MODEL = stringPreferencesKey("api_model")
    private val KEY_ENABLE_AI = booleanPreferencesKey("enable_ai")
    private val KEY_ENABLE_INTENT_RECEIVE = booleanPreferencesKey("enable_intent_receive")
    private val KEY_ENABLE_SHARE_DETECTION = booleanPreferencesKey("enable_share_detection")
    private val KEY_ENABLE_MAP_VERIFY = booleanPreferencesKey("enable_map_verify")
    private val KEY_AMAP_API_KEY = stringPreferencesKey("amap_api_key")
    private val KEY_ENABLE_KUAIDI100 = booleanPreferencesKey("enable_kuaidi100")
    private val KEY_KUAIDI100_KEY = stringPreferencesKey("kuaidi100_key")

    data class Settings(
        val confidenceThreshold: Float = 0.5f,
        val enableFoodCodes: Boolean = true,
        val enableParcelCodes: Boolean = true,
        val darkMode: String = "system",
        val apiKey: String = "",
        val apiBaseUrl: String = "https://api.openai.com/v1",
        val apiModel: String = "gpt-4o-mini",
        val enableAI: Boolean = true,
        val enableIntentReceive: Boolean = true,
        val enableShareDetection: Boolean = true,
        val enableMapVerify: Boolean = false,
        val amapApiKey: String = "",
        val enableKuaidi100: Boolean = false,
        val kuaidi100Key: String = ""
    )

    fun observe(context: Context): Flow<Settings> {
        return context.dataStore.data.map { prefs ->
            Settings(
                confidenceThreshold = prefs[KEY_CONFIDENCE_THRESHOLD] ?: 0.5f,
                enableFoodCodes = prefs[KEY_ENABLE_FOOD] ?: true,
                enableParcelCodes = prefs[KEY_ENABLE_PARCEL] ?: true,
                darkMode = prefs[KEY_DARK_MODE] ?: "system",
                apiKey = prefs[KEY_API_KEY] ?: "",
                apiBaseUrl = prefs[KEY_API_BASE_URL] ?: "https://api.openai.com/v1",
                apiModel = prefs[KEY_API_MODEL] ?: "gpt-4o-mini",
                enableAI = prefs[KEY_ENABLE_AI] ?: true,
                enableIntentReceive = prefs[KEY_ENABLE_INTENT_RECEIVE] ?: true,
                enableShareDetection = prefs[KEY_ENABLE_SHARE_DETECTION] ?: true,
                enableMapVerify = prefs[KEY_ENABLE_MAP_VERIFY] ?: false,
                amapApiKey = prefs[KEY_AMAP_API_KEY] ?: "",
                enableKuaidi100 = prefs[KEY_ENABLE_KUAIDI100] ?: false,
                kuaidi100Key = prefs[KEY_KUAIDI100_KEY] ?: ""
            )
        }
    }

    suspend fun setConfidenceThreshold(context: Context, value: Float) {
        context.dataStore.edit { it[KEY_CONFIDENCE_THRESHOLD] = value }
    }

    suspend fun setEnableFood(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_FOOD] = value }
    }

    suspend fun setEnableParcel(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_PARCEL] = value }
    }

    suspend fun setDarkMode(context: Context, value: String) {
        context.dataStore.edit { it[KEY_DARK_MODE] = value }
    }

    suspend fun setApiKey(context: Context, value: String) {
        context.dataStore.edit { it[KEY_API_KEY] = value }
    }

    suspend fun setApiBaseUrl(context: Context, value: String) {
        context.dataStore.edit { it[KEY_API_BASE_URL] = value }
    }

    suspend fun setApiModel(context: Context, value: String) {
        context.dataStore.edit { it[KEY_API_MODEL] = value }
    }

    suspend fun setEnableAI(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_AI] = value }
    }

    suspend fun setEnableIntentReceive(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_INTENT_RECEIVE] = value }
    }

    suspend fun setEnableShareDetection(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_SHARE_DETECTION] = value }
    }

    suspend fun setEnableMapVerify(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_MAP_VERIFY] = value }
    }

    suspend fun setAmapApiKey(context: Context, value: String) {
        context.dataStore.edit { it[KEY_AMAP_API_KEY] = value }
    }

    suspend fun setEnableKuaidi100(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_ENABLE_KUAIDI100] = value }
    }

    suspend fun setKuaidi100Key(context: Context, value: String) {
        context.dataStore.edit { it[KEY_KUAIDI100_KEY] = value }
    }
}
