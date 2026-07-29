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

    data class Settings(
        val confidenceThreshold: Float = 0.5f,
        val enableFoodCodes: Boolean = true,
        val enableParcelCodes: Boolean = true,
        val darkMode: String = "system",
        val apiKey: String = "",
        val apiBaseUrl: String = "https://api.openai.com/v1",
        val apiModel: String = "gpt-4o-mini",
        val enableAI: Boolean = true
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
                enableAI = prefs[KEY_ENABLE_AI] ?: true
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
}
