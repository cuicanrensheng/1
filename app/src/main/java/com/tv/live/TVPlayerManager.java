package com.tv.live;

import android.annotation.SuppressLint; // 🟢 已导入
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.CookieManager;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import androidx.core.content.ContextCompat; // 🔧 新增导入

import com.tv.live.util.NetUtil;
import com.tv.live.exception.RedirectFailedException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.Headers;

// 🟢【两个关键修复】
// 1. @SuppressLint("UnsafeOptInUsageError") - 解决 Media3 不稳定 API 的 Lint 错误
// 2. @SuppressLint("StaticFieldLeak") - 消除静态 Context 持有警告（ApplicationContext 安全）
@SuppressLint({"UnsafeOptInUsageError", "StaticFieldLeak"})
public class TVPlayerManager {
    private static final String TAG = "TVPlayerManager";
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;
    
    private static final int MAX_RETRY_COUNT = 2;
    private static final long STUCK_TIMEOUT = 20000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;

    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";

    private static volatile TVPlayerManager instance;
    private Context context;
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    private String currentUrl;
    private int currentChannelNumber = 0;
    private TextView channelNumberTextView;
    private String currentChannelName = "";
    private int mDecoderMode = DECODER_MODE_AUTO;

    private boolean isSwitching = false;

    private Channel currentChannel;
    private int backupRetryIndex = -1;

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

    private Handler mHandler;
    private Runnable hideChannelRunnable;

    private OnPlayStateListener listener;
    private OnSourceFailedListener sourceFailedListener;
    private OnLiveInfoUpdateListener liveInfoUpdateListener;
    private boolean isPlaying = false;

    private BroadcastReceiver decoderModeReceiver;
    private boolean decoderReceiverRegistered = false;
    private BroadcastReceiver rendererModeReceiver;
    private boolean rendererReceiverRegistered = false;

    private OnPlayerViewRecreatedListener onPlayerViewRecreatedListener;
    private boolean isRenderingSwitching = false;

    private final Map<String, String> reusableHeaderMap = new HashMap<>();

    private DefaultTrackSelector trackSelector;

    private ScaleMode mCurrentScaleMode = ScaleMode.FILL;

    // 修复：记录当前已应用的渲染器类型（texture=true / surface=false），
    // 用于在 attachPlayerView / 广播切换时避免无差别地重建 PlayerView
    private Boolean mCurrentUseTexture = null;

    // 清晰度相关
    private final Object variantListLock = new Object();
    private volatile List<Variant> variantList = new ArrayList<>();
    private volatile boolean isParsingMasterPlaylist = false;

    private SharedPreferences sp;

    // 解析主播放列表使用的单线程池，避免 new Thread 泛滥
    private static final ExecutorService sPlaylistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TVPlayer-PlaylistParser");
        t.setDaemon(true);
        return t;
    });

    // 清晰度实体类
    public static class Variant {
        public String url;
        public int bandwidth;
        public int width;
        public int height;
        public String resolutionLabel; // 如 "720p", "1080p"

        Variant(String url, int bandwidth, int width, int height) {
            this.url = url;
            this.bandwidth = bandwidth;
            this.width = width;
            this.height = height;
            if (height >= 2160) resolutionLabel = "4K (2160p)";
            else if (height >= 1080) resolutionLabel = "1080p";
            else if (height >= 720) resolutionLabel = "720p";
            else if (height > 0) resolutionLabel = height + "p";
            else resolutionLabel = "自适应";
        }
    }

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
        this.sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        mHandler = new Handler(Looper.getMainLooper());

        hideChannelRunnable = () -> hideChannelNum();

        stuckCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null || !player.isPlaying()) {
                    // 修复：暂停/播放未就绪时重置卡死检测状态，避免恢复播放后立刻被误判为卡死
                    lastPosition = 0;
                    lastPositionUpdateTime = System.currentTimeMillis();
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
    
    private void dLog(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            Log.d(TAG, msg);
            com.tv.live.util.LogCollector.getInstance().addLog(TAG, msg);
        }
    }
    
    private void initPlayer() {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        SoftwareFirstMediaCodecSelector codecSelector = new SoftwareFirstMediaCodecSelector(mDecoderMode);
        renderersFactory.setMediaCodecSelector(codecSelector);
        // ✅ 双重兜底：第一个被选中的解码器初始化失败时，自动回退到下一个候选
        //    配合黑名单过滤，最大程度避免 Androws / c2.intel.goldfish.* 等 Error 0x80000000
        renderersFactory.setEnableDecoderFallback(true);

        switch (mDecoderMode) {
            case DECODER_MODE_SOFT:
                dLog("【解码器】软解模式");
                break;
            case DECODER_MODE_HARD:
                dLog("【解码器】硬解模式");
                break;
            case DECODER_MODE_AUTO:
            default:
                dLog("【解码器】自动模式");
                break;
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 45000, 800, 1500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        trackSelector = new DefaultTrackSelector(context);

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .build();

        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos("video/avc", false, false);
            int softCount = 0, hardCount = 0;
            for (MediaCodecInfo codec : h264Codecs) {
                if (isSoftwareDecoder(codec)) softCount++;
                else hardCount++;
            }
            dLog("【解码器】软解 " + softCount + " 个，硬解 " + hardCount + " 个");
        } catch (Exception ignored) {
        }

        initPlayerListener();
        // 修复：CookieSyncManager 在 API 21+ 已废弃，CookieManager 会自动同步
        CookieManager.getInstance().setAcceptCookie(true);
    }

    static boolean isSoftwareDecoder(MediaCodecInfo codec) {
        if (codec == null) return false;
        String name = codec.name;
        if (name == null) return false;
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    /**
     * 修复：更可靠的 HLS URL 判断——只看 URL 的 path 部分是否以 .m3u8 结尾，
     * 避免 URL 查询参数包含 "m3u8" 时误判（如 ?debug=m3u8）。
     * 兼容带 query 的地址：http://x.com/stream.m3u8?token=xxx
     */
    private static boolean isHlsUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return false;
            String lower = path.toLowerCase(Locale.ROOT);
            // 兼容 m3u8 和 variant m3u8
            return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
        } catch (Exception e) {
            // URI 解析失败回退到原始但更严格的 contains（针对 path-like 段）
            String lower = url.toLowerCase(Locale.ROOT);
            int q = lower.indexOf('?');
            String beforeQuery = q >= 0 ? lower.substring(0, q) : lower;
            return beforeQuery.contains(".m3u8") || beforeQuery.contains(".m3u");
        }
    }

    private void initPlayerListener() {
        if (playerListener != null) return;
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                // 修复：配合 performDecoderSwitch，发生错误时也重置切换状态
                isSwitching = false;

                Throwable rootCause = error.getCause();
                boolean isRedirectError = false;
                // 修复：增加遍历深度限制，防御异常 cause 链循环
                int depth = 0;
                while (rootCause != null && depth < 20) {
                    if (rootCause instanceof RedirectFailedException) {
                        isRedirectError = true;
                        break;
                    }
                    rootCause = rootCause.getCause();
                    depth++;
                }

                // 修复：先处理内部逻辑（切备源/通知源失效），最后再通知外部，
                // 避免外部在 onPlayError 中 release 或 setState 打断内部流程
                boolean backupSwitched = false;
                if (!isRedirectError) {
                    backupSwitched = trySwitchBackup();
                }

                boolean sourceFailedNotified = false;
                if (!backupSwitched && sourceFailedListener != null) {
                    sourceFailedListener.onSourceFailed();
                    sourceFailedNotified = true;
                }

                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    // 修复：播放就绪时重置 isSwitching 切换状态
                    isSwitching = false;
                    if (listener != null) listener.onPlayReady();
                    retryCount = 0;
                    isRetrying = false;
                    startStuckDetection();
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
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
                    // 修复：STATE_IDLE 时同样清理切换状态（prepare 失败或 stop 后）
                    isSwitching = false;
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
                        dLog("【性能】卡顿结束，时长：" + stallDuration + "ms");
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                dLog("视频分辨率变化：" + videoSize.width + "×" + videoSize.height);
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }

    private boolean trySwitchBackup() {
        if (currentChannel == null || currentChannel.getBackupUrls().isEmpty()) {
            return false;
        }
        if (backupRetryIndex < 0) {
            backupRetryIndex = 0;
        } else {
            backupRetryIndex++;
        }
        List<String> backups = currentChannel.getBackupUrls();
        if (backupRetryIndex >= backups.size()) {
            backupRetryIndex = -1;
            return false;
        }
        String backupUrl = backups.get(backupRetryIndex);
        dLog("尝试切换到备用源：" + backupUrl);
        playUrlInternal(backupUrl);
        return true;
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

    // 修复：新增重载，优先使用 Throwable 判断重定向错误，不再依赖脆弱的字符串匹配
    private void autoRetry(String reason) {
        autoRetry(reason, null);
    }

    private void autoRetry(String reason, Throwable cause) {
        // 优先基于异常类型判断是否为重定向失败（最可靠）
        if (cause != null) {
            Throwable t = cause;
            int depth = 0;
            while (t != null && depth < 20) {
                if (t instanceof RedirectFailedException) return;
                t = t.getCause();
                depth++;
            }
        }
        // 兼容旧调用：仅传字符串时做严格判断（不再含含糊的"重定向"中文词）
        if (cause == null && reason != null && reason.contains("RedirectFailedException")) {
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
        retryRunnable = () -> {
            isRetrying = false;
            if (!TextUtils.isEmpty(currentUrl)) {
                playUrlInternal(currentUrl);
            }
            retryRunnable = null;
        };
        mHandler.postDelayed(retryRunnable, 3000);
    }

    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        dLog("手动切换解码器模式：" + mode);
        if (player != null) performDecoderSwitch();
    }

    private void performDecoderSwitch() {
        if (isSwitching) {
            Log.w(TAG, "正在解码器切换中，忽略当前请求");
            return;
        }
        isSwitching = true;
        long currentPosition = player != null ? player.getCurrentPosition() : 0;
        // 修复：删除未使用的 wasPlaying 死代码

        try {
            mHandler.removeCallbacks(stuckCheckRunnable);
            mHandler.removeCallbacks(retryRunnable);
            mHandler.removeCallbacks(hideChannelRunnable);
            if (player != null) {
                if (playerListener != null) {
                    player.removeListener(playerListener);
                    playerListener = null;
                }
                player.release();
                player = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放旧播放器异常", e);
        }

        initPlayer();
        final boolean hasUrl = !TextUtils.isEmpty(currentUrl);
        if (playerView != null) {
            mHandler.post(() -> {
                try {
                    if (playerView != null && player != null) {
                        playerView.setPlayer(player);
                    }
                } finally {
                    // 修复：只有在没有 URL 需要重播时，才在这里重置 isSwitching
                    // 如果有 URL，等播放状态回调（STATE_READY/onPlayerError）或超时再重置
                    if (!hasUrl) isSwitching = false;
                }
            });
        }
        if (hasUrl) {
            retryCount = 0;
            isRetrying = false;

            if (mDecoderMode == DECODER_MODE_SOFT) {
                Toast.makeText(context, "已切换至 软解模式", Toast.LENGTH_SHORT).show();
            } else if (mDecoderMode == DECODER_MODE_HARD) {
                Toast.makeText(context, "已切换至 硬解模式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "已切换至 自动模式", Toast.LENGTH_SHORT).show();
            }

            playUrlInternal(currentUrl, currentPosition);
            // 修复：增加兜底超时恢复 isSwitching，避免极端情况下永远卡在切换中
            mHandler.postDelayed(() -> { isSwitching = false; }, 30000);
        } else if (playerView == null) {
            isSwitching = false;
        }
    }

    public int getDecoderMode() {
        return mDecoderMode;
    }

    // 🔧 修复：使用 ContextCompat.registerReceiver 替代版本判断，消除 Lint Error
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
            ContextCompat.registerReceiver(context, decoderModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
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

    private void switchRenderer(boolean useTexture) {
        // 修复：player 为 null 时直接返回，避免 NPE
        if (player == null || playerView == null || context == null) return;
        // 修复：如果当前已是目标渲染模式，直接跳过，避免无差别重建 PlayerView
        if (mCurrentUseTexture != null && mCurrentUseTexture == useTexture) {
            if (playerView.getPlayer() != player) playerView.setPlayer(player);
            return;
        }
        // 修复：父容器泛化为 ViewGroup，不再强转 FrameLayout，兼容任何布局
        ViewParent rawParent = playerView.getParent();
        if (!(rawParent instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) rawParent;

        View blackMask = new View(context);
        blackMask.setBackgroundColor(Color.BLACK);
        // 使用通用 ViewGroup.LayoutParams，兼容所有父容器类型
        ViewGroup.LayoutParams maskParams = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
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
        newPlayerView.setKeepContentOnPlayerReset(true);

        int resizeMode;
        switch (mCurrentScaleMode) {
            case FILL:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                break;
            case ZOOM:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                break;
            case FIT:
            default:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                break;
        }
        newPlayerView.setResizeMode(resizeMode);
        newPlayerView.setPlayer(player);

        parent.addView(newPlayerView, index, layoutParams);
        playerView.setPlayer(null);
        parent.removeView(playerView);
        playerView = newPlayerView;

        if (currentPosition > 0) player.seekTo(currentPosition);
        if (wasPlaying) {
            mHandler.postDelayed(() -> {
                if (player != null && !player.isPlaying()) player.play();
            }, 200);
        }

        if (onPlayerViewRecreatedListener != null) {
            onPlayerViewRecreatedListener.onPlayerViewRecreated(newPlayerView);
        }
        playerView.requestFocus();

        final ViewGroup parentFinal = parent;
        playerView.postDelayed(() -> {
            blackMask.animate().alpha(0f).setDuration(250).withEndAction(() -> parentFinal.removeView(blackMask)).start();
        }, 100);

        // 修复：记录当前生效的渲染模式，下次调用时可短路跳过
        mCurrentUseTexture = useTexture;
        isRenderingSwitching = false;
    }

    // 🔧 修复：使用 ContextCompat.registerReceiver 替代版本判断，消除 Lint Error
    public void registerRendererModeReceiver() {
        if (rendererReceiverRegistered) return;
        try {
            rendererModeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.tv.live.RENDERER_TYPE_CHANGED".equals(intent.getAction())) {
                        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                        String mode = sp.getString("renderer_type", "surface");
                        if (playerView != null) switchRenderer("texture".equals(mode));
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.tv.live.RENDERER_TYPE_CHANGED");
            ContextCompat.registerReceiver(context, rendererModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
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

    public void playUrl(String url) {
        playUrl(url, null, null);
    }

    public void playUrl(String url, String channelName) {
        playUrl(url, channelName, null);
    }

    public void playUrl(String url, String channelName, Channel channel) {
        if (!TextUtils.isEmpty(channelName)) this.currentChannelName = channelName;
        this.currentChannel = channel;
        this.backupRetryIndex = -1;
        if (channel != null && TextUtils.isEmpty(this.currentChannelName)) {
            this.currentChannelName = channel.getName();
        }
        cancelRetry();
        retryCount = 0;
        isRetrying = false;
        initialPlayStartTime = 0;
        resetPerformanceStats();
        playUrlInternal(url, 0);
    }

    public Channel getCurrentChannel() {
        return currentChannel;
    }

    public interface OnSourceFailedListener {
        void onSourceFailed();
    }

    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        this.sourceFailedListener = listener;
    }

    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
    }

    private void playUrlInternal(String url) {
        playUrlInternal(url, 0);
    }

    private void playUrlInternal(String url, long initialSeekPosition) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;

            String playUrl = url.trim();
            if (currentChannel != null) {
                SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                int lineIndex = sp.getInt("channel_line_index", 0);
                if (lineIndex == 0) {
                    playUrl = currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = lineIndex - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        playUrl = backups.get(backupIndex);
                    } else {
                        playUrl = currentChannel.getMainPlayUrl();
                        Log.w(TAG, "线路索引越界，已自动切回主源");
                    }
                }
                currentUrl = playUrl;
                dLog("切换线路后播放：" + currentUrl);
            } else {
                currentUrl = playUrl;
            }

            if (isHlsUrl(currentUrl)) {
                fetchAndParseMasterPlaylist(currentUrl);
            } else {
                synchronized (variantListLock) { variantList.clear(); }
            }

            RedirectLoggingHttpDataSource.Factory httpFactory = new RedirectLoggingHttpDataSource.Factory();
            Headers globalHeaders = NetUtil.getInstance().createCommonHeaders(currentUrl);
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
            if (isHlsUrl(currentUrl)) {
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }

            player.setMediaSource(mediaSource, true);
            player.prepare();
            if (initialSeekPosition > 0) player.seekTo(initialSeekPosition);
            player.play();
            startStuckDetection();

        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            if (e instanceof RedirectFailedException) {
                if (listener != null) listener.onPlayError("源跳转失败：" + e.getMessage());
                return;
            }
            // 修复：传入异常对象，autoRetry 基于类型判断重定向错误而非字符串
            autoRetry("播放异常：" + e.getMessage(), e);
        }
    }

    private void fetchAndParseMasterPlaylist(String masterUrl) {
        if (isParsingMasterPlaylist) return;
        isParsingMasterPlaylist = true;
        sPlaylistExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                dLog("开始解析主播放列表: " + masterUrl);
                URL url = new URL(masterUrl);
                // 修复：改为通用 HttpURLConnection，同时支持 http:// 和 https://
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
                String cookies = CookieManager.getInstance().getCookie(masterUrl);
                if (cookies != null) connection.setRequestProperty("Cookie", cookies);

                StringBuilder content = new StringBuilder();
                // 修复：try-with-resources 确保 InputStream/BufferedReader 异常时也关闭
                try (InputStream is = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                }

                String playlist = content.toString();
                dLog("播放列表内容长度: " + playlist.length());
                parseMasterPlaylist(playlist, masterUrl);
            } catch (Exception e) {
                Log.e(TAG, "解析主播放列表失败: ", e);
                synchronized (variantListLock) { variantList.clear(); }
            } finally {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                isParsingMasterPlaylist = false;
            }
        });
    }

    private void parseMasterPlaylist(String playlist, String baseUrl) {
        List<Variant> list = new ArrayList<>();
        // 修复：拆分正则——先找 STREAM-INF 行，再分别提取 BANDWIDTH/RESOLUTION，
        // 不再依赖两者在属性中的先后顺序，符合 HLS 规范。
        Pattern streamInfPattern = Pattern.compile("^#EXT-X-STREAM-INF:", Pattern.CASE_INSENSITIVE);
        Pattern bandwidthPattern = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern resolutionPattern = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
        dLog("播放列表内容（截取前500字符）：\n" + playlist.substring(0, Math.min(playlist.length(), 500)));

        String[] lines = playlist.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!streamInfPattern.matcher(line).find()) continue;

            Matcher bwMatcher = bandwidthPattern.matcher(line);
            if (!bwMatcher.find()) continue; // STREAM-INF 必须有 BANDWIDTH，否则跳过
            int bandwidth = Integer.parseInt(bwMatcher.group(1));

            int width = 0, height = 0;
            String resolutionStr = null;
            Matcher resMatcher = resolutionPattern.matcher(line);
            if (resMatcher.find()) {
                width = Integer.parseInt(resMatcher.group(1));
                height = Integer.parseInt(resMatcher.group(2));
                resolutionStr = width + "x" + height;
            }

            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (!next.isEmpty() && !next.startsWith("#")) {
                    uri = next;
                    break;
                }
            }
            if (uri != null) {
                if (!uri.startsWith("http")) {
                    uri = resolveUrl(baseUrl, uri);
                }
                list.add(new Variant(uri, bandwidth, width, height));
                dLog("解析到清晰度: " + (height > 0 ? resolutionStr : "自适应") + " -> " + uri);
            }
        }
        list.sort((a, b) -> Integer.compare(a.height, b.height));
        synchronized (variantListLock) { this.variantList = list; }
        if (!list.isEmpty()) {
            dLog("解析到 " + list.size() + " 个清晰度");
        } else {
            Log.w(TAG, "未解析到任何清晰度流，可能是直播源本身不支持多码率或网络被拦截");
        }
    }

    private String resolveUrl(String base, String relative) {
        try {
            URL baseUrl = new URL(base);
            URL resolved = new URL(baseUrl, relative);
            return resolved.toString();
        } catch (Exception e) {
            return relative;
        }
    }

    public List<String> getAvailableResolutions() {
        List<String> resolutions = new ArrayList<>();
        synchronized (variantListLock) {
            for (Variant v : variantList) {
                if (!resolutions.contains(v.resolutionLabel)) {
                    resolutions.add(v.resolutionLabel);
                }
            }
        }
        return resolutions;
    }

    public void switchToResolution(int targetHeight) {
        List<Variant> snapshot;
        synchronized (variantListLock) {
            snapshot = new ArrayList<>(variantList);
        }
        if (snapshot.isEmpty()) {
            Log.w(TAG, "无多码率信息，无法切换清晰度");
            return;
        }
        Variant selected = null;
        for (Variant v : snapshot) {
            if (v.height >= targetHeight) {
                selected = v;
                break;
            }
        }
        if (selected == null) {
            selected = snapshot.get(snapshot.size() - 1);
        }
        dLog("切换清晰度到：" + selected.resolutionLabel + "，URL=" + selected.url);
        playUrlInternal(selected.url);
    }

    public enum ScaleMode {FIT, FILL, ZOOM}

    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            this.mCurrentScaleMode = mode;
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
                    info.format = friendlyMime(videoFormat.sampleMimeType);
                    if (videoFormat.bitrate > 0)
                        info.bitrate = String.format(Locale.getDefault(), "%.1f Mbps", videoFormat.bitrate / 1000000f);
                }
                Format audioFormat = player.getAudioFormat();
                if (audioFormat != null) {
                    info.audio = friendlyMime(audioFormat.sampleMimeType);
                    if (audioFormat.sampleRate > 0) info.audio += " " + (audioFormat.sampleRate / 1000) + "kHz";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取直播信息异常", e);
        }
        return info;
    }

    /**
     * 修复：把媒体 MIME 类型映射成用户友好的可读名称。
     * 未识别的类型返回原值（避免信息丢失）。
     */
    private static String friendlyMime(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) return "未知";
        String m = mimeType.toLowerCase(Locale.ROOT);
        // ---- 视频 ----
        if (m.contains("avc") || m.contains("h264") || m.endsWith("/264")) return "H.264";
        if (m.contains("hevc") || m.contains("h265")) return "H.265 (HEVC)";
        if (m.contains("av1")) return "AV1";
        if (m.contains("vp9")) return "VP9";
        if (m.contains("vp8")) return "VP8";
        if (m.contains("mpeg2") || m.contains("mp2v")) return "MPEG-2";
        if (m.contains("mpeg4") || m.contains("mp4v")) return "MPEG-4";
        if (m.contains("wmv")) return "WMV";
        // ---- 音频 ----
        if (m.contains("mp4a") || m.contains("aac") || m.contains("mpeg4-generic")) return "AAC";
        if (m.contains("ac3")) return "AC-3";
        if (m.contains("eac3") || m.contains("ec3")) return "E-AC-3 (Dolby Digital Plus)";
        if (m.contains("ac4")) return "AC-4";
        if (m.contains("opus")) return "Opus";
        if (m.contains("vorbis")) return "Vorbis";
        if (m.contains("flac")) return "FLAC";
        if (m.contains("g711") || m.contains("alaw") || m.contains("ulaw")) return "G.711";
        if (m.contains("pcm")) return "PCM";
        if (m.contains("wma")) return "WMA";
        if (m.contains("mp3") || m.endsWith("/mpeg") && m.startsWith("audio/")) return "MP3";
        return mimeType;
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

    public void setOnPlayStateListener(OnPlayStateListener l) {
        listener = l;
    }

    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }

    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        liveInfoUpdateListener = listener;
    }

    public void pause() {
        try {
            if (player != null) player.pause();
        } catch (Exception ignored) {}
    }

    public void resume() {
        try {
            if (player != null) player.play();
        } catch (Exception ignored) {}
    }

    /**
     * 切换播放/暂停状态（DPAD_CENTER 长按、媒体键调用）
     */
    public void togglePlayWhenReady() {
        try {
            if (player == null) return;
            if (player.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE
                    || player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) {
                return;
            }
            player.setPlayWhenReady(!player.getPlayWhenReady());
        } catch (Exception ignored) {}
    }

    public boolean isPlaying() {
        try {
            return player != null && player.getPlayWhenReady()
                    && player.getPlaybackState() != androidx.media3.common.Player.STATE_IDLE
                    && player.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED;
        } catch (Exception e) {
            return false;
        }
    }

    public void release() {
        try {
            stopStuckDetection();
            cancelRetry();
            mHandler.removeCallbacksAndMessages(null);
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
            if (playerView != null) {
                playerView.setPlayer(null);
                playerView = null;
            }
            // 修复：instance = null 必须放在所有清理动作的最后，
            // 防止其他线程在清理中途创建新实例造成状态混乱
            instance = null;
        } catch (Exception e) {
            Log.e(TAG, "释放异常", e);
        }
    }

    // ======================================================================
    // 解码策略（TDD 单元测试可直接调用）
    //   应用顺序：先过滤【不稳定硬件解码器黑名单】，再按 mode 排序/筛选
    //   目的：修复 Androws 模拟器上 c2.intel.goldfish.h264.decoder 崩溃（Error 0x80000000）
    // ======================================================================

    /** Androws / Hyper-V 模拟器里的"假硬解"，实际不稳定且会抛 0x80000000 */
    private static final String[] UNSTABLE_HARDWARE_BLACKLIST_LOWER = new String[] {
            "c2.intel.goldfish.",      // Androws / 腾讯移动应用引擎 Intel Goldfish 硬解（100% 复现 0x80000000）
            "omx.google.android.",     // Google 官方也把它标成 hardware，实际是纯软件模拟，慢又易崩
            "c2.amlogic.avc.decoder.awesome",  // 部分晶晨盒子驱动有 bug，需手动黑名单（可按实际情况增删）
    };

    /** @return true 表示这个编解码器名字命中黑名单 */
    static boolean isUnstableHardwareDecoder(String codecName) {
        if (codecName == null) return false;
        String lower = codecName.toLowerCase(Locale.ROOT);
        for (String prefix : UNSTABLE_HARDWARE_BLACKLIST_LOWER) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * 包私有（测试可直接调用）：对外公开的解码器过滤 + 排序策略
     *
     * @param allCodecs MediaCodecUtil.getDecoderInfos 返回的原始列表
     * @param mode      DECODER_MODE_AUTO / DECODER_MODE_SOFT / DECODER_MODE_HARD
     * @return 处理后的解码器列表
     */
    static List<MediaCodecInfo> applyCodecPolicy(List<MediaCodecInfo> allCodecs, int mode) {
        if (allCodecs == null || allCodecs.isEmpty()) return allCodecs;

        // Step 1: 所有模式先过一次黑名单（去掉 goldfish 等不稳定假硬件）
        List<MediaCodecInfo> afterBlacklist = new ArrayList<>();
        for (MediaCodecInfo codec : allCodecs) {
            if (codec == null) continue;
            if (isUnstableHardwareDecoder(codec.name)) continue;
            afterBlacklist.add(codec);
        }
        if (afterBlacklist.isEmpty()) {
            // 兜底：黑名单后没有候选了——退回原始列表（避免完全无解码器可用）
            afterBlacklist = new ArrayList<>(allCodecs);
        }

        // Step 2: 按 mode 分类处理
        switch (mode) {
            case DECODER_MODE_HARD: {
                // 只要硬件解码器（同时再次保留黑名单结果）
                List<MediaCodecInfo> hard = new ArrayList<>();
                for (MediaCodecInfo codec : afterBlacklist) {
                    if (!isSoftwareDecoder(codec)) hard.add(codec);
                }
                return hard.isEmpty() ? afterBlacklist : hard;
            }
            case DECODER_MODE_SOFT: {
                // 软件排前，硬件兜底放末尾
                List<MediaCodecInfo> soft = new ArrayList<>();
                List<MediaCodecInfo> hard = new ArrayList<>();
                for (MediaCodecInfo codec : afterBlacklist) {
                    if (isSoftwareDecoder(codec)) soft.add(codec);
                    else hard.add(codec);
                }
                soft.addAll(hard);
                return soft;
            }
            case DECODER_MODE_AUTO:
            default:
                return afterBlacklist;
        }
    }

    private static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;

        public SoftwareFirstMediaCodecSelector(int mode) {
            this.decoderMode = mode;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(mimeType, false, false);
            return applyCodecPolicy(allCodecs, decoderMode);
        }
    }
}
