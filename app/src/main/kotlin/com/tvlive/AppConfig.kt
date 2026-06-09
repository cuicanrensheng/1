package com.tvlive

import android.content.Context
import android.content.SharedPreferences

object AppConfig {
    private const val SP_NAME = "tv_config"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    }

    // 开机自启
    var bootAutoStart: Boolean
        get() = sp.getBoolean("boot_auto_start", true)
        set(v) = sp.edit().putBoolean("boot_auto_start", v).apply()

    // 缓存时长 分钟
    var cacheMinute: Int
        get() = sp.getInt("cache_minute", 60)
        set(v) = sp.edit().putInt("cache_minute", v).apply()

    // 自动切换线路
    var autoSwitchLine: Boolean
        get() = sp.getBoolean("auto_switch_line", true)
        set(v) = sp.edit().putBoolean("auto_switch_line", v).apply()

    // 历史订阅源
    var m3uSources: MutableSet<String>
        get() = sp.getStringSet("m3u_list", mutableSetOf()) ?: mutableSetOf()
        set(v) = sp.edit().putStringSet("m3u_list", v).apply()

    // 历史EPG源
    var epgSources: MutableSet<String>
        get() = sp.getStringSet("epg_list", mutableSetOf()) ?: mutableSetOf()
        set(v) = sp.edit().putStringSet("epg_list", v).apply()

    // 可用播放域名（线路优先）
    var validDomain: MutableSet<String>
        get() = sp.getStringSet("valid_domain", mutableSetOf()) ?: mutableSetOf()
        set(v) = sp.edit().putStringSet("valid_domain", v).apply()

    // 当前激活订阅源
    var currentM3u: String
        get() = sp.getString("current_m3u", "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u") ?: ""
        set(v) = sp.edit().putString("current_m3u", v).apply()

    // 当前激活EPG
    var currentEpg: String
        get() = sp.getString("current_epg", "https://epg.catvod.com/epg.xml") ?: ""
        set(v) = sp.edit().putString("current_epg", v).apply()
}
