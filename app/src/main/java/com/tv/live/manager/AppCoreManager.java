package com.tv.live.manager;
import com.tv.live.TVPlayerManager;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.SettingsActivity;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig;
import com.tv.live.loader.LiveSourceLoader;
import com.tv.live.util.CacheManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用核心管理器【内存泄漏修复版】
 */
public class AppCoreManager {
    // ====================== 常量 ======================
    private static final long LOAD_TIMEOUT = 15000;
    private static final int MAX_CONSECUTIVE_SKIP = 10;

    // ====================== 修复：弱引用上下文，移除强引用Context ======================
    private WeakReference<Context> contextRef;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private CacheManager cacheManager;

    // 数据缓存
    private List<Channel> channelSourceList = new ArrayList<>();
    private boolean hasPlayedWithCache = false;
    private Handler timeoutHandler;
    private boolean isLoading = false;

    // 广播接收器（替换匿名，使用静态弱引用类）
    private ToggleBroadcastReceiver toggleControllerReceiver;
    private RefreshBroadcastReceiver refreshReceiver;
    private boolean receiversRegistered = false;

    // 页面状态标记
    private boolean isOpeningSettings = false;
    private boolean isControllerVisible = false;

    // 切台计数
    private int consecutiveFailedCount = 0;

    // 监听器
    private OnSourceSkipListener sourceSkipListener;
    private OnDataLoadListener dataLoadListener;
    private OnRefreshListener refreshListener;

    // ====================== 静态弱引用内部类（消除匿名泄漏） ======================
    // 加载超时Runnable
    private static class TimeoutRunnable implements Runnable {
        private final WeakReference<AppCoreManager> coreRef;
        public TimeoutRunnable(AppCoreManager core) {
            this.coreRef = new WeakReference<>(core);
        }
        @Override
        public void run() {
            AppCoreManager core = coreRef.get();
            if (core == null || !core.isLoading) return;
            core.log("【加载】超时，自动隐藏加载动画");
            boolean hasData = !core.channelSourceList.isEmpty();
            if (core.dataLoadListener != null) {
                core.dataLoadListener.onLoadTimeout(hasData);
            }
            core.isLoading = false;
        }
    }

    // 直播源加载回调
    private static class LiveLoadCallback implements LiveSourceLoader.LoadCallback {
        private final WeakReference<AppCoreManager> coreRef;
        public LiveLoadCallback(AppCoreManager core) {
            this.coreRef = new WeakReference<>(core);
        }
        @Override
        public void onSuccess(List<Channel> channels) {
            AppCoreManager core = coreRef.get();
            if (core == null) return;
            core.log("【网络】直播源加载成功，频道总数：" + channels.size());
            core.channelSourceList.clear();
            core.channelSourceList.addAll(channels);
            core.isLoading = false;
            core.timeoutHandler.removeCallbacksAndMessages(null);
            if (core.dataLoadListener != null) {
                core.dataLoadListener.onLiveSourceLoaded(channels, false);
            }
            core.log("【网络】直播源列表已更新");
            core.loadEpg();
        }
        @Override
        public void onError(String errorMsg) {
            AppCoreManager core = coreRef.get();
            if (core == null) return;
            core.log("【网络】直播源加载失败：" + errorMsg);
            core.isLoading = false;
            core.timeoutHandler.removeCallbacksAndMessages(null);
            if (core.dataLoadListener != null) {
                core.dataLoadListener.onLiveSourceFailed(errorMsg);
            }
            core.loadEpgCache();
        }
    }

    // EPG加载完成主线程回调
    private static class EpgFinishRunnable implements Runnable {
        private final WeakReference<AppCoreManager> coreRef;
        public EpgFinishRunnable(AppCoreManager core) {
            this.coreRef = new WeakReference<>(core);
        }
        @Override
        public void run() {
            AppCoreManager core = coreRef.get();
            if (core == null) return;
            core.log("【EPG】最新节目单加载完成");
            if (core.dataLoadListener != null) {
                core.dataLoadListener.onEpgLoaded();
            }
        }
    }

    // 广播接收器：切换控制器
    private static class ToggleBroadcastReceiver extends BroadcastReceiver {
        private final WeakReference<AppCoreManager> coreRef;
        public ToggleBroadcastReceiver(AppCoreManager core) {
            this.coreRef = new WeakReference<>(core);
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            AppCoreManager core = coreRef.get();
            if (core == null) return;
            core.isControllerVisible = !core.isControllerVisible;
            if (core.refreshListener != null) {
                core.refreshListener.onRefreshNeeded();
            }
        }
    }

    // 广播接收器：刷新直播源
    private static class RefreshBroadcastReceiver extends BroadcastReceiver {
        private final WeakReference<AppCoreManager> coreRef;
        public RefreshBroadcastReceiver(AppCoreManager core) {
            this.coreRef = new WeakReference<>(core);
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            AppCoreManager core = coreRef.get();
            if (core == null) return;
            if ("com.tv.live.REFRESH_LIVE_AND_EPG".equals(intent.getAction())) {
                String customLive = core.appConfig.getCustomLiveUrl();
                String customEpg = core.appConfig.getCustomEpg();
                if (customLive != null) UrlConfig.LIVE_URL = customLive;
                if (customEpg != null) UrlConfig.EPG_URL = customEpg;
                core.hasPlayedWithCache = false;
                if (core.refreshListener != null) {
                    core.refreshListener.onRefreshNeeded();
                }
                core.loadLiveAndEpg();
                SettingsActivity.logOperation("【系统】自动刷新直播源/EPG");
            }
        }
    }

    // ====================== 接口定义 ======================
    public interface OnDataLoadListener {
        void onLiveSourceLoaded(List<Channel> channels, boolean fromCache);
        void onLiveSourceFailed(String errorMsg);
        void onEpgLoaded();
        void onLoadTimeout(boolean hasData);
    }
    public interface OnRefreshListener {
        void onRefreshNeeded();
    }
    public interface OnSourceSkipListener {
        void onNeedSkipChannel();
        void onSkipLimitReached(int maxSkip);
        void onSourceFailed(String channelName, int failedCount);
    }

    // ====================== 构造函数（修复：弱引用包装ApplicationContext） ======================
    public AppCoreManager(Context context, TVPlayerManager playerManager, AppConfig appConfig) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.cacheManager = CacheManager.getInstance(context);
        this.timeoutHandler = new Handler(Looper.getMainLooper());
    }

    // 获取上下文统一判空
    private Context getContext() {
        return contextRef != null ? contextRef.get() : null;
    }

    // ====================================================================
    // 1. 直播源 & EPG 加载
    // ====================================================================
    public void loadLiveAndEpg() {
        log("【直播源】开始加载直播源...");
        isLoading = true;
        // 使用静态弱引用超时Runnable
        timeoutHandler.postDelayed(new TimeoutRunnable(this), LOAD_TIMEOUT);

        // 读取缓存
        String cacheContent = cacheManager.getFileCache("live_source");
        if (cacheContent != null && !cacheContent.isEmpty()) {
            log("【缓存】找到直播源缓存，快速显示");
            List<Channel> cacheChannels = parseLiveSource(cacheContent);
            if (cacheChannels != null && !cacheChannels.isEmpty()) {
                channelSourceList.clear();
                channelSourceList.addAll(cacheChannels);
                if (dataLoadListener != null) {
                    dataLoadListener.onLiveSourceLoaded(cacheChannels, true);
                }
                loadEpgCache();
                log("【缓存】直播源缓存加载完成，频道数：" + cacheChannels.size());
            }
        }

        // 网络加载，传入静态弱引用回调
        log("【网络】后台加载最新直播源...");
        LiveSourceLoader.getInstance(getContext()).load(new LiveLoadCallback(this));
    }

    private void loadEpgCache() {
        if (dataLoadListener != null) {
            dataLoadListener.onEpgLoaded();
        }
        log("【EPG】尝试从缓存加载...");
    }

    private void loadEpg() {
        log("【EPG】开始加载节目单...");
        Context ctx = getContext();
        if (ctx == null) return;
        EpgManager.getInstance(ctx).setEpgUrl(UrlConfig.EPG_URL);
        EpgManager.getInstance(ctx).loadEpg(() -> {
            new Handler(Looper.getMainLooper()).post(new EpgFinishRunnable(AppCoreManager.this));
        });
    }

    private List<Channel> parseLiveSource(String content) {
        List<Channel> channels = new ArrayList<>();
        if (TextUtils.isEmpty(content)) return channels;
        String[] lines = content.split("\n");
        String currentName = "";
        String currentGroup = "";
        String currentLogo = "";
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
                    channels.add(new Channel(currentName, playUrl, currentGroup, currentTvgId));
                }
                currentName = "";
                currentGroup = "";
                currentLogo = "";
                currentTvgId = "";
            }
        }
        log("【缓存】解析完成，共 " + channels.size() + " 个频道");
        return channels;
    }

    public boolean hasPlayedWithCache() { return hasPlayedWithCache; }
    public void setHasPlayedWithCache(boolean played) { this.hasPlayedWithCache = played; }
    public List<Channel> getChannelList() { return channelSourceList; }

    // ====================================================================
    // 2. 广播注册/注销
    // ====================================================================
    public void registerReceivers() {
        if (receiversRegistered) return;
        Context ctx = getContext();
        if (ctx == null) return;
        // 使用静态弱引用广播类
        toggleControllerReceiver = new ToggleBroadcastReceiver(this);
        refreshReceiver = new RefreshBroadcastReceiver(this);
        try {
            ctx.registerReceiver(toggleControllerReceiver, new IntentFilter("com.tv.live.TOGGLE_CONTROL"));
            ctx.registerReceiver(refreshReceiver, new IntentFilter("com.tv.live.REFRESH_LIVE_AND_EPG"));
            receiversRegistered = true;
            SettingsActivity.logOperation("【广播】广播接收器已注册");
        } catch (Exception e) {
            SettingsActivity.logOperation("【广播】广播注册失败：" + e.getMessage());
        }
    }

    public void unregisterReceivers() {
        if (!receiversRegistered) return;
        Context ctx = getContext();
        try {
            if (toggleControllerReceiver != null) {
                ctx.unregisterReceiver(toggleControllerReceiver);
                toggleControllerReceiver = null;
            }
            if (refreshReceiver != null) {
                ctx.unregisterReceiver(refreshReceiver);
                refreshReceiver = null;
            }
            receiversRegistered = false;
            SettingsActivity.logOperation("【广播】广播接收器已注销");
        } catch (Exception e) {
            SettingsActivity.logOperation("【广播】广播注销失败：" + e.getMessage());
        }
    }

    public boolean isControllerVisible() { return isControllerVisible; }

    // ====================================================================
    // 3. 生命周期
    // ====================================================================
    public boolean onPause() {
        if (isOpeningSettings) {
            SettingsActivity.logOperation("【主页】onPause -> 打开设置，继续播放");
            return false;
        }
        SettingsActivity.logOperation("【主页】onPause -> 切后台");
        SettingsActivity.logOperation("【系统】APP切到后台");
        if (playerManager != null) playerManager.onBackground();
        return true;
    }

    public boolean onResume() {
        if (isOpeningSettings) {
            isOpeningSettings = false;
            SettingsActivity.logOperation("【主页】onResume -> 从设置返回");
            return false;
        }
        SettingsActivity.logOperation("【主页】onResume -> 回到前台");
        SettingsActivity.logOperation("【系统】APP回到前台");
        if (playerManager != null) playerManager.onForeground();
        return true;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            SettingsActivity.logOperation("【主页】窗口获得焦点");
        } else {
            SettingsActivity.logOperation("【主页】窗口失去焦点");
        }
    }

    public void onDestroy() {
        SettingsActivity.logOperation("【主页】onDestroy -> 页面销毁");
        SettingsActivity.logOperation("【系统】APP退出");
        unregisterReceivers();
        if (timeoutHandler != null) {
            timeoutHandler.removeCallbacksAndMessages(null);
        }
        if (playerManager != null) {
            playerManager.release();
        }
        channelSourceList = null;
    }

    public void beforeOpenSettings() {
        isOpeningSettings = true;
        SettingsActivity.logOperation("【系统】打开设置页面");
    }
    public boolean isOpeningSettings() { return isOpeningSettings; }

    // ====================================================================
    // 频道自动切台逻辑
    // ====================================================================
    public void setOnSourceSkipListener(OnSourceSkipListener listener) {
        this.sourceSkipListener = listener;
    }
    public boolean handleSourceFailed(String currentChannelName) {
        consecutiveFailedCount++;
        int count = consecutiveFailedCount;
        if (sourceSkipListener != null) {
            sourceSkipListener.onSourceFailed(currentChannelName, count);
        }
        SettingsActivity.logOperation("【自动切台】频道「" + currentChannelName + "」失效，连续" + count + "个");
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

    // ====================================================================
    // 远程配置更新
    // ====================================================================
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appConfig.setCustomUrls(liveUrl, epgUrl);
        if (liveUrl != null) UrlConfig.LIVE_URL = liveUrl;
        if (epgUrl != null) UrlConfig.EPG_URL = epgUrl;
        log("【远程配置】更新直播源：" + liveUrl);
        log("【远程配置】更新EPG：" + epgUrl);
        SettingsActivity.logOperation("【远程配置】更新直播源/EPG地址");
        hasPlayedWithCache = false;
        loadLiveAndEpg();
    }

    // ====================================================================
    // 监听器绑定
    // ====================================================================
    public void setOnDataLoadListener(OnDataLoadListener listener) {
        this.dataLoadListener = listener;
    }
    public void setOnRefreshListener(OnRefreshListener listener) {
        this.refreshListener = listener;
    }

    // ====================================================================
    // 日志工具
    // ====================================================================
    private void log(String msg) {
        SettingsActivity.log(msg);
    }

    // ====================================================================
    // ✅ 完整release() 满足全部清理要求
    // ====================================================================
    public void release() {
        // 1. 执行销毁逻辑（注销广播、移除Handler任务、释放播放器）
        onDestroy();

        // 2. 清空所有监听器
        dataLoadListener = null;
        refreshListener = null;
        sourceSkipListener = null;
        toggleControllerReceiver = null;
        refreshReceiver = null;

        // 3. 销毁Handler，清空所有延迟任务
        if (timeoutHandler != null) {
            timeoutHandler.removeCallbacksAndMessages(null);
            timeoutHandler = null;
        }

        // 4. 清空弱引用上下文
        if (contextRef != null) {
            contextRef.clear();
            contextRef = null;
        }

        // 5. 置空全部资源引用
        playerManager = null;
        appConfig = null;
        cacheManager = null;
        channelSourceList = null;

        // 6. 重置状态标记
        isLoading = false;
        isOpeningSettings = false;
        isControllerVisible = false;
        consecutiveFailedCount = 0;
        hasPlayedWithCache = false;
        receiversRegistered = false;
    }
}
