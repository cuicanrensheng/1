package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.SettingsActivity;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.util.CacheManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用核心管理器
 *
 * 【职责】
 * 统一管理应用的核心功能，包括：
 * 1. 数据加载（直播源加载、EPG 加载、M3U 解析、缓存管理）
 * 2. 广播管理（注册/注销广播接收器、处理广播事件）
 * 3. 生命周期管理（前后台切换、播放器暂停/恢复、进入设置不暂停）
 * 4. ✅ 源失效自动切台（2026-06-26 新增）
 *
 * 【2026-06-21 优化：日志分类】
 * 【优化内容】
 * 1. 生命周期/焦点/广播相关的日志 → 操作日志（logOperation）
 * 2. 数据加载/解析相关的日志 → 播放日志（log）
 * 3. 两种日志分开，互不混淆
 *
 * 【2026-06-26 新增：源失效自动切台】
 * 【功能说明】
 * 当频道源失效时，自动切换到下一个频道，
 * 连续跳过 MAX_CONSECUTIVE_SKIP 个失效频道后停止，提示用户检查直播源。
 *
 * 【为什么合并到这里？】
 * 源失效自动切台是纯业务逻辑，不涉及 UI 操作，
 * 统一放到 AppCoreManager 中管理，MainActivity 只负责回调处理。
 */
public class AppCoreManager {
    // ====================== 常量 ======================
    /** 加载超时时间（15 秒） */
    private static final long LOAD_TIMEOUT = 15000;
    /**
     * ✅ 2026-06-26 新增：源失效自动切台 - 最大连续跳过数
     * 【说明】连续跳过多少个失效频道后停止，防止无限切台
     */
    private static final int MAX_CONSECUTIVE_SKIP = 10;
    // ====================== 上下文与管理器 ======================
    private Context context;
    /** 播放器管理器 */
    private TVPlayerManager playerManager;
    /** 应用配置 */
    private AppConfig appConfig;
    /** 缓存管理器 */
    private CacheManager cacheManager;
    // ====================== 数据相关 ======================
    /** 全部频道列表 */
    private List<Channel> channelSourceList = new ArrayList<>();
    /** 是否已用缓存播放过（防止重复播放） */
    private boolean hasPlayedWithCache = false;
    /** 加载超时 Handler */
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    /** 是否正在加载 */
    private boolean isLoading = false;
    // ====================== 广播相关 ======================
    /** 切换控制器的广播接收器 */
    private BroadcastReceiver toggleControllerReceiver;
    /** 刷新直播源/EPG 的广播接收器 */
    private BroadcastReceiver refreshReceiver;
    /** 广播是否已注册 */
    private boolean receiversRegistered = false;
    // ====================== 生命周期相关 ======================
    /** 是否正在打开设置页面（用于区分 onPause 场景） */
    private boolean isOpeningSettings = false;
    /** 播放器控制器是否可见 */
    private boolean isControllerVisible = false;
    // ====================================================================
    // ✅ 2026-06-26 新增：源失效自动切台相关变量
    // ====================================================================
    /**
     * 连续失效频道计数
     * 【说明】每失效一个频道 +1，成功播放一个频道重置为 0
     */
    private int consecutiveFailedCount = 0;
    /**
     * 源失效监听器（回调给外部处理 UI 操作）
     * 【说明】AppCoreManager 只管理计数和判断，
     * 具体的切台、Toast 等 UI 操作由外部实现
     */
    private OnSourceSkipListener sourceSkipListener;
    // ====================== 回调监听器 ======================
    /** 数据加载监听器 */
    private OnDataLoadListener dataLoadListener;
    /** 配置刷新监听器 */
    private OnRefreshListener refreshListener;
    // ====================== 接口定义 ======================
    /**
     * 数据加载监听器
     */
    public interface OnDataLoadListener {
        /**
         * 直播源加载成功
         *
         * @param channels 频道列表
         * @param fromCache 是否来自缓存
         */
        void onLiveSourceLoaded(List<Channel> channels, boolean fromCache);
        /**
         * 直播源加载失败
         *
         * @param errorMsg 错误信息
         */
        void onLiveSourceFailed(String errorMsg);
        /**
         * EPG 加载完成
         */
        void onEpgLoaded();
        /**
         * 加载超时
         *
         * @param hasData 是否有缓存数据
         */
        void onLoadTimeout(boolean hasData);
    }
    /**
     * 配置刷新监听器
     */
    public interface OnRefreshListener {
        /**
         * 配置需要刷新
         * 收到刷新广播时回调
         */
        void onRefreshNeeded();
    }
    // ====================================================================
    // ✅ 2026-06-26 新增：源失效自动切台回调接口
    // ====================================================================
    /**
     * 源失效自动切台监听器
     *
     * 【说明】
     * AppCoreManager 只管理计数和判断逻辑，
     * 具体的切台、Toast 等 UI 操作由外部实现。
     */
    public interface OnSourceSkipListener {
        /**
         * 需要切换到下一个频道
         * 【触发时机】源失效且未达到最大跳过数时
         */
        void onNeedSkipChannel();
        /**
         * 达到最大跳过数，停止自动切台
         * 【触发时机】连续失效频道数达到 MAX_CONSECUTIVE_SKIP 时
         * @param maxSkip 最大跳过数
         */
        void onSkipLimitReached(int maxSkip);
        /**
         * 源失效（用于日志）
         * @param channelName 失效频道名称
         * @param failedCount 当前连续失效数
         */
        void onSourceFailed(String channelName, int failedCount);
    }
    // ====================== 构造函数 ======================
    /**
     * 构造函数
     *
     * @param context       上下文
     * @param playerManager 播放器管理器
     * @param appConfig     应用配置
     */
    public AppCoreManager(Context context, TVPlayerManager playerManager, AppConfig appConfig) {
        this.context = context.getApplicationContext();
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.cacheManager = CacheManager.getInstance(context);
    }
    // ====================================================================
    // 1. 直播源 & EPG 加载相关
    // ====================================================================
    /**
     * 加载直播源和 EPG 节目单
     *
     * 【加载策略】
     * 1. 先读缓存，快速显示（秒开）
     * 2. 后台网络加载最新数据
     * 3. 网络加载完成后更新列表
     * 4. 有缓存时不重复播放（防止闪烁）
     *
     * 【超时保护】
     * 15 秒还没加载完自动触发超时回调，避免一直卡在加载界面
     */
    public void loadLiveAndEpg() {
        // ✅ 保留：数据加载相关 → 播放日志
        log("【直播源】开始加载直播源...");
        isLoading = true;
        // ================================================
        // 加载超时保护（15 秒）
        // 防止网络异常时一直卡在加载界面
        // ================================================
        timeoutHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isLoading) {
                    // ✅ 保留：数据加载相关 → 播放日志
                    log("【加载】超时，自动隐藏加载动画");
                    boolean hasData = !channelSourceList.isEmpty();
                    if (dataLoadListener != null) {
                        dataLoadListener.onLoadTimeout(hasData);
                    }
                    isLoading = false;
                }
            }
        }, LOAD_TIMEOUT);
        // ===== 第一步：先读缓存，快速显示 =====
        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            // ✅ 保留：数据加载相关 → 播放日志
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                // 更新频道列表
                channelSourceList.clear();
                channelSourceList.addAll(cacheChannels);
                // 回调加载成功（来自缓存）
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(cacheChannels, true);
                }
                // 同时加载 EPG 缓存
                loadEpgCache();
                // ✅ 保留：数据加载相关 → 播放日志
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
            }
        }
        // ===== 第二步：后台网络加载最新数据 =====
        // ✅ 保留：数据加载相关 → 播放日志
        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(context).load(new LiveSourceLoader.LoadCallback() {
            @Override
            public void onSuccess(List<Channel> channels) {
                // ✅ 保留：数据加载相关 → 播放日志
                log("【网络】直播源加载成功，频道总数：" + channels.size());
                // 更新频道列表
                channelSourceList.clear();
                channelSourceList.addAll(channels);
                // 取消超时
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                // 回调加载成功（来自网络）
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(channels, false);
                }
                // ✅ 保留：数据加载相关 → 播放日志
                log("【网络】直播源列表已更新");
                // 加载最新 EPG
                loadEpg();
            }
            @Override
            public void onError(String errorMsg) {
                // ✅ 保留：数据加载相关 → 播放日志
                log("【网络】直播源加载失败：" + errorMsg);
                // 取消超时
                isLoading = false;
                timeoutHandler.removeCallbacksAndMessages(null);
                // 回调加载失败
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceFailed(errorMsg);
                }
                // 尝试加载 EPG 缓存
                loadEpgCache();
            }
        });
    }
    /**
     * 从缓存加载 EPG 节目单
     */
    private void loadEpgCache() {
        // EPG 缓存由 EpgManager 内部管理
        // 这里只触发回调，让外部去刷新 UI
        if (dataLoadListener != null) {
            dataLoadListener.onEpgLoaded();
        }
        // ✅ 保留：数据加载相关 → 播放日志
        log("【EPG】尝试从缓存加载...");
    }
    /**
     * 从网络加载 EPG 节目单
     */
    private void loadEpg() {
        // ✅ 保留：数据加载相关 → 播放日志
        log("【EPG】开始加载节目单...");
        EpgManager.getInstance(context).setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance(context).loadEpg(new Runnable() {
            @Override
            public void run() {
                // 在主线程回调
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        // ✅ 保留：数据加载相关 → 播放日志
                        log("【EPG】最新节目单加载完成");
                        if (dataLoadListener != null) {
                            dataLoadListener.onEpgLoaded();
                        }
                    }
                });
            }
        });
    }
    /**
     * 解析直播源内容（M3U 格式）
     *
     * 【修改点】：使用 Map 去重合并备用源
     * @param content M3U 文件内容
     * @return 解析后的频道列表
     */
    private List<Channel> parseLiveSource(String content) {
        // 🟢 使用 Map 缓存，以 tvgId 或 name 作为 Key 进行去重合并
        Map<String, Channel> channelMap = new HashMap<>();
        if (TextUtils.isEmpty(content)) {
            return new ArrayList<>();
        }
        String[] lines = content.split("\n");
        String currentName = "";
        String currentGroup = "";
        String currentLogo = "";
        String currentTvgId = "";
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                // #EXTINF 行：包含频道名称、分组、logo 等信息
                int commaIndex = line.indexOf(",");
                if (commaIndex > 0 && commaIndex < line.length() - 1) {
                    currentName = line.substring(commaIndex + 1).trim();
                }
                // 提取分组名称
                int groupIndex = line.indexOf("group-title=\"");
                if (groupIndex > 0) {
                    int groupEnd = line.indexOf("\"", groupIndex + 13);
                    if (groupEnd > groupIndex) {
                        currentGroup = line.substring(groupIndex + 13, groupEnd);
                    }
                }
                // 提取 tvg-id
                int tvgIndex = line.indexOf("tvg-id=\"");
                if (tvgIndex > 0) {
                    int tvgEnd = line.indexOf("\"", tvgIndex + 8);
                    if (tvgEnd > tvgIndex) {
                        currentTvgId = line.substring(tvgIndex + 8, tvgEnd);
                    }
                }
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                // 播放地址行
                String playUrl = line;
                if (!TextUtils.isEmpty(currentName) && !TextUtils.isEmpty(playUrl)) {
                    // 🟢 核心修改：使用 tvgId 或 频道名 作为唯一标识
                    String key = !TextUtils.isEmpty(currentTvgId) ? currentTvgId : currentName;
                    Channel existing = channelMap.get(key);
                    if (existing != null) {
                        // 已存在该频道：将新地址加到备用列表里（去重）
                        if (!existing.getUrls().contains(playUrl)) {
                            existing.getUrls().add(playUrl);
                        }
                    } else {
                        // 不存在：创建新频道，构造时第一条地址被设为“主源”
                        Channel newChannel = new Channel(currentName, playUrl, currentGroup, currentTvgId);
                        channelMap.put(key, newChannel);
                    }
                }
                // 重置，准备下一个频道
                currentName = "";
                currentGroup = "";
                currentLogo = "";
                currentTvgId = "";
            }
        }
        // ✅ 将 Map 里的所有 value 转入最终列表
        List<Channel> result = new ArrayList<>(channelMap.values());
        // ✅ 保留：数据解析相关 → 播放日志
        log("【缓存】解析完成，共 " + result.size() + " 个频道（去重后）");
        return result;
    }
    /**
     * 是否已用缓存播放过
     *
     * @return 是否已播放
     */
    public boolean hasPlayedWithCache() {
        return hasPlayedWithCache;
    }
    /**
     * 设置已用缓存播放
     *
     * @param played 是否已播放
     */
    public void setHasPlayedWithCache(boolean played) {
        this.hasPlayedWithCache = played;
    }
    /**
     * 获取频道列表
     *
     * @return 频道列表
     */
    public List<Channel> getChannelList() {
        return channelSourceList;
    }
    // ====================================================================
    // 2. 广播管理相关
    // ====================================================================
    /**
     * 注册所有广播接收器
     */
    public void registerReceivers() {
        if (receiversRegistered) return;
        // ===== 切换控制器的广播 =====
        toggleControllerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                isControllerVisible = !isControllerVisible;
                // 外部需要自己处理 PlayerView 的显示
                if (refreshListener != null) {
                    // 可以通过回调通知外部
                }
            }
        };
        // ===== 刷新直播源/EPG 的广播 =====
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                    // 重新加载设置
                    // 应用自定义直播源/EPG 地址
                    String customLive = appConfig.getCustomLiveUrl();
                    String customEpg = appConfig.getCustomEpgUrl();
                    if (customLive != null) UrlConfig.LIVE_URL = customLive;
                    if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                    // 重置缓存播放标志，让新数据重新播放
                    hasPlayedWithCache = false;
                    // 回调刷新
                    if (refreshListener != null) {
                        refreshListener.onRefreshNeeded();
                    }
                    // 重新加载直播源和 EPG
                    loadLiveAndEpg();
                    // SettingsActivity.logOperation("【系统】自动刷新直播源/EPG"); // 已注释：操作日志已移除
                }
            }
        };
        // 注册广播
        try {
            context.registerReceiver(toggleControllerReceiver,
                    new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
            context.registerReceiver(refreshReceiver,
                    new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
            receiversRegistered = true;
            // SettingsActivity.logOperation("【广播】广播接收器已注册"); // 已注释：操作日志已移除
        } catch (Exception e) {
            e.printStackTrace();
            // SettingsActivity.logOperation("【广播】广播注册失败：" + e.getMessage()); // 已注释：操作日志已移除
        }
    }
    /**
     * 注销所有广播接收器
     */
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
            // SettingsActivity.logOperation("【广播】广播接收器已注销"); // 已注释：操作日志已移除
        } catch (Exception e) {
            e.printStackTrace();
            // SettingsActivity.logOperation("【广播】广播注销失败：" + e.getMessage()); // 已注释：操作日志已移除
        }
    }
    /**
     * 播放器控制器是否可见
     *
     * @return 是否可见
     */
    public boolean isControllerVisible() {
        return isControllerVisible;
    }
    // ====================================================================
    // 3. 生命周期管理相关
    // ====================================================================
    /**
     * 页面暂停（onPause）
     *
     * 【进入设置不暂停】
     * 如果 isOpeningSettings 为 true，说明是打开设置页面，
     * 不暂停播放器，直接返回。
     *
     * @return 是否真的暂停了（false=进入设置，继续播放）
     */
    public boolean onPause() {
        if (isOpeningSettings) {
            // SettingsActivity.logOperation("【主页】onPause -> 打开设置页面，继续播放"); // 已注释：操作日志已移除
            return false;
        }
        // SettingsActivity.logOperation("【主页】onPause -> 切到后台"); // 已注释：操作日志已移除
        // SettingsActivity.logOperation("【系统】APP切到后台"); // 已注释：操作日志已移除
        if (playerManager != null) {
            playerManager.onBackground();
        }
        return true;
    }
    /**
     * 页面恢复（onResume）
     *
     * 【进入设置不暂停】
     * 如果 isOpeningSettings 为 true，说明是从设置页面回来，
     * 重置标志位即可，不需要调用 onForeground。
     *
     * @return 是否真的恢复了（false=从设置回来，不用恢复）
     */
    public boolean onResume() {
        if (isOpeningSettings) {
            isOpeningSettings = false;
            // SettingsActivity.logOperation("【主页】onResume -> 从设置页面回来"); // 已注释：操作日志已移除
            return false;
        }
        // SettingsActivity.logOperation("【主页】onResume -> 回到前台"); // 已注释：操作日志已移除
        // SettingsActivity.logOperation("【系统】APP回到前台"); // 已注释：操作日志已移除
        if (playerManager != null) {
            playerManager.onForeground();
        }
        return true;
    }
    /**
     * 窗口焦点变化
     *
     * @param hasFocus 是否获得焦点
     */
    public void onWindowFocusChanged(boolean hasFocus) {
        // 窗口焦点变化的逻辑主要是全面屏重应用
        // 这个由 DisplayManager 处理，这里只记录日志
        // ✅ 修改：窗口焦点 → 操作日志（用户最关心的这个！）
        if (hasFocus) {
            // SettingsActivity.logOperation("【主页】窗口获得焦点"); // 已注释：操作日志已移除
        } else {
            // SettingsActivity.logOperation("【主页】窗口失去焦点"); // 已注释：操作日志已移除
        }
    }
    /**
     * 页面销毁（onDestroy）
     */
    public void onDestroy() {
        // SettingsActivity.logOperation("【主页】onDestroy -> 页面销毁"); // 已注释：操作日志已移除
        // SettingsActivity.logOperation("【系统】APP退出"); // 已注释：操作日志已移除
        // 注销广播
        unregisterReceivers();
        // 取消超时
        timeoutHandler.removeCallbacksAndMessages(null);
        // 释放播放器
        if (playerManager != null) {
            playerManager.release();
        }
        // 清空引用
        channelSourceList = null;
    }
    /**
     * 打开设置页面
     * 设置 isOpeningSettings 标志，让 onPause 时不暂停播放器
     */
    public void beforeOpenSettings() {
        isOpeningSettings = true;
        // SettingsActivity.logOperation("【系统】打开设置页面"); // 已注释：操作日志已移除
    }
    /**
     * 是否正在打开设置页面
     *
     * @return 是否正在打开设置
     */
    public boolean isOpeningSettings() {
        return isOpeningSettings;
    }
    // ====================================================================
    // ✅ 2026-06-26 新增：源失效自动切台
    // ====================================================================
    /**
     * 设置源失效自动切台监听器
     *
     * 【说明】
     * AppCoreManager 只管理计数和判断逻辑，
     * 具体的切台、Toast 等 UI 操作由外部实现。
     *
     * @param listener 监听器
     */
    public void setOnSourceSkipListener(OnSourceSkipListener listener) {
        this.sourceSkipListener = listener;
    }
    /**
     * 处理源失效（自动切台核心逻辑）
     *
     * 【业务逻辑】
     * 1. 连续失效计数 +1
     * 2. 回调通知外部源失效（用于日志）
     * 3. 判断是否达到最大跳过数
     *    - 达到：回调通知停止跳过
     *    - 未达到：回调通知切到下一个频道
     *
     * @param currentChannelName 当前失效频道名称
     * @return true=继续切台，false=已达到上限停止
     */
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
   
    /**
     * 重置连续失效计数
     *
     * 【触发时机】成功播放一个频道时调用
     * 【为什么需要？】成功播放说明当前频道是好的，重置计数
     */
    public void resetSourceFailedCount() {
        consecutiveFailedCount = 0;
    }
    /**
     * 获取当前连续失效计数
     *
     * @return 连续失效数
     */
    public int getConsecutiveFailedCount() {
        return consecutiveFailedCount;
    }
    /**
     * 获取最大连续跳过数
     *
     * @return 最大跳过数
     */
    public int getMaxConsecutiveSkip() {
        return MAX_CONSECUTIVE_SKIP;
    }
    // ====================================================================
    // 4. 远程配置接收
    // ====================================================================
    /**
     * 接收远程配置（网页后台下发）
     *
     * @param liveUrl 直播源地址
     * @param epgUrl  EPG 地址
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        // ✅ 保留：配置更新/数据相关 → 播放日志
        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        // 重置缓存播放标志
        hasPlayedWithCache = false;
        // 重新加载
        loadLiveAndEpg();
    }
    // ====================================================================
    // 5. 监听器设置
    // ====================================================================
    /**
     * 设置数据加载监听器
     *
     * @param listener 监听器
     */
    public void setOnDataLoadListener(OnDataLoadListener listener) {
        this.dataLoadListener = listener;
    }
    /**
     * 设置配置刷新监听器
     *
     * @param listener 监听器
     */
    public void setOnRefreshListener(OnRefreshListener listener) {
        this.refreshListener = listener;
    }
    // ====================================================================
    // 6. 日志工具
    // ====================================================================
    /**
     * 记录播放日志（仅数据加载/播放相关的日志才调用这个）
     *
     * @param msg 日志内容
     */
    private void log(String msg) {
        SettingsActivity.log(msg);
    }
    // ====================================================================
    // 7. 资源释放
    // ====================================================================
    /**
     * 释放资源
     * Activity onDestroy 时调用
     */
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
