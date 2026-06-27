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
import com.tv.live.RedirectLoggingHttpDataSource;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;
    private static final int MAX_RETRY_COUNT = 2;
    private static final long STUCK_TIMEOUT = 10000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;

    private static TVPlayerManager instance;
    // 修复：替换强Context为弱引用
    private WeakReference<Context> contextRef;

    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;

    private String currentUrl;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;
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

    private Handler mHandler;
    private Handler stuckHandler;

    private HideChannelRunnable hideChannelRunnable;
    private StuckCheckRunnable stuckCheckRunnable;
    private RetryRunnable retryRunnable;

    private OnPlayStateListener listener;
    private OnSourceFailedListener sourceFailedListener;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;

    private boolean isPlaying = false;
    private final SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private DecoderBroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;

    // 静态弱引用 Runnable：隐藏频道号
    private static class HideChannelRunnable implements Runnable {
        private final WeakReference<TVPlayerManager> managerRef;
        public HideChannelRunnable(TVPlayerManager manager) {
            this.managerRef = new WeakReference<>(manager);
        }
        @Override
        public void run() {
            TVPlayerManager mgr = managerRef.get();
            if (mgr != null) mgr.hideChannelNum();
        }
    }

    // 静态弱引用 卡顿检测Runnable
    private static class StuckCheckRunnable implements Runnable {
        private final WeakReference<TVPlayerManager> managerRef;
        public StuckCheckRunnable(TVPlayerManager manager) {
            this.managerRef = new WeakReference<>(manager);
        }
        @Override
        public void run() {
            TVPlayerManager mgr = managerRef.get();
            if (mgr == null) return;
            mgr.checkStuckLogic();
        }
    }

    // 静态弱引用 重试Runnable
    private static class RetryRunnable implements Runnable {
        private final WeakReference<TVPlayerManager> managerRef;
        public RetryRunnable(TVPlayerManager manager) {
            this.managerRef = new WeakReference<>(manager);
        }
        @Override
        public void run() {
            TVPlayerManager mgr = managerRef.get();
            if (mgr == null) return;
            mgr.retryPlayLogic();
        }
    }

    // 静态弱引用 播放器事件监听器
    private static class ExoPlayerListener implements Player.Listener {
        private final WeakReference<TVPlayerManager> managerRef;
        public ExoPlayerListener(TVPlayerManager manager) {
            this.managerRef = new WeakReference<>(manager);
        }
        @Override
        public void onPlayerError(PlaybackException error) {
            TVPlayerManager mgr = managerRef.get();
            if (mgr == null) return;
            Log.e(TAG, "播放异常: " + error.getMessage());
            if (mgr.listener != null) {
                mgr.listener.onPlayError(error.getMessage());
            }
            mgr.autoRetry("播放错误：" + error.getMessage());
        }
        @Override
        public void onPlaybackStateChanged(int state) {
            TVPlayerManager mgr = managerRef.get();
            if (mgr == null) return;
            mgr.handlePlayState(state);
        }
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            TVPlayerManager mgr = managerRef.get();
            if (mgr == null) return;
            mgr.handlePlayingChange(isPlaying);
        }
        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            TVPlayerManager mgr = managerRef.get();
            if (mgr == null) return;
            int width = videoSize.width;
            int height = videoSize.height;
            Log.d(TAG, "视频分辨率变化：" + width + "×" + height);
            mgr.notifyLiveInfoUpdate();
        }
    }

    // 静态弱引用 解码器广播接收器
    private static class DecoderBroadcastReceiver extends BroadcastReceiver {
        private final WeakReference<TVPlayerManager> managerRef;
        public DecoderBroadcastReceiver(TVPlayerManager manager) {
            this.managerRef = new WeakReference<>(manager);
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            TVPlayerManager mgr = managerRef.get();
            if (mgr == null) return;
            if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                String modeStr = sp.getString("decoder_mode", "auto");
                int mode = DECODER_MODE_AUTO;
                if ("hard".equals(modeStr)) mode = DECODER_MODE_HARD;
                else if ("soft".equals(modeStr)) mode = DECODER_MODE_SOFT;
                mgr.setDecoderMode(mode);
                String modeName = switch (mode) {
                    case DECODER_MODE_HARD -> "硬解";
                    case DECODER_MODE_SOFT -> "软解（兼容性好）";
                    default -> "自动（推荐）";
                };
                SettingsActivity.logOperation("【解码器】收到广播，切换到：" + modeName);
            }
        }
    }

    // 单例改造：传入Context包装为Application上下文弱引用
    public static TVPlayerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TVPlayerManager.class) {
                if (instance == null) {
                    instance = new TVPlayerManager(context.getApplicationContext());
                }
            }
        }
        // 上下文失效时重新绑定
        Context ctx = instance.contextRef.get();
        if (ctx == null && context != null) {
            instance.contextRef = new WeakReference<>(context.getApplicationContext());
        }
        return instance;
    }

    // 私有构造：不再持有强Context，改用WeakReference
    private TVPlayerManager(Context appContext) {
        this.contextRef = new WeakReference<>(appContext);
        mHandler = new Handler(Looper.getMainLooper());
        stuckHandler = new Handler(Looper.getMainLooper());

        hideChannelRunnable = new HideChannelRunnable(this);
        stuckCheckRunnable = new StuckCheckRunnable(this);
        retryRunnable = new RetryRunnable(this);

        initPlayer();
    }

    // 统一获取上下文，判空保护
    private Context getContext() {
        return contextRef != null ? contextRef.get() : null;
    }

    private void initPlayer() {
        Context ctx = getContext();
        if (ctx == null) return;

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(ctx);
        SoftwareFirstMediaCodecSelector codecSelector = new SoftwareFirstMediaCodecSelector(mDecoderMode);
        renderersFactory.setMediaCodecSelector(codeSelector);

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

        player = new ExoPlayer.Builder(ctx)
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
            Log.d(TAG, "【解码器】软解解码器：" + softNames);
            Log.d(TAG, "【解码器】硬解解码器：" + hardNames);
            SettingsActivity.logOperation("【解码器】系统解码器：软解 " + softCount + " 个，硬解 " + hardCount + " 个");
            if (softCount == 0) {
                Log.w(TAG, "【解码器】⚠️ 系统未找到软件解码器，软解模式可能不生效");
                SettingsActivity.logOperation("【解码器】⚠️ 警告：未找到系统软件解码器");
            }
        } catch (Exception e) {
            Log.e(TAG, "【解码器】检测系统解码器失败：" + e.getMessage());
        }

        initPlayerListener();
        CookieSyncManager.createInstance(ctx);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private static boolean isSoftwareDecoder(String codecName) {
        if (codecName == null) return false;
        String lowerName = codecName.toLowerCase();
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    private void initPlayerListener() {
        playerListener = new ExoPlayerListener(this);
        player.addListener(playerListener);
    }

    // 卡顿检测业务逻辑（抽离到独立方法，静态Runnable调用）
    private void checkStuckLogic() {
        if (player == null || !player.isPlaying()) {
            stuckHandler.postDelayed(stuckCheckRunnable, 2000);
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
        stuckHandler.postDelayed(stuckCheckRunnable, 2000);
    }

    // 播放状态回调业务抽离
    private void handlePlayState(int state) {
        if (state == Player.STATE_READY) {
            updateWakeLock(true);
            notifyLiveInfoUpdate();
            showChannelAndAutoHide();
            if (listener != null) listener.onPlayReady();
            retryCount = 0;
            isRetrying = false;
            startStuckDetection();
            if (initialPlayStartTime == 0) initialPlayStartTime = System.currentTimeMillis();
            if (mDecoderMode == DECODER_MODE_AUTO && !hasSwitched
                    && initialPlayStartTime > 0
                    && System.currentTimeMillis() - initialPlayStartTime < 15000
                    && bufferCount > 1) {
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

    // 播放/暂停状态变更业务抽离
    private void handlePlayingChange(boolean isPlaying) {
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
        mHandler.removeCallbacks(retryRunnable);
        isRetrying = false;
    }

    // 重试播放业务抽离
    private void retryPlayLogic() {
        isRetrying = false;
        if (!TextUtils.isEmpty(currentUrl)) {
            playUrlInternal(currentUrl);
        }
    }

    private void autoRetry(String reason) {
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
        mHandler.postDelayed(retryRunnable, 1000);
    }

    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        useSoftwareDecoder = (mode == DECODER_MODE_SOFT);
        String decoderType = switch (mode) {
            case DECODER_MODE_HARD -> "系统硬解码（强制）";
            case DECODER_MODE_SOFT -> "系统软解码（优先）";
            default -> "自动模式（硬解优先）";
        };
        Log.d(TAG, "切换解码器模式：" + decoderType);
        SettingsActivity.logOperation("【解码器】切换模式：" + decoderType);

        if (player != null) {
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
        if (useSoftware) setDecoderMode(DECODER_MODE_SOFT);
        else setDecoderMode(DECODER_MODE_AUTO);
    }

    public void registerDecoderModeReceiver() {
        Context ctx = getContext();
        if (ctx == null || decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new DecoderBroadcastReceiver(this);
            IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
            ctx.registerReceiver(decoderModeReceiver, filter);
            decoderReceiverRegistered = true;
            SettingsActivity.logOperation("【解码器】广播接收器已注册");
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播接收器失败：" + e.getMessage());
            SettingsActivity.logOperation("【解码器】广播注册失败：" + e.getMessage());
        }
    }

    public void unregisterDecoderModeReceiver() {
        Context ctx = getContext();
        if (ctx == null || !decoderReceiverRegistered) return;
        try {
            if (decoderModeReceiver != null) {
                ctx.unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
            }
            decoderReceiverRegistered = false;
            SettingsActivity.logOperation("【解码器】广播接收器已注销");
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播接收器失败：" + e.getMessage());
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
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }

    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) playerView.setKeepScreenOn(enable);
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
        if (cookies != null) headers.put("Cookie", cookies);
        return headers;
    }

    public void play(String url) {
        playUrl(url);
    }

    public void playUrl(String url) {
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        hasSwitchedDecoder = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();
        SettingsActivity.logOperation("【播放器】开始加载新频道");
        playUrlInternal(url);
    }

    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }

    private void playUrlInternal(String url) {
        Context ctx = getContext();
        if (ctx == null) return;
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);
            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                Log.d(TAG, "流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                Log.d(TAG, "流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }
            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.play();
            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            autoRetry("播放异常：" + e.getMessage());
        }
    }

    public enum ScaleMode {
        FIT, FILL, ZOOM
    }

    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            switch (mode) {
                case FIT -> playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                case FILL -> playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                case ZOOM -> playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
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
                    int width = video.width;
                    int height = video.height;
                    if (width > 0 && height > 0) info.resolution = width + "×" + height;
                    info.format = video.sampleMimeType;
                    if (video.bitrate > 0) {
                        float mbps = video.bitrate / 1000000f;
                        info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", mbps);
                    }
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = audio.sampleMimeType;
                    if (audioFormat.sampleRate > 0) info.audio += " " + (audioFormat / 1000) + "kHz";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取直播信息异常", e);
        }
        return info;
    }

    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
    }

    public interface OnPlayStateListener {
        void onIdle();
        void onBuffering();
        void onPlayReady();
        void onPlayEnd();
        void onPlayError(String msg);
    }
    public void setOnPlayStateListener(OnPlayStateListener l) { listener = l; }

    public interface OnSourceFailedListener {
        void onSourceFailed();
    }
    public void setOnSourceFailedListener(OnSourceFailedListener listener) { sourceFailedListener = listener; }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) { liveInfoUpdateListener = listener; }

    public void pause() {
        try { if (player != null) player.pause(); } catch (Exception e) { Log.e(TAG, "暂停异常", e); }
    }
    public void resume() {
        try { if (player != null) player.play(); } catch (Exception e) { Log.e(TAG, "恢复异常", e); }
    }

    // ✅ 完整规范release() 全部清理
    public void release() {
        try {
            // 1 停止所有Handler任务并销毁Handler
            stopStuckDetection();
            cancelRetry();
            if (mHandler != null) {
                mHandler.removeCallbacksAndMessages(null);
                mHandler = null;
            }
            if (stuckHandler != null) {
                stuckHandler.removeCallbacksAndMessages(null);
                stuckHandler = null;
            }

            // 2 清空全部监听器
            if (player != null && playerListener != null) {
                player.removeListener(playerListener);
            }
            playerListener = null;
            listener = null;
            sourceFailedListener = null;
            liveInfoUpdateListener = null;

            // 3 注销广播接收器
            unregisterDecoderModeReceiver();
            decoderModeReceiver = null;

            // 4 释放播放器实例
            updateWakeLock(false);
            if (player != null) {
                player.release();
                player = null;
            }

            // 5 清空弱引用上下文
            if (contextRef != null) {
                contextRef.clear();
                contextRef = null;
            }

            // 6 置空所有View、缓存、字符串资源
            playerView = null;
            channelNumberTextView = null;
            currentUrl = null;
            hideChannelRunnable = null;
            stuckCheckRunnable = null;
            retryRunnable = null;

            // 7 清空单例
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "release释放异常", e);
        }
    }

    // 原有静态解码器选择器（无泄漏无需改动）
    private static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;
        public SoftwareFirstMediaCodecSelector(int mode) { this.decoderMode = mode; }
        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (allCodecs == null || allCodecs.isEmpty()) return allCodecs;
            return switch (decoderMode) {
                case DECODER_MODE_HARD -> allCodecs.stream().filter(c -> !isSoftwareDecoder(c.name)).toList();
                case DECODER_MODE_SOFT -> {
                    List<MediaCodecInfo> soft = new ArrayList<>();
                    List<MediaCodecInfo> hard = new ArrayList<>();
                    for (MediaCodecInfo c : allCodecs) {
                        if (isSoftwareDecoder(c.name)) soft.add(c);
                        else hard.add(c);
                    }
                    soft.addAll(hard);
                    yield soft;
                }
                default -> allCodecs;
            };
        }
    }
}
