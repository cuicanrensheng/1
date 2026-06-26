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
 * 11. ✅ 分辨率自适应检测（2026-06-26 新增）
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
 *
 * 【2026-06-26 新增：分辨率自适应检测】
 * 【功能】
 * 1. 检测当前视频分辨率
 * 2. 根据设备性能评估解码压力
 * 3. 分辨率过高时回调通知外部，可自动切换低码率源
 *
 * 【配合 ResolutionOptimizer 使用】
 * 需要 ResolutionOptimizer 类配合，提供分辨率等级划分、
 * 性能匹配推荐、解码压力评估等功能。
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
    // ✅ 分辨率过高监听器（2026-06-26 新增）
    // ====================================================================
    /**
     * 分辨率过高监听器
     * 【作用】
     * 当检测到视频分辨率超过设备性能时，回调这个监听器，
     * 通知外部可以切换到更低码率的源。
     *
     * 【2026-06-26 新增】
     */
    private OnResolutionTooHighListener mResolutionTooHighListener;
    /**
     * 分辨率过高监听器接口
     * 【2026-06-26 新增】
     */
    public interface OnResolutionTooHighListener {
        /**
         * 当检测到视频分辨率过高时回调
         *
         * @param currentWidth 当前视频宽度
         * @param currentHeight 当前视频高度
         * @param decodePressure 解码压力（0-100，越高压力越大）
         * @param recommendedLevel 推荐的分辨率等级名称
         */
        void onResolutionTooHigh(int currentWidth, int currentHeight,
                                  int decodePressure, String recommendedLevel);
    }
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
                    softNames.append(name).append(", ");
                } else {
                    hardCount++;
                    hardNames.append(name).append(", ");
                }
            }
            Log.d(TAG, "【解码器】系统 H.264 解码器总数：" + h264Codecs.size());
            Log.d(TAG, "【解码器】软件解码器：" + softCount + " 个 - " + softNames);
            Log.d(TAG, "【解码器】硬件解码器：" + hardCount + " 个 - " + hardNames);
            SettingsActivity.logOperation("【解码器】系统H.264解码器：软解" 
                    + softCount + "个，硬解" + hardCount + "个");
        } catch (Exception e) {
            Log.e(TAG, "【解码器】检测系统解码器失败：" + e.getMessage());
        }
        // 初始化播放器监听器
        initPlayerListener();
        // 开始卡住检测
        stuckHandler.postDelayed(stuckCheckRunnable, 2000);
    }
    // ====================================================================
    // ✅ 判断是否为软件解码器（2026-06-26 新增）
    // ====================================================================
    /**
     * 判断解码器名称是否为软件解码器
     *
     * @param codecName 解码器名称
     * @return true=软件解码器，false=硬件解码器
     *
     * 【2026-06-26 新增】
     *
     * 【识别规则】
     * 1. 名称以 OMX.google. 开头 → 旧版软件解码器
     * 2. 名称以 c2.android. 开头 → 新版 Codec2 软件解码器
     * 3. 其他 → 硬件解码器
     *
     * 【常见硬件解码器前缀】
     * - OMX.qcom. / c2.qti.（高通）
     * - OMX.hisi. / c2.hisi.（海思）
     * - OMX.MTK. / c2.mtk.（联发科）
     * - OMX.rk. / c2.rk.（瑞芯微）
     * - OMX.amlogic. / c2.amlogic.（晶晨）
     */
    private static boolean isSoftwareDecoder(String codecName) {
        if (codecName == null) return false;
        // 旧版软件解码器
        if (codecName.startsWith("OMX.google.")) {
            return true;
        }
        // 新版 Codec2 软件解码器
        if (codecName.startsWith("c2.android.")) {
            return true;
        }
        return false;
    }
    // ====================================================================
    // 初始化播放器监听器
    // ====================================================================
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                // 播放状态变化
                if (playbackState == Player.STATE_READY) {
                    // 首次播放开始时间（只设置一次）
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                    isPlaying = true;
                    if (listener != null) {
                        listener.onPlaySuccess();
                    }
                    // 结束卡顿统计
                    if (isStalled) {
                        isStalled = false;
                        long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                        totalStallTime += stallDuration;
                        Log.d(TAG, "卡顿结束，持续：" + stallDuration + "ms");
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    // 开始卡顿
                    if (!isStalled) {
                        isStalled = true;
                        lastStallStartTime = System.currentTimeMillis();
                        bufferCount++;
                        Log.d(TAG, "开始卡顿，第 " + bufferCount + " 次");
                        // ====================================================================
                        // ✅ 自动切换解码器（2026-06-25 新增）
                        // ====================================================================
                        // 【触发条件】
                        // 1. 当前是自动模式
                        // 2. 还没切换过解码器（每个频道只切一次）
                        // 3. 播放开始后 15 秒内
                        // 4. 缓冲次数 > 1 次
                        //
                        // 【为什么是 15 秒和 1 次？】
                        // 原来的 30 秒/2 次太宽松了，用户反馈卡顿明显。
                        // 调灵敏一点，让用户能更快感受到流畅度提升。
                        if (mDecoderMode == DECODER_MODE_AUTO 
                                && !hasSwitchedDecoder
                                && initialPlayStartTime > 0
                                && System.currentTimeMillis() - initialPlayStartTime < 15000
                                && bufferCount > 1) {
                            hasSwitchedDecoder = true;
                            Log.w(TAG, "【解码器】自动切换到系统软解（15秒内缓冲" 
                                    + bufferCount + "次）");
                            SettingsActivity.logOperation("【解码器】自动切换到系统软解（15秒内缓冲" 
                                    + bufferCount + "次）");
                            // 切换到软解模式
                            setDecoderMode(DECODER_MODE_SOFT);
                        }
                    }
                } else if (playbackState == Player.STATE_IDLE) {
                    isPlaying = false;
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                // 播放出错
                Log.e(TAG, "播放出错：" + error.getMessage());
                isPlaying = false;
                // 结束卡顿统计
                if (isStalled) {
                    isStalled = false;
                    long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                    totalStallTime += stallDuration;
                }
                // 判断是否是源失效
                boolean isSourceError = false;
                Throwable cause = error.getCause();
                if (cause instanceof HttpDataSource.HttpDataSourceException) {
                    isSourceError = true;
                }
                if (isSourceError) {
                    // 源失效，自动重试
                    SettingsActivity.logOperation("【播放器】源失效，准备自动重试");
                    autoRetry("源失效");
                } else {
                    // 其他错误，也重试
                    autoRetry("播放错误");
                }
            }
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                int width = videoSize.width;
                int height = videoSize.height;
                Log.d(TAG, "视频尺寸变化：" + width + "x" + height);
                // ====================================================================
                // ✅ 2026-06-26 新增：分辨率自适应检测
                // ====================================================================
                // 【作用】
                // 检测当前视频分辨率，评估对设备的解码压力。
                // 如果分辨率过高，回调通知外部，可自动切换低码率源。
                //
                // 【为什么在这里检测？】
                // onVideoSizeChanged 是播放器获取到视频真实分辨率后的回调，
                // 这时候检测最准确。
                //
                // 【检测逻辑】
                // 1. 评估解码压力（0-100）
                // 2. 如果压力超过阈值（60分以上），触发回调
                // 3. 回调中包含当前分辨率、压力值、推荐分辨率
                checkResolutionAndNotify(width, height);
                
                // 分辨率变化时，通知 UI 更新
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }
    // ====================================================================
    // ✅ 分辨率检测与回调（2026-06-26 新增）
    // ====================================================================
    /**
     * 检测分辨率并在过高时通知回调
     *
     * @param width 视频宽度
     * @param height 视频高度
     *
     * 【2026-06-26 新增】
     *
     * 【检测逻辑】
     * 1. 根据设备性能等级评估解码压力
     * 2. 压力超过 60 分认为过高，触发回调
     * 3. 每个频道只触发一次，避免反复回调
     *
     * 【为什么每个频道只触发一次？】
     * 防止同一个频道反复触发回调，导致频繁切源。
     * 切换频道时会在 playUrl() 中重置标记。
     */
    private boolean mHasNotifiedResolutionTooHigh = false;
    
    private void checkResolutionAndNotify(int width, int height) {
        // 每个频道只检测一次
        if (mHasNotifiedResolutionTooHigh) return;
        if (width <= 0 || height <= 0) return;
        
        try {
            // 评估解码压力
            int pressure = evaluateDecodePressure(width, height);
            
            // ✅ 修复：去掉多余的嵌套 Log.d，修正中文标点问题
            Log.d(TAG, "【分辨率】当前：" + width + "x" + height 
                    + "，解码压力：" + pressure);
            
            // 压力超过 60 分，认为分辨率过高
            if (pressure >= 60) {
                mHasNotifiedResolutionTooHigh = true;
                
                // 获取推荐分辨率
                String recommendedLevel = getRecommendedResolutionLevelName();
                
                Log.w(TAG, "【分辨率】分辨率过高！解码压力：" + pressure 
                        + "，推荐：" + recommendedLevel);
                SettingsActivity.logOperation("【分辨率】检测到分辨率过高（"
                        + width + "x" + height + "），压力：" + pressure
                        + "，推荐：" + recommendedLevel);
                
                // 回调通知外部
                if (mResolutionTooHighListener != null) {
                    final int finalWidth = width;
                    final int finalHeight = height;
                    final int finalPressure = pressure;
                    final String finalRecommended = recommendedLevel;
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mResolutionTooHighListener.onResolutionTooHigh(
                                    finalWidth, finalHeight, 
                                    finalPressure, finalRecommended);
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "【分辨率】检测失败：" + e.getMessage());
        }
    }
    /**
     * 评估当前分辨率对设备的解码压力
     *
     * @param width 视频宽度
     * @param height 视频高度
     * @return 压力值（0-100，越高压力越大）
     *
     * 【2026-06-26 新增】
     *
     * 【评估维度】
     * 1. 分辨率等级差距：当前分辨率 vs 设备推荐分辨率
     * 2. 解码器模式：软解模式压力更大
     * 3. 低端机额外加压力
     *
     * 【压力等级说明】
     * - 0-20：轻松，毫无压力
     * - 21-40：正常，流畅播放
     * - 41-60：略有压力，可能偶尔卡顿
     * - 61-80：压力较大，容易卡顿
     * - 81-100：压力很大，基本播不动
     */
    private int evaluateDecodePressure(int width, int height) {
        int pressure = 0;
        // 1. 计算分辨率等级差距
        int currentLevel = getResolutionLevel(width, height);
        int recommendedLevel = getRecommendedResolutionLevel();
        
        // 等级差距越大，压力越高
        int levelDiff = recommendedLevel - currentLevel;
        pressure += Math.abs(levelDiff) * 20; // 每差一级 +20 分
        // 2. 软解模式额外加压力（软解比硬解更耗 CPU）
        if (mDecoderMode == DECODER_MODE_SOFT) {
            pressure += 15;
        }
        // 3. 低端机额外加压力
        // （这里简化处理，通过系统版本和 CPU 核心数粗略判断）
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1
                || Runtime.getRuntime().availableProcessors() <= 4) {
            pressure += 10;
        }
        // 4. 超过 1080P 额外加压力（很多设备硬解 4K 有问题）
        if (currentLevel <= 1) { // FULL_HD 或 ULTRA_HD
            pressure += 10;
        }
        // 限制在 0-100
        return Math.min(100, Math.max(0, pressure));
    }
    /**
     * 获取分辨率等级（数值越小，分辨率越高）
     *
     * @param width 宽度
     * @param height 高度
     * @return 等级值（0=4K, 1=1080P, 2=720P, 3=480P, 4=360P）
     *
     * 【2026-06-26 新增】
     */
    private int getResolutionLevel(int width, int height) {
        int minSide = Math.min(width, height);
        if (minSide >= 2160) {
            return 0; // ULTRA_HD (4K)
        } else if (minSide >= 1080) {
            return 1; // FULL_HD (1080P)
        } else if (minSide >= 720) {
            return 2; // HD (720P)
        } else if (minSide >= 480) {
            return 3; // SD (480P)
        } else {
            return 4; // LD (360P)
        }
    }
    /**
     * 获取设备推荐的分辨率等级
     *
     * @return 推荐等级值（同 getResolutionLevel）
     *
     * 【2026-06-26 新增】
     *
     * 【判断逻辑】
     * 简化版的性能判断，不依赖 PerformanceOptimizer，
     * 避免循环依赖。如果有 PerformanceOptimizer，
     * 可以替换成更准确的判断。
     */
    private int getRecommendedResolutionLevel() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int sdkVersion = Build.VERSION.SDK_INT;
        // 高端机：8核以上 + Android 8+ → 1080P
        if (cpuCores >= 8 && sdkVersion >= Build.VERSION_CODES.O) {
            return 1; // FULL_HD (1080P)
        }
        // 中端机：4核以上 + Android 6+ → 720P
        else if (cpuCores >= 4 && sdkVersion >= Build.VERSION_CODES.M) {
            return 2; // HD (720P)
        }
        // 低端机：其他 → 480P
        else {
            return 3; // SD (480P)
        }
    }
    /**
     * 获取推荐分辨率等级的显示名称
     *
     * @return 显示名称（如 "720P"）
     *
     * 【2026-06-26 新增】
     */
    private String getRecommendedResolutionLevelName() {
        int level = getRecommendedResolutionLevel();
        switch (level) {
            case 0: return "4K";
            case 1: return "1080P";
            case 2: return "720P";
            case 3: return "480P";
            case 4:
            default: return "360P";
        }
    }
    /**
     * 设置分辨率过高监听器
     *
     * @param listener 监听器
     *
     * 【2026-06-26 新增】
     *
     * 【使用场景】
     * 外部可以设置这个监听器，当检测到分辨率过高时，
     * 自动切换到更低码率的源（如果有的话）。
     */
    public void setOnResolutionTooHighListener(OnResolutionTooHighListener listener) {
        mResolutionTooHighListener = listener;
    }
    // ====================================================================
    // 自动重试
    // ====================================================================
    /**
     * 自动重试
     * @param reason 重试原因
     */
    private void autoRetry(String reason) {
        if (isRetrying) return;
        if (retryCount >= MAX_RETRY_COUNT) {
            // 重试次数用完，回调源失效
            Log.w(TAG, "重试次数用完，源失效：" + reason);
            SettingsActivity.logOperation("【播放器】重试" + MAX_RETRY_COUNT 
                    + "次失败，判定源失效");
            if (sourceFailedListener != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        sourceFailedListener.onSourceFailed();
                    }
                });
            }
            return;
        }
        retryCount++;
        isRetrying = true;
        Log.d(TAG, "自动重试（第 " + retryCount + " 次），原因：" + reason);
        SettingsActivity.logOperation("【播放器】自动重试第" + retryCount + "次，原因：" + reason);
        // 延迟 1 秒后重试
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    isRetrying = false;
                    if (currentUrl != null) {
                        playUrlInternal(currentUrl);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "重试失败：" + e.getMessage());
                }
            }
        };
        mHandler.postDelayed(retryRunnable, 1000);
    }
    /**
     * 取消重试
     */
    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }
    // ====================================================================
    // 播放控制
    // ====================================================================
    /**
     * 内部播放 URL
     */
    private void playUrlInternal(String url) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        try {
            MediaItem mediaItem = MediaItem.fromUri(url);
            // 根据 URL 后缀判断使用哪种 MediaSource
            MediaSource mediaSource;
            if (url.contains(".m3u8") || url.contains("m3u8")) {
                // HLS 直播流
                mediaSource = new HlsMediaSource.Factory(
                        new RedirectLoggingHttpDataSource.Factory())
                        .createMediaSource(mediaItem);
            } else {
                // 普通 progressive 流
                mediaSource = new ProgressiveMediaSource.Factory(
                        new RedirectLoggingHttpDataSource.Factory())
                        .createMediaSource(mediaItem);
            }
            player.setMediaSource(mediaSource);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            Log.e(TAG, "播放URL失败：" + e.getMessage());
        }
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
        // ====================================================================
        // ✅ 2026-06-26 新增：切换频道，重置分辨率过高通知标记
        // ====================================================================
        // 每个频道只检测一次分辨率过高
        mHasNotifiedResolutionTooHigh = false;
        // 接入操作日志
        SettingsActivity.logOperation("【播放器】开始加载新频道");
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
     * 暂停
     */
    public void pause() {
        if (player != null) {
            player.pause();
            isPlaying = false;
        }
    }
    /**
     * 恢复播放
     */
    public void resume() {
        if (player != null) {
            player.play();
            isPlaying = true;
        }
    }
    /**
     * 停止
     */
    public void stop() {
        if (player != null) {
            player.stop();
            isPlaying = false;
        }
    }
    /**
     * 释放资源
     */
    public void release() {
        cancelRetry();
        if (stuckHandler != null) {
            stuckHandler.removeCallbacks(stuckCheckRunnable);
        }
        if (player != null) {
            player.release();
            player = null;
        }
        instance = null;
    }
    /**
     * 绑定 PlayerView
     */
    public void attachPlayerView(PlayerView view) {
        this.playerView = view;
        if (player != null && view != null) {
            view.setPlayer(player);
        }
    }
    /**
     * 设置缩放模式
     * @param resizeMode 缩放模式
     */
    public void setResizeMode(int resizeMode) {
        if (playerView != null) {
            playerView.setResizeMode(resizeMode);
        }
    }
    // ====================================================================
    // ✅ 解码器模式设置（2026-06-25 新增）
    // ====================================================================
    /**
     * 设置解码器模式
     *
     * @param mode 解码器模式
     *             - DECODER_MODE_AUTO：自动（推荐）
     *             - DECODER_MODE_HARD：强制硬解
     *             - DECODER_MODE_SOFT：软解优先
     *
     * 【2026-06-25 新增】
     *
     * 【实现方式】
     * 1. 更新 mDecoderMode 变量
     * 2. 重新创建播放器（因为渲染器工厂需要重新设置）
     * 3. 重新加载当前频道
     *
     * 【为什么需要重新创建播放器？】
     * 因为 MediaCodecSelector 是在渲染器工厂中设置的，
     * 而渲染器工厂是在 ExoPlayer 创建时传入的，
     * 创建后不能动态修改。所以切换解码器模式时，
     * 需要重新创建播放器。
     */
    public void setDecoderMode(int mode) {
        if (mDecoderMode == mode) return;
        mDecoderMode = mode;
        // 记录日志
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
        Log.d(TAG, "【解码器】切换模式：" + modeName);
        SettingsActivity.logOperation("【解码器】切换到：" + modeName);
        // 保存当前播放位置和 URL
        long currentPosition = 0;
        String url = currentUrl;
        if (player != null) {
            currentPosition = player.getCurrentPosition();
        }
        // 释放旧播放器
        if (player != null) {
            if (playerListener != null) {
                player.removeListener(playerListener);
            }
            player.release();
            player = null;
        }
        // 重新创建播放器
        initPlayer();
        // 重新绑定 PlayerView
        if (playerView != null) {
            playerView.setPlayer(player);
        }
        // 重新加载当前频道
        if (url != null && !url.isEmpty()) {
            playUrlInternal(url);
            // 尝试恢复播放位置
            if (currentPosition > 0) {
                player.seekTo(currentPosition);
            }
        }
    }
    /**
     * 获取当前解码器模式
     *
     * @return 当前解码器模式
     *
     * 【2026-06-25 新增】
     */
    public int getDecoderMode() {
        return mDecoderMode;
    }
    /**
     * 设置是否使用软解码（向后兼容）
     *
     * @param useSoftware true=使用软解，false=使用硬解
     *
     * @deprecated 请使用 setDecoderMode(int) 替代
     *
     * 【2026-06-25 说明】
     * 保留这个方法是为了向后兼容，
     * 内部调用 setDecoderMode() 方法。
     * - useSoftware=true → DECODER_MODE_SOFT
     * - useSoftware=false → DECODER_MODE_AUTO
     *
     * 【为什么 false 对应 AUTO 而不是 HARD？】
     * 因为原来的行为就是"不用软解就用硬解"，
     * 但实际上系统默认就是硬解优先，
     * 而且自动模式（硬解优先+卡顿自动切软解）体验更好。
     * 所以 false 对应 AUTO，用户体验更好。
     */
    @Deprecated
    public void setSoftwareDecoder(boolean useSoftware) {
        this.useSoftwareDecoder = useSoftware;
        if (useSoftware) {
            setDecoderMode(DECODER_MODE_SOFT);
        } else {
            setDecoderMode(DECODER_MODE_AUTO);
        }
    }
    // ====================================================================
    // 频道号显示
    // ====================================================================
    /**
     * 显示频道号
     */
    public void showChannelNum(int channelNumber) {
        this.currentChannelNumber = channelNumber;
        if (channelNumberTextView != null) {
            channelNumberTextView.setText(String.valueOf(channelNumber));
            channelNumberTextView.setVisibility(View.VISIBLE);
        }
        // 延迟隐藏
        mHandler.removeCallbacks(hideChannelRunnable);
        mHandler.postDelayed(hideChannelRunnable, CHANNEL_NUM_HIDE_DELAY);
    }
    /**
     * 隐藏频道号
     */
    private void hideChannelNum() {
        if (channelNumberTextView != null) {
            channelNumberTextView.setVisibility(View.GONE);
        }
    }
    /**
     * 设置频道号显示的 TextView
     */
    public void setChannelNumberTextView(TextView tv) {
        this.channelNumberTextView = tv;
    }
    // ====================================================================
    // 监听器设置
    // ====================================================================
    /**
     * 设置播放状态监听器
     */
    public void setOnPlayStateListener(OnPlayStateListener listener) {
        this.listener = listener;
    }
    /**
     * 播放状态监听器接口
     */
    public interface OnPlayStateListener {
        void onPlaySuccess();
        void onPlayFailed(String error);
    }
    /**
     * 设置源失效监听器
     *
     * 【2026-06-25 新增】
     */
    public void setOnSourceFailedListener(OnSourceFailedListener listener) {
        this.sourceFailedListener = listener;
    }
    /**
     * 源失效监听器接口
     *
     * 【2026-06-25 新增】
     */
    public interface OnSourceFailedListener {
        void onSourceFailed();
    }
    /**
     * 设置直播信息更新监听器
     */
    public void setOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        this.liveInfoUpdateListener = listener;
    }
    /**
     * 直播信息更新监听器接口
     */
    public interface OnLiveInfoUpdateListener {
        void onLiveInfoUpdate(LiveInfo info);
    }
    /**
     * 通知直播信息更新
     */
    private void notifyLiveInfoUpdate() {
        if (liveInfoUpdateListener != null) {
            final LiveInfo info = getLiveInfo();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    liveInfoUpdateListener.onLiveInfoUpdate(info);
                }
            });
        }
    }
    // ====================================================================
    // 直播信息
    // ====================================================================
    /**
     * 直播信息类
     */
    public static class LiveInfo {
        public String resolution = "未知";  // 分辨率（如 1920x1080）
        public String resolutionLevel = "未知"; // 分辨率等级（如 1080P）
        public String bitrate = "0";        // 码率（Mbps）
        public String audio = "未知";       // 音频信息
        public String format = "未知";      // 视频格式
        // ====================================================================
        // ✅ 2026-06-26 新增：解码压力
        // ====================================================================
        public int decodePressure = 0;      // 解码压力（0-100）
        public String decodePressureDesc = "未知"; // 解码压力描述
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
                        info.resolution = width + "x" + height;
                        // ====================================================================
                        // ✅ 2026-06-26 新增：分辨率等级
                        // ====================================================================
                        int level = getResolutionLevel(width, height);
                        switch (level) {
                            case 0: info.resolutionLevel = "4K"; break;
                            case 1: info.resolutionLevel = "1080P"; break;
                            case 2: info.resolutionLevel = "720P"; break;
                            case 3: info.resolutionLevel = "480P"; break;
                            case 4:
                            default: info.resolutionLevel = "360P"; break;
                        }
                        // ====================================================================
                        // ✅ 2026-06-26 新增：解码压力
                        // ====================================================================
                        info.decodePressure = evaluateDecodePressure(width, height);
                        info.decodePressureDesc = getPressureDescription(info.decodePressure);
                    }
                    info.format = videoFormat.sampleMimeType;
                    // 码率（转成 Mbps，保留1位小数）
                    if (videoFormat.bitrate > 0) {
                        float mbps = videoFormat.bitrate / 1000000f;
                        info.bitrate = String.format(Locale.getDefault(),
                                "%.1f Mbps", mbps);
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
     * 获取解码压力的文字描述
     *
     * @param pressure 压力值（0-100）
     * @return 描述文字
     *
     * 【2026-06-26 新增】
     */
    private String getPressureDescription(int pressure) {
        if (pressure <= 20) {
            return "轻松";
        } else if (pressure <= 40) {
            return "正常";
        } else if (pressure <= 60) {
            return "略有压力";
        } else if (pressure <= 80) {
            return "压力较大";
        } else {
            return "压力很大";
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
                    // ================================================================
                    // 自动模式：系统默认顺序（硬解优先）
                    // ================================================================
                    return allCodecs;
            }
        }
    }
}
