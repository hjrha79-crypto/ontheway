package com.vita.ontheway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** v3.3 즐겨찾기 가게 + 블랙리스트 관리 */
object StoreManager {
    private const val PREFS = "store_manager"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_BLACKLIST = "blacklist"

    data class StoreEntry(val name: String, val platform: String = "", val note: String = "")

    fun getFavorites(ctx: Context): List<StoreEntry> = getList(ctx, KEY_FAVORITES)
    fun getBlacklist(ctx: Context): List<StoreEntry> = getList(ctx, KEY_BLACKLIST)

    fun addFavorite(ctx: Context, name: String, platform: String = "") {
        addToList(ctx, KEY_FAVORITES, StoreEntry(name, platform))
    }

    fun removeFavorite(ctx: Context, name: String) = removeFromList(ctx, KEY_FAVORITES, name)

    fun addBlacklist(ctx: Context, name: String, platform: String = "") {
        addToList(ctx, KEY_BLACKLIST, StoreEntry(name, platform))
    }

    fun removeBlacklist(ctx: Context, name: String) = removeFromList(ctx, KEY_BLACKLIST, name)

    fun isFavorite(ctx: Context, storeName: String): Boolean {
        if (storeName.isBlank()) return false
        return getFavorites(ctx).any { it.name == storeName || storeName.contains(it.name) || it.name.contains(storeName) }
    }

    fun isBlacklisted(ctx: Context, storeName: String): Boolean {
        if (storeName.isBlank()) return false
        return getBlacklist(ctx).any { it.name == storeName || storeName.contains(it.name) || it.name.contains(storeName) }
    }

    private fun getList(ctx: Context, key: String): List<StoreEntry> {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            StoreEntry(obj.optString("name", ""), obj.optString("platform", ""), obj.optString("note", ""))
        }
    }

    private fun addToList(ctx: Context, key: String, entry: StoreEntry) {
        val list = getList(ctx, key).toMutableList()
        if (list.any { it.name == entry.name }) return
        list.add(entry)
        saveList(ctx, key, list)
    }

    private fun removeFromList(ctx: Context, key: String, name: String) {
        val list = getList(ctx, key).filter { it.name != name }
        saveList(ctx, key, list)
    }

    private fun saveList(ctx: Context, key: String, list: List<StoreEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("platform", e.platform)
                put("note", e.note)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
    }
}
