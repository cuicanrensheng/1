package com.tvlive

import android.content.Context
import android.content.SharedPreferences

object FavoriteManager {
    private const val SP_FAV = "tv_favorite"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(SP_FAV, Context.MODE_PRIVATE)
    }

    // 收藏/取消 频道名唯一标识
    fun toggleFav(channelName: String) {
        val set = sp.getStringSet("fav_list", mutableSetOf()) ?: mutableSetOf()
        if (set.contains(channelName)) set.remove(channelName)
        else set.add(channelName)
        sp.edit().putStringSet("fav_list", set).apply()
    }

    fun isFav(name: String): Boolean {
        val set = sp.getStringSet("fav_list", mutableSetOf()) ?: mutableSetOf()
        return set.contains(name)
    }

    fun getAllFav(): Set<String> {
        return sp.getStringSet("fav_list", mutableSetOf()) ?: mutableSetOf()
    }
}
