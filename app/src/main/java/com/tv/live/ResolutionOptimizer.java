package com.tv.live;

import android.content.Context;
import android.util.Log;

import androidx.media3.common.VideoSize;

/**
 * 分辨率自适应优化器
 *
 * 【功能】
 * 1. 根据设备性能等级，推荐合适的目标分辨率
 * 2. 检测当前播放视频的实际分辨率
 * 3. 如果分辨率过高，自动建议切换到更低码率
 * 4. 低端机自动降低分辨率，减少解码压力
 *
 * 【分辨率等级】
 * - ULTRA_HD (4K)：3840×2160 及以上
 * - FULL_HD (1080P)：1920×1080
 * - HD (720P)：1280×720
 * - SD (480P)：720×480
 * - LD (360P)：640×360 及以下
 *
 * 【使用场景】
 * 1. 设备性能差 → 自动选择低分辨率源，减少解码压力
 * 2. 网络差 → 自动选择低码率源，减少卡顿
 * 3. 用户手动选择 → 记住用户偏好
 *
 * 【集成方式】
 * 1. 在 TVPlayerManager 的 onVideoSizeChanged 中调用
 * 2. 在频道切换时检查是否有更低码率的源
 * 3. 配合 ChannelPlayManager 实现自动切换
 */
public class ResolutionOptimizer {
    private static final String TAG = "ResolutionOptimizer";

    // ====================================================================
    // 分辨率等级枚举
    // ====================================================================
    /**
     * 分辨率等级
     */
    public enum ResolutionLevel {
        /**
         * 超高清（4K）
         * 3840×2160 及以上
         */
        ULTRA_HD(3840, 2160, "4K"),
        /**
         * 全高清（1080P）
         * 1920×1080
         */
        FULL_HD(1920, 1080, "1080P"),
        /**
         * 高清（720P）
         * 1280×720
         */
        HD(1280, 720, "720P"),
        /**
         * 标清（480P）
         * 720×480
         */
        SD(720, 480, "480P"),
        /**
         * 流畅（360P）
         * 640×360 及以下
         */
        LD(640, 360, "360P");

        public final int width;
        public final int height;
        public final String displayName;

        ResolutionLevel(int width, int height, String displayName) {
            this.width = width;
            this.height = height;
            this.displayName = displayName;
        }
    }

    // ====================================================================
    // 成员变量
    // ====================================================================
    /**
     * 当前视频分辨率
     */
    private static VideoSize sCurrentVideoSize = null;

    /**
     * 目标分辨率等级（用户或系统推荐的）
     */
    private static ResolutionLevel sTargetResolution = ResolutionLevel.HD;

    /**
     * 是否启用自动分辨率调整
     */
    private static boolean sAutoResolutionEnabled = true;

    /**
     * 分辨率过高的回调
     */
    public interface OnResolutionTooHighListener {
        /**
         * 当检测到视频分辨率过高时回调
         *
         * @param currentLevel 当前视频分辨率等级
         * @param recommendedLevel 推荐的分辨率等级
         * @param currentWidth 当前宽度
         * @param currentHeight 当前高度
         */
        void onResolutionTooHigh(ResolutionLevel currentLevel, 
                                  ResolutionLevel recommendedLevel,
                                  int currentWidth, 
                                  int currentHeight);
    }

    /**
     * 分辨率过高监听器
     */
    private static OnResolutionTooHighListener sListener;

    // ====================================================================
    // 初始化
    // ====================================================================
    /**
     * 初始化分辨率优化器
     * 根据设备性能等级设置推荐的目标分辨率
     *
     * @param context 上下文
     */
    public static void init(Context context) {
        // 根据性能等级设置推荐分辨率
        PerformanceOptimizer.PerformanceLevel perfLevel = PerformanceOptimizer.getCurrentLevel();
        sTargetResolution = getRecommendedResolution(perfLevel);

        Log.d(TAG, "【分辨率优化】初始化完成，推荐分辨率：" + sTargetResolution.displayName);
        SettingsActivity.logOperation("【分辨率优化】初始化，推荐分辨率：" + sTargetResolution.displayName);
    }

    // ====================================================================
    // 推荐分辨率计算
    // ====================================================================
    /**
     * 根据设备性能等级获取推荐的分辨率
     *
     * @param perfLevel 性能等级
     * @return 推荐的分辨率等级
     */
    public static ResolutionLevel getRecommendedResolution(
            PerformanceOptimizer.PerformanceLevel perfLevel) {
        switch (perfLevel) {
            case LOW:
                // 低端机：推荐 480P，解码压力小
                // 很多低端电视硬解 720P 都卡
                return ResolutionLevel.SD;
            case MEDIUM:
                // 中端机：推荐 720P，平衡清晰度和性能
                return ResolutionLevel.HD;
            case HIGH:
            default:
                // 高端机：推荐 1080P，享受最佳画质
                return ResolutionLevel.FULL_HD;
        }
    }

    /**
     * 根据网络状态获取推荐的分辨率
     *
     * @param networkSpeedKbps 网络速度（kbps）
     * @return 推荐的分辨率等级
     */
    public static ResolutionLevel getRecommendedResolutionByNetwork(int networkSpeedKbps) {
        if (networkSpeedKbps >= 8000) {
            // 8Mbps 以上：1080P
            return ResolutionLevel.FULL_HD;
        } else if (networkSpeedKbps >= 4000) {
            // 4-8Mbps：720P
            return ResolutionLevel.HD;
        } else if (networkSpeedKbps >= 2000) {
            // 2-4Mbps：480P
            return ResolutionLevel.SD;
        } else {
            // 2Mbps 以下：360P
            return ResolutionLevel.LD;
        }
    }

    // ====================================================================
    // 分辨率检测与判断
    // ====================================================================
    /**
     * 更新当前视频分辨率
     * 在 Player.Listener 的 onVideoSizeChanged 中调用
     *
     * @param videoSize 视频尺寸
     */
    public static void updateCurrentResolution(VideoSize videoSize) {
        if (videoSize == null) return;
        sCurrentVideoSize = videoSize;

        int width = videoSize.width;
        int height = videoSize.height;

        Log.d(TAG, "【分辨率优化】当前视频分辨率：" + width + "×" + height);

        // 如果启用了自动调整，检查分辨率是否过高
        if (sAutoResolutionEnabled && sListener != null) {
            ResolutionLevel currentLevel = getResolutionLevel(width, height);
            if (isResolutionTooHigh(currentLevel, sTargetResolution)) {
                Log.w(TAG, "【分辨率优化】当前分辨率过高！当前：" 
                        + currentLevel.displayName 
                        + "，推荐：" + sTargetResolution.displayName);
                SettingsActivity.logOperation("【分辨率优化】检测到分辨率过高（"
                        + currentLevel.displayName + "），推荐：" 
                        + sTargetResolution.displayName);

                // 回调通知外部
                sListener.onResolutionTooHigh(currentLevel, sTargetResolution,
                        width, height);
            }
        }
    }

    /**
     * 判断当前分辨率是否过高（超过推荐值）
     *
     * @param current 当前分辨率等级
     * @param target 目标分辨率等级
     * @return true = 过高
     */
    public static boolean isResolutionTooHigh(ResolutionLevel current, ResolutionLevel target) {
        // 等级值越小，分辨率越高
        // ULTRA_HD = 0, FULL_HD = 1, HD = 2, SD = 3, LD = 4
        return current.ordinal() < target.ordinal();
    }

    /**
     * 根据宽高获取分辨率等级
     *
     * @param width 宽度
     * @param height 高度
     * @return 分辨率等级
     */
    public static ResolutionLevel getResolutionLevel(int width, int height) {
        // 取较小的边作为判断依据（兼容横屏竖屏）
        int minSide = Math.min(width, height);

        if (minSide >= 2160) {
            return ResolutionLevel.ULTRA_HD;
        } else if (minSide >= 1080) {
            return ResolutionLevel.FULL_HD;
        } else if (minSide >= 720) {
            return ResolutionLevel.HD;
        } else if (minSide >= 480) {
            return ResolutionLevel.SD;
        } else {
            return ResolutionLevel.LD;
        }
    }

    // ====================================================================
    // 码率估算
    // ====================================================================
    /**
     * 根据分辨率估算所需的码率
     *
     * @param level 分辨率等级
     * @return 估算码率（kbps）
     */
    public static int estimateBitrate(ResolutionLevel level) {
        switch (level) {
            case ULTRA_HD:
                return 16000; // 16Mbps
            case FULL_HD:
                return 8000;  // 8Mbps
            case HD:
                return 4000;  // 4Mbps
            case SD:
                return 2000;  // 2Mbps
            case LD:
            default:
                return 1000;  // 1Mbps
        }
    }

    /**
     * 根据实际码率估算分辨率等级
     *
     * @param bitrateKbps 码率（kbps）
     * @return 估算的分辨率等级
     */
    public static ResolutionLevel estimateResolutionFromBitrate(int bitrateKbps) {
        if (bitrateKbps >= 12000) {
            return ResolutionLevel.ULTRA_HD;
        } else if (bitrateKbps >= 6000) {
            return ResolutionLevel.FULL_HD;
        } else if (bitrateKbps >= 3000) {
            return ResolutionLevel.HD;
        } else if (bitrateKbps >= 1500) {
            return ResolutionLevel.SD;
        } else {
            return ResolutionLevel.LD;
        }
    }

    // ====================================================================
    // 多码率源选择
    // ====================================================================
    /**
     * 从多个源中选择最适合当前设备性能的源
     *
     * 【使用场景】
     * 如果同一个频道有多个清晰度的源（比如高清、标清），
     * 可以调用这个方法自动选择最合适的。
     *
     * @param sources 源列表，每个元素是 [url, width, height] 或 [url, bitrate]
     * @param perfLevel 设备性能等级
     * @return 选中的源索引，-1 表示没有合适的
     */
    public static int selectBestSource(Object[] sources, 
                                        PerformanceOptimizer.PerformanceLevel perfLevel) {
        if (sources == null || sources.length == 0) return -1;

        ResolutionLevel targetLevel = getRecommendedResolution(perfLevel);
        int targetOrdinal = targetLevel.ordinal();

        int bestIndex = -1;
        int bestDiff = Integer.MAX_VALUE;

        for (int i = 0; i < sources.length; i++) {
            Object source = sources[i];
            ResolutionLevel sourceLevel = null;

            // 支持两种格式：int[]（宽高）或 Integer（码率）
            if (source instanceof int[]) {
                int[] wh = (int[]) source;
                if (wh.length >= 2) {
                    sourceLevel = getResolutionLevel(wh[0], wh[1]);
                }
            } else if (source instanceof Integer) {
                int bitrate = (Integer) source;
                sourceLevel = estimateResolutionFromBitrate(bitrate);
            }

            if (sourceLevel != null) {
                int diff = Math.abs(sourceLevel.ordinal() - targetOrdinal);
                // 优先选择等于或低于目标分辨率的（不超过设备能力）
                if (sourceLevel.ordinal() >= targetOrdinal && diff < bestDiff) {
                    bestDiff = diff;
                    bestIndex = i;
                }
            }
        }

        // 如果没有找到等于或低于目标的，就选最接近的
        if (bestIndex == -1) {
            for (int i = 0; i < sources.length; i++) {
                Object source = sources[i];
                ResolutionLevel sourceLevel = null;

                if (source instanceof int[]) {
                    int[] wh = (int[]) source;
                    if (wh.length >= 2) {
                        sourceLevel = getResolutionLevel(wh[0], wh[1]);
                    }
                } else if (source instanceof Integer) {
                    int bitrate = (Integer) source;
                    sourceLevel = estimateResolutionFromBitrate(bitrate);
                }

                if (sourceLevel != null) {
                    int diff = Math.abs(sourceLevel.ordinal() - targetOrdinal);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestIndex = i;
                    }
                }
            }
        }

        return bestIndex;
    }

    // ====================================================================
    // 解码压力评估
    // ====================================================================
    /**
     * 评估当前分辨率对设备的解码压力
     *
     * @param context 上下文
     * @param width 视频宽度
     * @param height 视频高度
     * @return 压力等级（0-100，越高压力越大）
     */
    public static int evaluateDecodePressure(Context context, int width, int height) {
        PerformanceOptimizer.PerformanceLevel perfLevel = PerformanceOptimizer.getCurrentLevel();
        ResolutionLevel videoLevel = getResolutionLevel(width, height);
        ResolutionLevel recommendedLevel = getRecommendedResolution(perfLevel);

        // 计算压力值
        int pressure = 0;

        // 分辨率差距越大，压力越高
        int levelDiff = recommendedLevel.ordinal() - videoLevel.ordinal();
        pressure += Math.abs(levelDiff) * 25; // 每差一级 +25 分

        // 低端机额外加压力
        if (perfLevel == PerformanceOptimizer.PerformanceLevel.LOW) {
            pressure += 20;
        }

        // 超过 1080P 额外加压力（很多设备硬解 4K 有问题）
        if (videoLevel == ResolutionLevel.ULTRA_HD) {
            pressure += 20;
        }

        // 限制在 0-100
        return Math.min(100, Math.max(0, pressure));
    }

    /**
     * 获取解码压力的文字描述
     *
     * @param pressure 压力值（0-100）
     * @return 描述文字
     */
    public static String getPressureDescription(int pressure) {
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
    // Getter / Setter
    // ====================================================================
    /**
     * 获取当前视频分辨率
     */
    public static VideoSize getCurrentVideoSize() {
        return sCurrentVideoSize;
    }

    /**
     * 获取目标分辨率等级
     */
    public static ResolutionLevel getTargetResolution() {
        return sTargetResolution;
    }

    /**
     * 设置目标分辨率等级
     *
     * @param resolution 目标分辨率
     */
    public static void setTargetResolution(ResolutionLevel resolution) {
        sTargetResolution = resolution;
        Log.d(TAG, "【分辨率优化】目标分辨率设置为：" + resolution.displayName);
        SettingsActivity.logOperation("【分辨率优化】目标分辨率设置为：" + resolution.displayName);
    }

    /**
     * 是否启用自动分辨率调整
     */
    public static boolean isAutoResolutionEnabled() {
        return sAutoResolutionEnabled;
    }

    /**
     * 设置是否启用自动分辨率调整
     */
    public static void setAutoResolutionEnabled(boolean enabled) {
        sAutoResolutionEnabled = enabled;
    }

    /**
     * 设置分辨率过高监听器
     */
    public static void setOnResolutionTooHighListener(OnResolutionTooHighListener listener) {
        sListener = listener;
    }

    /**
     * 获取当前分辨率等级的显示名称
     */
    public static String getCurrentResolutionDisplayName() {
        if (sCurrentVideoSize == null) {
            return "未知";
        }
        ResolutionLevel level = getResolutionLevel(
                sCurrentVideoSize.width, 
                sCurrentVideoSize.height);
        return level.displayName + " (" + sCurrentVideoSize.width 
                + "×" + sCurrentVideoSize.height + ")";
    }
}
