package com.tv.live;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
/**
 * 性能优化管理器
 *
 * 【功能】
 * 1. 检测设备性能等级（高端/中端/低端）
 * 2. 根据性能等级自动调整播放器参数
 * 3. 提供统一的性能判断接口，供其他模块调用
 *
 * 【性能等级划分】
 * - HIGH（高端机）：8核以上 + Android 8+ + 2GB 内存以上
 * - MEDIUM（中端机）：4核以上 + Android 6+ + 1GB 内存以上
 * - LOW（低端机）：其他
 *
 * 【使用场景】
 * 1. ResolutionOptimizer 根据性能等级推荐分辨率
 * 2. TVPlayerManager 根据性能等级调整缓冲策略
 * 3. 设置页面根据性能等级显示不同的默认选项
 *
 * 【为什么需要单独的性能检测类？】
 * 因为性能检测涉及多个维度（CPU、内存、系统版本），
 * 如果每个模块都自己检测，代码会重复，而且判断标准不一致。
 * 统一由 PerformanceOptimizer 管理，保证判断标准一致。
 */
public class PerformanceOptimizer {
    private static final String TAG = "PerformanceOptimizer";
    // ====================================================================
    // 性能等级枚举
    // ====================================================================
    /**
     * 设备性能等级
     */
    public enum PerformanceLevel {
        /**
         * 高端机
         * 8核以上 + Android 8+ + 大内存
         */
        HIGH,
        /**
         * 中端机
         * 4核以上 + Android 6+ + 中等内存
         */
        MEDIUM,
        /**
         * 低端机
         * 4核以下 或 Android 5 及以下 或 小内存
         */
        LOW
    }
    // ====================================================================
    // 成员变量
    // ====================================================================
    /**
     * 缓存的性能等级（只检测一次）
     */
    private static PerformanceLevel sCachedLevel = null;
    /**
     * 是否已经初始化过
     */
    private static boolean sInitialized = false;
    // ====================================================================
    // 初始化
    // ====================================================================
    /**
     * 初始化性能优化器
     * 检测设备性能等级并缓存结果
     *
     * @param context 上下文
     */
    public static void init(Context context) {
        if (sInitialized) return;
        sInitialized = true;
        // 检测性能等级
        sCachedLevel = detectPerformanceLevel(context);
        Log.d(TAG, "【性能优化】初始化完成，设备性能等级：" + sCachedLevel);
        SettingsActivity.logOperation("【性能优化】设备性能等级：" + sCachedLevel);
    }
    // ====================================================================
    // 性能等级检测
    // ====================================================================
    /**
     * 获取当前设备的性能等级
     *
     * @return 性能等级
     *
     * 【注意】
     * 如果还没初始化，会自动检测一次。
     * 建议在 Application 或 MainActivity 的 onCreate 中先调用 init()。
     */
    public static PerformanceLevel getCurrentLevel() {
        if (sCachedLevel == null) {
            // 还没初始化，自动检测一次
            sCachedLevel = detectPerformanceLevel(null);
        }
        return sCachedLevel;
    }
    /**
     * 检测设备性能等级
     *
     * @param context 上下文（可为 null）
     * @return 性能等级
     *
     * 【检测维度】
     * 1. CPU 核心数
     * 2. 系统版本
     * 3. 总内存大小
     *
     * 【判断逻辑】
     * - 高端机：8核以上 + Android 8+ + 2GB 内存以上
     * - 中端机：4核以上 + Android 6+ + 1GB 内存以上
     * - 低端机：其他
     *
     * 【为什么用这三个维度？】
     * 1. CPU 核心数：直接影响解码能力
     * 2. 系统版本：影响系统解码器的支持程度和优化程度
     * 3. 内存大小：影响缓冲能力和后台保活
     */
    private static PerformanceLevel detectPerformanceLevel(Context context) {
        // 1. 获取 CPU 核心数
        int cpuCores = getCpuCoreCount();
        // 2. 获取系统版本
        int sdkVersion = Build.VERSION.SDK_INT;
        // 3. 获取总内存
        long totalMemory = getTotalMemory(context);
        Log.d(TAG, "【性能检测】CPU核心数：" + cpuCores 
                + "，系统版本：" + sdkVersion 
                + "，总内存：" + (totalMemory / 1024 / 1024) + "MB");
        // 判断高端机
        if (cpuCores >= 8 
                && sdkVersion >= Build.VERSION_CODES.O 
                && totalMemory >= 2L * 1024 * 1024 * 1024) {
            return PerformanceLevel.HIGH;
        }
        // 判断中端机
        if (cpuCores >= 4 
                && sdkVersion >= Build.VERSION_CODES.M 
                && totalMemory >= 1L * 1024 * 1024 * 1024) {
            return PerformanceLevel.MEDIUM;
        }
        // 低端机
        return PerformanceLevel.LOW;
    }
    // ====================================================================
    // CPU 核心数检测
    // ====================================================================
    /**
     * 获取 CPU 核心数
     *
     * @return CPU 核心数
     *
     * 【检测方法】
     * 优先使用 Runtime.getRuntime().availableProcessors()，
     * 这是 Java 标准 API，最可靠。
     *
     * 【备选方案】
     * 如果 availableProcessors() 返回的结果不准确（某些设备上
     * 可能只返回在线核心数），可以读取 /sys/devices/system/cpu/
     * 目录下的 cpu* 文件夹来统计。
     */
    private static int getCpuCoreCount() {
        // 方法1：使用标准 API
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores > 0) {
            return cores;
        }
        // 方法2：读取系统文件（备选）
        try {
            File cpuDir = new File("/sys/devices/system/cpu/");
            File[] cpuFiles = cpuDir.listFiles();
            if (cpuFiles != null) {
                int count = 0;
                for (File file : cpuFiles) {
                    if (file.getName().matches("cpu\\d+")) {
                        count++;
                    }
                }
                if (count > 0) {
                    return count;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取CPU核心数失败：" + e.getMessage());
        }
        // 默认返回 4（保守估计）
        return 4;
    }
    // ====================================================================
    // 内存大小检测
    // ====================================================================
    /**
     * 获取设备总内存
     *
     * @param context 上下文（可为 null）
     * @return 总内存大小（字节）
     *
     * 【检测方法】
     * 读取 /proc/meminfo 文件中的 MemTotal 字段。
     * 这是 Linux 系统标准方法，所有 Android 设备都支持。
     *
     * 【为什么不用 ActivityManager.getMemoryInfo()？】
     * 因为那个方法需要 Context，而且某些定制 ROM 上不准确。
     * 读取 /proc/meminfo 更直接、更可靠。
     */
    private static long getTotalMemory(Context context) {
        try {
            BufferedReader reader = new BufferedReader(
                    new FileReader("/proc/meminfo"));
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("MemTotal:")) {
                    // 格式：MemTotal:        2048000 kB
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        long kb = Long.parseLong(parts[1]);
                        reader.close();
                        return kb * 1024; // 转成字节
                    }
                }
                line = reader.readLine();
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "读取内存信息失败：" + e.getMessage());
        }
        // 默认返回 1GB（保守估计）
        return 1L * 1024 * 1024 * 1024;
    }
    // ====================================================================
    // 优化建议
    // ====================================================================
    /**
     * 获取针对当前设备的优化建议
     *
     * @return 优化建议文字
     *
     * 【用途】
     * 可以在设置页面显示，告诉用户当前设备适合什么配置。
     */
    public static String getOptimizationSuggestion() {
        PerformanceLevel level = getCurrentLevel();
        switch (level) {
            case HIGH:
                return "高端设备，推荐使用硬解 + 1080P 画质，体验最佳";
            case MEDIUM:
                return "中端设备，推荐使用硬解 + 720P 画质，平衡流畅与清晰";
            case LOW:
            default:
                return "低端设备，推荐使用软解 + 480P 画质，保证流畅播放";
        }
    }
    /**
     * 判断是否为低端机
     *
     * @return true = 低端机
     */
    public static boolean isLowEndDevice() {
        return getCurrentLevel() == PerformanceLevel.LOW;
    }
    /**
     * 判断是否为高端机
     *
     * @return true = 高端机
     */
    public static boolean isHighEndDevice() {
        return getCurrentLevel() == PerformanceLevel.HIGH;
    }
    // ====================================================================
    // 播放器参数优化
    // ====================================================================
    /**
     * 获取推荐的缓冲时长（毫秒）
     *
     * @return 推荐的最大缓冲时长
     *
     * 【优化逻辑】
     * - 高端机：大缓冲（50秒），抗网络波动
     * - 中端机：中等缓冲（30秒），平衡内存和抗波动
     * - 低端机：小缓冲（15秒），节省内存
     */
    public static int getRecommendedBufferMs() {
        PerformanceLevel level = getCurrentLevel();
        switch (level) {
            case HIGH:
                return 50000;   // 50秒
            case MEDIUM:
                return 30000;   // 30秒
            case LOW:
            default:
                return 15000;   // 15秒
        }
    }
    /**
     * 获取推荐的最小缓冲时长（毫秒）
     *
     * @return 推荐的最小缓冲时长
     */
    public static int getRecommendedMinBufferMs() {
        PerformanceLevel level = getCurrentLevel();
        switch (level) {
            case HIGH:
                return 2000;    // 2秒
            case MEDIUM:
                return 1500;    // 1.5秒
            case LOW:
            default:
                return 1000;    // 1秒
        }
    }
    /**
     * 获取推荐的开始播放缓冲时长（毫秒）
     *
     * @return 推荐的开始播放缓冲时长
     *
     * 【优化逻辑】
     * - 高端机：快速出画（300ms）
     * - 中端机：平衡（500ms）
     * - 低端机：稳一点（1000ms）
     */
    public static int getRecommendedBufferForPlaybackMs() {
        PerformanceLevel level = getCurrentLevel();
        switch (level) {
            case HIGH:
                return 300;     // 300ms
            case MEDIUM:
                return 500;     // 500ms
            case LOW:
            default:
                return 1000;    // 1秒
        }
    }
}
