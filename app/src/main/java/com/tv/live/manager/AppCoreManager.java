package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import android.content.BroadcastReceiver;
import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.util.CacheManager;
import com.tv.live.SourceManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppCoreManager {
    private static final long LOAD_TIMEOUT = 15000;
    private static final int MAX_CONSECUTIVE_SKIP = 10;

    private Context context;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private CacheManager cacheManager;

    private List<Channel> channelSourceList = new ArrayList<>();
    private final Object channelListLock = new Object(); // 读写锁

    private boolean hasPlayedWithCache = false;
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;

    private BroadcastReceiver toggleControllerReceiver;
    private BroadcastReceiver refreshReceiver;
    private boolean receiversRegistered = false;

    private boolean isOpeningSettings = false;
    private boolean isControllerVisible = false;

    private int consecutiveFailedCount = 0;
    private OnSourceSkipListener sourceSkipListener;

    private OnDataLoadListener dataLoadListener;
    private OnRefreshListener refreshListener;

    // 🔧 虎牙房间号数据（房间号, 标题, 分组）
    private static final String[][] HUYA_ROOM_DATA = {
        // ==================== 电影 ====================
        {"11602047", "异形铁血战士", "电影"},
        {"26355795", "惊悚电影", "电影"},
        {"23863778", "漫威科幻剧", "电影"},
        {"30080257", "科幻史诗巨作", "电影"},
        {"11342421", "林正英僵尸系列", "电影"},
        {"31308716", "林正英经典电影", "电影"},
        {"11342412", "星爷经典不间断", "电影"},
        {"30631746", "李连杰经典", "电影"},
        {"30080234", "星球大战", "电影"},
        {"11336571", "【女神系列】", "电影"},
        {"30682679", "港片经典", "电影"},
        {"30509122", "动作电影", "电影"},
        {"31295293", "澳门电影", "电影"},
        {"30985600", "高评分电影", "电影"},
        {"31087618", "剧情电影", "电影"},
        {"30951757", "三叉戟电影", "电影"},
        {"30716827", "猛鬼系列", "电影"},
        {"30951578", "真实事件改编", "电影"},
        {"31311959", "惊悚电影", "电影"},
        {"30968485", "金马影帝", "电影"},
        {"26355784", "恐怖电影系列", "电影"},
        // ==================== 电视剧 ====================
        {"880256",   "怪奇物语", "电视剧"},
        {"31055598", "康熙微服私访记", "电视剧"},
        {"30612264", "封神榜", "电视剧"},
        {"11342384", "新水浒", "电视剧"},
        {"11602081", "老三国", "电视剧"},
        {"11336726", "爱情公寓", "电视剧"},
        {"11342425", "古代神探推理", "电视剧"},
        {"11601964", "男主比女主穷", "电视剧"},
        {"11352958", "少年包青天", "电视剧"},
        {"31336243", "神探狄仁杰", "电视剧"},
        {"11342396", "铁齿铜纪晓岚", "电视剧"},
        {"31312768", "洪金宝成龙彪成电影", "电视剧"},
        {"30080238", "李云龙", "电视剧"},
        {"11352944", "新三国", "电视剧"},
        {"31087618", "TVB 午夜场", "电视剧"},
        {"23740156", "庆余年系列", "电视剧"},
        {"30627334", "雍正王朝", "电视剧"},
        {"30655127", "雍正王朝李卫当官", "电视剧"},
        {"30080146", "寻秦记", "电视剧"},
        // ==================== 动漫 ====================
        {"31312788", "一人之下", "动漫"},
        {"21059561", "五条悟", "动漫"},
        {"29982655", "瑞克与莫蒂", "动漫"},
        {"26355796", "食神小当家", "动漫"},
        {"26355785", "世界杯来啦", "动漫"},
        {"24314166", "林七夜之路", "动漫"},
        {"20985850", "超自然武装", "动漫"},
        {"30065341", "蜡笔小新", "动漫"},
        {"11352919", "海绵宝宝", "动漫"},
        {"21059565", "仙逆", "动漫"},
        {"30080236", "柯南", "动漫"},
        {"19807776", "科幻悬疑惊悚灾难", "动漫"},
        {"30613314", "中华小当家", "动漫"}
    };

    public interface OnDataLoadListener {
        void onLiveSourceLoaded(List<Channel> channels, boolean fromCache);
        void onLiveSourceFailed(String errorMsg);
        void onEpgLoaded();
        void onLoadTimeout(boolean hasData);
    }
    public interface OnRefreshListener { void onRefreshNeeded(); }
    public interface OnSourceSkipListener {
        void onNeedSkipChannel();
        void onSkipLimitReached(int maxSkip);
        void onSourceFailed(String channelName, int failedCount);
    }

    public AppCoreManager(Context context, TVPlayerManager playerManager, AppConfig appConfig) {
        this.context = context.getApplicationContext();
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.cacheManager = CacheManager.getInstance(context);
    }

    static <T> List<T> sanitizeChannels(List<T> channels) {
        return (channels != null) ? channels : new ArrayList<>();
    }

    static <T> List<T> ensureChannelListNotNull(List<T> existing) {
        return (existing != null) ? existing : new ArrayList<>();
    }

    // ========== 1. 直播源 & EPG 加载 ==========
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");
        isLoading = true;

        timeoutHandler.postDelayed(() -> {
            if (isLoading) {
                log("【加载】超时，自动隐藏加载动画");
                boolean hasData;
                synchronized (channelListLock) {
                    hasData = !channelSourceList.isEmpty();
                }
                if (dataLoadListener != null) {
                    dataLoadListener.onLoadTimeout(hasData);
                }
                isLoading = false;
            }
        }, LOAD_TIMEOUT);

        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                synchronized (channelListLock) {
                    channelSourceList.clear();
                    channelSourceList.addAll(cacheChannels);
                }
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(cacheChannels, true);
                }
                loadEpgCache();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
            }
        }

        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(context).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                List<Channel> safeChannels = sanitizeChannels(channels);
                log("【网络】直播源加载成功，频道总数：" + safeChannels.size());
                synchronized (channelListLock) {
                    channelSourceList = ensureChannelListNotNull(channelSourceList);
                    if (channelSourceList.isEmpty()) {
                        channelSourceList.clear();
                        channelSourceList.addAll(safeChannels);
                    } else {
                        mergeChannels(safeChannels);
                    }
                }
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(safeChannels, false);
                }
                log("【网络】直播源列表已更新");
                triggerHealthCheck(safeChannels);
                loadEpg();

                // 🔧 不管网络是否加载成功，都在最后追加虎牙房间（独立分组）
                appendHuyaRoomList();
            }

            @Override
            public void onError(String errorMsg) {
                log("【网络】直播源加载失败：" + errorMsg);
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceFailed(errorMsg);
                }
                loadEpgCache();

                // 🔧 即使网络加载失败，仍然可以显示出缓存 + 虎牙列表
                appendHuyaRoomList();
            }
        });
    }

    // 🔧 追加虎牙列表到现有列表（加“虎牙”前缀，完全独立）
    private void appendHuyaRoomList() {
        if (HUYA_ROOM_DATA == null || HUYA_ROOM_DATA.length == 0) return;

        List<Channel> huyaChannels = new ArrayList<>();
        for (String[] pair : HUYA_ROOM_DATA) {
            String roomId = pair[0];
            String name = pair[1];
            String originalGroup = pair[2];
            // 🟢 关键修改：给分组名加上“虎牙”前缀，永远独立显示
            String group = "虎牙" + originalGroup;
            String roomUrl = "https://www.huya.com/" + roomId;
            Channel ch = new Channel(name, roomUrl, group, roomId);
            huyaChannels.add(ch);
        }

        synchronized (channelListLock) {
            // 直接追加到列表尾部，不打乱原有列表
            channelSourceList.addAll(huyaChannels);
        }

        if (dataLoadListener != null) {
            List<Channel> fullList;
            synchronized (channelListLock) {
                fullList = new ArrayList<>(channelSourceList);
            }
            dataLoadListener.onLiveSourceLoaded(fullList, false);
        }
        log("【虎牙】已追加 " + huyaChannels.size() + " 个房间（独立分组：虎牙电影 / 虎牙电视剧 / 虎牙动漫）");
    }

    private void triggerHealthCheck(List<Channel> channels) {
        try {
            com.tv.live.TVPlayerManager pm = com.tv.live.TVPlayerManager.getInstance(context);
            if (pm != null) {
                com.tv.live.util.SourceHealthChecker hc = pm.getHealthChecker();
                if (hc != null && hc.isEnabled()) {
                    hc.checkAll(channels);
                    log("【健康检测】已触发后台全量源检测");
                }
            }
        } catch (Exception e) {
            log("【健康检测】触发异常: " + e.getMessage());
        }
    }

    private void loadEpgCache() {
        if (dataLoadListener != null) {
            dataLoadListener.onEpgLoaded();
        }
        log("【EPG】尝试从缓存加载...");
    }

    private void loadEpg() {
        log("【EPG】开始加载节目单...");
        EpgManager.getInstance(context).setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance(context).loadEpg(() -> {
            new Handler(Looper.getMainLooper()).post(() -> {
                log("【EPG】最新节目单加载完成");
                if (dataLoadListener != null) {
                    dataLoadListener.onEpgLoaded();
                }
            });
        });
    }

    private List<Channel> parseLiveSource(String content) {
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        if (TextUtils.isEmpty(content)) {
            return new ArrayList<>();
        }
        String[] lines = content.split("\n");
        String currentName = "";
        String currentGroup = "";
        String currentTvgId = "";

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName) && !TextUtils.isEmpty(playUrl)) {
                    String key = !TextUtils.isEmpty(currentTvgId) ? currentTvgId : currentName;
                    if (TextUtils.isEmpty(key)) continue;

                    Channel existing = channelMap.get(key);
                    if (existing != null) {
                        existing.addBackupUrl(playUrl);
                        if (!TextUtils.isEmpty(currentGroup)) {
                            existing.setGroup(currentGroup);
                        }
                    } else {
                        Channel newChannel = new Channel(currentName, playUrl, currentGroup, currentTvgId);
                        channelMap.put(key, newChannel);
                    }
                }
                currentName = "";
                currentGroup = "";
                currentTvgId = "";
            }
        }
        return new ArrayList<>(channelMap.values());
    }

    public void mergeChannels(List<Channel> newChannels) {
        synchronized (channelListLock) {
            Map<String, Channel> mergedMap = new LinkedHashMap<>();
            for (Channel ch : channelSourceList) {
                String key = !TextUtils.isEmpty(ch.getChannelId()) ? ch.getChannelId() : ch.getName();
                if (!TextUtils.isEmpty(key)) {
                    mergedMap.put(key, ch);
                }
            }
            for (Channel ch : newChannels) {
                String key = !TextUtils.isEmpty(ch.getChannelId()) ? ch.getChannelId() : ch.getName();
                if (TextUtils.isEmpty(key)) continue;

                Channel existing = mergedMap.get(key);
                if (existing != null) {
                    for (String url : ch.getBackupUrls()) {
                        existing.addBackupUrl(url);
                    }
                    String newGroup = ch.getGroup();
                    if (!TextUtils.isEmpty(newGroup)) {
                        existing.setGroup(newGroup);
                    }
                } else {
                    mergedMap.put(key, ch);
                }
            }
            channelSourceList.clear();
            channelSourceList.addAll(mergedMap.values());
        }
    }

    // ========== 2. 广播管理 ==========
    public void registerReceivers() {
        if (receiversRegistered) return;
        toggleControllerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                isControllerVisible = !isControllerVisible;
            }
        };
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                    new Thread(() -> {
                        if (cacheManager != null) {
                            cacheManager.clearAll();
                            log("【缓存】已强制清除所有缓存，正在重新拉取最新数据");
                        }
                        synchronized (channelListLock) {
                            channelSourceList.clear();
                        }

                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String customLive = sp.getString("custom_live_url", "");
                        if (!TextUtils.isEmpty(customLive)) {
                            UrlConfig.LIVE_URL = customLive;
                            log("【推送】成功读取到网页推送的直播源地址：" + customLive);
                        } else {
                            SourceManager liveManager = new SourceManager(context, "live_history");
                            String defaultLive = liveManager.getDefaultUrl();
                            if (!TextUtils.isEmpty(defaultLive)) {
                                UrlConfig.LIVE_URL = defaultLive;
                                log("【推送】未找到推送地址，使用历史默认源：" + defaultLive);
                            }
                        }

                        String customEpg = sp.getString("custom_epg_url", "");
                        if (!TextUtils.isEmpty(customEpg)) {
                            UrlConfig.EPG_URL = customEpg;
                            log("【推送】成功读取到网页推送的EPG地址：" + customEpg);
                        } else {
                            SourceManager epgManager = new SourceManager(context, "epg_history");
                            String defaultEpg = epgManager.getDefaultUrl();
                            if (!TextUtils.isEmpty(defaultEpg)) {
                                UrlConfig.EPG_URL = defaultEpg;
                            }
                        }

                        hasPlayedWithCache = false;
                        if (refreshListener != null) {
                            refreshListener.onRefreshNeeded();
                        }
                        loadLiveAndEpg();
                    }).start();
                }
            }
        };
        try {
            IntentFilter filterToggle = new IntentFilter("com.tv.live.TOGGLE_CONTROL");
            ContextCompat.registerReceiver(context, toggleControllerReceiver, filterToggle, ContextCompat.RECEIVER_NOT_EXPORTED);

            IntentFilter filterRefresh = new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG");
            ContextCompat.registerReceiver(context, refreshReceiver, filterRefresh, ContextCompat.RECEIVER_NOT_EXPORTED);

            receiversRegistered = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void unregisterReceivers() {
        if (!receiversRegistered) return;
        try {
            if (toggleControllerReceiver != null) {
                context.unregisterReceiver(toggleControllerReceiver);
            }
            if (refreshReceiver != null) {
                context.unregisterReceiver(refreshReceiver);
            }
            receiversRegistered = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isControllerVisible() { return isControllerVisible; }

    // ========== 3. 生命周期 ==========
    public boolean onPause() {
        if (isOpeningSettings) return false;
        if (playerManager != null) {
            playerManager.onBackground();
        }
        return true;
    }

    public boolean onResume() {
        if (isOpeningSettings) {
            isOpeningSettings = false;
            return false;
        }
        if (playerManager != null) {
            playerManager.onForeground();
        }
        return true;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
    }

    public void onDestroy() {
        unregisterReceivers();
        timeoutHandler.removeCallbacksAndMessages(null);
        if (playerManager != null) {
            playerManager.release();
        }
        synchronized (channelListLock) {
            if (channelSourceList != null) {
                channelSourceList.clear();
            } else {
                channelSourceList = new ArrayList<>();
            }
        }
    }

    public void beforeOpenSettings() {
        isOpeningSettings = true;
    }

    public boolean isOpeningSettings() { return isOpeningSettings; }

    public boolean hasPlayedWithCache() { return hasPlayedWithCache; }
    public void setHasPlayedWithCache(boolean played) { this.hasPlayedWithCache = played; }

    public List<Channel> getChannelList() {
        synchronized (channelListLock) {
            if (channelSourceList == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(channelSourceList);
        }
    }

    // ========== 4. 源失效自动切台 ==========
    public void setOnSourceSkipListener(OnSourceSkipListener listener) {
        this.sourceSkipListener = listener;
    }

    public boolean handleSourceFailed(String currentChannelName) {
        consecutiveFailedCount++;
        int count = consecutiveFailedCount;
        if (sourceSkipListener != null) {
            sourceSkipListener.onSourceFailed(currentChannelName, count);
        }
        if (count >= MAX_CONSECUTIVE_SKIP) {
            if (sourceSkipListener != null) {
                sourceSkipListener.onSkipLimitReached(MAX_CONSECUTIVE_SKIP);
            }
            return false;
        }
        if (sourceSkipListener != null) {
            sourceSkipListener.onNeedSkipChannel();
        }
        return true;
    }

    public void resetSourceFailedCount() { consecutiveFailedCount = 0; }
    public int getConsecutiveFailedCount() { return consecutiveFailedCount; }
    public int getMaxConsecutiveSkip() { return MAX_CONSECUTIVE_SKIP; }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        new Thread(() -> {
            appConfig.setCustomUrls(liveUrl, epgUrl);
            if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
            if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
            log("【远程配置】更新直播源：" + liveUrl);
            log("【远程配置】更新EPG：" + epgUrl);

            if (cacheManager != null) {
                cacheManager.clearAll();
                log("【缓存】远程配置触发，强制清除旧缓存");
            }

            synchronized (channelListLock) {
                channelSourceList.clear();
            }

            hasPlayedWithCache = false;
            loadLiveAndEpg();
        }).start();
    }

    public void setOnDataLoadListener(OnDataLoadListener listener) { this.dataLoadListener = listener; }
    public void setOnRefreshListener(OnRefreshListener listener) { this.refreshListener = listener; }

    private void log(String msg) {
        Log.d("AppCoreManager", msg);
    }

    public void release() {
        onDestroy();
        context = null;
        playerManager = null;
        appConfig = null;
        cacheManager = null;
        dataLoadListener = null;
        refreshListener = null;
        sourceSkipListener = null;
    }
}
