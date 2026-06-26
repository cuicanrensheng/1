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
import androidx.media3.exoplayer.RenderersFactory;
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
 * 自定义 FfmpegRenderersFactory，实现 RenderersFactory 接口，
 * 内部包装 DefaultRenderersFactory，在 createRenderers 方法中
 * 手动把 ExperimentalFfmpegVideoRenderer 加到渲染器数组里。
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
        //
        // 【为什么变量类型是 FfmpegRenderersFactory 而不是 RenderersFactory？】
        // 因为后面还要调用 setExtensionRendererMode() 和 setEnableDecoderFallback()，
        // 这两个方法是 FfmpegRenderersFactory 自己的，不是 RenderersFactory 接口的。
        FfmpegRenderersFactory renderersFactory = new FfmpegRenderersFactory(context);
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
                Log.w(TAG, "【解码器】⚠️ FFmpeg 库不可用，软解模式可能不生效");
                SettingsActivity.logOperation("【解码器】⚠️ 警告：FFmpeg 库不可用");
            }
            
            // 3. 检测音频渲染器
            try {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer");
                Log.d(TAG, "【解码器】✅ FfmpegAudioRenderer 类存在（音频渲染器）");
                SettingsActivity.logOperation("【解码器】FFmpeg 音频渲染器：✅ 可用");
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "【解码器】❌ FfmpegAudioRenderer 类不存在");
                SettingsActivity.logOperation("【解码器】FFmpeg 音频渲染器：❌ 不可用");
            }
            
            // 4. 检测视频渲染器（实验性）
            try {
                Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer");
                Log.d(TAG, "【解码器】✅ ExperimentalFfmpegVideoRenderer 类存在（视频渲染器，实验性）");
                SettingsActivity.logOperation("【解码器】FFmpeg 视频渲染器：✅ 可用（实验性）");
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "【解码器】❌ ExperimentalFfmpegVideoRenderer 类不存在");
                SettingsActivity.logOperation("【解码器】FFmpeg 视频渲染器：❌ 不可用（实验性）");
            }
            
            // 5. 检测视频渲染器（正式版，备用检测）
            try {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegVideoRenderer");
                Log.d(TAG, "【解码器】✅ FfmpegVideoRenderer 类存在（视频渲染器，正式版）");
                SettingsActivity.logOperation("【解码器】FFmpeg 视频渲染器（正式版）：✅ 可用");
            } catch (ClassNotFoundException e) {
                Log.d(TAG, "【解码器】FfmpegVideoRenderer 类不存在（正常，可能是实验性版本）");
                // 这个不存在是正常的，因为用的是实验性版本
            }
            
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "【解码器】❌ FFmpeg 扩展未集成", e);
            SettingsActivity.logOperation("【解码器】❌ FFmpeg 扩展未集成");
        } catch (Exception e) {
            Log.e(TAG, "【解码器】检测 FFmpeg 可用性失败", e);
            SettingsActivity.logOperation("【解码器】检测 FFmpeg 可用性失败：" + e.getMessage());
        }
        // 初始化播放器监听器
        initPlayerListener();
        // ====================================================================
        // ✅ 2026-06-26 新增：添加 EventLogger 调试日志
        // ====================================================================
        // 【作用】
        // EventLogger 是 Media3 官方提供的调试工具，
        // 会把所有播放器事件打印到 logcat，包括：
        // - 播放状态变化
        // - 缓冲状态
        // - 解码器初始化（videoDecoderInitialized / audioDecoderInitialized）
        // - 丢帧情况
        // - 等等
        //
        // 【为什么用这个？】
        // 自定义 AnalyticsListener 的 onVideoDecoderInitialized 方法
        // 在 Media3 1.7.1 中可能不存在或签名不同，导致编译错误。
        // EventLogger 是官方的，肯定能编译通过。
        //
        // 【怎么看日志】
        // 在 logcat 中搜索 "EventLogger" 或 "videoDecoderInitialized"
        // 就能看到真实的解码器名称。
        player.addAnalyticsListener(new EventLogger());
        Log.d(TAG, "【调试】已添加 EventLogger 调试日志");
        SettingsActivity.logOperation("【调试】已添加 EventLogger 调试日志（logcat 查看）");
    }
    // ====================================================================
    // 初始化播放器监听器
    // ====================================================================
    private void initPlayerListener() {
        playerListener = new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "播放错误", error);
                String errorMsg = error.getMessage();
                if (errorMsg == null) errorMsg = "未知错误";
                // 记录错误日志
                SettingsActivity.logOperation("【播放器】❌ 播放错误：" + errorMsg);
                // 自动重试
                autoRetry(errorMsg);
                if (listener != null) {
                    listener.onPlayError(errorMsg);
                }
            }
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_IDLE:
                        Log.d(TAG, "播放状态：空闲");
                        if (listener != null) listener.onIdle();
                        break;
                    case Player.STATE_BUFFERING:
                        Log.d(TAG, "播放状态：缓冲中");
                        // ====================================================================
                        // ✅ 2026-06-25 新增：缓冲计数 + 自动切换解码器
                        // ====================================================================
                        // 只统计播放过程中的缓冲（STATE_READY 之后的缓冲）
                        // 不统计首次加载的缓冲
                        if (initialPlayStartTime > 0 && isPlaying) {
                            bufferCount++;
                            // 记录卡顿开始时间
                            if (!isStalled) {
                                isStalled = true;
                                lastStallStartTime = System.currentTimeMillis();
                            }
                            Log.d(TAG, "【性能】缓冲次数：" + bufferCount);
                            // 自动模式下，15秒内缓冲 > 1次，自动切换到软解
                            if (mDecoderMode == DECODER_MODE_AUTO 
                                    && !hasSwitchedDecoder
                                    && bufferCount > 1
                                    && System.currentTimeMillis() - initialPlayStartTime < 15000) {
                                Log.w(TAG, "【解码器】15秒内缓冲超过1次，自动切换到 FFmpeg 软解");
                                SettingsActivity.logOperation("【解码器】⚠️ 卡顿检测：15秒内缓冲" + bufferCount 
                                        + "次，自动切换到 FFmpeg 软解");
                                hasSwitchedDecoder = true;
                                setDecoderMode(DECODER_MODE_SOFT);
                            }
                        }
                        if (listener != null) listener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        Log.d(TAG, "播放状态：就绪");
                        // ====================================================================
                        // ✅ 首次播放开始时间（只设置一次）
                        // ====================================================================
                        if (initialPlayStartTime == 0) {
                            initialPlayStartTime = System.currentTimeMillis();
                            Log.d(TAG, "【性能】首次播放就绪时间：" + initialPlayStartTime);
                        }
                        // 记录卡顿结束时间
                        if (isStalled) {
                            isStalled = false;
                            long stallDuration = System.currentTimeMillis() - lastStallStartTime;
                            totalStallTime += stallDuration;
                            Log.d(TAG, "【性能】本次卡顿时长：" + stallDuration + "ms，累计卡顿：" + totalStallTime + "ms");
                        }
                        // 通知直播信息更新
                        notifyLiveInfoUpdate();
                        // 显示频道号
                        showChannelAndAutoHide();
                        if (listener != null) listener.onPlayReady();
                        break;
                    case Player.STATE_ENDED:
                        Log.d(TAG, "播放状态：结束");
                        stopStuckDetection();
                        if (listener != null) listener.onPlayEnd();
                        break;
                }
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                TVPlayerManager.this.isPlaying = isPlaying;
                updateWakeLock(isPlaying);
                if (isPlaying) {
                    startStuckDetection();
                } else {
                    stopStuckDetection();
                }
            }
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                Log.d(TAG, "视频尺寸变化：" + videoSize.width + "x" + videoSize.height);
                // 视频尺寸变化时也更新直播信息
                notifyLiveInfoUpdate();
            }
        };
        player.addListener(playerListener);
    }
    // ====================================================================
    // 请求头相关
    // ====================================================================
    private Map<String, String> getHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Accept", "*/*");
        headers.put("Connection", "keep-alive");
        // 添加 Cookie
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            String cookie = cookieManager.getCookie(url);
            if (!TextUtils.isEmpty(cookie)) {
                headers.put("Cookie", cookie);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取Cookie异常", e);
        }
        return headers;
    }
    // ====================================================================
    // 播放状态查询
    // ====================================================================
    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }
    public void setPlayerView(PlayerView view) {
        this.playerView = view;
        if (playerView != null && player != null) {
            playerView.setPlayer(player);
            // 隐藏控制器
            playerView.setUseController(false);
        }
    }
    public long getCurrentPosition() {
        try {
            if (player != null) return player.getCurrentPosition();
        } catch (Exception e) {
            Log.e(TAG, "获取播放位置异常", e);
        }
        return 0;
    }
    public long getDuration() {
        try {
            if (player != null) return player.getDuration();
        } catch (Exception e) {
            Log.e(TAG, "获取时长异常", e);
        }
        return 0;
    }
    public long getBufferedPosition() {
        try {
            if (player != null) return player.getBufferedPosition();
        } catch (Exception e) {
            Log.e(TAG, "获取缓冲位置异常", e);
        }
        return 0;
    }
    public ExoPlayer getPlayer() {
        return player;
    }
    // ====================================================================
    // ✅ 解码器模式相关（2026-06-25 新增）
    // ====================================================================
    /**
     * 设置解码器模式
     *
     * @param mode 解码器模式
     *             - DECODER_MODE_AUTO（0）：自动模式
     *             - DECODER_MODE_HARD（1）：强制硬解
     *             - DECODER_MODE_SOFT（2）：强制软解
     *
     * 【切换后会发生什么？】
     * 1. 释放当前播放器
     * 2. 用新的解码器模式重新创建播放器
     * 3. 重新加载当前频道
     *
     * 【注意】
     * 切换解码器会导致短暂的播放中断，
     * 因为需要重新创建播放器。
     */
    public void setDecoderMode(int mode) {
        if (mode == mDecoderMode) {
            Log.d(TAG, "【解码器】模式未变化，跳过：" + mode);
            return;
        }
        Log.d(TAG, "【解码器】切换模式：" + mDecoderMode + " → " + mode);
        String modeName = mode == DECODER_MODE_AUTO ? "自动" 
                : mode == DECODER_MODE_HARD ? "硬解" : "软解（FFmpeg）";
        SettingsActivity.logOperation("【解码器】切换模式：" + modeName);
        
        mDecoderMode = mode;
        // 保存当前播放的 URL
        String urlToReplay = currentUrl;
        // 释放当前播放器
        if (player != null) {
            if (playerListener != null) {
                player.removeListener(playerListener);
            }
            player.release();
            player = null;
        }
        // 重新初始化播放器（用新的解码器模式）
        initPlayer();
        // 重新播放当前频道
        if (urlToReplay != null && !urlToReplay.isEmpty()) {
            Log.d(TAG, "【解码器】重新加载频道：" + urlToReplay);
            // 注意：这里不用 playUrl()，因为 playUrl() 会重置 hasSwitchedDecoder
            // 我们用 playUrlInternal() 直接播放
            playUrlInternal(urlToReplay);
        }
    }
    /**
     * 获取当前解码器模式
     */
    public int getDecoderMode() {
        return mDecoderMode;
    }
    /**
     * 设置是否使用软解码（向后兼容）
     *
     * @param useSoftware true = 软解，false = 自动模式
     *
     * @deprecated 请使用 setDecoderMode(int) 替代
     */
    @Deprecated
    public void setSoftwareDecoder(boolean useSoftware) {
        this.useSoftwareDecoder = useSoftware;
        if (useSoftware) {
            setDecoderMode(DECODER_MODE_SOFT);
        } else {
            // 原来的 false 对应的是硬解优先（自动模式），不是强制硬解
            setDecoderMode(DECODER_MODE_AUTO);
        }
    }
    // ====================================================================
    // 自动重试
    // ====================================================================
    /**
     * 自动重试
     * 【逻辑】
     * 1. 重试次数 < MAX_RETRY_COUNT → 延迟 2 秒后重试
     * 2. 重试次数 >= MAX_RETRY_COUNT → 回调源失效监听器（自动切台）
     *
     * 【2026-06-25 新增】
     * 重试用完后，回调 sourceFailedListener，
     * 让外部（MainActivity）自动切到下一个频道。
     */
    private void autoRetry(String reason) {
        // 如果正在重试中，跳过
        if (isRetrying) {
            Log.d(TAG, "已有重试任务在等待，跳过");
            return;
        }
        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++;
            isRetrying = true;
            Log.d(TAG, "自动重试 " + retryCount + "/" + MAX_RETRY_COUNT + "，原因：" + reason);
            SettingsActivity.logOperation("【播放器】自动重试 " + retryCount + "/" + MAX_RETRY_COUNT 
                    + "，原因：" + reason);
            retryRunnable = new Runnable() {
                @Override
                public void run() {
                    isRetrying = false;
                    if (currentUrl != null) {
                        Log.d(TAG, "开始重试播放...");
                        playUrlInternal(currentUrl);
                    }
                }
            };
            mHandler.postDelayed(retryRunnable, 2000);
        } else {
            Log.e(TAG, "重试次数已用完，源失效：" + currentUrl);
            SettingsActivity.logOperation("【播放器】❌ 重试次数已用完，源失效");
            // 重试用完，回调源失效监听器（自动切台）
            if (sourceFailedListener != null) {
                // 切到主线程回调
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        sourceFailedListener.onSourceFailed();
                    }
                });
            }
        }
    }
    /**
     * 取消重试
     * 【调用时机】
     * 1. 切换频道时（新频道开始播放，旧频道的重试就没用了）
     * 2. 释放播放器时
     */
    private void cancelRetry() {
        if (retryRunnable != null) {
            mHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        isRetrying = false;
    }
    // ====================================================================
    // 卡住检测
    // ====================================================================
    private void startStuckDetection() {
        if (stuckHandler != null && stuckCheckRunnable != null) {
            stuckHandler.removeCallbacks(stuckCheckRunnable);
            stuckHandler.postDelayed(stuckCheckRunnable, 2000);
        }
    }
    private void stopStuckDetection() {
        if (stuckHandler != null && stuckCheckRunnable != null) {
            stuckHandler.removeCallbacks(stuckCheckRunnable);
        }
    }
    // ====================================================================
    // 屏幕常亮
    // ====================================================================
    private void updateWakeLock(boolean keepOn) {
        try {
            if (playerView != null) {
                playerView.setKeepScreenOn(keepOn);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置屏幕常亮异常", e);
        }
    }
    // ====================================================================
    // 播放控制
    // ====================================================================
    /**
     * 播放指定 URL
     *
     * 【2026-06-25 修改】
     * 切换频道时需要做的事情：
     * 1. 取消之前的重试任务
     * 2. 重置重试计数
     * 3. 重置解码器切换标记（每个频道只自动切一次）
     * 4. 重置 initialPlayStartTime（重新计时）
     * 5. 重置性能统计
     * 6. 用户选择的硬解/软解模式保持不变
     * 7. 切换频道时记录操作日志
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
            // 每一次重定向都会打印详细日志，方便调试直播源
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
            // 从 com.google.android.exoplayer2.ui.AspectRatioFrame
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
    // ✅ 自定义渲染器工厂（2026-06-26 新增，v4 版本：实现 RenderersFactory 接口）
    // ====================================================================
    /**
     * 自定义渲染器工厂
     *
     * 【为什么需要这个？】
     * Media3 的 DefaultRenderersFactory 默认不会加载实验性渲染器
     * （ExperimentalFfmpegVideoRenderer），导致软解模式下视频还是硬解。
     * 我们实现 RenderersFactory 接口，内部包装 DefaultRenderersFactory，
     * 在 createRenderers 方法中手动把实验性 FFmpeg 视频渲染器加进去。
     *
     * 【v4 改进】
     * 不继承 DefaultRenderersFactory（避免父类方法是 final 的无法重写），
     * 改为直接实现 RenderersFactory 接口，内部用代理模式包装
     * DefaultRenderersFactory，完全可控，不会因为父类实现变化而出错。
     */
    private static class FfmpegRenderersFactory implements RenderersFactory {
        private static final String TAG = "FfmpegRenderersFactory";

        private final DefaultRenderersFactory delegate;
        private final Context context;
        private int extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;

        public FfmpegRenderersFactory(Context context) {
            this.context = context;
            this.delegate = new DefaultRenderersFactory(context);
        }

        /**
         * 设置扩展渲染器模式
         */
        public void setExtensionRendererMode(int mode) {
            this.extensionRendererMode = mode;
            delegate.setExtensionRendererMode(mode);
        }

        /**
         * 设置是否启用解码器降级
         */
        public void setEnableDecoderFallback(boolean enable) {
            delegate.setEnableDecoderFallback(enable);
        }

        @Override
        public Renderer[] createRenderers(
                Handler eventHandler,
                VideoRendererEventListener videoRendererEventListener,
                androidx.media3.exoplayer.audio.AudioRendererEventListener audioRendererEventListener,
                androidx.media3.exoplayer.text.TextOutput textRendererOutput,
                androidx.media3.exoplayer.metadata.MetadataOutput metadataRendererOutput) {

            // 先调用默认的工厂创建渲染器数组（系统硬解等）
            Renderer[] renderers = delegate.createRenderers(
                    eventHandler,
                    videoRendererEventListener,
                    audioRendererEventListener,
                    textRendererOutput,
                    metadataRendererOutput);

            // 如果是 PREFER 模式或 ON 模式，尝试手动添加 ExperimentalFfmpegVideoRenderer
            if (extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    || extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) {
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
                        return renderers;
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
                    Object[] args = new Object[paramTypes.length];
                    for (int i = 0; i < paramTypes.length; i++) {
                        String typeName = paramTypes[i].getName();
                        
                        if (typeName.equals("android.os.Handler") 
                                || typeName.equals("Handler")) {
                            args[i] = eventHandler;
                        } else if (typeName.contains("VideoRendererEventListener")) {
                            args[i] = videoRendererEventListener;
                        } else if (typeName.equals("long")) {
                            args[i] = 5000L; // allowedVideoJoiningTimeMs 默认值
                        } else if (typeName.equals("int")) {
                            args[i] = 0;
                        } else if (typeName.equals("boolean")) {
                            args[i] = false;
                        } else if (typeName.equals("android.content.Context")
                                || typeName.equals("Context")) {
                            args[i] = context;
                        } else if (typeName.equals("float")) {
                            args[i] = 0f;
                        } else {
                            args[i] = null;
                            Log.d(TAG, "  ⚠️ 未知参数类型：" + typeName + "，传 null");
                        }
                    }

                    // 尝试创建实例
                    Renderer renderer = (Renderer) firstConstructor.newInstance(args);

                    // 创建新的数组，把 FFmpeg 渲染器加进去
                    Renderer[] newRenderers = new Renderer[renderers.length + 1];

                    if (extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) {
                        // PREFER 模式：插到最前面，优先使用 FFmpeg 软解
                        newRenderers[0] = renderer;
                        System.arraycopy(renderers, 0, newRenderers, 1, renderers.length);
                        Log.d(TAG, "✅ 已添加 ExperimentalFfmpegVideoRenderer（优先模式）");
                        SettingsActivity.logOperation("【解码器】✅ 手动添加 FFmpeg 视频渲染器（优先模式）");
                    } else {
                        // ON 模式：加到最后，作为备用方案
                        System.arraycopy(renderers, 0, newRenderers, 0, renderers.length);
                        newRenderers[renderers.length] = renderer;
                        Log.d(TAG, "✅ 已添加 ExperimentalFfmpegVideoRenderer（备用模式）");
                        SettingsActivity.logOperation("【解码器】✅ 手动添加 FFmpeg 视频渲染器（备用模式）");
                    }

                    return newRenderers;

                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "❌ ExperimentalFfmpegVideoRenderer 类不存在", e);
                    SettingsActivity.logOperation("【解码器】❌ FFmpeg 视频渲染器类不存在");
                } catch (Exception e) {
                    Log.e(TAG, "❌ 创建 ExperimentalFfmpegVideoRenderer 失败", e);
                    SettingsActivity.logOperation("【解码器】❌ 创建失败：" + e.getMessage());
                    if (e.getCause() != null) {
                        SettingsActivity.logOperation("【解码器】原因：" + e.getCause().getMessage());
                    }
                }
            }

            return renderers;
        }
    }
}
