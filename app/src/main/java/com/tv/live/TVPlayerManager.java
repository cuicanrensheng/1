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
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.tv.live.RedirectLoggingHttpDataSource;
import java.lang.reflect.Constructor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
/**
 * 电视直播播放器管理器
 *
 * 【功能】
 * 1. 封装 ExoPlayer 的播放控制
 * 2. 支持硬解/软解切换（FFmpeg 软解码）
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
 *    - 硬解优先（EXTENSION_RENDERER_MODE_ON）
 *    - 播放开始后 15 秒内如果缓冲 > 1 次，自动切换到 FFmpeg 软解
 *    - 每个频道只自动切换一次，避免反复切换
 *    - 兼顾性能和兼容性
 *
 * 2. DECODER_MODE_HARD（强制硬解）：
 *    - 只用系统硬解码器（EXTENSION_RENDERER_MODE_OFF）
 *    - 完全不用 FFmpeg
 *    - 性能最好，最省电，但兼容性一般
 *
 * 3. DECODER_MODE_SOFT（强制软解）：
 *    - 优先使用 FFmpeg 软解码器（EXTENSION_RENDERER_MODE_PREFER）
 *    - 兼容性最好，支持格式最多
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
 * 【2026-06-25 修复：软解模式不生效问题】
 * 【问题描述】
 * 用户选择软解模式后，实际播放时还是用的系统硬解。
 * 【原因分析】
 * 1. setEnableDecoderFallback(true) 导致 FFmpeg 失败时静默降级到硬解
 * 2. FFmpeg 扩展可能未正确加载（so 库缺失、版本不匹配等）
 * 【修复方案】
 * 1. 软解模式下关闭解码器降级，让问题暴露出来
 * 2. 增加 FFmpeg 可用性检测日志
 * 3. 播放时检测实际解码器，与设置不符时给出警告
 *
 * 【2026-06-26 修复：ExperimentalFfmpegVideoRenderer 手动加载】
 * 【问题原因】
 * Media3 的 DefaultRenderersFactory 默认不会加载实验性渲染器
 * （ExperimentalFfmpegVideoRenderer），导致即使 FFmpeg 库可用，
 * 视频软解也不会生效。
 * 【解决方案】
 * 自定义 FfmpegRenderersFactory，继承 DefaultRenderersFactory，
 * 重写 buildVideoRenderers() 方法，手动把 ExperimentalFfmpegVideoRenderer
 * 加到渲染器列表里。
 *
 * 【2026-06-26 新增：准确的解码器检测】
 * 【问题】
 * 原来用 videoFormat.sampleMimeType 判断解码器，这是错的！
 * sampleMimeType 是视频格式（如 video/avc），不是解码器名称。
 * 【解决方案】
 * 用 AnalyticsListener.onVideoDecoderInitialized 回调获取真实解码器名称。
 * 同时添加 EventLogger 方便在 logcat 中查看完整的播放器事件。
 */
public class TVPlayerManager {
    // ====================== 常量 ======================
    private static final String TAG = "TVPlayerManager";
    // ====================================================================
    // ✅ 解码器模式常量（2026-06-25 新增）
    // ====================================================================
    /**
     * 解码器模式：自动（推荐）
     * 硬解优先，卡顿自动切换到 FFmpeg 软解
     */
    public static final int DECODER_MODE_AUTO = 0;
    /**
     * 解码器模式：强制硬解
     * 只用系统硬解码器，完全不用 FFmpeg
     */
    public static final int DECODER_MODE_HARD = 1;
    /**
     * 解码器模式：强制软解（FFmpeg）
     * 优先使用 FFmpeg 软解码器
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
     * - DECODER_MODE_SOFT（2）：强制软解
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
     * 【三种模式对应的扩展渲染器模式】
     * - AUTO → EXTENSION_RENDERER_MODE_ON（硬解优先，FFmpeg 备用）
     * - HARD → EXTENSION_RENDERER_MODE_OFF（只用硬解，不用 FFmpeg）
     * - SOFT → EXTENSION_RENDERER_MODE_PREFER（优先 FFmpeg 软解）
     *
     * 【2026-06-25 修复：软解模式不生效问题】
     * 1. 软解模式下关闭解码器降级（setEnableDecoderFallback(false)）
     *    防止 FFmpeg 失败时静默降级到硬解，让问题暴露出来
     * 2. 增加 FFmpeg 可用性检测日志
     *    初始化后检测系统中 FFmpeg 解码器的数量，判断 FFmpeg 是否正确加载
     *
     * 【2026-06-26 修复：使用自定义 FfmpegRenderersFactory】
     * 原来用 DefaultRenderersFactory，它不会自动加载实验性的
     * ExperimentalFfmpegVideoRenderer。
     * 现在改用自定义的 FfmpegRenderersFactory，手动添加实验性视频渲染器。
     *
     * 【2026-06-26 新增：准确的解码器检测】
     * 添加 EventLogger（打印所有事件到 logcat）和自定义 AnalyticsListener
     * （把真实解码器名称写到操作日志）。
     */
    private void initPlayer() {
        // ====================================================================
        // ✅ 2026-06-26 修改：使用自定义的 FfmpegRenderersFactory
        // ====================================================================
        // 【为什么不用 DefaultRenderersFactory？】
        // DefaultRenderersFactory 默认不会加载实验性渲染器
        // （ExperimentalFfmpegVideoRenderer），导致软解模式下视频还是硬解。
        // 自定义的 FfmpegRenderersFactory 会手动把实验性 FFmpeg 视频渲染器
        // 加到渲染器列表里。
        DefaultRenderersFactory renderersFactory = new FfmpegRenderersFactory(context);
        // ====================================================================
        // ✅ 根据解码器模式设置扩展渲染器模式（2026-06-25 重构）
        // ====================================================================
        switch (mDecoderMode) {
            case DECODER_MODE_SOFT:
                // ================================================================
                // 软解模式：优先使用 FFmpeg 解码器
                // ================================================================
                // 【EXTENSION_RENDERER_MODE_PREFER 的含义】
                // 优先使用扩展渲染器（FFmpeg），系统解码器作为备用。
                // 只要 FFmpeg 支持的格式，都用 FFmpeg 解码。
                //
                // 【好处】
                // 1. 兼容性最好，支持格式最多
                // 2. 有些硬解卡顿的源，软解反而流畅
                //
                // 【缺点】
                // 性能稍差，耗电多一些
                renderersFactory.setExtensionRendererMode(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                );
                // ====================================================================
                // ✅ 2026-06-25 修复：软解模式下关闭解码器降级
                // ====================================================================
                // 【为什么要关闭降级？】
                // 原来 setEnableDecoderFallback(true) 会导致：
                // FFmpeg 初始化失败 → 自动降级到系统硬解 → 用户以为软解生效了
                // 结果就是"设置了软解，但实际还是硬解"。
                //
                // 【关闭降级后的行为】
                // FFmpeg 失败 → 直接报错（播放失败）→ 用户能看到问题
                // 虽然可能导致某些频道播放失败，但至少能暴露真实问题，
                // 而不是被"假软解"欺骗。
                //
                // 【注意】
                // 如果你的设备上 FFmpeg 确实不可用，开启这个可能导致某些频道播放失败。
                // 但这样至少能知道真实情况，方便后续排查 FFmpeg 加载问题。
                renderersFactory.setEnableDecoderFallback(false);
                // 删掉原来重复的 setExtensionRendererMode 调用（代码里有两次）
                // 原来的 try-catch 块里又调用了一次，是冗余的，现在去掉
                Log.d(TAG, "【FFmpeg】软解码模式：优先使用 FFmpeg 解码器（降级已关闭）");
                SettingsActivity.logOperation("【解码器】初始化：FFmpeg 软解码模式（优先，降级已关闭）");
                break;
            case DECODER_MODE_HARD:
                // ================================================================
                // 硬解模式：只用系统硬解码器，完全不用 FFmpeg
                // ================================================================
                // 【EXTENSION_RENDERER_MODE_OFF 的含义】
                // 不使用扩展渲染器（FFmpeg），只用系统的 MediaCodec 硬解码器。
                //
                // 【好处】
                // 1. 性能最好，最省电
                // 2. 完全不依赖 FFmpeg 库，包体更小
                //
                // 【缺点】
                // 兼容性一般，有些特殊格式的直播源可能不支持
                renderersFactory.setExtensionRendererMode(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                );
                // 禁用解码器降级（硬解不行就报错，不用软解兜底）
                renderersFactory.setEnableDecoderFallback(false);
                Log.d(TAG, "【解码器】硬解码模式：只用系统硬解，不使用 FFmpeg");
                SettingsActivity.logOperation("【解码器】初始化：系统硬解模式（不使用 FFmpeg）");
                break;
            case DECODER_MODE_AUTO:
            default:
                // ================================================================
                // 自动模式（默认）：硬解优先，FFmpeg 作为备用方案
                // ================================================================
                // 【为什么用 ON 模式？】
                // 正常情况下用系统硬解码，性能好，省电。
                // 但是有些特殊格式的直播源，硬解码不支持，
                // 这时候自动回退到 FFmpeg 软解码，保证能播出来。
                //
                // 【EXTENSION_RENDERER_MODE_ON 的含义】
                // 系统 MediaCodec 优先，扩展渲染器（FFmpeg）作为备用。
                // 当系统解码器都不支持时，才尝试 FFmpeg。
                //
                // 【好处】
                // 1. 大部分情况用硬解，性能好，省电
                // 2. 特殊格式自动用 FFmpeg 软解，兼容性好
                // 3. 用户无感知，自动切换
                // 4. 配合自动切换解码器功能，卡顿了还能主动切软解
                renderersFactory.setExtensionRendererMode(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                );
                // 启用解码器降级（硬解不行自动降级到软解）
                renderersFactory.setEnableDecoderFallback(true);
                Log.d(TAG, "【FFmpeg】硬解码模式：系统硬解优先，FFmpeg 作为备用");
                SettingsActivity.logOperation("【解码器】初始化：自动模式（系统硬解优先，FFmpeg 备用）");
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
        // ✅ 2026-06-26 更新：FFmpeg 可用性检测（反射方式，修正版）
        // ====================================================================
        // 【为什么改用反射？】
        // 原来用 MediaCodecList API 检测 FFmpeg 解码器，但那是错的！
        // MediaCodecList 只能检测系统内置的 MediaCodec 解码器，
        // FFmpeg 扩展是 Media3 自己实现的，不走系统 MediaCodec 框架。
        // 所以检测结果"未发现 FFmpeg 解码器"是假阴性。
        //
        // 【正确检测方法】
        // 用反射尝试加载 FFmpeg 相关的类，判断 FFmpeg 扩展是否正确集成。
        //
        // 【检测内容】
        // 1. FfmpegLibrary 类是否存在
        // 2. FfmpegLibrary.isAvailable() 是否返回 true
        // 3. FfmpegAudioRenderer 类是否存在（音频渲染器）
        // 4. ExperimentalFfmpegVideoRenderer 类是否存在（视频渲染器，实验性）
        // 5. FfmpegVideoRenderer 类是否存在（视频渲染器，正式版，备用检测）
        try {
            // 1. 检测 FfmpegLibrary 类是否存在
            Class<?> ffmpegLibraryClass = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary");
            Log.d(TAG, "【解码器】✅ FfmpegLibrary 类存在");
            
            // 2. 检测 isAvailable() 方法
            java.lang.reflect.Method isAvailableMethod = ffmpegLibraryClass.getMethod("isAvailable");
            boolean isAvailable = (boolean) isAvailableMethod.invoke(null);
            Log.d(TAG, "【解码器】FfmpegLibrary.isAvailable() = " + isAvailable);
            SettingsActivity.logOperation("【解码器】FFmpeg 库可用状态：" + (isAvailable ? "✅ 可用" : "❌ 不可用"));
            
            if (!isAvailable) {
                Log.w(TAG, "【解码器】⚠️ FFmpeg 库不可用，软解可能不生效");
                SettingsActivity.logOperation("【解码器】⚠️ 警告：FFmpeg 库不可用，软解可能不生效");
            }
            
            // 3. 检测音频渲染器
            try {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer");
                Log.d(TAG, "【解码器】✅ FfmpegAudioRenderer 存在（音频渲染器）");
                SettingsActivity.logOperation("【解码器】FFmpeg 音频渲染器：✅ 可用");
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "【解码器】❌ FfmpegAudioRenderer 不存在");
                SettingsActivity.logOperation("【解码器】FFmpeg 音频渲染器：❌ 不存在");
            }
            
            // 4. 检测视频渲染器（注意：Media3 1.7.1 是 ExperimentalFfmpegVideoRenderer）
            boolean hasVideoRenderer = false;
            try {
                Class<?> videoRendererClass = Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer");
                Log.d(TAG, "【解码器】✅ ExperimentalFfmpegVideoRenderer 存在（视频渲染器，实验性）");
                SettingsActivity.logOperation("【解码器】FFmpeg 视频渲染器：✅ 可用（实验性）");
                hasVideoRenderer = true;
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "【解码器】⚠️ ExperimentalFfmpegVideoRenderer 不存在，尝试检测正式版...");
                // 再试试 FfmpegVideoRenderer（不带 Experimental，某些版本可能叫这个）
                try {
                    Class.forName("androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer");
                    Log.d(TAG, "【解码器】✅ FfmpegVideoRenderer 存在（视频渲染器）");
                    SettingsActivity.logOperation("【解码器】FFmpeg 视频渲染器：✅ 可用");
                    hasVideoRenderer = true;
                } catch (ClassNotFoundException e2) {
                    Log.w(TAG, "【解码器】❌ FfmpegVideoRenderer 也不存在");
                    SettingsActivity.logOperation("【解码器】FFmpeg 视频渲染器：❌ 未找到");
                }
            }
            
            // 如果只有音频没有视频，输出警告
            if (!hasVideoRenderer) {
                Log.w(TAG, "【解码器】⚠️ 警告：FFmpeg 仅支持音频，视频软解不可用！");
                Log.w(TAG, "【解码器】这就是设置了软解但视频还是硬解的原因！");
                SettingsActivity.logOperation("【解码器】⚠️ 警告：FFmpeg 仅支持音频，视频软解不可用");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "【解码器】检测 FFmpeg 失败：" + e.getMessage(), e);
            SettingsActivity.logOperation("【解码器】检测 FFmpeg 失败：" + e.getMessage());
        }
        // ====================================================================
        // ✅ 方案一：添加 EventLogger（自动打印所有事件到 logcat）
        // ====================================================================
        // 官方提供的调试工具，会自动打印所有播放器事件，
        // 包括解码器初始化、缓冲状态、播放状态等。
        // 在 logcat 中搜索 "EventLogger" 或 "videoDecoderInitialized" 即可查看。
        player.addAnalyticsListener(new EventLogger());
        // ====================================================================
        // ✅ 方案二：自定义 AnalyticsListener（把真实解码器名称写到操作日志）
        // ====================================================================
        // 【为什么不用 videoFormat.sampleMimeType？】
        // 因为 sampleMimeType 是视频格式（如 video/avc），不是解码器名称。
        // 用 AnalyticsListener.onVideoDecoderInitialized 才能拿到真实解码器名。
        // 【效果】
        // 操作日志里会显示：【解码器】真实解码器：FFmpeg 软解（ffmpeg-video）
        // 或者：【解码器】真实解码器：系统硬解（OMX.qcom.video.decoder.avc）
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String decoderName) {
                Log.d(TAG, "【解码器】真实解码器名称：" + decoderName);
                boolean isFfmpeg = decoderName != null 
                        && decoderName.toLowerCase().contains("ffmpeg");
                String decoderType = isFfmpeg ? "FFmpeg 软解" : "系统硬解";
                SettingsActivity.logOperation("【解码器】真实解码器：" + decoderType 
                        + "（" + decoderName + "）");
                
                // 软解模式下检查是否真的用了 FFmpeg
                if (mDecoderMode == DECODER_MODE_SOFT && !isFfmpeg) {
                    Log.w(TAG, "【解码器】⚠️ 警告：软解模式未生效");
                    SettingsActivity.logOperation("【解码器】⚠️ 警告：软解模式未生效");
                }
                
                // 硬解模式下检查是否真的用了硬解
                if (mDecoderMode == DECODER_MODE_HARD && isFfmpeg) {
                    Log.w(TAG, "【解码器】⚠️ 警告：硬解模式未生效");
                    SettingsActivity.logOperation("【解码器】⚠️ 警告：硬解模式未生效");
                }
            }
        });
        // 初始化播放监听器
        initPlayerListener();
        // 初始化Cookie管理器
        CookieSyncManager.createInstance(context);
        CookieManager.getInstance().setAcceptCookie(true);
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
                    // 【为什么要这样？】
                    // 原来每次 STATE_READY 都会重置时间，
                    // 但自动切换解码器的判断是基于"播放开始后 15 秒内"，
                    // 如果中间因为缓冲导致状态变化，会重置这个时间，
                    // 导致自动切换判断不准确。
                    //
                    // 修复后：只在第一次 STATE_READY 时设置 initialPlayStartTime，
                    // 后续的状态变化不会影响这个时间。
                    if (initialPlayStartTime == 0) {
                        initialPlayStartTime = System.currentTimeMillis();
                    }
                    // ====================================================================
                    // ✅ 自动切换解码器（硬解 → 软解）（2026-06-25 调优）
                    // ====================================================================
                    // 【触发条件】
                    // 1. 当前是自动模式（DECODER_MODE_AUTO）
                    // 2. 还没切换过解码器（每个频道只切一次）
                    // 3. 播放开始后 15 秒内（刚开播的这段时间最能反映是否卡顿）
                    // 4. 缓冲次数 > 1 次（说明网络或解码有问题）
                    //
                    // 【为什么要自动切换？】
                    // 有些频道用硬解会很卡（码率太高、格式不兼容等），
                    // 自动切换到 FFmpeg 软解可以提升播放流畅度。
                    // 每个频道只切一次，避免反复切换。
                    //
                    // 【2026-06-25 调优】
                    // 原来：30 秒内缓冲 > 2 次才切换
                    // 现在：15 秒内缓冲 > 1 次就切换
                    // 原因：用户反馈画面卡顿，原来的触发条件太宽松了，
                    // 调灵敏一点，让用户能更快感受到流畅度提升。
                    if (mDecoderMode == DECODER_MODE_AUTO && !hasSwitchedDecoder
                            && initialPlayStartTime > 0
                            && System.currentTimeMillis() - initialPlayStartTime < 15000
                            && bufferCount > 1) {
                        Log.d(TAG, "【自动切换】硬解卡顿，自动切换到 FFmpeg 软解");
                        SettingsActivity.logOperation("【解码器】硬解卡顿（缓冲"
                                + bufferCount + "次），自动切换到 FFmpeg 软解");
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
                    // 【作用】
                    // 统计播放过程中的缓冲次数和卡顿总时长，
                    // 用于判断是否需要自动切换解码器。
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
                    // 【为什么改这里？】
                    // 原来的 else 分支是死代码（四个状态都覆盖了），
                    // 现在把 updateWakeLock(false) 移到 IDLE 状态里，
                    // 确保空闲状态时屏幕常亮会被关闭。
                    updateWakeLock(false);
                }
                // ⚠️ 注意：去掉了原来的 else 分支
                // 因为四个状态（READY/BUFFERING/ENDED/IDLE）已经覆盖了所有情况，
                // else 分支永远不会执行，是死代码。
                // 现在把 updateWakeLock(false) 分别放到合适的状态里处理。
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
            /**
             * 为什么需要这个？
             * 有些直播流刚开始时分辨率还没确定，
             * 等视频解码器初始化完成后，才会回调真实的分辨率。
             * 这时候我们需要更新一下信息栏的画质标签。
             */
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
     *
     * 【为什么需要这个？】
     * 自动跳过失效频道时，切到新频道后，
     * 旧频道的延迟重试任务还在 Handler 队列里，
     * 1秒后会执行并重新加载（但 currentUrl 已经是新频道了），
     * 导致新频道被重新加载一次，播放中断，体验不好。
     *
     * 【调用时机】
     * 1. playUrl() 切换频道时自动调用
     * 2. 外部也可以手动调用
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
     * 【修改说明】
     * 1. 修复了 isRetrying 一直是 true 的 bug（重试开始时就清除等待标记）
     * 2. 重试次数用完后，回调 onSourceFailed()，通知外部自动切台
     * 3. 最大重试次数改成 2 次（重试2次还不行就算失效）
     * 4. 所有关键节点接入 SettingsActivity 操作日志
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
            // 【作用】
            // 通知外部（MainActivity）这个源失效了，
            // 让外部自动跳过这个频道，切到下一个。
            //
            // 【为什么不在 TVPlayerManager 里直接切台？】
            // TVPlayerManager 只负责播放单个 URL，
            // 不知道频道列表的概念，也不知道怎么切台。
            // 频道管理和切台逻辑应该在外部。
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
                // ====================================================================
                // 重试任务开始执行时，清除等待标记
                // ====================================================================
                // 【为什么要在这里清除？】
                // isRetrying 的含义是"是否有重试任务正在等待中"。
                // 重试任务开始执行后，等待状态就结束了。
                // 如果这次重试又失败了，onPlayerError 会再次触发 autoRetry，
                // 这时候 isRetrying 应该是 false，允许安排下一次重试。
                //
                // 【原来的 bug】
                // 原来 isRetrying 一直是 true，直到播放成功才重置。
                // 导致重试一次后如果又失败，就不能再重试了，
                // 实际上只能重试 1 次，而不是 MAX_RETRY_COUNT 次。
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
    // ✅ 设置解码器模式（2026-06-25 新增）
    // ====================================================================
    /**
     * 设置解码器模式
     *
     * @param mode 解码器模式
     *             - DECODER_MODE_AUTO：自动模式（推荐）
     *             - DECODER_MODE_HARD：强制硬解
     *             - DECODER_MODE_SOFT：强制软解（FFmpeg）
     *
     * 【功能】
     * 切换解码器模式后，会重新创建播放器，
     * 并重新加载当前频道，立即生效。
     *
     * 【为什么要重新创建播放器？】
     * 因为渲染器工厂的扩展模式只能在创建播放器时设置，
     * 播放器创建后不能动态修改，所以必须重新创建。
     *
     * 【调用场景】
     * 1. 用户在设置页面手动切换解码器模式
     * 2. 自动切换解码器（硬解卡顿自动切软解）
     * 3. 应用启动时读取设置初始化
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
                decoderType = "FFmpeg 软解码（强制）";
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
     *         - DECODER_MODE_SOFT：强制软解
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
     * 【为什么 false 对应 AUTO 而不是 HARD？】
     * 因为原来的 useSoftware=false 行为是"硬解优先，FFmpeg 备用"，
     * 这和新的 AUTO 模式行为一致，而不是 HARD 模式（完全不用 FFmpeg）。
     * 这样可以保证旧代码的行为不变。
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
     *
     * 【2026-06-24 修改：切换频道时取消旧重试任务】
     * 【修改说明】
     * 切换到新频道前，先调用 cancelRetry() 取消旧频道的重试任务，
     * 避免旧频道的延迟重试干扰新频道的播放。
     *
     * 【2026-06-25 修改：切换频道时重置解码器状态 + 操作日志】
     * 【修改说明】
     * 每个频道都有独立的解码策略判断：
     * 1. 重置 hasSwitchedDecoder（每个频道都可以自动切一次）
     * 2. 重置 initialPlayStartTime（重新计时）
     * 3. 重置性能统计
     * 4. 用户选择的硬解/软解模式保持不变
     * 5. 切换频道时记录操作日志
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
     * 【优化3】换回 DefaultHttpDataSource
     * 自定义的 RedirectLoggingHttpDataSource 可能有bug，先换回官方的稳定版
     * 如果需要看重定向日志，可以再切回去
     *
     * 【优化4】切台保持最后一帧
     * 去掉 player.stop() 和 player.clearMediaItems()
     * 直接用 setMediaSource 切换，旧画面会保留到新画面出来
     * 这样就完全避免了切台黑屏的问题
     */
    private void playUrlInternal(String url) {
        try {
            if (player == null || url == null || url.trim().isEmpty()) return;
            currentUrl = url.trim();
            Log.d(TAG, "开始播放：" + currentUrl);
            // ====================================================================
            // ✅ 关键修改：去掉 player.stop() 和 player.clearMediaItems()
            // ====================================================================
            /**
             * 【为什么去掉 stop() 就能保持最后一帧？】
             *
             * 调用 player.stop() 会立刻清空渲染器的画面，导致黑屏。
             * 直接调用 setMediaSource() + prepare()，旧画面会保留到新画面渲染出来。
             *
             * 用户看到的效果：旧画面静止不动 → 新画面突然出现
             * 而不是：黑屏 → 新画面出现
             *
             * 这样就完全避免了切台黑屏的问题。
             *
             * 【为什么去掉 clearMediaItems()？】
             * setMediaSource(mediaSource, true) 会自动替换所有媒体源，
             * 不需要先 clear 再 set。
             *
             * 【第二个参数 true 是什么意思？】
             * setMediaSource(mediaSource, resetPosition = true)
             * true = 重置播放位置到开头（直播流必须用 true）
             * false = 保持当前播放位置（点播连播时用 false）
             */
            // player.stop();          // 注释掉，保持最后一帧
            // player.clearMediaItems(); // 注释掉，保持最后一帧
            // ===== 创建数据源（带重定向日志版） =====
            // 每一重定向都会打印详细日志，方便调试直播源
            RedirectLoggingHttpDataSource.Factory httpFactory =
                    new RedirectLoggingHttpDataSource.Factory();
            httpFactory.setDefaultRequestProperties(getHeaders(currentUrl));
            httpFactory.setAllowCrossProtocolRedirects(true);
            MediaItem mediaItem = MediaItem.fromUri(currentUrl);
            // ====================================================================
            // MediaSource 类型改成 Media3 的
            // ====================================================================
            // 从 com.google.android.exoplayer2.source.MediaSource
            // 改成 androidx.media3.exoplayer.source.MediaSource
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
            // 从 com.google.android.exoplayer2.ui.AspectRatioFrameLayout
            // 改成 androidx.media3.ui.AspectRatioFrameLayout
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
     * 【作用】
     * 重试次数用完后，回调这个监听器，
     * 通知外部这个源失效了，让外部自动切台。
     *
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
    // ✅ 自定义渲染器工厂（2026-06-26 新增，v2 版本）
    // ====================================================================
    /**
     * 自定义渲染器工厂
     *
     * 【为什么需要这个？】
     * Media3 的 DefaultRenderersFactory 默认不会加载实验性渲染器
     * （ExperimentalFfmpegVideoRenderer），导致软解模式下视频还是硬解。
     * 我们继承 DefaultRenderersFactory，手动把实验性 FFmpeg 视频渲染器加进去。
     *
     * 【v2 改进】
     * 1. 自动匹配构造函数参数类型，不用硬编码参数顺序
     * 2. 把构造函数详细信息打印到操作日志，方便调试
     */
    private static class FfmpegRenderersFactory extends DefaultRenderersFactory {
        private static final String TAG = "FfmpegRenderersFactory";

        public FfmpegRenderersFactory(Context context) {
            super(context);
        }

        @Override
        protected void buildVideoRenderers(
                Context context,
                int extensionRendererMode,
                MediaCodecSelector mediaCodecSelector,
                boolean enableDecoderFallback,
                Handler eventHandler,
                VideoRendererEventListener eventListener,
                long allowedVideoJoiningTimeMs,
                ArrayList<Renderer> out) {

            // 先调用父类方法，添加默认的渲染器（系统硬解等）
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
                    enableDecoderFallback, eventHandler, eventListener,
                    allowedVideoJoiningTimeMs, out);

            // 如果是 PREFER 模式或 ON 模式，尝试手动添加 ExperimentalFfmpegVideoRenderer
            if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER
                    || extensionRendererMode == EXTENSION_RENDERER_MODE_ON) {
                try {
                    // 加载 ExperimentalFfmpegVideoRenderer 类
                    Class<?> rendererClass = Class.forName(
                            "androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer");

                    Log.d(TAG, "✅ 找到 ExperimentalFfmpegVideoRenderer 类");

                    // 获取所有构造函数
                    Constructor<?>[] constructors = rendererClass.getConstructors();
                    Log.d(TAG, "构造函数数量：" + constructors.length);
                    SettingsActivity.logOperation("【解码器】找到 " + constructors.length 
                            + " 个构造函数");

                    if (constructors.length == 0) {
                        Log.e(TAG, "❌ 没有找到公开的构造函数");
                        SettingsActivity.logOperation("【解码器】❌ 没有找到公开构造函数");
                        return;
                    }

                    // 打印第一个构造函数的详细参数到操作日志
                    Constructor<?> firstConstructor = constructors[0];
                    Class<?>[] paramTypes = firstConstructor.getParameterTypes();
                    StringBuilder paramLog = new StringBuilder();
                    paramLog.append("【解码器】构造函数参数：");
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (i > 0) paramLog.append(", ");
                        paramLog.append(paramTypes[i].getSimpleName());
                    }
                    Log.d(TAG, paramLog.toString());
                    SettingsActivity.logOperation(paramLog.toString());

                    // ================================================================
                    // ✅ 智能匹配构造函数参数
                    // ================================================================
                    // 【原理】
                    // 遍历构造函数的每个参数，根据参数类型自动匹配对应的值。
                    // 这样不用硬编码参数顺序，不管构造函数是什么样的，
                    // 只要参数类型是我们认识的，就能自动传对值。
                    //
                    // 【支持的参数类型】
                    // - Handler → 传 eventHandler
                    // - VideoRendererEventListener → 传 eventListener
                    // - long → 传 allowedVideoJoiningTimeMs
                    // - int → 传 0（默认值）
                    // - boolean → 传 false
                    // - Context → 传 context
                    // - 其他引用类型 → 传 null
                    Object[] args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        String typeName = paramTypes[i].getName();
                        
                        if (typeName.equals("android.os.Handler") 
                                || typeName.equals("Handler")) {
                            args[i] = eventHandler;
                        } else if (typeName.contains("VideoRendererEventListener")) {
                            args[i] = eventListener;
                        } else if (typeName.equals("long")) {
                            args[i] = allowedVideoJoiningTimeMs;
                        } else if (typeName.equals("int")) {
                            args[i] = 0; // int 类型默认传 0
                        } else if (typeName.equals("boolean")) {
                            args[i] = false; // boolean 类型默认传 false
                        } else if (typeName.equals("android.content.Context")
                                || typeName.equals("Context")) {
                            args[i] = context;
                        } else if (typeName.equals("float")) {
                            args[i] = 0f;
                        } else {
                            // 其他引用类型，传 null
                            args[i] = null;
                            Log.d(TAG, "  ⚠️ 未知参数类型：" + typeName + "，传 null");
                        }
                    }

                    // 尝试创建实例
                    Renderer renderer = (Renderer) firstConstructor.newInstance(args);

                    if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
                        // PREFER 模式：插到最前面，优先使用 FFmpeg 软解
                        out.add(0, renderer);
                        Log.d(TAG, "✅ 已添加 ExperimentalFfmpegVideoRenderer（优先模式）");
                        SettingsActivity.logOperation("【解码器】✅ 手动添加 FFmpeg 视频渲染器（优先模式）");
                    } else {
                        // ON 模式：加到最后，作为备用方案
                        out.add(renderer);
                        Log.d(TAG, "✅ 已添加 ExperimentalFfmpegVideoRenderer（备用模式）");
                        SettingsActivity.logOperation("【解码器】✅ 手动添加 FFmpeg 视频渲染器（备用模式）");
                    }

                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "❌ ExperimentalFfmpegVideoRenderer 类不存在", e);
                    SettingsActivity.logOperation("【解码器】❌ FFmpeg 视频渲染器类不存在");
                } catch (Exception e) {
                    Log.e(TAG, "❌ 创建 ExperimentalFfmpegVideoRenderer 失败", e);
                    SettingsActivity.logOperation("【解码器】❌ 创建失败：" + e.getMessage());
                    // 打印详细异常信息
                    if (e.getCause() != null) {
                        SettingsActivity.logOperation("【解码器】原因：" + e.getCause().getMessage());
                    }
                }
            }
        }
    }
}
       
