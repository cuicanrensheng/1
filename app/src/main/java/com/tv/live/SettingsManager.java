package com.tv.live;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {

    private static final String SP_NAME = "tv_live_setting";
    private static SettingsManager instance;
    private final SharedPreferences sp;

    public static final String KEY_LINE_INDEX = "line_index";
    public static final String KEY_VIDEO_SCALE = "video_scale";
    public static final String KEY_DECODE_MODE = "decode_mode";
    public static final String KEY_TIMEOUT_OPEN = "timeout_open";
    public static final String KEY_TIMEOUT_TIME = "timeout_time";
    public static final String KEY_SUB_URL = "sub_m3u_url";

    public static final int SCALE_FIT = 0;
    public static final int SCALE_16_9 = 1;
    public static final int SCALE_FILL = 2;

    public static final int DECODE_AUTO = 0;
    public static final int DECODE_HARD = 1;
    public static final int DECODE_SOFT = 2;

    // ====================== 2026-06-25 新增：MainActivity 用到的设置项 ======================

    public static final String KEY_EPG_ENABLE = "epg_enable";
    public static final String KEY_CHANNEL_REVERSE = "channel_reverse";
    public static final String KEY_NUMBER_CHANNEL_ENABLE = "number_channel_enable";
    public static final String KEY_AUTO_UPDATE_SOURCE = "auto_update_source";
    public static final String KEY_PIP_ENABLE = "pip_enable";
    public static final String KEY_DECODER_MODE_STRING = "decoder_mode";

    private SettingsManager(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SettingsManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new SettingsManager(ctx);
        }
        return instance;
    }

    // ====================== 线路 ======================
    public void setLine(int pos) {
        sp.edit().putInt(KEY_LINE_INDEX, pos).apply();
    }

    public int getLine() {
        return sp.getInt(KEY_LINE_INDEX, 0);
    }

    // ====================== 画面比例 ======================
    public void setScale(int scale) {
        sp.edit().putInt(KEY_VIDEO_SCALE, scale).apply();
    }

    public int getScale() {
        return sp.getInt(KEY_VIDEO_SCALE, SCALE_FIT);
    }

    // ====================== 解码模式（int） ======================
    public void setDecode(int mode) {
        sp.edit().putInt(KEY_DECODE_MODE, mode).apply();
    }

    public int getDecode() {
        return sp.getInt(KEY_DECODE_MODE, DECODE_AUTO);
    }

    // ====================== 超时开关 ======================
    public void setTimeoutEnable(boolean open) {
        sp.edit().putBoolean(KEY_TIMEOUT_OPEN, open).apply();
    }

    public boolean isTimeoutEnable() {
        return sp.getBoolean(KEY_TIMEOUT_OPEN, true);
    }

    public boolean getTimeoutEnable() {
        return isTimeoutEnable();
    }

    // ====================== 超时秒数 ======================
    public void setTimeoutSec(int sec) {
        sp.edit().putInt(KEY_TIMEOUT_TIME, sec).apply();
    }

    public int getTimeoutSec() {
        return sp.getInt(KEY_TIMEOUT_TIME, 6);
    }

    // ====================== 订阅地址 ======================
    public void setSubUrl(String url) {
        sp.edit().putString(KEY_SUB_URL, url).apply();
    }

    public String getSubUrl() {
        return sp.getString(KEY_SUB_URL, "https://gitee.com/qf_1111/iptv/raw/master/playlist.m3u");
    }

    // ====================== 2026-06-25 新增：EPG开关 ======================
    public boolean isEpgEnabled() {
        return sp.getBoolean(KEY_EPG_ENABLE, true);
    }

    public void setEpgEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_EPG_ENABLE, enabled).apply();
    }

    // ====================== 2026-06-25 新增：切台反转 ======================
    public boolean isChannelReverse() {
        return sp.getBoolean(KEY_CHANNEL_REVERSE, false);
    }

    public void setChannelReverse(boolean reverse) {
        sp.edit().putBoolean(KEY_CHANNEL_REVERSE, reverse).apply();
    }

    // ====================== 2026-06-25 新增：数字选台开关 ======================
    public boolean isNumberChannelEnabled() {
        return sp.getBoolean(KEY_NUMBER_CHANNEL_ENABLE, true);
    }

    public void setNumberChannelEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_NUMBER_CHANNEL_ENABLE, enabled).apply();
    }

    // ====================== 2026-06-25 新增：自动更新源 ======================
    public boolean isAutoUpdateSource() {
        return sp.getBoolean(KEY_AUTO_UPDATE_SOURCE, true);
    }

    public void setAutoUpdateSource(boolean auto) {
        sp.edit().putBoolean(KEY_AUTO_UPDATE_SOURCE, auto).apply();
    }

    // ====================== 2026-06-25 新增：画中画开关 ======================
    public boolean isPipEnabled() {
        return sp.getBoolean(KEY_PIP_ENABLE, false);
    }

    public void setPipEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PIP_ENABLE, enabled).apply();
    }

    // ====================== 2026-06-25 新增：解码器模式（String，兼容旧版） ======================
    public String getDecoderModeString() {
        return sp.getString(KEY_DECODER_MODE_STRING, "auto");
    }

    public void setDecoderModeString(String mode) {
        sp.edit().putString(KEY_DECODER_MODE_STRING, mode).apply();
    }

    public int getDecoderModeInt() {
        String mode = getDecoderModeString();
        if ("hard".equals(mode)) {
            return TVPlayerManager.DECODER_MODE_HARD;
        } else if ("soft".equals(mode)) {
            return TVPlayerManager.DECODER_MODE_SOFT;
        } else {
            return TVPlayerManager.DECODER_MODE_AUTO;
        }
    }

    public static String getDecoderModeName(int mode) {
        switch (mode) {
            case TVPlayerManager.DECODER_MODE_HARD:
                return "硬解";
            case TVPlayerManager.DECODER_MODE_SOFT:
                return "软解（FFmpeg）";
            case TVPlayerManager.DECODER_MODE_AUTO:
            default:
                return "自动（推荐）";
        }
    }

    // ====================== 通用方法 ======================
    public SharedPreferences getSharedPreferences() {
        return sp;
    }

    public void registerOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        sp.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
