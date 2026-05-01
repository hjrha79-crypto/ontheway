package com.vita.ontheway

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * AI API 키를 EncryptedSharedPreferences에 안전하게 저장/조회.
 * 키 미설정 시 BuildConfig fallback.
 */
object ApiKeyManager {

    private const val PREFS_FILE = "ai_api_keys_encrypted"
    private const val KEY_ANTHROPIC = "anthropic_api_key"
    private const val TAG = "ApiKeyManager"

    private fun getPrefs(ctx: Context): SharedPreferences? {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                ctx.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences 생성 실패: ${e.message}")
            null
        }
    }

    fun saveApiKey(ctx: Context, key: String) {
        getPrefs(ctx)?.edit()?.putString(KEY_ANTHROPIC, key)?.apply()
    }

    fun getApiKey(ctx: Context): String {
        val stored = getPrefs(ctx)?.getString(KEY_ANTHROPIC, null)
        if (!stored.isNullOrBlank()) return stored
        // fallback: BuildConfig
        return Config.ANTHROPIC_API_KEY
    }

    fun hasUserKey(ctx: Context): Boolean {
        val stored = getPrefs(ctx)?.getString(KEY_ANTHROPIC, null)
        return !stored.isNullOrBlank()
    }

    fun clearApiKey(ctx: Context) {
        getPrefs(ctx)?.edit()?.remove(KEY_ANTHROPIC)?.apply()
    }

    fun maskKey(key: String): String {
        if (key.length <= 4) return "****"
        return "*".repeat(key.length - 4) + key.takeLast(4)
    }
}
