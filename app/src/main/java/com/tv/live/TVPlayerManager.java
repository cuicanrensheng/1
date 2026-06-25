      package com.tv.live;
import com.tv.live.RedirectLoggingHttpDataSource;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.TextView;
// ====================================================================
// ✅ 2026-06-23 修改：升级到 Media3 1.10.1
// ====================================================================
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
public class TVPlayerManager {
    private static final String TAG = "TVPlayerLog";
    private static TVPlayerManager instance;
    private ExoPlayer player;
    private Context context;
    private PlayerView playerView;
    public enum ScaleMode { FIT, FILL, ZOOM }
    private OnPlayStateListener listener;
    private String currentUrl = "";
    private boolean isPlaying = false;
    private int currentChannelNumber = 0;
    private TextView channelNumText;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_SHOW_DURATION = 3000L;
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private OnLiveInfoUpdateListener infoUpdateListener;
    private Player.Listener playerListener;
    // 软解码开关
    private boolean useSoftwareDecoder = false;
    // 卡住检测
    private long lastPositionUpdateTime = 0;
    private long lastPosition = 0;
    private static final long STUCK_TIMEOUT = 5000;
    private int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 3;
    private final Handler stuckHandler = new Handler(Looper.getMainLooper());
    private boolean isRetrying = false;
    private Runnable retryRunnable = null;
    // ====================================================================
    // ✅ 性能统计相关变量
    // ====================================================================
    /**
     * 上次进入 STATE_READY 的时间（用于性能统计的周期计算）
     * 每次缓冲完都会重置，用于计算本次播放了多久
     */
    private long playStartTime = 0;
    private int totalBufferCount = 0;
    private long totalBufferDuration = 0;
    private long lastBufferStartTime = 0;
    private int totalDroppedFrames = 0;
    private static final long PERFORMANCE_LOG_INTERVAL = 5000;
    private final Handler performanceHandler = new Handler(Looper.getMainLooper());
    // ====================================================================
    // ✅ 2026-06-25 新增：自动切换解码器相关变量（修复版）
    // ====================================================================
    /**
     * 真正的播放开始时间（从第一次进入 STATE_READY 开始算）
     * 
     * 【为什么单独弄一个变量？】
     * 原来的 playStartTime 每次缓冲完都会重置，
     * 导致自动切换判断的不是"从播放开始后 30 秒内"，
     * 而是"上次缓冲结束后 30 秒内"，判断不准确。
     * 
     * 这个变量只在第一次进入 STATE_READY 时设置一次，
     * 之后不再改变，直到切换频道才重置。
     */
    private long initialPlayStartTime = 0;
    /**
     * 是否已经自动切换过解码器（只切一次，避免反复切换）
     */
    private boolean hasSwitchedDecoder = false;
    /**
     * 自动切换解码器的缓冲次数阈值（30秒内超过这个次数就切换）
     */
    private static final int AUTO_SWITCH_BUFFER_THRESHOLD = 2;
    /**
     * 自动切换解码器的时间窗口（毫秒）
     * 播放开始后这个时间内，如果缓冲次数超过阈值，就自动切换
     */
    private static final long AUTO_SWITCH_TIME_WINDOW = 30000;
    // 直播信息实体类
    public static class LiveInfo {
        public String quality;
        public String audio;
        public String bitrate;
        public int channelNum;
        public int videoWidth;
        public int videoHeight;
    }
        public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.infoUpdateListener = listener;
    }
    public LiveInfo getLiveInfo() {
        LiveInfo info = new LiveInfo();
        info.channelNum = currentChannelNumber;
        try {
            if (player != null) {
                Format videoFormat = player.getVideoFormat();
                if (videoFormat != null && videoFormat.width != Format.NO_VALUE) {
                    info.videoWidth = videoFormat.width;
                    info.videoHeight = videoFormat.height;
                    if (videoFormat.width >= 1920 || videoFormat.height >= 1080) {
                        info.quality = "FHD";
                    } else if (videoFormat.width >= 1280 || videoFormat.height >= 720) {
                        info.quality = "HD";
                    } else {
                        info.quality = "SD";
                    }
                    if (videoFormat.bitrate != Format.NO_VALUE && videoFormat.bitrate > 0) {
                        double bitrateMBs = videoFormat.bitrate / 8.0 / 1024.0 / 1024.0;
                        info.bitrate = String.format("%.1fMB/s", bitrateMBs);
                    } else {
                        info.bitrate = "—";
                    }
                } else {
                    info.quality = "—";
                    info.bitrate = "—";
                    info.videoWidth = 0;
                    info.videoHeight = 0;
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    int channels = audioFormat.channelCount;
                    if (channels == 1) {
                        info.audio = "单声道";
                    } else if (channels == 2) {
                        info.audio = "立体声";
                    } else if (channels >= 6) {
                        info.audio = "5.1";
                    } else {
                        info.audio = channels + "声道";
                    }
                } else {
                    info.audio = "—";
                }
            } else {
                info.quality = "—";
                info.audio = "—";
                info.bitrate = "—";
            }
        } catch (Exception e) {
            Log.e(TAG, "获取播放信息失败", e);
            info.quality = "—";
            info.audio = "—";
            info.bitrate = "—";
        }
        return info;
    }
    public void setCurrentChannelNumber(int num) {
        this.currentChannelNumber = num;
    }
    private void notifyLiveInfoUpdate() {
        if (infoUpdateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    infoUpdateListener.onLiveInfoUpdate(getLiveInfo()));
        }
    }
    public void bindChannelText(TextView textView) {
        this.channelNumText = textView;
    }
    private void showChannelAndAutoHide() {
        if (channelNumText == null) return;
        mHandler.removeCallbacks(hideChannelRunnable);
        channelNumText.setText("频道：" + currentChannelNumber);
        channelNumText.setVisibility(View.VISIBLE);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_SHOW_DURATION);
    }
    private final Runnable hideChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelNumText != null) {
                channelNumText.setVisibility(View.GONE);
            }
        }
    };
    public static TVPlayerManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new TVPlayerManager(ctx);
        }
        return instance;
    }
    private TVPlayerManager(Context ctx) {
        context = ctx.getApplicationContext();
        initPlayer();
    }
    // ====================================================================
    // ✅ 输出日志到设置页面
    // ====================================================================
    private void logToSettings(String msg) {
        try {
            SettingsActivity.log("【解码】" + msg);
        } catch (Exception e) {
            Log.w(TAG, "输出设置日志失败（忽略）：" + e.getMessage());
        }
    }
    // ====================================================================
    // ✅ 初始化播放器
    // ====================================================================
    private void initPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        if (useSoftwareDecoder) {
            renderersFactory.setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            );
            try {
                renderersFactory.setEnableDecoderFallback(true);
            } catch (Exception e) {
                Log.e(TAG, "设置软解失败", e);
            }
            Log.d(TAG, "【FFmpeg】软解码模式：优先使用 FFmpeg 解码器");
            logToSettings("软解码模式：优先使用 FFmpeg 解码器");
        } else {
            renderersFactory.setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            );
            renderersFactory.setEnableDecoderFallback(true);
            Log.d(TAG, "【FFmpeg】硬解码模式：系统硬解优先，FFmpeg 备用");
            logToSettings("硬解码模式：系统硬解优先，FFmpeg 备用");
        }
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        5000,   // minBufferMs
                        50000,  // maxBufferMs
                        1500,   // bufferForPlaybackMs
                        2000    // bufferForPlaybackAfterRebufferMs
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();
        initPlayerListener();
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
        resetPerformanceStats();
    }
    // ====================================================================
    // ✅ 重置性能统计数据
    // ====================================================================
    /**
     * 【注意】
     * hasSwitchedDecoder 不在此重置，
     * 只在 playUrl() 切换频道时重置，
     * 避免切换解码器时把标记意外清零。
     */
    private void resetPerformanceStats() {
        playStartTime = 0;
        initialPlayStartTime = 0; // ✅ 重置真正的播放开始时间
        totalBufferCount = 0;
        totalBufferDuration = 0;
        lastBufferStartTime = 0;
        totalDroppedFrames = 0;
        // hasSwitchedDecoder 不在此重置！
    }
    // ====================================================================
    // ✅ 初始化播放器监听器
    // ====================================================================
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                String errorDetail = "错误码: " + error.errorCode
                    + ", 错误类型: " + getErrorTypeName(error.errorCode);
                Log.e(TAG, "【错误详情】" + errorDetail);
                if (error.getCause() != null) {
                    Log.e(TAG, "【错误原因】" + error.getCause().getMessage());
                }
                logToSettings("播放异常：" + error.getMessage());
                logToSettings("错误详情：" + errorDetail);
                if (error.getCause() != null) {
                    logToSettings("错误原因：" + error.getCause().getMessage());
                }
                stopPerformanceLog();
                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
                autoRetry("播放错误");
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
                    // ====================================================================
                    // ✅ 2026-06-25 修复：区分两种开始时间
                    // ====================================================================
                    // playStartTime：每次缓冲完都重置，用于性能统计周期计算
                    playStartTime = System.currentTimeMillis();
                    // initialPlayStartTime：只在第一次播放时设置，用于自动切换解码器判断
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                        Log.d(TAG, "【自动切换】记录初始播放时间");
                    }
                    startPerformanceLog();
                    // —— 打印解码器信息 ——
                    try {
                        Format videoFormat = player.getVideoFormat();
                        if (videoFormat != null) {
                            String decoderName = videoFormat.sampleMimeType;
                            boolean isFfmpeg = decoderName != null
                                && decoderName.toLowerCase().contains("ffmpeg");
                            Log.d(TAG, "========================================");
                            Log.d(TAG, "【解码器信息】");
                            Log.d(TAG, "  解码器类型: " + (isFfmpeg ? "FFmpeg 软解" : "系统硬解"));
                            Log.d(TAG, "  解码器名称: " + decoderName);
                            Log.d(TAG, "  视频编码: " + videoFormat.sampleMimeType);
                            Log.d(TAG, "  分辨率: " + videoFormat.width + "×" + videoFormat.height);
                            Log.d(TAG, "  码率: " + (videoFormat.bitrate / 1024) + "kbps");
                            Log.d(TAG, "  帧率: " + videoFormat.frameRate);
                            Log.d(TAG, "========================================");
                            logToSettings("========================================");
                            logToSettings("【解码器信息】");
                            logToSettings("  解码器类型: " + (isFfmpeg ? "FFmpeg 软解" : "系统硬解"));
                            logToSettings("  解码器名称: " + decoderName);
                            logToSettings("  视频编码: " + videoFormat.sampleMimeType);
                            logToSettings("  分辨率: " + videoFormat.width + "×" + videoFormat.height);
                            logToSettings("  码率: " + (videoFormat.bitrate / 1024) + "kbps");
                            logToSettings("  帧率: " + videoFormat.frameRate);
                            logToSettings("========================================");
                            if (isFfmpeg) {
                                Log.w(TAG, "【警告】当前使用 FFmpeg 软解码，CPU 占用较高，可能导致卡顿");
                                logToSettings("【警告】当前使用 FFmpeg 软解码，CPU 占用较高，可能导致卡顿");
                            }
                        }
                    } catch (Exception e) {
                        // 忽略
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();
                    lastPositionUpdateTime = System.currentTimeMillis();
                    // —— 缓冲统计 ——
                    lastBufferStartTime = System.currentTimeMillis();
                    totalBufferCount++;
                    try {
                        long bufferedDuration = player.getBufferedPosition() - player.getCurrentPosition();
                        Log.d(TAG, "【缓冲】第 " + totalBufferCount
                            + " 次缓冲开始，当前已缓冲: " + bufferedDuration + "ms");
                        logToSettings("第 " + totalBufferCount
                            + " 次缓冲开始，当前已缓冲: " + bufferedDuration + "ms");
                    } catch (Exception e) {
                        // 忽略
                    }
                    // ====================================================================
                    // ✅ 2026-06-25 修复：自动切换解码器（使用 initialPlayStartTime）
                    // ====================================================================
                    if (!useSoftwareDecoder && !hasSwitchedDecoder && initialPlayStartTime > 0) {
                        long totalPlayDuration = System.currentTimeMillis() - initialPlayStartTime;
                        if (totalPlayDuration <= AUTO_SWITCH_TIME_WINDOW
                                && totalBufferCount > AUTO_SWITCH_BUFFER_THRESHOLD) {
                            hasSwitchedDecoder = true; // 标记已切换，只切一次
                            Log.w(TAG, "【自动切换】播放 " + (totalPlayDuration / 1000)
                                + " 秒内缓冲 " + totalBufferCount
                                + " 次，自动切换到软解");
                            logToSettings("【自动切换】播放 " + (totalPlayDuration / 1000)
                                + " 秒内缓冲 " + totalBufferCount
                                + " 次，自动切换到软解");
                            setSoftwareDecoder(true);
                            return; // 切换后不再继续处理
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (listener != null) listener.onPlayEnd();
                    stopPerformanceLog();
                    autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    if (listener != null) listener.onIdle();
                } else {
                    updateWakeLock(false);
                }
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                }
            }
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int width = videoSize.width;
                int height = videoSize.height;
                Log.d(TAG, "视频分辨率变化：" + width + "×" + height);
                logToSettings("视频分辨率变化：" + width + "×" + height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }
    // ====================================================================
    // ✅ 错误码转名称
    // ====================================================================
    private String getErrorTypeName(int errorCode) {
        switch (errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return "网络连接失败";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "网络连接超时";
            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION:
                return "没有网络权限";
            case PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
                return "不允许明文传输";
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "HTTP 状态码错误";
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
                return "文件不存在";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
                return "媒体格式错误";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
                return "解码器初始化失败";
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                return "解码失败";
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "不支持的视频格式";
            default:
                return "未知错误(" + errorCode + ")";
        }
    }
    // ====================================================================
    // ✅ 性能统计日志
    // ====================================================================
    private void startPerformanceLog() {
        stopPerformanceLog();
        performanceHandler.postDelayed(performanceLogRunnable, PERFORMANCE_LOG_INTERVAL);
    }
    private void stopPerformanceLog() {
        performanceHandler.removeCallbacks(performanceLogRunnable);
    }
    private final Runnable performanceLogRunnable = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            try {
                long playDuration = System.currentTimeMillis() - playStartTime;
                long bufferedDuration = player.getBufferedPosition() - player.getCurrentPosition();
                int droppedFrames = 0;
                double bufferRate = playDuration > 0
                    ? (totalBufferDuration * 100.0 / playDuration)
                    : 0;
                Log.d(TAG, "【性能统计】");
                Log.d(TAG, "  播放时长: " + (playDuration / 1000) + "秒");
                Log.d(TAG, "  缓冲次数: " + totalBufferCount + " 次");
                Log.d(TAG, "  缓冲率: " + String.format("%.1f", bufferRate) + "%");
                Log.d(TAG, "  当前缓冲: " + bufferedDuration + "ms");
                Log.d(TAG, "  丢帧数: " + droppedFrames + "（1.5.0 暂不支持统计）");
                logToSettings("【性能统计】");
                logToSettings("  播放时长: " + (playDuration / 1000) + "秒");
                logToSettings("  缓冲次数: " + totalBufferCount + " 次");
                logToSettings("  缓冲率: " + String.format("%.1f", bufferRate) + "%");
                logToSettings("  当前缓冲: " + bufferedDuration + "ms");
                logToSettings("  丢帧数: " + droppedFrames + "（1.5.0 暂不支持统计）");
                if (totalBufferCount > 3 && bufferRate > 10) {
                    Log.w(TAG, "【卡顿分析】缓冲频繁，可能是网络带宽不足或直播源不稳定");
                    logToSettings("【卡顿分析】缓冲频繁，可能是网络带宽不足或直播源不稳定");
                }
            } catch (Exception e) {
                Log.e(TAG, "性能统计异常", e);
            }
            performanceHandler.postDelayed(this, PERFORMANCE_LOG_INTERVAL);
        }
    };
    // ====================================================================
    // ✅ 卡住检测
    // ====================================================================
    private void startStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        stuckHandler.postDelayed(stuckCheckRunnable, 2000);
    }
    private void stopStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
    }
    private final Runnable stuckCheckRunnable = new Runnable() {
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
                        logToSettings("检测到播放卡住，自动重试...");
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
    // ====================================================================
    // ✅ 取消重试
    // ====================================================================
    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }
    // ====================================================================
    // ✅ 自动重试
    // ====================================================================
    private void autoRetry(String reason) {
        if (isRetrying) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT);
            logToSettings("重试次数已达上限：" + MAX_RETRY_COUNT);
            return;
        }
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);
        logToSettings("自动重试（第" + retryCount + "次），原因：" + reason);
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(currentUrl)) {
                    playUrlInternal(currentUrl);
                }
                retryRunnable = null;
            }
        };
        mHandler.postDelayed(retryRunnable, 1000);
    }
    // ====================================================================
    // ✅ 切换软解/硬解
    // ====================================================================
    public void setSoftwareDecoder(boolean useSoftware) {
        if (useSoftwareDecoder == useSoftware) return;
        useSoftwareDecoder = useSoftware;
        Log.d(TAG, "切换解码器：" + (useSoftware ? "FFmpeg 软解码" : "系统硬解码"));
        logToSettings("切换解码器：" + (useSoftware ? "FFmpeg 软解码" : "系统硬解码"));
        // —— 如果切换到硬解，重置自动切换标记（给用户一次重试机会）——
        if (!useSoftware) {
            hasSwitchedDecoder = false;
            Log.d(TAG, "【自动切换】切换到硬解，重置自动切换标记");
            logToSettings("【自动切换】切换到硬解，重置自动切换标记");
        }
        // —— 重新创建播放器 ——
        if (player != null) {
            try {
                stopStuckDetection();
                stopPerformanceLog();
                cancelRetry();
                if (playerListener != null) {
                    player.removeListener(playerListener);
                }
                player.release();
                player = null;
            } catch (Exception e) {
                Log.e(TAG, "释放播放器异常", e);
            }
        }
        initPlayer();
        if (playerView != null) {
            playerView.setPlayer(player);
        }
        if (!TextUtils.isEmpty(currentUrl)) {
            retryCount = 0;
            isRetrying = false;
            playUrlInternal(currentUrl);
        }
    }
    // ====================================================================
    // ✅ 前后台切换
    // ====================================================================
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
    // ====================================================================
    // ✅ 绑定 PlayerView
    // ====================================================================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }
    // ====================================================================
    // ✅ 唤醒锁
    // ====================================================================
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }
    // ====================================================================
    // ✅ 请求头
    // ====================================================================
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");
        boolean isHuya = url.contains("huya.com") || url.contains("huya.cn");
        boolean isDouyu = url.contains("douyu.com") || url.contains("douyucdn.cn");
        if (isHuya) {
            headers.put("Referer", "https://www.huya.com/");
            Log.d(TAG, "虎牙直播，设置虎牙Referer");
        } else if (isDouyu) {
            headers.put("Referer", "https://www.douyu.com/");
            Log.d(TAG, "斗鱼直播，设置斗鱼Referer");
        } else {
            headers.put("Referer", "https://www.huya.com/");
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }
    // ====================================================================
    // ✅ 播放入口
    // ====================================================================
    public void play(String url) {
        playUrl(url);
    }
    public void playUrl(String url) {
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        resetPerformanceStats();
        // ✅ 切换频道时重置自动切换标记（每个新频道都有一次机会）
        hasSwitchedDecoder = false;
        playUrlInternal(url);
    }
    // ====================================================================
    // ✅ 内部播放方法
    // ====================================================================
    private void playUrlInternal(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);
            logToSettings("开始播放：" + currentUrl);
            RedirectLoggingHttpDataSource.Factory httpFactory =
                    new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                Log.d(TAG, "流格式：HLS (m3u8)");
                logToSettings("流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem);
            } else {
                Log.d(TAG, "流格式：普通流 (Progressive)");
                logToSettings("流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory)
                        .createMediaSource(mediaItem);
            }
            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.play();
            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            logToSettings("播放异常：" + e.getMessage());
            autoRetry("播放异常：" + e.getMessage());
        }
    }
    // ====================================================================
    // ✅ 画面比例
    // ====================================================================
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
    // ====================================================================
    // ✅ 播放状态回调
    // ====================================================================
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
    // ====================================================================
    // ✅ 暂停/恢复/释放
    // ====================================================================
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
            stopPerformanceLog();
            cancelRetry();
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                }
                player.release();
                player = null;
            }
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放播放器异常", e);
        }
    }
}
