package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
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
import com.tv.live.util.NetUtil;
import com.tv.live.exception.RedirectFailedException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.Headers;

public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;
    private static final int MAX_RETRY_COUNT = 2;
    private static final long STUCK_TIMEOUT = 10000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    // ====================== 新增：重定向SP存储Key（与Settings完全对齐） ======================
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static TVPlayerManager instance;
    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;
    private String currentChannelName = "";
    private int mDecoderMode = DECODER_MODE_AUTO;
    @Deprecated
    private boolean useSoftwareDecoder = false;
    private boolean hasSwitchedDecoder = false;
    private long initialPlayStartTime = 0;
    private int bufferCount = 0;
    private long totalStallTime = 0;
    private boolean isStalled = false;
    private long lastStallStartTime = 0;
    private int retryCount = 0;
    private boolean isRetrying = false;
    private Runnable retryRunnable;
    private Handler stuckHandler;
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private Runnable stuckCheckRunnable;
    private Handler mHandler;
    private Runnable hideChannelRunnable;
    private OnPlayStateListener listener;
    private OnSourceFailedListener sourceFailedListener;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;
    private boolean isPlaying = false;
    private SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;
    private BroadcastReceiver rendererModeReceiver;
    private boolean rendererReceiverRegistered = false;
    // ============================================================
    // PlayerView 重建监听器，用于重新绑定手势和焦点
    // ============================================================
    private OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    public interface OnPlayerViewRecreatedListener {
        void onPlayerViewRecreated(PlayerView newPlayerView);
    }
    public void setOnPlayerViewRecreatedListener(OnPlayerViewRecreatedListener listener) {
        this.onPlayerViewRecreatedListener = listener;
    }
    // ============================================================
    // 渲染器切换锁定状态，防止自动解码器切换误触发
    // ============================================================
    private boolean isRenderingSwitching = false;
    public static TVPlayerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) {
                    instance = new TVPlayerManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }
    private TVPlayerManager(Context context) {
        this.context = context;
        mHandler = new Handler(Looper.getMainLooper());
        stuckHandler = new Handler(Looper.getMainLooper());
        hideChannelRunnable = new Runnable() {
            @Override
            public void run() {
                hideChannelNum();
            }
        };
        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null || !player.isPlaying()) {
                    stuckHandler.postDelayed(this, 2000);
                    return;
                }
                try {
                    long currentPosition = player.getCurrentPosition();
                    long now = System.currentTimeMillis();
                    if (currentPosition != lastPosition) {
                        lastPosition = currentPosition;
                        lastPositionUpdateTime = now;
                    } else {
                        if (now - lastPositionUpdateTime > STUCK_TIMEOUT) {
                            Log.w(TAG, "检测到播放卡住，自动重试...");
                            SettingsActivity.logOperation("【播放器】检测到播放卡住，准备自动重试");
                            autoRetry("播放卡住");
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "卡住检测异常", e);
                }
                stuckHandler.postDelayed(this, 2000);
            }
        };
        initPlayer();
    }
    private void initPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        SoftwareFirstMediaCodecSelector codecSelector =
                new SoftwareFirstMediaCodecSelector(mDecoderMode);
        renderersFactory.setMediaCodecSelector(codecSelector);
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
        // 🟢【核心优化】将最大缓冲从 50秒 降为 15秒，防止 1GB 内存被视频数据撑爆！
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        2000,
                        15000,
                        300,
                        500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();
        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos(
                    "video/avc", false, false);
            int softCount = 0;
            int hardCount = 0;
            StringBuilder softNames = new StringBuilder();
            StringBuilder hardNames = new StringBuilder();
            for (MediaCodecInfo codec : h264Codecs) {
                String name = codec.name;
                // 🟢 修复：使用名称前缀匹配替代 isSoftwareOnly()
                if (isSoftwareDecoder(codec)) {
                    softCount++;
                    if (softCount <= 3) {
                        if (softCount > 1) softNames.append(", ");
                        softNames.append(name);
                    }
                } else {
                    hardCount++;
                    if (hardCount <= 3) {
                        if (hardCount > 1) hardNames.append(", ");
                        hardNames.append(name);
                    }
                }
            }
            Log.d(TAG, "【解码器】H.264 解码器统计：软解 " + softCount
                    + " 个，硬解 " + hardCount + " 个");
            Log.d(TAG, "【解码器】软解解码器：" + softNames.toString());
            Log.d(TAG, "【解码器】硬解解码器：" + hardNames.toString());
            SettingsActivity.logOperation("【解码器】系统解码器：软解 " + softCount
                    + " 个，硬解 " + hardCount + " 个");
            if (softCount == 0) {
                Log.w(TAG, "【解码器】⚠️ 系统未找到软件解码器，软解模式可能不生效");
                SettingsActivity.logOperation("【解码器】⚠️ 警告：未找到系统软件解码器");
            }
        } catch (Exception e) {
            Log.e(TAG, "【解码器】检测系统解码器失败：" + e.getMessage());
        }
        initPlayerListener();
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // 🟢 修复核心：使用安卓普遍的软解前缀匹配，兼容所有旧版本 Media3 库编译
    private static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        // 谷歌标准的软件解码器总是以这两个前缀开头
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                // 区分重定向异常，禁止重试、禁止判定源失效
                Throwable rootCause = error.getCause();
                boolean isRedirectError = false;
                while (rootCause != null) {
                    if (rootCause instanceof RedirectFailedException) {
                        isRedirectError = true;
                        RedirectFailedException redirectErr = (RedirectFailedException) rootCause;
                        SettingsActivity.logOperation("【播放器】重定向拦截失败：" + redirectErr.getMessage()
                                + " Location=" + redirectErr.getLocation());
                        break;
                    }
                    rootCause = rootCause.getCause();
                }
                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
                if (!isRedirectError) {
                    autoRetry("播放错误：" + error.getMessage());
                } else {
                    SettingsActivity.logOperation("【播放器】检测为重定向失败，跳过自动重试");
                }
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    if (listener != null) listener.onPlayReady();
                    retryCount = 0;
                    isRetrying = false;
                    startStuckDetection();
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                    // 切台加载中禁止自动切换解码器
                    if (mDecoderMode == DECODER_MODE_AUTO && !hasSwitchedDecoder
                            && !isRenderingSwitching
                            && initialPlayStartTime > 0
                            && System.currentTimeMillis() - initialPlayStartTime < 15000
                            && bufferCount > 1) {
                        if (isRetrying || TextUtils.isEmpty(currentUrl)) {
                            return;
                        }
                        Log.d(TAG, "【自动切换】硬解卡顿，自动切换到系统软解");
                        SettingsActivity.logOperation("【解码器】硬解卡顿（缓冲"
                                + bufferCount + "次），自动切换到系统软解");
                        hasSwitchedDecoder = true;
                        setDecoderMode(DECODER_MODE_SOFT);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();
                    lastPositionUpdateTime = System.currentTimeMillis();
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                    }
                    if (bufferCount == 1) {
                        SettingsActivity.logOperation("【播放器】开始缓冲（第1次）");
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (listener != null) listener.onPlayEnd();
                    autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    if (listener != null) listener.onIdle();
                    updateWakeLock(false);
                }
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                    if (isStalled) {
                        isStalled = false;
                        long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                        Log.d(TAG, "【性能】卡顿结束，时长：" + stallDuration + "ms，总卡顿：" + totalStallTime + "ms");
                    }
                }
            }
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int width = videoSize.width;
                int height = videoSize.height;
                Log.d(TAG, "视频分辨率变化：" + width + "×" + height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }
    private void startStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        stuckHandler.postDelayed(stuckCheckRunnable, 2000);
    }
    private void stopStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
    }
    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }
    private void autoRetry(String reason) {
        // 重定向错误直接终止重试
        if (reason.contains("RedirectFailedException") || reason.contains("重定向")) {
            SettingsActivity.logOperation("【播放器】重定向类错误，不执行重试");
            return;
        }
        if (isRetrying) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT + "，判定为失效源");
            SettingsActivity.logOperation("【播放器】重试" + MAX_RETRY_COUNT + "次均失败，判定为失效源");
            if (sourceFailedListener != null) {
                mHandler.post(() -> sourceFailedListener.onSourceFailed());
            }
            return;
        }
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);
        SettingsActivity.logOperation("【播放器】自动重试（第" + retryCount + "次），原因：" + reason);
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                isRetrying = false;
                if (!TextUtils.isEmpty(currentUrl)) {
                    playUrlInternal(currentUrl);
                }
                retryRunnable = null;
            }
        };
        mHandler.postDelayed(retryRunnable, 1000);
    }
    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        useSoftwareDecoder = (mode == DECODER_MODE_SOFT);
        String decoderType;
        switch (mode) {
            case DECODER_MODE_HARD:
                decoderType = "系统硬解码（强制）";
                break;
            case DECODER_MODE_SOFT:
                decoderType = "系统软解码（优先）";
                break;
            case DECODER_MODE_AUTO:
            default:
                decoderType = "自动模式（硬解优先）";
                break;
        }
        Log.d(TAG, "切换解码器模式：" + decoderType);
        SettingsActivity.logOperation("【解码器】切换模式：" + decoderType);
        if (player != null) {
            performDecoderSwitch();
        }
    }
    private void performDecoderSwitch() {
        try {
            stopStuckDetection();
            cancelRetry();
            if (playerListener != null) {
                player.removeListener(playerListener);
            }
            player.release();
            player = null;
        } catch (Exception e) {
            Log.e(TAG, "释放播放器异常", e);
        }
        initPlayer();
        if (playerView != null) {
            playerView.setPlayer(player);
        }
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
    @Deprecated
    public void setSoftwareDecoder(boolean useSoftware) {
        if (useSoftware) {
            setDecoderMode(DECODER_MODE_SOFT);
        } else {
            setDecoderMode(DECODER_MODE_AUTO);
        }
    }
    public void registerDecoderModeReceiver() {
        if (decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences(
                                "app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = DECODER_MODE_AUTO;
                        if ("hard".equals(modeStr)) {
                            mode = DECODER_MODE_HARD;
                        } else if ("soft".equals(modeStr)) {
                            mode = DECODER_MODE_SOFT;
                        }
                        setDecoderMode(mode);
                        String modeName;
                        switch (mode) {
                            case DECODER_MODE_HARD:
                                modeName = "硬解";
                                break;
                            case DECODER_MODE_SOFT:
                                modeName = "软解（兼容性好）";
                                break;
                            case DECODER_MODE_AUTO:
                            default:
                                modeName = "自动（推荐）";
                                break;
                        }
                        SettingsActivity.logOperation("【解码器】收到广播，切换到：" + modeName);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            context.registerReceiver(decoderModeReceiver, filter);
            decoderReceiverRegistered = true;
            SettingsActivity.logOperation("【解码器】广播接收器已注册");
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播接收器失败：" + e.getMessage());
            SettingsActivity.logOperation("【解码器】广播注册失败：" + e.getMessage());
        }
    }
    public void unregisterDecoderModeReceiver() {
        if (!decoderReceiverRegistered) return;
        try {
            if (decoderModeReceiver != null) {
                context.unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
            }
            decoderReceiverRegistered = false;
            SettingsActivity.logOperation("【解码器】广播接收器已注销");
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播接收器失败：" + e.getMessage());
        }
    }
    private void switchRenderer(boolean useTexture) {
        if (playerView == null || context == null) return;
        isRenderingSwitching = true;
        bufferCount = 0;
        long currentPosition = player.getCurrentPosition();
        boolean wasPlaying = player.isPlaying();
        boolean useController = playerView.getUseController();
        ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();
        ViewGroup parent = (ViewGroup) playerView.getParent();
        if (parent == null) return;
        int index = parent.indexOfChild(playerView);
        int styleRes = useTexture ? R.style.PlayerView_Texture : R.style.PlayerView_Surface;
        ContextThemeWrapper themedContext = new ContextThemeWrapper(context, styleRes);
        PlayerView newPlayerView = new PlayerView(themedContext);
        newPlayerView.setLayoutParams(layoutParams);
        newPlayerView.setUseController(useController);
        newPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        newPlayerView.setKeepContentOnPlayerReset(true);
        newPlayerView.setPlayer(player);
        parent.addView(newPlayerView, index, layoutParams);
        playerView.setPlayer(null);
        parent.removeView(playerView);
        playerView = newPlayerView;
        if (currentPosition > 0) {
            player.seekTo(currentPosition);
        }
        if (wasPlaying) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (player != null && !player.isPlaying()) {
                    player.play();
                }
            }, 200);
        }
        if (onPlayerViewRecreatedListener != null) {
            onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
        }
        playerView.requestFocus();
        isRenderingSwitching = false;
        SettingsActivity.logOperation("【渲染器】已切换为：" + (useTexture ? "TextureView" : "SurfaceView") + "（双缓冲无黑屏）");
    }
    public void registerRendererModeReceiver() {
        if (rendererReceiverRegistered) return;
        try {
            rendererModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.RENDERER_TYPE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String mode = sp.getString("renderer_type", "surface");
                        if (playerView != null) {
                            boolean useTexture = "texture".equals(mode);
                            switchRenderer(useTexture);
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            context.registerReceiver(rendererModeReceiver, filter);
            rendererReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册渲染方式广播接收器失败：" + e.getMessage());
        }
    }
    public void unregisterRendererModeReceiver() {
        if (!rendererReceiverRegistered) return;
        try {
            if (rendererModeReceiver != null) {
                context.unregisterReceiver(rendererModeReceiver);
                rendererModeReceiver = null;
            }
            rendererReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销渲染方式广播接收器失败：" + e.getMessage());
        }
    }
    public void onForeground() {
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                player.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "切前台异常", e);
        }
    }
    public void onBackground() {
        try {
            if (player != null) {
                player.pause();
            }
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String rendererMode = sp.getString("renderer_type", "surface");
        boolean useTexture = "texture".equals(rendererMode);
        switchRenderer(useTexture);
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }
    public void play(String url, String channelName) {
        playUrl(url, channelName);
    }
    public void play(String url) {
        playUrl(url, null);
    }
    public void playUrl(String url) {
        playUrl(url, null);
    }
    public void playUrl(String url, String channelName) {
        if (!TextUtils.isEmpty(channelName)) {
            this.currentChannelName = channelName;
        }
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        hasSwitchedDecoder = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();
        SettingsActivity.logOperation("【播放器】开始加载新频道: " + (TextUtils.isEmpty(this.currentChannelName) ? "未知" : this.currentChannelName));
        playUrlInternal(url);
    }
    public void setCurrentChannelName(String name) {
        this.currentChannelName = (name != null) ? name : "";
    }
    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }
    private void playUrlInternal(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);
            SettingsActivity.logOperation("【播放器-数据源】传给底层日志的频道名: [" + currentChannelName + "]");
            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            // ========== 核心逻辑 ==========
            Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(currentUrl);
            Map<String, String> headerMap = new HashMap<>();
            for (String name : globalHeaders.names()) {
                headerMap.put(name, globalHeaders.get(name));
            }
            
            // 🟢【最终修复：只清除污染】彻底清除 Cookie 逻辑！
            // 发送任何 Cookie 都会导致某些 CDN 直接返回 HTTP 403
            // 因此这里全部注释掉，避免 Cookie 污染请求头
            // String cookies = CookieManager.getInstance().getCookie(currentUrl);
            // if (cookies != null) {
            //     headerMap.put("Cookie", cookies);
            // }
            
            httpFactory.setDefaultRequestProperties(headerMap);
            httpFactory.setChannelName(currentChannelName);
            // 读取设置持久化的全部重定向配置
            SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            int maxRedirect = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
            boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
            boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
            boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
            boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
            // 🟢【配套优化】缩短超时时间，在 2.4G 弱网环境下能更快重试
            httpFactory.setMaxRedirects(maxRedirect)
                    .setAllowCrossDomainRedirects(crossDomain)
                    .setAllowCrossProtocolRedirects(crossProto)
                    .setFollowRedirectsWithHeaders(followHeader)
                    .setIgnoreSslErrorRedirect(ignoreSsl)
                    .setConnectTimeoutMs(8000)  // 从 10000 改为 8000
                    .setReadTimeoutMs(10000);   // 从 15000 改为 10000
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                Log.d(TAG, "流格式：HLS (m3u8)");
                HlsMediaSource.Factory hlsFactory = new HlsMediaSource.Factory(httpFactory);
                mediaSource = hlsFactory.createMediaSource(mediaItem);
            } else {
                Log.d(TAG, "流格式：普通流 (Progressive)");
                ProgressiveMediaSource.Factory progFactory = new ProgressiveMediaSource.Factory(httpFactory);
                mediaSource = progFactory.createMediaSource(mediaItem);
            }
            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.play();
            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                RedirectFailedException redirectErr = (RedirectFailedException) e;
                SettingsActivity.logOperation("【播放器重定向失败】码：" + redirectErr.getCode()
                        + " 原地址：" + redirectErr.getOriginUrl()
                        + " 跳转地址：" + redirectErr.getLocation());
                if (listener != null) listener.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            autoRetry("播放异常：" + e.getMessage());
        }
    }
    public enum ScaleMode {
        FIT,
        FILL,
        ZOOM
    }
    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            switch (mode) {
                case FIT:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    break;
                case FILL:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                    break;
                case ZOOM:
                    playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "设置缩放模式异常", e);
        }
    }
    public void setCurrentChannelNumber(int num) {
        currentChannelNumber = num;
    }
    public void bindChannelText(TextView textView) {
        channelNumberTextView = textView;
    }
    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
            mHandler.removeCallbacks(hideChannelRunnable);
            mHandler.postDelayed(hideChannelRunnable, CHANNEL_NUM_HIDE_DELAY);
        }
    }
    private void hideChannelNum() {
        if (channelNumberTextView != null) {
            channelNumberTextView.setVisibility(View.GONE);
        }
    }
    public static class LiveInfo {
        public String resolution = "未知";
        public String bitrate = "0";
        public String audio = "未知";
        public String format = "未知";
    }
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        try {
            if (player != null) {
                Format videoFormat = player.getVideoFormat();
                if (videoFormat != null) {
                    int width = videoFormat.width;
                    int height = videoFormat.height;
                    if (width > 0 && height > 0) {
                        info.resolution = width + "×" + height;
                    }
                    info.format = videoFormat.sampleMimeType;
                    if (videoFormat.bitrate > 0) {
                        float mbps = videoFormat.bitrate / 1000000f;
                        info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", mbps);
                    }
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = audioFormat.sampleMimeType;
                    if (audioFormat.sampleRate > 0) {
                        info.audio += " " + (audioFormat.sampleRate / 1000) + "kHz";
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取直播信息异常", e);
        }
        return info;
    }
    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) {
            liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
        }
    }
    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }
    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }
    public interface OnSourceFailedListener {
        void onSourceFailed();
    }
    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        sourceFailedListener = listener;
    }
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        liveInfoUpdateListener = listener;
    }
    public void pause() {
        try { if (player != null) player.pause(); } catch (Exception e) {
            Log.e(TAG, "暂停异常", e);
        }
    }
    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) {
            Log.e(TAG, "恢复异常", e);
        }
    }
    public void release() {
        try {
            stopStuckDetection();
            cancelRetry();
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            unregisterDecoderModeReceiver();
            unregisterRendererModeReceiver();
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                }
                player.release();
                player = null;
            }
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }
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
                    mimeType, false, false);
            if (allCodecs == null || allCodecs.isEmpty()) {
                return allCodecs;
            }
            switch (decoderMode) {
                case DECODER_MODE_HARD:
                    List<MediaCodecInfo> hardCodecs = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        // 🟢 修复：传入 codec 对象
                        if (!isSoftwareDecoder(codec)) {
                            hardCodecs.add(codec);
                        }
                    }
                    return hardCodecs;
                case DECODER_MODE_SOFT:
                    List<MediaCodecInfo> softCodecs = new ArrayList<>();
                    List<MediaCodecInfo> hardCodecs2 = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        // 🟢 修复：传入 codec 对象
                        if (isSoftwareDecoder(codec))
                            softCodecs.add(codec);
                        else hardCodecs2.add(codec);
                    }
                    softCodecs.addAll(hardCodecs2);
                    return softCodecs;
                case DECODER_MODE_AUTO:
                default:
                    return allCodecs;
            }
        }
    }
}
