package com.vita.ontheway

import android.content.Context

object FontSizeManager {
    private const val PREF = "ontheway_prefs"
    private const val KEY = "font_scale"

    fun getScale(context: Context): Float {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getFloat(KEY, 1.0f)
    }

    fun setScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putFloat(KEY, scale).apply()
    }
}
