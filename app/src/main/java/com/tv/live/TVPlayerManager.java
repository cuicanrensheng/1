package com.tv.live;
import android.content.Context;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
/**
 * 电视直播播放器管理器
 *
 * 【功能】
 * 1. 封装 ExoPlayer 的播放控制
 * 2. ✅ 支持硬解/软解切换（系统自带软解码，2026-06-26 修改）
 * 3. ✅ 支持三种解码器模式：自动/硬解/软解（2026-06-25 新增）
 * 4. 自动重试机制
 * 5. 卡住检测
 * 6. 源失效回调（自动切台用）
 * 7. 性能统计（缓冲次数、卡顿时间）
 * 8. ✅ 自动切换解码器（硬解卡顿自动切软解）（2026-06-25 新增）
 * 9. 画中画支持
 * 10. 缩放模式切换
 *
 * 【单例模式】
 * 整个应用只有一个播放器实例，避免重复创建和释放。
 *
 * 【2026-06-23 修改：升级到 Media3 1.x】
 * 包名从 com.google.android.exoplayer2.* 改成 androidx.media3.*
 * SimpleExoPlayer 改成 ExoPlayer
 *
 * 【2026-06-25 新增：解码器模式三选一 + 自动切换灵敏度调优】
 * 
 * 【解码器模式说明】
 * 1. DECODER_MODE_AUTO（自动，推荐）：
 *    - 硬解优先（系统默认行为）
 *    - 播放开始后 15 秒内如果缓冲 > 1 次，自动切换到系统软解
 *    - 每个频道只自动切换一次，避免反复切换
 *    - 兼顾性能和兼容性
 *
 * 2. DECODER_MODE_HARD（强制硬解）：
 *    - 只用系统硬解码器
 *    - 性能最好，最省电，但兼容性一般
 *
 * 3. DECODER_MODE_SOFT（软解优先）：
 *    - 优先使用系统软件解码器
 *    - 兼容性好，不依赖额外库
 *    - 性能稍差，耗电多一些
 *
 * 【自动切换灵敏度调优】
 * 原来：30 秒内缓冲 > 2 次才切换
 * 现在：15 秒内缓冲 > 1 次就切换
 * 【原因】用户反馈画面卡顿，原来的触发条件太宽松了，
 * 调灵敏一点，让用户能更快感受到流畅度提升。
 *
 * 【向后兼容】
 * 保留 setSoftwareDecoder(boolean) 方法，内部调用 setDecoderMode()，
 * 确保旧代码不用改也能正常运行。
 *
 * 【2026-06-26 修改：改用系统自带软解，替代 FFmpeg 方案】
 * 【为什么改用系统软解？】
 * FFmpeg 扩展编译复杂、集成麻烦，而且 Media3 的 decoder_ffmpeg
 * 模块默认只支持音频，视频需要手动编译和加载实验性渲染器。
 * 系统自带的软件解码器（OMX.google.* / c2.android.*）虽然性能
 * 不如 FFmpeg，但胜在稳定、无需额外依赖、集成简单。
 *
 * 【实现原理】
 * 通过自定义 MediaCodecSelector 调整解码器优先级：
 * - 硬解模式：只保留硬件解码器
 * - 软解模式：软件解码器排在前面，硬件解码器排在后面
 * - 自动模式：系统默认顺序（硬解优先）
 */
public class TVPlayerManager {
    // ====================== 常量 ======================
    private static final String TAG = "TVPlayerManager";
    // ====================================================================
    // ✅ 解码器模式常量（2026-06-25 新增）
    // ====================================================================
    /**
     * 解码器模式：自动（推荐）
     * 硬解优先，卡顿自动切换到系统软解
     */
    public static final int DECODER_MODE_AUTO = 0;
    /**
     * 解码器模式：强制硬解
     * 只用系统硬解码器
     */
    public static final int DECODER_MODE_HARD = 1;
    /**
     * 解码器模式：软解优先（系统自带）
     * 优先使用系统软件解码器
     */
    public static final int DECODER_MODE_SOFT = 2;
    /**
     * 最大重试次数
     * 【2026-06-25 修改：从 3 改成 2】
     * 【修改原因】重试太多次用户等待时间太长，
     * 2 次还不行就判定为失效源，自动切下一个。
     */
    private static final int MAX_RETRY_COUNT = 2;
    /**
     * 卡住超时时间（毫秒）
     * 播放位置 10 秒没动，就认为卡住了
     */
    private static final long STUCK_TIMEOUT = 10000;
    /**
     * 频道号自动隐藏延迟（毫秒）
     */
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    // ====================== 单例相关 ======================
    private static TVPlayerManager instance;
    private Context context;
    // ====================== 播放器相关 ======================
    private ExoPlayer player;
    private PlayerView playerView;
    private Player.Listener playerListener;
    /**
     * 当前播放的 URL
     */
    private String currentUrl;
    /**
     * 当前频道号
     */
    private int currentChannelNumber = 0;
    /**
     * 频道号显示的 TextView
     */
    private TextView channelNumberTextView;
    // ====================================================================
    // ✅ 解码器模式（2026-06-25 新增，替代原来的 useSoftwareDecoder）
    // ====================================================================
    /**
     * 当前解码器模式
     * 【可选值】
     * - DECODER_MODE_AUTO（0）：自动模式
     * - DECODER_MODE_HARD（1）：强制硬解
     * - DECODER_MODE_SOFT（2）：软解优先
     *
     * 【默认值】DECODER_MODE_AUTO（自动模式）
     *
     * 【为什么用 int 而不是 boolean？】
     * 原来只有硬解/软解两种模式，用 boolean 就够了。
     * 现在增加了"自动"模式，三种模式，所以改成 int。
     */
    private int mDecoderMode = DECODER_MODE_AUTO;
    /**
     * 是否使用软解码（保留，用于向后兼容）
     * 【2026-06-25 说明】
     * 这个变量保留是为了向后兼容，
     * 实际逻辑已经迁移到 mDecoderMode。
     * setSoftwareDecoder() 方法内部会同步更新 mDecoderMode。
     *
     * @deprecated 请使用 mDecoderMode 替代
     */
    @Deprecated
    private boolean useSoftwareDecoder = false;
    // ====================================================================
    // 自动切换解码器相关
    // ====================================================================
    /**
     * 是否已切换过解码器（每个频道只切一次）
     * 【作用】
     * 防止在同一个频道上反复切换解码器，
     * 导致播放不稳定。
     *
     * 【重置时机】
     * 切换频道时（playUrl() 方法中）重置为 false
     */
    private boolean hasSwitchedDecoder = false;
    /**
     * 首次播放开始时间（只在第一次 STATE_READY 时设置）
     * 【作用】
     * 用于判断自动切换解码器的时间窗口（15 秒内）。
     * 只在第一次 STATE_READY 时设置，
     * 后续的状态变化不会影响这个时间。
     *
     * 【重置时机】
     * 切换频道时（playUrl() 方法中）重置为 0
     */
    private long initialPlayStartTime = 0;
    // ====================================================================
    // 性能统计相关
    // ====================================================================
    /**
     * 缓冲次数
     * 【作用】统计播放过程中的缓冲次数，
     * 用于判断是否需要自动切换解码器。
     */
    private int bufferCount = 0;
    /**
     * 总卡顿时间（毫秒）
     * 【作用】统计播放过程中的总卡顿时长，
     * 用于性能分析和日志记录。
     */
    private long totalStallTime = 0;
    /**
     * 是否正在卡顿
     * 【作用】标记当前是否处于卡顿状态，
     * 用于计算单次卡顿的时长。
     */
    private boolean isStalled = false;
    /**
     * 上次卡顿开始时间
     * 【作用】记录卡顿开始的时间点，
     * 卡顿结束时用当前时间减去这个值，得到卡顿时长。
     */
    private long lastStallStartTime = 0;
    // ====================================================================
    // 重试相关
    // ====================================================================
    /**
     * 当前重试次数
     */
    private int retryCount = 0;
    /**
     * 是否正在重试中（有重试任务在等待）
     * 【作用】防止重复安排重试任务。
     */
    private boolean isRetrying = false;
    /**
     * 重试任务的引用
     * 【作用】方便 cancelRetry() 取消掉。
     */
    private Runnable retryRunnable;
    // ====================================================================
    // 卡住检测相关
    // ====================================================================
    /**
     * 卡住检测的 Handler
     */
    private Handler stuckHandler;
    /**
     * 上次播放位置更新时间
     */
    private long lastPositionUpdateTime = 0;
    /**
     * 上次记录的播放位置
     */
    private long lastPosition = 0;
    /**
     * 卡住检测的 Runnable
     */
    private Runnable stuckCheckRunnable;
    // ====================================================================
    // 频道号自动隐藏相关
    // ====================================================================
    /**
     * 主线程 Handler
     * 【作用】
     * 1. 延迟隐藏频道号
     * 2. 回调源失效监听器（切到主线程）
     * 3. 其他需要在主线程执行的任务
     */
    private Handler mHandler;
    /**
     * 隐藏频道号的 Runnable
     */
    private Runnable hideChannelRunnable;
    // ====================================================================
    // 监听器相关
    // ====================================================================
    /**
     * 播放状态监听器
     */
    private OnPlayStateListener listener;
    /**
     * 源失效监听器
     * 【作用】
     * 重试次数用完后，回调这个监听器，
     * 通知外部（MainActivity）这个源失效了，
     * 让外部自动跳过这个频道，切到下一个。
     *
     * 【2026-06-25 新增】
     */
    private OnSourceFailedListener sourceFailedListener;
    /**
     * 直播信息更新监听器
     */
    private OnLiveInfoUpdateListener liveInfoUpdateListener;
    // ====================================================================
    // 其他
    // ====================================================================
    /**
     * 是否正在播放
     * 【作用】用于控制屏幕常亮。
     */
    private boolean isPlaying = false;
    /**
     * 日志时间格式化
     */
    private SimpleDateFormat logSdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    // ====================== 单例获取 ======================
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
    // ====================== 构造方法 ======================
    private TVPlayerManager(Context context) {
        this.context = context;
        mHandler = new Handler(Looper.getMainLooper());
        stuckHandler = new Handler(Looper.getMainLooper());
        // 初始化隐藏频道号的 Runnable
        hideChannelRunnable = new Runnable() {
            @Override
            public void run() {
                hideChannelNum();
            }
        };
        // 初始化卡住检测 Runnable
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
        // 初始化播放器
        initPlayer();
    }
    // ====================================================================
    // ✅ 初始化播放器
    // ====================================================================
    /**
     * 初始化 ExoPlayer 播放器
     *
     * 【2026-06-25 重构：根据解码器模式设置渲染器工厂】
     * 原来只有硬解/软解两种模式，用 if-else 判断。
     * 现在有三种模式（自动/硬解/软解），改成 switch-case。
     *
     * 【2026-06-26 修改：改用系统自带软解，替代 FFmpeg 方案】
     * 【实现原理】
     * 通过自定义 MediaCodecSelector 调整解码器优先级：
     * - 硬解模式：只保留硬件解码器
     * - 软解模式：软件解码器排在前面，硬件解码器排在后面
     * - 自动模式：系统默认顺序（硬解优先）
     *
     * 【为什么不用 FFmpeg 了？】
     * FFmpeg 扩展编译复杂、集成麻烦，而且 Media3 的 decoder_ffmpeg
     * 模块默认只支持音频，视频需要手动编译和加载实验性渲染器。
     * 系统自带的软件解码器虽然性能不如 FFmpeg，但胜在稳定、
     * 无需额外依赖、集成简单。
     */
    private void initPlayer() {
        // 创建渲染器工厂
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
        // ====================================================================
        // ✅ 2026-06-26 修改：设置自定义 MediaCodecSelector 控制软解/硬解优先级
        // ====================================================================
        // 【为什么需要自定义 MediaCodecSelector？】
        // Media3 默认是硬解优先，软件解码器排在后面。
        // 我们通过自定义 MediaCodecSelector 来调整优先级，
        // 实现"软解优先"和"强制硬解"两种模式。
        //
        // 【软件解码器识别规则】
        // 名称以 OMX.google. 或 c2.android. 开头的是软件解码器
        // 其他的一般是硬件解码器（如 OMX.qcom.、OMX.hisi.、c2.qti. 等）
        SoftwareFirstMediaCodecSelector codecSelector = 
                new SoftwareFirstMediaCodecSelector(mDecoderMode);
        renderersFactory.setMediaCodecSelector(codecSelector);
        // ====================================================================
        // ✅ 根据解码器模式输出日志（2026-06-26 修改：去掉 FFmpeg 相关）
        // ====================================================================
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
        // ================================================
        // ✅ 缓冲配置（快速出画 + 大缓冲防卡）
        // ================================================
        /**
         * 【参数说明】
         *
         * minBufferMs：最小缓冲，低于这个值就继续加载
         * maxBufferMs：最大缓冲，超过这个值就停止加载
         * bufferForPlaybackMs：开始播放所需的最小缓冲量
         * bufferForPlaybackAfterRebufferMs：重缓冲后开始播放所需的最小缓冲量
         *
         * 【优化思路】
         * - 把 bufferForPlaybackMs 从 1000ms 改成 300ms
         *   意思是：只要有 300ms 的数据，就开始播放
         *   这样首帧出来得更快，用户等待时间更短
         *
         * - maxBufferMs 保持 50000ms（50秒）
         *   大缓冲可以抵抗网络波动，防止卡顿
         *
         * - 这是"快速出画 + 稳定播放"的平衡方案
         *
         * 【注意】
         * minBufferMs 必须 >= bufferForPlaybackAfterRebufferMs
         * 否则 ExoPlayer 会崩溃
         */
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        2000,      // minBufferMs - 最小缓冲 2秒
                        50000,     // maxBufferMs - 最大缓冲 50秒（抗网络波动）
                        300,       // bufferForPlaybackMs - 有 300ms 就开始播（快速出画）
                        500        // bufferForPlaybackAfterRebufferMs - 重缓冲后 500ms 就播
                )
                .setPrioritizeTimeOverSizeThresholds(true) // 优先保证时间缓冲
                .build();
        // 创建ExoPlayer实例
        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .build();
        // ====================================================================
        // ✅ 2026-06-26 新增：检测系统中可用的软解/硬解解码器数量
        // ====================================================================
        // 【作用】
        // 初始化后检测系统中可用的软件解码器和硬件解码器数量，
        // 方便调试和确认软解模式是否生效。
        //
        // 【2026-06-26 修复：codec.getName() → codec.name】
        // Media3 中 MediaCodecInfo 是数据类，直接用 name 字段，
        // 没有 getName() 方法。
        try {
            // 检测 H.264 解码器（最常见的视频格式）
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos(
                    "video/avc", false, false);
            int softCount = 0;
            int hardCount = 0;
            StringBuilder softNames = new StringBuilder();
            StringBuilder hardNames = new StringBuilder();
            for (MediaCodecInfo codec : h264Codecs) {
                // ✅ 修复：用 codec.name 而不是 codec.getName()
                // Media3 中 MediaCodecInfo 是数据类，直接暴露 public final 字段
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
        // 初始化播放监听器
        initPlayerListener();
        // 初始化Cookie管理器
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
    }
    // ====================================================================
    // ✅ 判断是否是软件解码器（2026-06-26 新增）
    // ====================================================================
    /**
     * 判断解码器名称是否是软件解码器
     *
     * 【识别规则】
     * 软件解码器的名称通常以以下前缀开头：
     * - OMX.google. （旧版软件解码器）
     * - c2.android. （新版 Codec2 软件解码器）
     *
     * 硬件解码器的名称通常以厂商前缀开头：
     * - OMX.qcom. / c2.qti. （高通）
     * - OMX.hisi. / c2.hisi. （海思）
     * - OMX.MTK. / c2.mtk. （联发科）
     * - OMX.Exynos. / c2.exynos. （三星）
     * - 等等
     *
     * @param codecName 解码器名称
     * @return true = 软件解码器，false = 硬件解码器
     */
    private static boolean isSoftwareDecoder(String codecName) {
        if (codecName == null) return false;
        String lowerName = codecName.toLowerCase();
        return lowerName.startsWith("omx.google.") 
                || lowerName.startsWith("c2.android.");
    }
        // ====================================================================
    // ✅ 初始化播放状态监听器
    // ====================================================================
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放异常: " + error.getMessage());
                if (listener != null) {
                    listener.onPlayError(error.getMessage());
                }
                // 播放错误时自动重试（重试次数用完后回调源失效）
                autoRetry("播放错误：" + error.getMessage());
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    updateWakeLock(true);
                    notifyLiveInfoUpdate();
                    showChannelAndAutoHide();
                    if (listener != null) listener.onPlayReady();
                    // 播放就绪，重置重试计数
                    retryCount = 0;
                    isRetrying = false;
                    // 开始卡住检测
                    startStuckDetection();
                    // ====================================================================
                    // 只在第一次 STATE_READY 时记录开始时间
                    // ====================================================================
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                    // ====================================================================
                    // ✅ 自动切换解码器（硬解 → 软解）（2026-06-25 调优）
                    // ====================================================================
                    if (mDecoderMode == DECODER_MODE_AUTO && !hasSwitchedDecoder
                            && initialPlayStartTime > 0
                            && System.currentTimeMillis() - initialPlayStartTime < 15000
                            && bufferCount > 1) {
                        Log.d(TAG, "【自动切换】硬解卡顿，自动切换到系统软解");
                        SettingsActivity.logOperation("【解码器】硬解卡顿（缓冲"
                                + bufferCount + "次），自动切换到系统软解");
                        hasSwitchedDecoder = true;
                        setDecoderMode(DECODER_MODE_SOFT);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (listener != null) listener.onBuffering();
                    // 缓冲中也重置卡住检测
                    lastPositionUpdateTime = System.currentTimeMillis();
                    // ====================================================================
                    // 统计缓冲次数和卡顿时间
                    // ====================================================================
                    bufferCount++;
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                    }
                    // 只在第一次缓冲时记录操作日志，避免刷屏
                    if (bufferCount == 1) {
                        SettingsActivity.logOperation("【播放器】开始缓冲（第1次）");
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (listener != null) listener.onPlayEnd();
                    // 直播流意外结束，自动重试
                    autoRetry("播放结束");
                } else if (state == Player.STATE_IDLE) {
                    if (listener != null) listener.onIdle();
                    // ====================================================================
                    // IDLE 状态也更新唤醒锁
                    // ====================================================================
                    updateWakeLock(false);
                }
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // 播放状态变化时更新卡住检测
                if (isPlaying) {
                    lastPositionUpdateTime = System.currentTimeMillis();
                    // ====================================================================
                    // 卡顿结束，统计卡顿时间
                    // ====================================================================
                    if (isStalled) {
                        isStalled = false;
                        long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                        totalStallTime += stallDuration;
                        Log.d(TAG, "【性能】卡顿结束，时长：" + stallDuration + "ms，总卡顿：" + totalStallTime + "ms");
                    }
                }
            }
            // ====================================================================
            // 视频分辨率变化时触发
            // ====================================================================
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int width = videoSize.width;
                int height = videoSize.height;
                Log.d(TAG, "视频分辨率变化：" + width + "×" + height);
                // 分辨率变化时，通知 UI 更新
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }
    // ================================================
    // ✅ 卡住检测 + 自动重试
    // ================================================
    /**
     * 开始卡住检测
     * 每隔2秒检查一次播放位置，如果长时间没动，说明卡住了
     */
    private void startStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
        lastPositionUpdateTime = System.currentTimeMillis();
        lastPosition = 0;
        stuckHandler.postDelayed(stuckCheckRunnable, 2000);
    }
    /**
     * 停止卡住检测
     */
    private void stopStuckDetection() {
        stuckHandler.removeCallbacks(stuckCheckRunnable);
    }
    // ====================================================================
    // 取消重试任务
    // ====================================================================
    /**
     * 取消待执行的重试任务
     *
     * 【作用】
     * 切换频道时调用，取消旧频道的重试任务，
     * 避免旧频道的延迟重试干扰新频道的播放。
     */
    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }
    /**
     * ✅ 自动重试
     * @param reason 重试原因（用于日志）
     *
     * 【2026-06-25 修改：修复重试 bug + 增加源失效回调 + 操作日志】
     */
    private void autoRetry(String reason) {
        if (isRetrying) return; // 已经有重试任务在等待中，避免重复
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "重试次数已达上限：" + MAX_RETRY_COUNT + "，判定为失效源");
            SettingsActivity.logOperation("【播放器】重试" + MAX_RETRY_COUNT
                    + "次均失败，判定为失效源");
            // ====================================================================
            // 重试次数用完，回调源失效
            // ====================================================================
            if (sourceFailedListener != null) {
                mHandler.post(() -> sourceFailedListener.onSourceFailed());
            }
            return;
        }
        isRetrying = true;
        retryCount++;
        Log.w(TAG, "自动重试（第" + retryCount + "次），原因：" + reason);
        SettingsActivity.logOperation("【播放器】自动重试（第" + retryCount + "次），原因：" + reason);
        // 保存重试任务的引用，方便后续取消
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                isRetrying = false;
                if (!TextUtils.isEmpty(currentUrl)) {
                    // 重新播放当前地址
                    playUrlInternal(currentUrl);
                }
                // 执行完后清空引用
                retryRunnable = null;
            }
        };
        // 延迟1秒后重新加载
        mHandler.postDelayed(retryRunnable, 1000);
    }
    // ====================================================================
    // ✅ 设置解码器模式（2026-06-25 新增，2026-06-26 修改：去掉 FFmpeg 相关）
    // ====================================================================
    /**
     * 设置解码器模式
     *
     * @param mode 解码器模式
     *             - DECODER_MODE_AUTO：自动模式（推荐）
     *             - DECODER_MODE_HARD：强制硬解
     *             - DECODER_MODE_SOFT：软解优先（系统自带）
     *
     * 【功能】
     * 切换解码器模式后，会重新创建播放器，
     * 并重新加载当前频道，立即生效。
     *
     * 【为什么要重新创建播放器？】
     * 因为 MediaCodecSelector 只能在创建播放器时设置，
     * 播放器创建后不能动态修改，所以必须重新创建。
     */
    public void setDecoderMode(int mode) {
        // 如果模式没变，不做任何操作
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        // 同步更新旧变量（保持向后兼容）
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
        // 重新创建播放器
        if (player != null) {
            try {
                stopStuckDetection();
                // 重新创建播放器前取消重试
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
        // 重新播放当前地址
        if (!TextUtils.isEmpty(currentUrl)) {
            retryCount = 0;
            isRetrying = false;
            // 切换解码器后，重置自动切换标记
            // （因为已经是用户手动选择的模式了，不需要再自动切）
            hasSwitchedDecoder = true;
            playUrlInternal(currentUrl);
        }
    }
    /**
     * 获取当前解码器模式
     *
     * @return 当前解码器模式
     *         - DECODER_MODE_AUTO：自动模式
     *         - DECODER_MODE_HARD：强制硬解
     *         - DECODER_MODE_SOFT：软解优先
     */
    public int getDecoderMode() {
        return mDecoderMode;
    }
    /**
     * 切换软解码/硬解码（保留，用于向后兼容）
     *
     * @param useSoftware true=软解码，false=硬解码
     *
     * 【2026-06-25 更新】
     * 内部调用 setDecoderMode()，保持向后兼容。
     * - useSoftware=true → DECODER_MODE_SOFT
     * - useSoftware=false → DECODER_MODE_AUTO（自动模式，硬解优先）
     *
     * @deprecated 请使用 setDecoderMode(int) 替代
     */
    @Deprecated
    public void setSoftwareDecoder(boolean useSoftware) {
        if (useSoftware) {
            setDecoderMode(DECODER_MODE_SOFT);
        } else {
            setDecoderMode(DECODER_MODE_AUTO);
        }
    }
    // ====================================================================
    // 前后台切换
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
    // PlayerView 绑定
    // ====================================================================
    public void attachPlayerView(PlayerView view) {
        playerView = view;
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }
    // ====================================================================
    // 屏幕常亮控制
    // ====================================================================
    private void updateWakeLock(boolean enable) {
        isPlaying = enable;
        if (playerView != null) {
            playerView.setKeepScreenOn(enable);
        }
    }
    // ====================================================================
    // 日志时间格式化
    // ====================================================================
    private String getLogTime() {
        return "[" + logSdf.format(new Date()) + "]";
    }
    // ====================================================================
    // 请求头获取
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
        }
        else if (isDouyu) {
            headers.put("Referer", "https://www.douyu.com/");
            Log.d(TAG, "斗鱼直播，设置斗鱼Referer");
        }
        else {
            headers.put("Referer", "https://www.huya.com/");
        }
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }
    // ====================================================================
    // 播放入口
    // ====================================================================
    public void play(String url) {
        playUrl(url);
    }
    /**
     * 播放指定URL（对外接口）
     * 切换频道时调用，重置重试计数和解码器状态
     */
    public void playUrl(String url) {
        // 切换频道，先取消之前的重试任务
        cancelRetry();
        // 切换频道，重置重试计数
        retryCount = 0;
        isRetrying = false;
        // ====================================================================
        // 切换频道，重置解码器切换标记
        // ====================================================================
        // 每个频道只自动切换一次解码器
        hasSwitchedDecoder = false;
        // 切换频道，重置首次播放开始时间
        initialPlayStartTime = 0;
        // 切换频道，重置性能统计
        resetPerformanceStats();
        // 接入操作日志
        SettingsActivity.logOperation("【播放器】开始加载新频道");
        playUrlInternal(url);
    }
    // ====================================================================
    // 重置性能统计
    // ====================================================================
    /**
     * 重置性能统计数据
     * 切换频道时调用
     */
    private void resetPerformanceStats() {
        bufferCount = 0;
        totalStallTime = 0;
        isStalled = false;
        lastStallStartTime = 0;
        // ⚠️ 注意：hasSwitchedDecoder 不在这重置
        // 因为它是按频道来的，已经在 playUrl() 里重置了
    }
    /**
     * ✅ 内部播放方法
     *
     * 【优化】切台保持最后一帧
     * 去掉 player.stop() 和 player.clearMediaItems()
     * 直接用 setMediaSource 切换，旧画面会保留到新画面出来
     * 这样就完全避免了切台黑屏的问题
     */
    private void playUrlInternal(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);
            // ===== 创建数据源（带重定向日志版） =====
            RedirectLoggingHttpDataSource.Factory httpFactory =
                    new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            // ====================================================================
            // MediaSource 类型改成 Media3 的
            // ====================================================================
            MediaSource mediaSource;
            if (currentUrl.toLowerCase().contains("m3u8")) {
                Log.d(TAG, "流格式：HLS (m3u8)");
                mediaSource = new HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            } else {
                Log.d(TAG, "流格式：普通流 (Progressive)");
                mediaSource = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem);
            }
            // ====================================================================
            // ✅ 关键修改：直接设置新的媒体源，第二个参数 true = 重置到开头
            // ====================================================================
            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.play();
            // 开始卡住检测
            startStuckDetection();
        } catch (Exception e) {
            Log.e(TAG, "播放异常", e);
            autoRetry("播放异常：" + e.getMessage());
        }
    }
    // ====================================================================
    // 缩放模式
    // ====================================================================
    public enum ScaleMode {
        FIT,    // 等比缩放，完整显示（有黑边）
        FILL,   // 拉伸填满（变形）
        ZOOM    // 等比缩放，填满屏幕（裁剪）
    }
    public void setScaleMode(ScaleMode mode) {
        try {
            if (playerView == null) return;
            // ====================================================================
            // AspectRatioFrameLayout 包名改成 Media3 的
            // ====================================================================
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
    // 频道号显示
    // ====================================================================
    /**
     * 设置当前频道号
     */
    public void setCurrentChannelNumber(int num) {
        currentChannelNumber = num;
    }
    /**
     * 绑定频道号显示的 TextView
     */
    public void bindChannelText(TextView textView) {
        channelNumberTextView = textView;
    }
    /**
     * 显示频道号并自动隐藏
     */
    private void showChannelAndAutoHide() {
        if (channelNumberTextView != null && currentChannelNumber > 0) {
            channelNumberTextView.setText(String.valueOf(currentChannelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
            // 取消之前的隐藏任务
            mHandler.removeCallbacks(hideChannelRunnable);
            // 3秒后隐藏
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
    // ====================================================================
    // 直播信息
    // ====================================================================
    /**
     * 直播信息类
     */
    public static class LiveInfo {
        public String resolution = "未知";  // 分辨率
        public String bitrate = "0";        // 码率（Mbps）
        public String audio = "未知";       // 音频信息
        public String format = "未知";      // 视频格式
    }
    /**
     * 获取当前直播信息
     */
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
                    // 码率（转成 Mbps，保留1位小数）
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
    /**
     * 通知直播信息更新
     */
    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) {
            liveInfoUpdateListener.onLiveInfoUpdate(getLiveInfo());
        }
    }
    // ====================================================================
    // 监听器接口
    // ====================================================================
    /**
     * 播放状态监听器
     */
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
    /**
     * 源失效监听器
     * 【2026-06-25 新增】
     */
    public interface OnSourceFailedListener {
        void onSourceFailed();
    }
    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        sourceFailedListener = listener;
    }
    /**
     * 直播信息更新监听器
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        liveInfoUpdateListener = listener;
    }
    // ====================================================================
    // 播放控制
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
    /**
     * 释放播放器
     */
    public void release() {
        try {
            stopStuckDetection();
            // 释放时取消重试任务
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
            Log.e(TAG, "释放异常", e);
        }
    }
    // ====================================================================
    // ✅ 自定义 MediaCodecSelector（2026-06-26 新增，系统软解方案核心）
    // ====================================================================
    /**
     * 自定义 MediaCodec 选择器
     *
     * 【作用】
     * 通过调整解码器列表的顺序，实现软解优先或强制硬解。
     *
     * 【三种模式】
     * 1. DECODER_MODE_AUTO（自动）：系统默认顺序（硬解优先）
     * 2. DECODER_MODE_HARD（强制硬解）：只保留硬件解码器
     * 3. DECODER_MODE_SOFT（软解优先）：软件解码器排在前面，硬件解码器排在后面
     *
     * 【软件解码器识别规则】
     * 名称以 OMX.google. 或 c2.android. 开头的是软件解码器
     *
     * 【为什么不用 FFmpeg 了？】
     * FFmpeg 扩展编译复杂、集成麻烦，而且 Media3 的 decoder_ffmpeg
     * 模块默认只支持音频，视频需要手动编译和加载实验性渲染器。
     * 系统自带的软件解码器虽然性能不如 FFmpeg，但胜在稳定、
     * 无需额外依赖、集成简单。
     *
     * 【2026-06-26 修复：codec.getName() → codec.name】
     * Media3 中 MediaCodecInfo 是数据类，直接用 name 字段，
     * 没有 getName() 方法。这是 Media3 重构时的 API 变化。
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
            // 获取系统默认的解码器列表
            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (allCodecs == null || allCodecs.isEmpty()) {
                return allCodecs;
            }
            switch (decoderMode) {
                case DECODER_MODE_HARD:
                    // ================================================================
                    // 强制硬解：只保留硬件解码器
                    // ================================================================
                    List<MediaCodecInfo> hardCodecs = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        // ✅ 修复：用 codec.name 而不是 codec.getName()
                        if (!isSoftwareDecoder(codec.name)) {
                            hardCodecs.add(codec);
                        }
                    }
                    return hardCodecs;
                case DECODER_MODE_SOFT:
                    // ================================================================
                    // 软解优先：软件解码器排在前面，硬件解码器排在后面
                    // ================================================================
                    List<MediaCodecInfo> softCodecs = new ArrayList<>();
                    List<MediaCodecInfo> hardCodecs2 = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        // ✅ 修复：用 codec.name 而不是 codec.getName()
                        if (isSoftwareDecoder(codec.name)) {
                            softCodecs.add(codec);
                        } else {
                            hardCodecs2.add(codec);
                        }
                    }
                    // 合并：软解在前，硬解在后
                    List<MediaCodecInfo> result = new ArrayList<>();
                    result.addAll(softCodecs);
                    result.addAll(hardCodecs2);
                    return result;
                case DECODER_MODE_AUTO:
                default:
                    // ================================================================
                    // 自动模式：系统默认顺序（硬解优先）
                    // ================================================================
                    return allCodecs;
            }
        }
    }
}
