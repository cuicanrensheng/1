package com.tv.live;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.tv.live.RedirectLoggingHttpDataSource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

public class TVPlayerManager {
    private static final String TAG_PREFIX = "TVPlayer_";
    private static final String TAG = TAG_PREFIX + "Manager";
    private static final String LOG_TIME_FORMAT = "HH:mm:ss";
    private static final int MAX_RETRY_COUNT = 2;
    private static final long RETRY_DELAY_MS = 1000;
    private static final long STUCK_TIMEOUT_MS = 10000;
    private static final long STUCK_CHECK_INTERVAL_MS = 2000;
    private static final long CHANNEL_NUM_HIDE_DELAY_MS = 3000;
    private static final int CHANNEL_NUM_DEFAULT_COLOR = Color.WHITE;
    private static final float CHANNEL_NUM_DEFAULT_SIZE_SP = 18f;
    private static final int MIN_BUFFER_MS = 2000;
    private static final int MAX_BUFFER_MS = 50000;
    private static final int BUFFER_FOR_PLAYBACK_MS = 300;
    private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 500;
    private static final long DECODER_SWITCH_TIME_WINDOW_MS = 15000;
    private static final int DECODER_SWITCH_MIN_BUFFER_COUNT = 1;
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_SOFT = 1;
    public static final int DECODER_MODE_HARD = 2;
    public static final String ACTION_DECODER_MODE_CHANGED = "com.tv.live.action.DECODER_MODE_CHANGED";
    private static final String DEFAULT_USER_AGENT = "ExoPlayer";
    private static final String DEFAULT_REFERER = "https://www.huya.com/";
    private static final String HUYA_DOMAIN_KEY = "huya";
    private static final String DOUYU_DOMAIN_KEY = "douyu";
    private static final String DOUYU_REFERER = "https://www.douyu.com/";
    private static final String HLS_STREAM_FLAG = "m3u8";
    private static final String ICY_META_DATA_VALUE = "1";
    public static final int PLAY_STATE_IDLE = Player.STATE_IDLE;
    public static final int PLAY_STATE_BUFFERING = Player.STATE_BUFFERING;
    public static final int PLAY_STATE_READY = Player.STATE_READY;
    public static final int PLAY_STATE_ENDED = Player.STATE_ENDED;

    private static TVPlayerManager instance;
    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;
    private DecoderManager decoderManager;
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
    private boolean isPlaying = false;
    private SimpleDateFormat logSdf = new SimpleDateFormat(LOG_TIME_FORMAT, Locale.getDefault());
    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;

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
        this.decoderManager = DecoderManager.getInstance(context);
        mHandler = new Handler(Looper.getMainLooper());
        stuckHandler = new Handler(Looper.getMainLooper());
        hideChannelRunnable = () -> hideChannelNum();
        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null || !player.isPlaying()) {
                    stuckHandler.postDelayed(this, STUCK_CHECK_INTERVAL_MS);
                    return;
                }
                try {
                    long currentPosition = player.getCurrentPosition();
                    long now = System.currentTimeMillis();
                    if (currentPosition != lastPosition) {
                        lastPosition = currentPosition;
                        lastPositionUpdateTime = now;
                    } else if (now - lastPositionUpdateTime > STUCK_TIMEOUT_MS) {
                        Log.w(TAG, "检测到播放卡住，自动重试");
                        SettingsActivity.logOperation("【播放器】检测到播放卡住，准备自动重试");
                        autoRetry("播放卡住");
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "卡住检测异常", e);
                }
                stuckHandler.postDelayed(this, STUCK_CHECK_INTERVAL_MS);
            }
        };
        initPlayer();
    }

    private void initPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        renderersFactory.setMediaCodecSelector(decoderManager.createMediaCodecSelector());
        int currentMode = decoderManager.getCurrentDecoderMode();
        String modeDesc = decoderManager.getDecoderModeName(currentMode);
        Log.d(TAG, "【解码器】初始化：" + modeDesc);
        SettingsActivity.logOperation("【解码器】初始化：" + modeDesc);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true).build();
        player = new ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).setLoadControl(loadControl).build();
        decoderManager.detectSystemH264Decoders();
        initPlayerListener();
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                autoRetry("播放错误：" + error.getMessage());
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == PLAY_STATE_READY) {
                    updateWakeLock(true);
                    showChannelAndAutoHide();
                    retryCount = 0;
                    isRetrying = false;
                    startStuckDetection();
                    if (initialPlayStartTime == 0) initialPlayStartTime = System.currentTimeMillis();
                    int currentMode = decoderManager.getCurrentDecoderMode();
                    if (currentMode == DECODER_MODE_AUTO && !hasSwitchedDecoder
                            && initialPlayStartTime > 0
                            && System.currentTimeMillis() < initialPlayStartTime + DECODER_SWITCH_TIME_WINDOW_MS
                            && bufferCount > DECODER_SWITCH_MIN_BUFFER_COUNT) {
                        Log.d(TAG, "硬解卡顿自动切换软解");
                        SettingsActivity.logOperation("【解码器】硬解卡顿，自动切换到系统软解");
                        hasSwitchedDecoder = true;
                        setDecoderMode(DECODER_MODE_SOFT);
                    }
                } else if (state == PLAY_STATE_BUFFERING) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                    }
                    if (bufferCount == 1) SettingsActivity.logOperation("【播放器】开始缓冲");
                } else if (state == PLAY_STATE_ENDED) {
                    autoRetry("播放结束");
                } else if (state == PLAY_STATE_IDLE) {
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
                        Log.d(TAG, "卡顿结束，时长：" + stallDuration + "ms");
                    }
                }
            }
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int w = video.width, h = video.height;
                Log.d(TAG, "分辨率：" + w + "×" + h);
            }
        };
        player.addListener(playerListener);
    }

    private void startStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        stuckHandler.postDelayed(stuckCheckRunnable, STUCK_CHECK_INTERVAL_MS);
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
        if (isRetrying) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数达上限");
            SettingsActivity.logOperation("【播放器】多次重试失败，源失效");
            return;
        }
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试第" + retryCount + "次，原因：" + reason);
        SettingsActivity.logOperation("【播放器】自动重试（第" + retryCount + "次）");
        retryRunnable = () -> {
            isRetrying = false;
            if (!TextUtils.isEmpty(currentUrl)) playUrlInternal(currentUrl);
            retryRunnable = null;
        };
        mHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    public void setDecoderMode(int mode) {
        int oldMode = decoderManager.getCurrentDecoderMode();
        if (oldMode == mode) return;
        decoderManager.setDecoderMode(mode);
        String modeDesc = decoderManager.getDecoderModeName(mode);
        Log.d(TAG, "切换解码器：" + modeDesc);
        if (player != null) {
            try {
                stopStuckDetection();
                cancelRetry();
                if (playerListener != null) player.removeListener(playerListener);
                player.release();
                player = null;
            } catch (Exception e) {
                Log.e(TAG, "释放播放器异常", e);
            }
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
        return decoderManager.getCurrentDecoderMode();
    }

    @Deprecated
    public void setSoftwareDecoder(boolean useSoftware) {
        setDecoderMode(useSoftware ? DECODER_MODE_SOFT : DECODER_MODE_AUTO);
    }

    public void registerDecoderModeReceiver() {
        if (decoderReceiverRegistered) return;
        try {
            decoderModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_DECODER_MODE_CHANGED.equals(intent.getAction())) {
                        int newMode = decoderManager.getCurrentDecoderMode();
                        setDecoderMode(newMode);
                        String name = decoderManager.getDecoderModeName(newMode);
                        SettingsActivity.logOperation("【解码器】广播切换：" + name);
                    }
                }
            };
            context.registerReceiver(decoderModeReceiver, new IntentFilter(ACTION_DECODER_MODE_CHANGED));
            decoderReceiverRegistered = true;
            SettingsActivity.logOperation("【解码器】广播注册成功");
        } catch (Exception e) {
            Log.e(TAG, "注册广播失败", e);
        }
    }

    public void unregisterDecoderModeReceiver() {
        if (!decoderReceiverRegistered) return;
        try {
            if (decoderModeReceiver != null) context.unregisterReceiver(decoderModeReceiver);
            decoderModeReceiver = null;
            decoderReceiverRegistered = false;
            SettingsActivity.logOperation("【解码器】广播注销");
        } catch (Exception e) {
            Log.e(TAG, "注销广播异常", e);
        }
    }

    public void onForeground() {
        try {
            if (player != null && playerView != null) {
                playerView.setPlayer(player);
                player.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "前台恢复异常", e);
        }
    }

    public void onBackground() {
        try {
            if (player != null) player.pause();
        } catch (Exception e) {
            Log.e(TAG, "后台暂停异常", e);
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

    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DEFAULT_USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", ICY_META_DATA_VALUE);
        boolean huya = url.contains(HUYA_DOMAIN_KEY);
        boolean douyu = url.contains(DOUYU_DOMAIN_KEY);
        if (huya) headers.put("Referer", DEFAULT_REFERER);
        else if (douyu) headers.put("Referer", DOUYU_REFERER);
        else headers.put("Referer", DEFAULT_REFERER);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) headers.put("Cookie", cookie);
        return headers;
    }

    public void play(String url) { playUrl(url); }
    public void playUrl(String url) {
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        hasSwitchedDecoder = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();
        SettingsActivity.logOperation("【播放器】加载新流");
        playUrlInternal(url);
    }

    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }

    private void playUrlInternal(String url) {
        try {
            if (player == null || TextUtils.isEmpty(url)) return;
            currentUrl = url.trim();
            Log.d(TAG, "播放地址：" + currentUrl);
            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);
            MediaItem item = MediaItem.fromUri(currentUrl);
            MediaSource source;
            if (currentUrl.toLowerCase().contains(HLS_STREAM_FLAG)) {
                source = new HlsMediaSource.Factory(httpFactory).createMediaSource(item);
            } else {
                source = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item);
            }
            player.setMediaSource(source, true);
            player.prepare();
            player.play();
            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放加载异常", e);
            autoRetry("播放加载异常");
        }
    }

    public enum ScaleMode {FIT,FILL,ZOOM}
    public void setScaleMode(ScaleMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case FIT:playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);break;
            case FILL:playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);break;
            case ZOOM:playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);break;
        }
    }

    public void setCurrentChannelNumber(int num) {
        currentChannelNumber = num;
    }
    public void bindChannelText(TextView textView) {
        channelNumberTextView = textView;
        if (channelNumberTextView != null) {
            channelNumberTextView.setTextColor(CHANNEL_NUM_DEFAULT_COLOR);
            channelNumberTextView.setTextSize(CHANNEL_NUM_DEFAULT_SIZE_SP);
        }
    }
    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
            mHandler.removeCallbacks(hideChannelRunnable);
            mHandler.postDelayed(hideChannelRunnable, CHANNEL_NUM_HIDE_DELAY_MS);
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
                Format vf = player.getVideoFormat();
                if (vf != null) {
                    int w = vf.width, h = vf.height;
                    if (w > 0 && h > 0) info.resolution = w + "×" + h;
                    info.format = vf.sampleMimeType;
                    if (vf.bitrate > 0) info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", vf / 1000000f);
                }
                Format af = player.getAudioFormat();
                if (af != null) {
                    info.audio = af.sampleMimeType;
                    if (af.sampleRate > 0) info.audio += " " + af.sampleRate / 1000 + "kHz";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取流信息失败", e);
        }
        return info;
    }

    public void pause() {
        try {if(player!=null)player.pause();}catch(Exception e){Log.e(TAG,"暂停异常",e);}
    }
    public void resume() {
        try {if(player!=null)player.play();}catch(Exception e){Log.e(TAG,"恢复异常",e);}
    }
    public void release() {
        try {
            stopStuckDetection();
            cancelRetry();
            mHandler.removeCallbacks(hideChannelRunnable);
            updateWakeLock(false);
            unregisterDecoderModeReceiver();
            if (player != null) {
                if (playerListener != null) player.removeListener(playerListener);
                player.release();
                player = null;
            }
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放播放器异常", e);
        }
    }
}
