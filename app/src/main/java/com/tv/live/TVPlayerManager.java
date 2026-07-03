package com.tv.live;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import com.tv.live.RedirectLoggingHttpDataSource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// 修复：类名统一为TVPlayerManager
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;
    private static final int MAX_RETRY_COUNT = 2;
    private static final long STUCK_TIMEOUT = 10000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long RENDER_SWITCH_TIMEOUT = 3000;
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
    private OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    private volatile boolean isRenderingSwitching = false;
    private final Object renderSwitchLock = new Object();

    public interface OnPlayerViewRecreatedListener {
        void onRendererSwitchStart();
        void onPlayerViewRecreated(PlayerView newPlayerView);
        void onDecoderSwitchFreezeFrame(Bitmap freezeFrame);
        void onDecoderSwitchUnfreezeFrame();
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

    // 修复：构造函数英文括号、类名匹配
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
        SoftwareFirstMediaCodecSelector codecSelector = new SoftwareFirstMediaCodecSelector(mDecoderMode);
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
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 50000, 300, 500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();
        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos("video/avc", false, false);
            int softCount = 0;
            int hardCount = 0;
            StringBuilder softNames = new StringBuilder();
            StringBuilder hardNames = new StringBuilder();
            for (MediaCodecInfo codec : h264Codecs) {
                String name = codec.name;
                if (isSoftwareDecoder(name)) {
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
            Log.d(TAG, "【解码器】H.264 解码器统计：软解 " + softCount + " 个，硬解 " + hardCount + " 个");
            Log.d(TAG, "【解码器】软解解码器：" + softNames.toString());
            Log.d(TAG, "【解码器】硬解解码器：" + hardNames.toString());
            SettingsActivity.logOperation("【解码器】系统解码器：软解 " + softCount + " 个，硬解 " + hardCount + " 个");
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

    private static boolean isSoftwareDecoder(String codecName) {
        if (codecName == null) return false;
        String lowerName = codecName.toLowerCase();
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                if (listener != null) listener.onPlayError(error.getMessage());
                if (!isRenderingSwitching) autoRetry("播放错误：" + error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (isRenderingSwitching) return;
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    if (listener != null) listener.onPlayReady();
                    retryCount = 0;
                    isRetrying = false;
                    startStuckDetection();
                    if (initialPlayStartTime == 0) initialPlayStartTime = System.currentTimeMillis();
                    if (mDecoderMode == DECODER_MODE_AUTO && !hasSwitchedDecoder && !isRenderingSwitching
                            && initialPlayStartTime > 0 && System.currentTimeMillis() - initialPlayStartTime < 15000 && bufferCount > 1) {
                        if (isRetrying || TextUtils.isEmpty(currentUrl)) return;
                        Log.d(TAG, "【自动切换】硬解卡顿，自动切换到系统软解");
                        SettingsActivity.logOperation("【解码器】硬解卡顿（缓冲" + bufferCount + "次），自动切换到系统软解");
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
                    if (bufferCount == 1) SettingsActivity.logOperation("【播放器】开始缓冲（第1次）");
                } else if (state == Player.STATE_ENDED) {
                    if (listener != null) listener.onPlayEnd();
                    if (!isRenderingSwitching) autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    if (listener != null) listener.onIdle();
                    updateWakeLock(false);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isRenderingSwitching) return;
                if (isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
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
                if (isRenderingSwitching) return;
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
        if (retryRunnable != null) mHandler.removeCallbacks(retryRunnable);
        isRetrying = false;
    }

    private void autoRetry(String reason) {
        if (isRetrying || isRenderingSwitching) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT + "，判定为失效源");
            SettingsActivity.logOperation("【播放器】重试" + MAX_RETRY_COUNT + "次均失败，判定为失效源");
            if (sourceFailedListener != null) mHandler.post(() -> sourceFailedListener.onSourceFailed());
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
                if (!TextUtils.isEmpty(currentUrl)) playUrlInternal(currentUrl);
                retryRunnable = null;
            }
        };
        mHandler.postDelayed(retryRunnable, 1000);
    }

    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode || isRenderingSwitching) return;
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
        if (player != null) performDecoderSwitch();
    }

    private void performDecoderSwitch() {
        try {
            stopStuckDetection();
            cancelRetry();
            if (playerListener != null) player.removeListener(playerListener);
            player.release();
            player = null;
        } catch (Exception e) {
            Log.e(TAG, "释放播放器异常", e);
        }
        initPlayer();
        if (playerView != null) playerView.setPlayer(player);
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
        if (useSoftware) setDecoderMode(DECODER_MODE_SOFT);
        else setDecoderMode(DECODER_MODE_AUTO);
    }

    public void registerDecoderModeReceiver() {
        if (decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (isRenderingSwitching) {
                        Log.w(TAG, "【解码器】渲染器切换中，忽略解码器模式变更");
                        return;
                    }
                    if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String modeStr = sp.getString("decoder_mode", "auto");
                        int mode = DECODER_MODE_AUTO;
                        if ("hard".equals(modeStr)) mode = DECODER_MODE_HARD;
                        else if ("soft".equals(modeStr)) mode = DECODER_MODE_SOFT;
                        setDecoderMode(mode);
                        String modeName;
                        switch (mode) {
                            case DECODER_MODE_HARD: modeName = "硬解"; break;
                            case DECODER_MODE_SOFT: modeName = "软解（兼容）"; break;
                            default: modeName = "自动";
                        }
                        SettingsActivity.logOperation("【解码器】切换至：" + modeName);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            context.registerReceiver(decoderModeReceiver);
            decoderReceiverRegistered = true;
            SettingsActivity.logOperation("【解码器广播注册完成】");
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播失败：" + e.getMessage());
        }
    }

    public void unregisterDecoderModeReceiver() {
        if (!decoderReceiverRegistered) return;
        try {
            if (decoderModeReceiver != null) context.unregisterReceiver(decoderModeReceiver);
            decoderReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播异常");
        }
    }

    private void switchRenderer(boolean useTexture) {
        synchronized (renderSwitchLock) {
            if (playerView == null || context == null || isRenderingSwitching) {
                Log.w(TAG, "渲染切换跳过");
                return;
            }
            isRenderingSwitching = true;
            if (onPlayerViewRecreatedListener != null) onPlayerViewRecreatedListener.onRendererSwitchStart();
            Log.d(TAG, "切换渲染器：" + (useTexture ? "TextureView" : "SurfaceView"));

            long currentPosition = 0;
            boolean wasPlaying = false;
            boolean useController = false;
            // 修复：仅使用int常量，彻底删除ResizeMode枚举
            int targetResize = AspectRatioFrameLayout.RESIZE_MODE_FIT;
            boolean keepScreenOn = playerView.getKeepScreenOn();

            try {
                currentPosition = player.getCurrentPosition();
                wasPlaying = player.isPlaying();
                useController = playerView.getUseController();
                targetResize = playerView.getResizeMode();
                player.pause();
                stopStuckDetection();
                cancelRetry();
                Bitmap frame = captureCurrentFrame();
                if (onPlayerViewRecreatedListener != null) onPlayerViewRecreatedListener.onDecoderSwitchFreezeFrame(frame);
            } catch (Exception e) {
                Log.e(TAG, "保存播放状态异常", e);
            }

            ViewGroup parent = (ViewGroup) playerView.getParent();
            if (parent == null) {
                isRenderingSwitching = false;
                return;
            }
            ViewGroup.LayoutParams layoutParams = playerView.getLayoutParams();
            int index = parent.indexOfChild(playerView);
            int styleRes = useTexture ? R.style.PlayerView_Texture : R.style.PlayerView_Surface;
            ContextThemeWrapper themedContext = new ContextThemeWrapper(context);
            themedContext.setTheme(styleRes);

            CountDownLatch latch = new CountDownLatch(1);
            PlayerView[] newPlayerViewRef = new PlayerView[1];
            mHandler.post(() -> {
                try {
                    PlayerView newPlayerView = new PlayerView(themedContext);
                    newPlayerView.setLayoutParams(layoutParams);
                    newPlayerView.setUseController(useController);
                    // 直接传入int，无switch预览语法
                    newPlayerView.setResizeMode(targetResize);
                    newPlayerView.setKeepContentOnPlayerReset(true);
                    newPlayerView.setKeepScreenOn(keepScreenOn);
                    newPlayerView.setShowShuffleButton(false);
                    newPlayerView.setShowFastForwardButton(false);
                    newPlayerView.setShowRewindButton(false);
                    newPlayerView.setShowSubtitleButton(false);
                    // 删除不存在setShowVideoFrame

                    newPlayerView.setPlayer(player);
                    parent.addView(newPlayerView, index, layoutParams);
                    playerView.setPlayer(null);
                    parent.removeView(playerView);

                    newPlayerViewRef[0] = newPlayerView;
                    playerView = newPlayerView;

                    if (currentPosition > 0) player.seekTo(currentPosition);
                    if (wasPlaying) player.play();

                    if (onPlayerViewRecreatedListener != null) onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
                    newPlayerView.requestFocus();
                    Log.d(TAG, "渲染切换完成");
                } catch (Exception e) {
                    Log.e(TAG, "创建PlayerView失败", e);
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await(RENDER_SWITCH_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            startStuckDetection();
            if (onPlayerViewRecreatedListener != null) onPlayerViewRecreatedListener.onDecoderSwitchUnfreezeFrame();
            isRenderingSwitching = false;
        }
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
                        boolean useTexture = "texture".equals(mode);
                        mHandler.post(() -> switchRenderer(useTexture));
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            context.registerReceiver(rendererModeReceiver, filter);
            rendererReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册渲染广播失败");
        }
    }

    public void unregisterRendererModeReceiver() {
        if (!rendererReceiverRegistered) return;
        try {
            if (rendererModeReceiver != null) context.unregisterReceiver(rendererModeReceiver);
            rendererReceiverRegistered = false;
        } catch (Exception e) {
            Log.e(TAG, "注销渲染广播异常");
        }
    }

    public void onForeground() {
        if (isRenderingSwitching) return;
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                player.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "切前台异常");
        }
    }

    public void onBackground() {
        if (isRenderingSwitching) return;
        try {
            if (player != null) player.pause();
        } catch (Exception e) {
            Log.e(TAG, "切后台异常");
        }
    }

    public void attachPlayerView(PlayerView view) {
        if (isRenderingSwitching) {
            mHandler.postDelayed(() -> attachPlayerView(view), 500);
            return;
        }
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
        if (playerView != null && !isRenderingSwitching) playerView.setKeepScreenOn(enable);
    }

    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }

    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ExoPlayer");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");
        boolean isHuya = url.contains("huya.com") || url.contains("huya.cn");
        boolean isDouyu = url.contains("douyu.com") || url.contains("douyucdn.cn");
        if (isHuya) headers.put("Referer", "https://www.huya.com/");
        else if (isDouyu) headers.put("Referer", "https://www.douyu.com/");
        else headers.put("Referer", "https://www.huya.com/");
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) headers.put("Cookie", cookies);
        return headers;
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
        if (isRenderingSwitching) {
            mHandler.postDelayed(() -> playUrl(url, channelName), 500);
            return;
        }
        if (!TextUtils.isEmpty(channelName)) this.currentChannelName = channelName;
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        hasSwitchedDecoder = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();
        SettingsActivity.logOperation("加载频道：" + (TextUtils.isEmpty(currentChannelName) ? "未知" : currentChannelName));
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
            Log.d(TAG, "播放地址：" + currentUrl);
            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);
            httpFactory.setChannelName(currentChannelName);
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
            Log.e(TAG, "播放失败", e);
            if (!isRenderingSwitching) autoRetry("播放异常");
        }
    }

    public enum ScaleMode { FIT, FILL, ZOOM }

    public void setScaleMode(ScaleMode mode) {
        if (isRenderingSwitching) return;
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
                    int w = video.width;
                    int h = video.height;
                    if (w > 0 && h > 0) info.resolution = w + "×" + h;
                    info.format = videoFormat.sampleMimeType;
                    if (videoFormat.bitrate > 0) {
                        float mb = videoFormat.bitrate / 1000000f;
                        info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", mb);
                    }
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = audioFormat.sampleMimeType;
                    if (audioFormat.sampleRate > 0) {
                        info.audio += " " + (audioFormat / 1000) + "kHz";
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取播放信息异常");
        }
        return info;
    }

    private void notifyLiveInfoUpdate() {
        if (isRenderingSwitching) return;
        if (liveInfoUpdateListener != null) liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
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
        if (isRenderingSwitching) return;
        try { if (player != null) player.pause(); } catch (Exception e) {}
    }

    public void resume() {
        if (isRenderingSwitching) return;
        try { if (player != null) player.play(); } catch (Exception e) {}
    }

    // 修复MainActivity找不到isPlaying()
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public void release() {
        try {
            synchronized (renderSwitchLock) {
                isRenderingSwitching = true;
                stopStuckDetection();
                cancelRetry();
                mHandler.removeCallbacks(hideChannelRunnable);
                updateWakeLock(false);
                unregisterDecoderModeReceiver();
                unregisterRendererModeReceiver();
                if (player != null) {
                    if (playerListener != null) player.removeListener(playerListener);
                    player.release();
                    player = null;
                }
                instance = null;
                isRenderingSwitching = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放播放器异常");
        }
    }

    public Bitmap captureCurrentFrame() {
        if (isRenderingSwitching || playerView == null || player == null) {
            return null;
        }
        try {
            View videoView = playerView.getVideoSurfaceView();
            if (videoView == null) videoView = playerView;
            Bitmap bitmap = Bitmap.createBitmap(videoView.getWidth(), videoView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            video.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    public Bitmap captureCurrentFrame(int width, int height) {
        Bitmap ori = captureCurrentFrame();
        if (ori == null) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(ori, width, height, true);
        if (scaled != ori) ori.recycle();
        return scaled;
    }

    private static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;
        public SoftwareFirstMediaCodecSelector(int mode) {
            this.decoderMode = mode;
        }
        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mime, boolean secure, boolean tunnel) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> all = MediaCodecUtil.getDecoderInfos(mime, secure, tunnel);
            if (all.isEmpty()) return all;
            switch (decoderMode) {
                case DECODER_MODE_HARD:
                    List<MediaCodecInfo> hardList = new ArrayList<>();
                    for (MediaCodecInfo c : all) if (!isSoftwareDecoder(c.name)) hardList.add(c);
                    return hardList;
                case DECODER_MODE_SOFT:
                    List<MediaCodecInfo> softList = new ArrayList<>();
                    List<MediaCodecInfo> hardList = new ArrayList<>();
                    for (MediaCodecInfo c : all) {
                        if (isSoftwareDecoder(c.name)) softList.add(c);
                        else hardList.add(c);
                    }
                    softList.addAll(hardList);
                    return softList;
                default:
                    return all;
            }
        }
    }
}
