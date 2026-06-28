package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电视直播播放器管理类（单例）
 * 核心能力：
 * 1. 基于ExoPlayer的直播流播放（支持HLS/m3u8、普通Progressive流）
 * 2. 解码器模式切换（自动/硬解/软解）
 * 3. 播放卡顿检测&自动重试
 * 4. 直播信息（分辨率、码率、音频格式）获取
 * 5. 播放状态监听、频道号显示/隐藏
 */
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";

    // 解码器模式
    public static final int DECODER_MODE_AUTO = 0;    // 自动（硬解优先）
    public static final int DECODER_MODE_HARD = 1;    // 强制硬解
    public static final int DECODER_MODE_SOFT = 2;    // 优先软解

    // 重试配置
    private static final int MAX_RETRY_COUNT = 2;
    private static final long RETRY_DELAY_MS = 1000;
    // 卡顿检测配置
    private static final long STUCK_TIMEOUT = 10000;   // 10秒无进度判定为卡顿
    private static final long STUCK_CHECK_INTERVAL = 2000; // 2秒检测一次卡顿
    // 频道号显示配置
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000; // 显示3秒后隐藏

    // 单例
    private static volatile TVPlayerManager instance;
    private final Context appContext; // 持有Application Context避免内存泄漏

    // 播放器核心
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;

    // 解码器相关
    private int mDecoderMode = DECODER_MODE_AUTO;
    @Deprecated
    private boolean useSoftwareDecoder = false;
    private boolean hasSwitchedDecoder = false;
    private long initialPlayStartTime = 0;

    // 性能统计
    private int bufferCount = 0;
    private long totalStallTime = 0;
    private boolean isStalled = false;
    private long lastStallStartTime = 0;

    // 重试相关
    private int retryCount = 0;
    private boolean isRetrying = false;
    private Runnable retryRunnable;

    // 卡顿检测
    private Handler stuckHandler;
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private Runnable stuckCheckRunnable;

    // 通用Handler（主线程）
    private Handler mHandler;
    private Runnable hideChannelRunnable;

    // 监听回调
    private OnPlayStateListener playStateListener;
    private OnSourceFailedListener sourceFailedListener;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;

    // 播放状态
    private boolean isPlaying = false;
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 广播接收器（解码器模式切换）
    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;

    // 标记是否已释放（防止重复释放）
    private final AtomicBoolean isReleased = new AtomicBoolean(false);

    // ======================== 单例 ========================
    public static TVPlayerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) {
                    // 必须传入ApplicationContext，避免Activity Context内存泄漏
                    instance = new TVPlayerManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private TVPlayerManager(Context context) {
        this.appContext = context;
        this.mHandler = new Handler(Looper.getMainLooper());
        this.stuckHandler = new Handler(Looper.getMainLooper());
        initRunnables();
        initPlayer();
    }

    // ======================== 初始化 ========================
    /**
     * 初始化各类Runnable（避免匿名内部类重复创建）
     */
    private void initRunnables() {
        // 隐藏频道号Runnable
        hideChannelRunnable = () -> hideChannelNum();

        // 卡顿检测Runnable
        stuckCheckRunnable = () -> {
            if (isReleased.get() || player == null || !player.isPlaying()) {
                postStuckCheckDelay();
                return;
            }

            try {
                long currentPosition = player.getCurrentPosition();
                long now = System.currentTimeMillis();

                if (currentPosition != lastPosition) {
                    // 播放进度有更新，重置卡顿检测
                    lastPosition = currentPosition;
                    lastPositionUpdateTime = now;
                } else {
                    // 进度无更新，检查是否超过卡顿阈值
                    if (now - lastPositionUpdateTime > STUCK_TIMEOUT) {
                        Log.w(TAG, "检测到播放卡住（" + (now - lastPositionUpdateTime) + "ms无进度），自动重试...");
                        SettingsActivity.logOperation("【播放器】检测到播放卡住，准备自动重试");
                        autoRetry("播放卡住（无进度更新）");
                        return;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "卡顿检测异常", e);
            }
            postStuckCheckDelay();
        };
    }

    /**
     * 初始化ExoPlayer
     */
    private void initPlayer() {
        if (isReleased.get()) return;

        // 1. 构建渲染器工厂（指定解码器策略）
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(appContext);
        SoftwareFirstMediaCodecSelector codecSelector = new SoftwareFirstMediaCodecSelector(mDecoderMode);
        renderersFactory.setMediaCodecSelector(codecSelector);

        // 2. 打印解码器模式日志
        logDecoderMode();

        // 3. 构建加载控制（缓冲配置）
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        2000,    // 最小缓冲时间
                        50000,   // 最大缓冲时间
                        300,     // 缓冲播放触发时间
                        500      // 缓冲重试时间
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        // 4. 创建ExoPlayer
        player = new ExoPlayer.Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 5. 检测系统解码器信息
        checkSystemCodecs();

        // 6. 初始化播放器监听
        initPlayerListener();

        // 7. Cookie配置（兼容旧版WebView）
        CookieSyncManager.createInstance(appContext);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    /**
     * 打印当前解码器模式日志
     */
    private void logDecoderMode() {
        switch (mDecoderMode) {
            case DECODER_MODE_SOFT:
                Log.d(TAG, "【解码器】软解模式：优先使用系统软件解码器");
                SettingsActivity.logOperation("【解码器】初始化：系统软解模式（优先）");
                break;
            case DECODER_MODE_HARD:
                Log.d(TAG, "【解码器】硬解模式：只用系统硬解码器");
                SettingsActivity.logOperation("【解码器】初始化：系统硬解模式（强制）");
                break;
            case DECODER_MODE_AUTO:
            default:
                Log.d(TAG, "【解码器】自动模式：系统硬解优先");
                SettingsActivity.logOperation("【解码器】初始化：自动模式（系统硬解优先）");
                break;
        }
    }

    /**
     * 检测系统H.264解码器信息
     */
    private void checkSystemCodecs() {
        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos(
                    "video/avc", false, false);
            int softCount = 0;
            int hardCount = 0;
            StringBuilder softNames = new StringBuilder();
            StringBuilder hardNames = new StringBuilder();

            for (MediaCodecInfo codec : h264Codecs) {
                String name = codec.name;
                if (isSoftwareDecoder(name)) {
                    softCount++;
                    if (softCount <= 3) { // 最多记录前3个
                        if (softCount > 1) softNames.append(", ");
                        softNames.append(name);
                    }
                } else {
                    hardCount++;
                    if (hardCount <= 3) { // 最多记录前3个
                        if (hardCount > 1) hardNames.append(", ");
                        hardNames.append(name);
                    }
                }
            }

            Log.d(TAG, "【解码器】H.264 解码器统计：软解 " + softCount + " 个，硬解 " + hardCount + " 个");
            Log.d(TAG, "【解码器】软解解码器：" + softNames);
            Log.d(TAG, "【解码器】硬解解码器：" + hardNames);
            SettingsActivity.logOperation("【解码器】系统解码器：软解 " + softCount + " 个，硬解 " + hardCount + " 个");

            if (softCount == 0) {
                Log.w(TAG, "【解码器】⚠️ 系统未找到软件解码器，软解模式可能不生效");
                SettingsActivity.logOperation("【解码器】⚠️ 警告：未找到系统软件解码器");
            }
        } catch (Exception e) {
            Log.e(TAG, "【解码器】检测系统解码器失败：" + e.getMessage(), e);
        }
    }

    /**
     * 初始化播放器监听
     */
    private void initPlayerListener() {
        if (playerListener != null) {
            player.removeListener(playerListener);
        }

        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage(), error);
                if (playStateListener != null) {
                    playStateListener.onPlayError(error.getMessage());
                }
                autoRetry("播放错误：" + error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (isReleased.get()) return;

                switch (state) {
                    case Player.STATE_READY:
                        // 播放就绪
                        updateWakeLock(true);
                        notifyLiveInfoUpdate();
                        showChannelAndAutoHide();
                        if (playStateListener != null) playStateListener.onPlayReady();
                        retryCount = 0;
                        isRetrying = false;
                        startStuckDetection();

                        // 自动切换解码器（硬解卡顿→软解）
                        checkAutoSwitchDecoder();
                        break;

                    case Player.STATE_BUFFERING:
                        // 缓冲中
                        if (playStateListener != null) playStateListener.onBuffering();
                        lastPositionUpdateTime = System.currentTimeMillis();
                        bufferCount++;
                        if (!isStalled) {
                            isStalled = true;
                            lastStallStartTime = System.currentTimeMillis();
                        }
                        if (bufferCount == 1) {
                            SettingsActivity.logOperation("【播放器】开始缓冲（第1次）");
                        }
                        break;

                    case Player.STATE_ENDED:
                        // 播放结束（直播流一般不会触发）
                        if (playStateListener != null) playStateListener.onPlayEnd();
                        autoRetry("播放结束");
                        break;

                    case Player.STATE_IDLE:
                        // 空闲状态
                        if (playStateListener != null) playStateListener.onIdle();
                        updateWakeLock(false);
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isReleased.get()) return;

                if (isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                    // 卡顿结束，统计时长
                    if (isStalled) {
                        isStalled = false;
                        long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                        totalStallTime += stallDuration;
                        Log.d(TAG, "【性能】卡顿结束，时长：" + stallDuration + "ms，总卡顿：" + totalStallTime + "ms");
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                Log.d(TAG, "视频分辨率变化：" + videoSize.width + "×" + videoSize.height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }

    /**
     * 检查是否需要自动切换解码器（硬解卡顿→软解）
     */
    private void checkAutoSwitchDecoder() {
        if (mDecoderMode == DECODER_MODE_AUTO && !hasSwitchedDecoder && initialPlayStartTime > 0) {
            long playTime = System.currentTimeMillis() - initialPlayStartTime;
            // 播放15秒内缓冲超过1次，判定为硬解卡顿，切换软解
            if (playTime < 15000 && bufferCount > 1) {
                Log.d(TAG, "【自动切换】硬解卡顿（缓冲" + bufferCount + "次），自动切换到系统软解");
                SettingsActivity.logOperation("【解码器】硬解卡顿（缓冲" + bufferCount + "次），自动切换到系统软解");
                hasSwitchedDecoder = true;
                setDecoderMode(DECODER_MODE_SOFT);
            }
        }
    }

    // ======================== 卡顿检测 ========================
    /**
     * 启动卡顿检测
     */
    private void startStuckDetection() {
        if (isReleased.get()) return;
        stopStuckDetection();
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        postStuckCheckDelay();
    }

    /**
     * 停止卡顿检测
     */
    private void stopStuckDetection() {
        if (stuckHandler != null && stuckCheckRunnable != null) {
            stuckHandler.removeCallbacks(stuckCheckRunnable);
        }
    }

    /**
     * 延迟发布卡顿检测任务
     */
    private void postStuckCheckDelay() {
        if (isReleased.get() || stuckHandler == null || stuckCheckRunnable == null) return;
        stuckHandler.postDelayed(stuckCheckRunnable, STUCK_CHECK_INTERVAL);
    }

    // ======================== 自动重试 ========================
    /**
     * 取消重试任务
     */
    private void cancelRetry() {
        if (mHandler != null && retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }

    /**
     * 自动重试播放
     * @param reason 重试原因
     */
    private void autoRetry(String reason) {
        if (isReleased.get() || isRetrying) return;

        // 重试次数达上限，回调失效源
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT + "，判定为失效源，原因：" + reason);
            SettingsActivity.logOperation("【播放器】重试" + MAX_RETRY_COUNT + "次均失败，判定为失效源");
            if (sourceFailedListener != null) {
                mHandler.post(() -> sourceFailedListener.onSourceFailed());
            }
            return;
        }

        // 执行重试
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);
        SettingsActivity.logOperation("【播放器】自动重试（第" + retryCount + "次），原因：" + reason);

        retryRunnable = () -> {
            isRetrying = false;
            if (!TextUtils.isEmpty(currentUrl) && !isReleased.get()) {
                playUrlInternal(currentUrl);
            }
            retryRunnable = null;
        };
        mHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    // ======================== 解码器模式 ========================
    /**
     * 设置解码器模式
     * @param mode 解码器模式（AUTO/HARD/SOFT）
     */
    public void setDecoderMode(int mode) {
        if (isReleased.get() || mDecoderMode == mode) return;

        mDecoderMode = mode;
        useSoftwareDecoder = (mode == DECODER_MODE_SOFT);

        // 打印切换日志
        String decoderType = switch (mode) {
            case DECODER_MODE_HARD -> "系统硬解码（强制）";
            case DECODER_MODE_SOFT -> "系统软解码（优先）";
            default -> "自动模式（硬解优先）";
        };
        Log.d(TAG, "切换解码器模式：" + decoderType);
        SettingsActivity.logOperation("【解码器】切换模式：" + decoderType);

        // 重建播放器
        try {
            stopStuckDetection();
            cancelRetry();

            if (player != null && playerListener != null) {
                player.removeListener(playerListener);
            }
            if (player != null) {
                player.release();
                player = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放播放器异常", e);
        }

        initPlayer();

        // 重新绑定PlayerView
        if (playerView != null) {
            playerView.setPlayer(player);
        }

        // 重新播放当前URL
        if (!TextUtils.isEmpty(currentUrl)) {
            retryCount = 0;
            isRetrying = false;
            hasSwitchedDecoder = true;
            playUrlInternal(currentUrl);
        }
    }

    public int getDecoderMode() {
        return mDecoderMode;
    }

    /**
     * 兼容旧版API - 设置是否使用软解
     * @param useSoftware 是否使用软解
     */
    @Deprecated
    public void setSoftwareDecoder(boolean useSoftware) {
        setDecoderMode(useSoftware ? DECODER_MODE_SOFT : DECODER_MODE_AUTO);
    }

    // ======================== 广播接收器（解码器模式切换） ========================
    /**
     * 注册解码器模式切换广播
     */
    public void registerDecoderModeReceiver() {
        if (isReleased.get() || decoderReceiverRegistered) return;

        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences(
                                "app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = switch (modeStr) {
                            case "hard" -> DECODER_MODE_HARD;
                            case "soft" -> DECODER_MODE_SOFT;
                            default -> DECODER_MODE_AUTO;
                        };
                        setDecoderMode(mode);

                        String modeName = switch (mode) {
                            case DECODER_MODE_HARD -> "硬解";
                            case DECODER_MODE_SOFT -> "软解（兼容性好）";
                            default -> "自动（推荐）";
                        };
                        SettingsActivity.logOperation("【解码器】收到广播，切换到：" + modeName);
                    }
                }
            };

            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            appContext.registerReceiver(decoderModeReceiver, filter);
            decoderReceiverRegistered = true;
            SettingsActivity.logOperation("【解码器】广播接收器已注册");
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播接收器失败：" + e.getMessage(), e);
            SettingsActivity.logOperation("【解码器】广播注册失败：" + e.getMessage());
        }
    }

    /**
     * 注销解码器模式切换广播
     */
    public void unregisterDecoderModeReceiver() {
        if (isReleased.get() || !decoderReceiverRegistered || decoderModeReceiver == null) return;

        try {
            appContext.unregisterReceiver(decoderModeReceiver);
            decoderModeReceiver = null;
            decoderReceiverRegistered = false;
            SettingsActivity.logOperation("【解码器】广播接收器已注销");
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播接收器失败：" + e.getMessage(), e);
        }
    }

    // ======================== 前后台切换 ========================
    /**
     * 切前台时恢复播放
     */
    public void onForeground() {
        if (isReleased.get()) return;
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                player.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "切前台异常", e);
        }
    }

    /**
     * 切后台时暂停播放
     */
    public void onBackground() {
        if (isReleased.get()) return;
        try {
            if (player != null) {
                player.pause();
            }
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }

    // ======================== 播放控制 ========================
    /**
     * 绑定PlayerView
     * @param view 播放器视图
     */
    public void attachPlayerView(PlayerView view) {
        if (isReleased.get()) return;
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false); // 隐藏默认控制栏
    }

    /**
     * 更新唤醒锁/屏幕常亮
     * @param enable 是否开启
     */
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }

    /**
     * 获取请求头（适配虎牙/斗鱼等平台）
     * @param url 播放URL
     * @return 请求头Map
     */
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");

        // 适配虎牙/斗鱼Referer
        boolean isHuya = url.contains("huya.com") || url.contains("huya.cn");
        boolean isDouyu = url.contains("douyu.com") || url.contains("douyucdn.cn");
        if (isHuya) {
            headers.put("Referer", "https://www.huya.com/");
            Log.d(TAG, "虎牙直播，设置虎牙Referer");
        } else if (isDouyu) {
            headers.put("Referer", "https://www.douyu.com/");
            Log.d(TAG, "斗鱼直播，设置斗鱼Referer");
        } else {
            headers.put("Referer", "https://www.huya.com/"); // 默认虎牙Referer
        }

        // 添加Cookie
        String cookies = CookieManager.getInstance().getCookie(url);
        if (!TextUtils.isEmpty(cookies)) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    /**
     * 播放指定URL（对外API）
     * @param url 播放地址
     */
    public void play(String url) {
        playUrl(url);
    }

    public void playUrl(String url) {
        if (isReleased.get() || TextUtils.isEmpty(url)) return;

        // 重置播放状态
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        hasSwitchedDecoder = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();

        SettingsActivity.logOperation("【播放器】开始加载新频道：" + url);
        playUrlInternal(url);
    }

    /**
     * 重置性能统计
     */
    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }

    /**
     * 内部播放逻辑
     * @param url 播放地址
     */
    private void playUrlInternal(String url) {
        if (isReleased.get() || player == null || TextUtils.isEmpty(url)) return;

        currentUrl = url.trim();
        Log.d(TAG, "开始播放：" + currentUrl);
        initialPlayStartTime = System.currentTimeMillis();

        try {
            // 1. 构建HTTP数据源（支持重定向日志）
            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true); // 允许跨协议重定向

            // 2. 构建MediaItem
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);

            // 3. 根据URL后缀判断流类型（HLS/m3u8 或 普通流）
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                Log.d(TAG, "流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                Log.d(TAG, "流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            // 4. 设置媒体源并播放
            player.setMediaSource(mediaSource, true); // 重置播放状态
            player.prepare();
            player.play();
            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放初始化异常", e);
            autoRetry("播放异常：" + e.getMessage());
        }
    }

    // ======================== 缩放模式 ========================
    public enum ScaleMode {
        FIT,    // 适配（保持比例，黑边）
        FILL,   // 填充（拉伸填满）
        ZOOM    // 缩放（裁剪黑边）
    }

    /**
     * 设置视频缩放模式
     * @param mode 缩放模式
     */
    public void setScaleMode(ScaleMode mode) {
        if (isReleased.get() || playerView == null) return;

        try {
            int resizeMode = switch (mode) {
                case FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT;
                case FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL;
                case ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            };
            playerView.setResizeMode(resizeMode);
        } catch (Exception e) {
            Log.e(TAG, "设置缩放模式异常", e);
        }
    }

    // ======================== 频道号显示 ========================
    /**
     * 设置当前频道号
     * @param num 频道号
     */
    public void setCurrentChannelNumber(int num) {
        currentChannelNumber = num;
    }

    /**
     * 绑定频道号显示的TextView
     * @param textView 频道号文本视图
     */
    public void bindChannelText(TextView textView) {
        channelNumberTextView = textView;
    }

    /**
     * 显示频道号并自动隐藏
     */
    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            mHandler.removeCallbacks(hideChannelRunnable);
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
            mHandler.postDelayed(hideChannelRunnable, CHANNEL_NUM_HIDE_DELAY);
        }
    }

    /**
     * 隐藏频道号
     */
    private void hideChannelNum() {
        if (channelNumberTextView != null) {
            channelNumberTextView.setVisibility(View.GONE);
        }
    }

    // ======================== 直播信息 ========================
    /**
     * 直播信息实体类
     */
    public static class LiveInfo {
        public String resolution = "未知"; // 分辨率（如 1920×1080）
        public String bitrate = "0";       // 码率（如 2.5 Mbps）
        public String audio = "未知";      // 音频格式（如 audio/mp4a-latm 48kHz）
        public String format = "未知";     // 视频格式（如 video/avc）
    }

    /**
     * 获取当前直播信息
     * @return 直播信息
     */
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        if (isReleased.get() || player == null) return info;

        try {
            // 获取视频信息
            Format videoFormat = player.getVideoFormat();
            if (videoFormat != null) {
                if (videoFormat.width > 0 && videoFormat.height > 0) {
                    info.resolution = videoFormat.width + "×" + videoFormat.height;
                }
                info.format = videoFormat.sampleMimeType;
                if (videoFormat.bitrate > 0) {
                    float mbps = videoFormat.bitrate / 1000000f;
                    info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", mbps);
                }
            }

            // 获取音频信息
            Format audioFormat = player.getAudioFormat();
            if (audioFormat != null) {
                info.audio = audioFormat.sampleMimeType;
                if (audioFormat.sampleRate > 0) {
                    info.audio += " " + (audioFormat.sampleRate / 1000) + "kHz";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取直播信息异常", e);
        }
        return info;
    }

    /**
     * 通知直播信息更新
     */
    private void notifyLiveInfoUpdate() {
        if (isReleased.get() || liveInfoUpdateListener == null) return;
        liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
    }

    // ======================== 监听回调 ========================
    /**
     * 播放状态监听
     */
    public interface OnPlayStateListener {
        void onIdle();          // 空闲
        void onBuffering();     // 缓冲中
        void onPlayReady();     // 播放就绪
        void onPlayEnd();       // 播放结束
        void onPlayError(String msg); // 播放错误
    }

    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.playStateListener = listener;
    }

    /**
     * 播放源失效监听
     */
    public interface OnSourceFailedListener {
        void onSourceFailed(); // 重试达上限，源失效
    }

    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        this.sourceFailedListener = listener;
    }

    /**
     * 直播信息更新监听
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info); // 直播信息更新
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.liveInfoUpdateListener = listener;
    }

    // ======================== 基础控制 ========================
    /**
     * 暂停播放
     */
    public void pause() {
        if (isReleased.get()) return;
        try {
            if (player != null) player.pause();
        } catch (Exception e) {
            Log.e(TAG, "暂停异常", e);
        }
    }

    /**
     * 恢复播放
     */
    public void resume() {
        if (isReleased.get()) return;
        try {
            if (player != null) player.play();
        } catch (Exception e) {
            Log.e(TAG, "恢复异常", e);
        }
    }

    /**
     * 释放播放器资源（单例销毁）
     */
    public void release() {
        if (!isReleased.compareAndSet(false, true)) return; // 确保只释放一次

        try {
            // 停止所有任务
            stopStuckDetection();
            cancelRetry();
            if (mHandler != null) {
                mHandler.removeCallbacks(hideChannelRunnable);
            }

            // 取消屏幕常亮
            updateWakeLock(false);

            // 注销广播
            unregisterDecoderModeReceiver();

            // 释放播放器
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                }
                player.release();
                player = null;
            }

            // 清空引用
            playerView = null;
            channelNumberTextView = null;
            playStateListener = null;
            sourceFailedListener = null;
            liveInfoUpdateListener = null;

            // 单例置空
            instance = null;

            Log.d(TAG, "播放器资源已释放");
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }

    // ======================== 工具方法 ========================
    /**
     * 判断是否为软件解码器
     * @param codecName 解码器名称
     * @return true=软解，false=硬解
     */
    private static boolean isSoftwareDecoder(String codecName) {
        if (TextUtils.isEmpty(codecName)) return false;
        String lowerName = codecName.toLowerCase();
        // 谷歌/安卓系统软解码器特征
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    /**
     * 获取日志时间戳
     * @return 格式化时间（HH:mm:ss）
     */
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    // ======================== 解码器选择器 ========================
    /**
     * 解码器选择器（按模式优先选择软解/硬解）
     */
    private static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;

        public SoftwareFirstMediaCodecSelector(int mode) {
            this.decoderMode = mode;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(
                String mimeType,
                boolean requiresSecureDecoder,
                boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {

            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

            if (allCodecs == null || allCodecs.isEmpty()) {
                return allCodecs;
            }

            switch (decoderMode) {
                case DECODER_MODE_HARD:
                    // 强制硬解：只返回硬解码器
                    List<MediaCodecInfo> hardCodecs = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (!isSoftwareDecoder(codec.name)) {
                            hardCodecs.add(codec);
                        }
                    }
                    return hardCodecs;

                case DECODER_MODE_SOFT:
                    // 优先软解：软解码器在前，硬解码器在后
                    List<MediaCodecInfo> softCodecs = new ArrayList<>();
                    List<MediaCodecInfo> hardCodecs2 = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (isSoftwareDecoder(codec.name)) {
                            softCodecs.add(codec);
                        } else {
                            hardCodecs2.add(codec);
                        }
                    }
                    List<MediaCodecInfo> result = new ArrayList<>();
                    result.addAll(softCodecs);
                    result.addAll(hardCodecs2);
                    return result;

                case DECODER_MODE_AUTO:
                default:
                    // 自动模式：返回所有解码器（系统默认优先硬解）
                    return allCodecs;
            }
        }
    }
}
