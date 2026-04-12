package com.vita.ontheway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedPlace(val name: String, val address: String)

object PlaceManager {

    private const val PREF_KEY = "saved_places_v2"

    fun getPlaces(context: Context): List<SavedPlace> {
        val prefs = context.getSharedPreferences("ontheway", Context.MODE_PRIVATE)
        val json = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            SavedPlace(obj.getString("name"), obj.getString("address"))
        }
    }

    fun savePlace(context: Context, name: String, address: String) {
        val places = getPlaces(context).toMutableList()
        places.removeAll { it.name == name }
        places.add(0, SavedPlace(name, address))
        if (places.size > 6) places.removeAt(places.size - 1)
        val arr = JSONArray(places.map {
            JSONObject().apply { put("name", it.name); put("address", it.address) }
        })
        context.getSharedPreferences("ontheway", Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, arr.toString()).apply()
    }

    fun removePlace(context: Context, name: String) {
        val places = getPlaces(context).toMutableList()
        places.removeAll { it.name == name }
        val arr = JSONArray(places.map {
            JSONObject().apply { put("name", it.name); put("address", it.address) }
        })
        context.getSharedPreferences("ontheway", Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, arr.toString()).apply()
    }
}
