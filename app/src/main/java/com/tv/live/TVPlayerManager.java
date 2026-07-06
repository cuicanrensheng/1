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
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

    // ====================== 配置常量 ======================
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";

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

    // 🟢【新增】保存当前正在播放的 Channel 对象，供设置页获取线路数量使用
    private Channel currentChannel;

    // 🟢【修复1】 将首次播放时间记录移到成员变量，防止逻辑错误
    private long initialPlayStartTime = 0;
    private int bufferCount = 0;
    private long totalStallTime = 0;
    private boolean isStalled = false;
    private long lastStallStartTime = 0;
    private int retryCount = 0;
    private boolean isRetrying = false;
    private Runnable retryRunnable;
    
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private Runnable stuckCheckRunnable;
    
    // 🟢【修复2】 统一使用一个主 Handler，避免匿名 Handler 造成的内存泄漏
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

    private OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    private boolean isRenderingSwitching = false;

    // 🟢【修复3】 复用 Map 对象，避免频繁 new 导致频繁 GC 卡顿
    private final Map<String, String> reusableHeaderMap = new HashMap<>();

    public interface OnPlayerViewRecreatedListener {
        void onPlayerViewRecreated(PlayerView newPlayerView);
    }
    public void setOnPlayerViewRecreatedListener(OnPlayerViewRecreatedListener listener) {
        this.onPlayerViewRecreatedListener = listener;
    }

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
        
        hideChannelRunnable = new Runnable() {
            @Override
            public void run() {
                hideChannelNum();
            }
        };

        // 🟢【修复4】 卡顿检测增加 player == null 判断，防止死循环空指针
        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null || !player.isPlaying()) {
                    mHandler.postDelayed(this, 2000);
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
                            autoRetry("播放卡住");
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "卡住检测异常", e);
                }
                mHandler.postDelayed(this, 2000);
            }
        };
        initPlayer();
    }

    private void initPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        SoftwareFirstMediaCodecSelector codecSelector = new SoftwareFirstMediaCodecSelector(mDecoderMode);
        renderersFactory.setMediaCodecSelector(codecSelector);

        switch (mDecoderMode) {
            case DECODER_MODE_SOFT:
                Log.d(TAG, "【解码器】软解模式");
                break;
            case DECODER_MODE_HARD:
                Log.d(TAG, "【解码器】硬解模式");
                break;
            case DECODER_MODE_AUTO:
            default:
                Log.d(TAG, "【解码器】自动模式");
                break;
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 45000, 2500, 5000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();

        // 检测解码器日志
        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos("video/avc", false, false);
            int softCount = 0, hardCount = 0;
            for (MediaCodecInfo codec : h264Codecs) {
                if (isSoftwareDecoder(codec)) softCount++; else hardCount++;
            }
            Log.d(TAG, "【解码器】软解 " + softCount + " 个，硬解 " + hardCount + " 个");
        } catch (Exception ignored) {}

        initPlayerListener();
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase();
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    private void initPlayerListener() {
        if (playerListener != null) return;
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                Throwable rootCause = error.getCause();
                boolean isRedirectError = false;
                while (rootCause != null) {
                    if (rootCause instanceof RedirectFailedException) {
                        isRedirectError = true;
                        RedirectFailedException redirectErr = (RedirectFailedException) rootCause;
                        // SettingsActivity.logOperation("【播放器】重定向拦截失败：" + redirectErr.getMessage() + " Location=" + redirectErr.getLocation()); // 已注释：操作日志已移除
                        break;
                    }
                    rootCause = rootCause.getCause();
                }
                if (listener != null) listener.onPlayError(error.getMessage());
                if (!isRedirectError) autoRetry("播放错误：" + error.getMessage());
                // else SettingsActivity.logOperation("【播放器】检测为重定向失败，跳过自动重试"); // 已注释：操作日志已移除
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
                    
                    // 自动硬解转软解逻辑
                    if (mDecoderMode == DECODER_MODE_AUTO && !isRenderingSwitching
                            && initialPlayStartTime > 0 
                            && System.currentTimeMillis() - initialPlayStartTime > 15000 
                            && bufferCount > 1) {
                        if (isRetrying || TextUtils.isEmpty(currentUrl)) return;
                        Log.d(TAG, "【自动切换】硬解卡顿，自动切换到系统软解");
                        // 🟢【修复5】 标记切换状态，避免无限循环切换
                        mDecoderMode = DECODER_MODE_SOFT; 
                        performDecoderSwitch();
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();
                    lastPositionUpdateTime = System.currentTimeMillis();
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
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
                        totalStallTime += stallDuration;
                        Log.d(TAG, "【性能】卡顿结束，时长：" + stallDuration + "ms");
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

    private void startStuckDetection() {
        mHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        mHandler.postDelayed(stuckCheckRunnable, 2000);
    }

    private void stopStuckDetection() {
        mHandler.removeCallbacks(stuckCheckRunnable);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }

    private void autoRetry(String reason) {
        if (reason.contains("RedirectFailedException") || reason.contains("重定向")) {
            // SettingsActivity.logOperation("【播放器】重定向类错误，不执行重试"); // 已注释：操作日志已移除
            return;
        }
        if (isRetrying) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT + "，判定为失效源");
            if (sourceFailedListener != null) {
                mHandler.post(() -> sourceFailedListener.onSourceFailed());
            }
            return;
        }
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);
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
        Log.d(TAG, "切换解码器模式：" + mode);
        if (player != null) performDecoderSwitch();
    }

    // 🟢【修复6】 彻底解决 performDecoderSwitch 中的资源清理和时序问题
    private void performDecoderSwitch() {
        try {
            // 1. 停止当前所有检测任务和延迟任务，防止 postDelayed 死循环
            stopStuckDetection();
            cancelRetry();
            mHandler.removeCallbacksAndMessages(null);

            // 2. 释放旧播放器并移除监听器
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                    playerListener = null; // 置空防止泄漏
                }
                player.release();
                player = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放旧播放器异常", e);
        }

        // 3. 重新初始化
        initPlayer();

        // 4. 重新绑定 PlayerView (必须确保操作在主线程且顺序正确)
        if (playerView != null) {
            mHandler.post(() -> {
                if (playerView != null && player != null) {
                    playerView.setPlayer(player);
                }
            });
        }

        // 5. 重新播放
        if (!TextUtils.isEmpty(currentUrl)) {
            retryCount = 0;
            isRetrying = false;
            playUrlInternal(currentUrl);
        }
    }

    public int getDecoderMode() { return mDecoderMode; }

    public void registerDecoderModeReceiver() {
        if (decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = DECODER_MODE_AUTO;
                        if ("hard".equals(modeStr)) mode = DECODER_MODE_HARD;
                        else if ("soft".equals(modeStr)) mode = DECODER_MODE_SOFT;
                        setDecoderMode(mode);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            context.registerReceiver(decoderModeReceiver, filter);
            decoderReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播失败", e);
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
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播失败", e);
        }
    }

    // 🟢【修复7】 彻底解决切换渲染器时的黑屏闪烁和 Handler 泄漏
    private void switchRenderer(boolean useTexture) {
        if (playerView == null || context == null) return;

        FrameLayout parent = (FrameLayout) playerView.getParent();
        if (parent == null) return;

        // 1. 添加黑屏遮罩
        View blackMask = new View(context);
        blackMask.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams maskParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        parent.addView(blackMask, maskParams);
        blackMask.bringToFront();

        isRenderingSwitching = true;
        bufferCount = 0;
        long currentPosition = player.getCurrentPosition();
        boolean wasPlaying = player.isPlaying();
        boolean useController = playerView.getUseController();
        ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();

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

        if (currentPosition > 0) player.seekTo(currentPosition);
        
        if (wasPlaying) {
            // 复用全局 mHandler，配合 mHandler.removeCallbacksAndMessages(null) 清除，解决内存泄漏
            mHandler.postDelayed(() -> {
                if (player != null && !player.isPlaying()) player.play();
            }, 200);
        }

        if (onPlayerViewRecreatedListener != null) {
            onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
        }
        playerView.requestFocus();

        // 2. 移除遮罩：通过动画实现平滑过渡，避免闪烁
        playerView.postDelayed(() -> {
            blackMask.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction(() -> {
                        // 动画结束后从布局移除释放内存
                        parent.removeView(blackMask);
                    })
                    .start();
        }, 100);

        isRenderingSwitching = false;
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
                            switchRenderer("texture".equals(mode));
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            context.registerReceiver(rendererModeReceiver, filter);
            rendererReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册渲染方式广播失败", e);
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
            Log.e(TAG, "注销渲染方式广播失败", e);
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
            if (player != null) player.pause();
        } catch (Exception e) {
            Log.e(TAG, "切后台异常", e);
        }
    }

    public void attachPlayerView(PlayerView view) {
        playerView = view;
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String rendererMode = sp.getString("renderer_type", "surface");
        switchRenderer("texture".equals(rendererMode));
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) playerView.setKeepScreenOn(enable);
    }

    // ====================================================================
    // 🟢【修改】新增 playUrl 重载方法，支持传递 Channel 对象
    // ====================================================================
    public void playUrl(String url) { playUrl(url, null, null); }
    public void playUrl(String url, String channelName) { playUrl(url, channelName, null); }

    public void playUrl(String url, String channelName, Channel channel) {
        if (!TextUtils.isEmpty(channelName)) this.currentChannelName = channelName;
        // 🟢 保存当前 Channel 对象
        this.currentChannel = channel;
        // 如果传入的 channel 不为空且名字还没更新，用 channel.getName() 更新
        if (channel != null && TextUtils.isEmpty(this.currentChannelName)) {
            this.currentChannelName = channel.getName();
        }

        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        // 🟢【修复8】 重置状态，防止旧状态影响新开播
        initialPlayStartTime = 0;
        resetPerformanceStats();
        playUrlInternal(url);
    }
    // ====================================================================

    // 🟢【新增】供 SettingsActivity 获取当前频道对象
    public Channel getCurrentChannel() {
        return currentChannel;
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
            
            // 🟢【新增】根据用户设置选择主源还是备用源播放
            String playUrl = url.trim();
            if (currentChannel != null) {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                int lineIndex = sp.getInt("channel_line_index", 0);
                
                if (lineIndex == 0) {
                    // 选了主源
                    playUrl = currentChannel.getMainPlayUrl();
                } else {
                    // 选了备用源
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = lineIndex - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        playUrl = backups.get(backupIndex);
                    } else {
                        // 越界保护，自动切回主源
                        playUrl = currentChannel.getMainPlayUrl();
                        Log.w(TAG, "线路索引越界，已自动切回主源");
                    }
                }
                currentUrl = playUrl;
                Log.d(TAG, "切换线路后播放：" + currentUrl);
            } else {
                currentUrl = playUrl;
            }

            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(currentUrl);

            // 🟢【修复9】 复用 reusableHeaderMap 对象并 clear，解决频繁 new HashMap 引起的 GC 掉帧
            reusableHeaderMap.clear();
            for (String name : globalHeaders.names()) {
                reusableHeaderMap.put(name, globalHeaders.get(name));
            }

            SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            if (sendCookie) {
                String cookies = CookieManager.getInstance().getCookie(currentUrl);
                if (cookies != null) reusableHeaderMap.put("Cookie", cookies);
            }

            httpFactory.setDefaultRequestProperties(reusableHeaderMap);
            httpFactory.setChannelName(currentChannelName);
            httpFactory.setMaxRedirects(sp.getInt(KEY_REDIRECT_MAX_COUNT, 5))
                    .setAllowCrossDomainRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true))
                    .setAllowCrossProtocolRedirects(sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true))
                    .setFollowRedirectsWithHeaders(sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true))
                    .setIgnoreSslErrorRedirect(sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false))
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(10000);

            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.play();
            startStuckDetection();

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                RedirectFailedException redirectErr = (RedirectFailedException) e;
                // SettingsActivity.logOperation("【重定向失败】" + redirectErr.getOriginUrl() + " -> " + redirectErr.getLocation()); // 已注释：操作日志已移除
                if (listener != null) listener.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            autoRetry("播放异常：" + e.getMessage());
        }
    }

    public enum ScaleMode { FIT, FILL, ZOOM }
    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            switch (mode) {
                case FIT: playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); break;
                case FILL: playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL); break;
                case ZOOM: playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); break;
            }
        } catch (Exception e) { Log.e(TAG, "设置缩放模式异常", e); }
    }

    public void setCurrentChannelNumber(int num) { currentChannelNumber = num; }
    public void bindChannelText(TextView textView) { channelNumberTextView = textView; }

    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
            mHandler.removeCallbacks(hideChannelRunnable);
            mHandler.postDelayed(hideChannelRunnable, CHANNEL_NUM_HIDE_DELAY);
        }
    }
    private void hideChannelNum() {
        if (channelNumberTextView != null) channelNumberTextView.setVisibility(View.GONE);
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
                    int width = videoFormat.width, height = videoFormat.height;
                    if (width > 0 && height > 0) info.resolution = width + "×" + height;
                    info.format = videoFormat.sampleMimeType;
                    if (videoFormat.bitrate > 0) info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", videoFormat.bitrate / 1000000f);
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = audioFormat.sampleMimeType;
                    if (audioFormat.sampleRate > 0) info.audio += " " + (audioFormat.sampleRate / 1000) + "kHz";
                }
            }
        } catch (Exception e) { Log.e(TAG, "获取直播信息异常", e); }
        return info;
    }

    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
    }

    public interface OnPlayStateListener {
        void onIdle(); void onBuffering(); void onPlayReady(); void onPlayEnd(); void onPlayError(String msg);
    }
    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }

    public interface OnSourceFailedListener { void onSourceFailed(); }
    public void setOnSourceFailedListener(OnSourceFailedListener listener) { sourceFailedListener = listener; }

    public interface OnLiveInfoUpdateListener { void onLiveInfoUpdate(LiveInfo info); }
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) { liveInfoUpdateListener = listener; }

    public void pause() { try { if (player != null) player.pause(); } catch (Exception ignored) {} }
    public void resume() { try { if (player != null) player.play(); } catch (Exception ignored) {} }

    // 🟢【修复10】 彻底安全的释放逻辑，防止单例内存泄漏
    public void release() {
        try {
            stopStuckDetection();
            cancelRetry();
            mHandler.removeCallbacksAndMessages(null); // 清除所有未执行的排期任务！
            
            updateWakeLock(false);
            unregisterDecoderModeReceiver();
            unregisterRendererModeReceiver();

            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                    playerListener = null;
                }
                player.release();
                player = null;
            }
            instance = null; // 彻底销毁单例，允许后续 GC 回收
            
            // 释放对外暴露的 View 引用
            if (playerView != null) {
                playerView.setPlayer(null);
                playerView = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }

    private static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;
        public SoftwareFirstMediaCodecSelector(int mode) { this.decoderMode = mode; }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(mimeType, false, false);
            if (allCodecs == null || allCodecs.isEmpty()) return allCodecs;
            switch (decoderMode) {
                case DECODER_MODE_HARD:
                    List<MediaCodecInfo> hardCodecs = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (!isSoftwareDecoder(codec)) hardCodecs.add(codec);
                    }
                    return hardCodecs;
                case DECODER_MODE_SOFT:
                    List<MediaCodecInfo> softCodecs = new ArrayList<>();
                    List<MediaCodecInfo> hardCodecs2 = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (isSoftwareDecoder(codec)) softCodecs.add(codec);
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
