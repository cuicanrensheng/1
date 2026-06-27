package com.tv.live.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.SettingsActivity;
import com.tv.live.TVPlayerManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 信息展示管理器（核心UI信息展示统一调度类）
 * <p>
 * 核心职责：
 * 1. 统一管理直播场景下所有信息展示类UI组件的显隐和数据更新
 * 2. 封装EPG节目单数据的解析、匹配、进度计算逻辑
 * 3. 提供标准化的信息展示API，降低上层调用复杂度
 * <p>
 * 优化记录：
 * 2026-06-25 - 新增EPG详细日志+模糊匹配机制，解决节目信息匹配不到的排查难题
 * <p>
 * 依赖说明：
 * - 依赖EpgManager获取节目单数据
 * - 依赖TVPlayerManager获取直播流的画质/码率等实时信息
 * - 依赖SettingsActivity的日志接口记录匹配过程
 *
 * @author 开发团队
 * @date 2026-06-25
 */
public class InfoDisplayManager {

    // ====================== 常量定义（统一管理定时延迟/更新间隔）======================
    /** 信息栏自动隐藏延迟时间（毫秒）：3秒 */
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    /** 频道号弹窗自动隐藏延迟时间（毫秒）：3秒 */
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    /** 节目进度更新间隔（毫秒）：1分钟，避免频繁更新导致性能损耗 */
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;

    // ====================== 视图引用（所有需管理的UI组件）======================
    /** 应用上下文（使用ApplicationContext避免内存泄漏） */
    private Context context;
    /** 右上角频道号显示TextView */
    private TextView tvChannelNum;
    /** 底部信息栏根布局（整体显隐控制） */
    private View infoBar;
    /** 信息栏-频道名称显示TextView */
    private TextView tvChannelName;
    /** 信息栏-画质标签（FHD/HD/SD）TextView */
    private TextView tvTagFhd;
    /** 信息栏-音频类型标签TextView */
    private TextView tvTagAudio;
    /** 信息栏-实时码率显示TextView */
    private TextView tvBitrate;
    /** 信息栏-当前节目名称TextView */
    private TextView tvCurrentProgramName;
    /** 信息栏-当前节目时间范围（开始-结束）TextView */
    private TextView tvCurrentTimeRange;
    /** 信息栏-当前节目播放进度条 */
    private ProgressBar progressProgram;
    /** 信息栏-当前节目已播放时长TextView */
    private TextView tvRemainingTime;
    /** 信息栏-下一档节目名称+时间TextView */
    private TextView tvNextProgramName;
    /** 信息栏-下一档节目时间范围TextView（预留，当前合并到名称栏展示） */
    private TextView tvNextTimeRange;

    // ====================== 状态相关（调度/缓存）======================
    /** 主线程Handler，用于UI调度和定时任务（避免子线程操作UI） */
    private Handler handler = new Handler(Looper.getMainLooper());
    /** 当前选中的频道（缓存，用于进度定时更新） */
    private Channel currentChannel;

    /** 信息栏自动隐藏Runnable（抽离为常量避免重复创建） */
    private final Runnable hideInfoBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (infoBar != null) {
                infoBar.setVisibility(View.GONE); // 隐藏信息栏
            }
        }
    };

    /** 频道号自动隐藏Runnable */
    private final Runnable hideChannelNumRunnable = new Runnable() {
        @Override
        public void run() {
            if (tvChannelNum != null) {
                tvChannelNum.setVisibility(View.GONE); // 隐藏频道号
            }
        }
    };

    /** 节目进度定时更新Runnable（每分钟更新一次进度） */
    private final Runnable updateProgramProgressRunnable = new Runnable() {
        @Override
        public void run() {
            // 仅当当前频道有效时更新进度
            if (currentChannel != null) {
                updateEpgInfoInternal(currentChannel);
            }
            // 循环调度：延迟1分钟后再次执行
            handler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };

    // ====================== 构造函数（初始化所有UI引用+基础配置）======================
    /**
     * 构造函数（初始化信息展示管理器）
     * <p>
     * 注意事项：
     * 1. 所有UI组件允许传null（内部做判空处理，避免空指针）
     * 2. 上下文使用ApplicationContext，防止Activity销毁导致的内存泄漏
     * 3. 音频标签默认设置为"立体声"，无需依赖直播流返回数据
     *
     * @param context            上下文（建议传Activity/Fragment的context）
     * @param tvChannelNum       频道号显示TextView
     * @param infoBar            信息栏根布局
     * @param tvChannelName      频道名称TextView
     * @param tvTagFhd           画质标签TextView
     * @param tvTagAudio         音频类型TextView
     * @param tvBitrate          码率显示TextView
     * @param tvCurrentProgramName 当前节目名称TextView
     * @param tvCurrentTimeRange 当前节目时间范围TextView
     * @param progressProgram    当前节目进度条
     * @param tvRemainingTime    已播放时长TextView
     * @param tvNextProgramName  下一档节目名称TextView
     * @param tvNextTimeRange    下一档节目时间范围TextView
     */
    public InfoDisplayManager(
            Context context,
            TextView tvChannelNum,
            View infoBar,
            TextView tvChannelName,
            TextView tvTagFhd,
            TextView tvTagAudio,
            TextView tvBitrate,
            TextView tvCurrentProgramName,
            TextView tvCurrentTimeRange,
            ProgressBar progressProgram,
            TextView tvRemainingTime,
            TextView tvNextProgramName,
            TextView tvNextTimeRange
    ) {
        // 上下文解耦：使用ApplicationContext避免内存泄漏
        this.context = context.getApplicationContext();
        // 赋值所有UI组件引用
        this.tvChannelNum = tvChannelNum;
        this.infoBar = infoBar;
        this.tvChannelName = tvChannelName;
        this.tvTagFhd = tvTagFhd;
        this.tvTagAudio = tvTagAudio;
        this.tvBitrate = tvBitrate;
        this.tvCurrentProgramName = tvCurrentProgramName;
        this.tvCurrentTimeRange = tvCurrentTimeRange;
        this.progressProgram = progressProgram;
        this.tvRemainingTime = tvRemainingTime;
        this.tvNextProgramName = tvNextProgramName;
        this.tvNextTimeRange = tvNextTimeRange;

        // 音频标签固定配置：立体声（无需依赖直播流数据）
        if (tvTagAudio != null) {
            tvTagAudio.setText("立体声");
        }
    }

    // ====================================================================
    // 1. 频道号相关（显隐+数据更新）
    // ====================================================================

    /**
     * 显示频道号（自动延迟隐藏）
     * <p>
     * 逻辑说明：
     * 1. 先清空之前的隐藏任务，避免提前隐藏
     * 2. 设置频道号文本并显示
     * 3. 延迟3秒后自动隐藏
     *
     * @param num 要显示的频道号（如：1、25、108）
     */
    public void showChannelNum(int num) {
        if (tvChannelNum == null) return; // 组件为空直接返回
        tvChannelNum.setText(String.valueOf(num)); // 设置频道号文本
        tvChannelNum.setVisibility(View.VISIBLE); // 显示频道号
        // 重置隐藏定时器：先移除旧任务，再添加新任务
        handler.removeCallbacks(hideChannelNumRunnable);
        handler.postDelayed(hideChannelNumRunnable, CHANNEL_NUM_HIDE_DELAY);
    }

    /**
     * 立即隐藏频道号（手动触发，如切台时）
     */
    public void hideChannelNum() {
        if (tvChannelNum == null) return;
        handler.removeCallbacks(hideChannelNumRunnable); // 移除自动隐藏任务
        tvChannelNum.setVisibility(View.GONE); // 立即隐藏
    }

    // ====================================================================
    // 2. 信息栏相关（显隐+直播信息更新）
    // ====================================================================

    /**
     * 显示底部信息栏（自动填充频道+直播+EPG信息）
     * <p>
     * 核心流程：
     * 1. 校验必要参数（信息栏/频道不能为空）
     * 2. 缓存当前频道（用于后续进度更新）
     * 3. 显示信息栏并重置隐藏定时器
     * 4. 更新频道名称、直播信息、EPG节目信息
     * 5. 启动节目进度定时更新
     *
     * @param channel  当前频道（不能为空，否则不显示）
     * @param liveInfo 直播流信息（包含分辨率、码率等）
     */
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        if (infoBar == null || channel == null) return; // 必要参数为空直接返回

        // 缓存当前频道（用于进度定时更新）
        currentChannel = channel;
        // 显示信息栏
        infoBar.setVisibility(View.VISIBLE);
        // 重置隐藏定时器：先移除旧任务，再添加新任务
        handler.removeCallbacks(hideInfoBarRunnable);
        handler.postDelayed(hideInfoBarRunnable, INFO_BAR_HIDE_DELAY);

        // 更新频道名称
        if (tvChannelName != null) {
            tvChannelName.setText(channel.getName());
        }

        // 更新直播信息（画质/码率）
        updateLiveInfo(liveInfo);
        // 更新EPG节目信息（当前/下一档）
        updateEpgInfoInternal(channel);
        // 启动节目进度定时更新（每分钟更新一次）
        startProgressUpdate();
    }

    /**
     * 立即隐藏信息栏（手动触发）
     */
    public void hideInfoBar() {
        if (infoBar == null) return;
        handler.removeCallbacks(hideInfoBarRunnable); // 移除自动隐藏任务
        infoBar.setVisibility(View.GONE); // 立即隐藏
    }

    /**
     * 更新直播流信息（画质+码率）
     * <p>
     * 逻辑说明：
     * 1. 画质标签：根据分辨率自动计算（FHD/HD/SD/未知）
     * 2. 码率：直接展示直播流返回的原始值
     *
     * @param info 直播流信息（包含resolution分辨率、bitrate码率）
     */
    public void updateLiveInfo(TVPlayerManager.LiveInfo info) {
        if (info == null) return; // 信息为空直接返回

        // 更新画质标签（自动解析分辨率）
        if (tvTagFhd != null) {
            tvTagFhd.setText(calculateQualityTag(info.resolution));
        }
        // 更新实时码率（直接展示原始值）
        if (tvBitrate != null) {
            tvBitrate.setText(info.bitrate);
        }
    }

    // ====================================================================
    // 画质标签计算（根据分辨率自动匹配）
    // ====================================================================

    /**
     * 根据分辨率字符串计算画质标签
     * <p>
     * 匹配规则：
     * - 高度≥1080 → FHD（全高清）
     * - 高度≥720 → HD（高清）
     * - 其他 → SD（标清）
     * - 解析失败 → 返回原始分辨率/未知
     *
     * @param resolution 分辨率字符串（格式如：1920×1080、1280×720）
     * @return 画质标签（FHD/HD/SD/未知/原始分辨率）
     */
    private String calculateQualityTag(String resolution) {
        // 空值处理：返回"未知"
        if (resolution == null || resolution.isEmpty()) {
            return "未知";
        }

        try {
            // 拆分分辨率（按×分割，取高度值）
            String[] parts = resolution.split("×");
            if (parts.length >= 2) {
                int height = Integer.parseInt(parts[1].trim()); // 取高度（第二个值）

                // 按高度匹配画质标签
                if (height >= 1080) {
                    return "FHD";
                } else if (height >= 720) {
                    return "HD";
                } else {
                    return "SD";
                }
            }
        } catch (Exception e) {
            // 解析异常：记录日志，返回原始分辨率
            SettingsActivity.log("【信息栏】解析分辨率失败：" + resolution + "，异常：" + e.getMessage());
        }

        // 格式不匹配：返回原始分辨率
        return resolution;
    }

    // ====================================================================
    // 3. EPG 节目信息相关（对外API+内部核心逻辑）
    // ====================================================================

    /**
     * 对外暴露的EPG信息更新API（供上层主动触发更新）
     *
     * @param channel 要更新的频道（不能为空）
     */
    public void updateEpgInfo(Channel channel) {
        if (channel == null) return;
        currentChannel = channel; // 缓存当前频道
        updateEpgInfoInternal(channel); // 调用内部更新逻辑
    }

    /**
     * EPG信息更新核心逻辑（内部实现，封装所有匹配/计算/展示逻辑）
     * <p>
     * 2026-06-25优化点：
     * 1. 增加全流程详细日志，便于排查匹配失败问题
     * 2. 精确匹配失败后，触发模糊匹配机制
     * <p>
     * 核心流程：
     * 1. 参数校验 → 2. 日志初始化 → 3. EPG数据获取（精确+模糊） → 4. 今日节目筛选 →
     * 5. 节目排序 → 6. 当前/下一档节目匹配 → 7. 节目信息展示 → 8. 异常兜底
     *
     * @param channel 目标频道（不能为空）
     */
    private void updateEpgInfoInternal(Channel channel) {
        // 必要组件校验：当前节目名称TextView为空则无需更新
        if (channel == null || tvCurrentProgramName == null) {
            return;
        }

        try {
            String channelName = channel.getName();
            // 日志：记录匹配开始（便于追踪）
            SettingsActivity.logOperation("【EPG匹配】开始匹配频道：" + channelName);

            // 步骤1：精确匹配EPG节目数据
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);

            // 步骤2：精确匹配失败 → 触发模糊匹配（2026-06-25新增）
            if ((epgList == null || epgList.isEmpty()) && channelName != null) {
                SettingsActivity.logOperation("【EPG匹配】精确匹配失败，尝试模糊匹配...");
                epgList = tryFuzzyMatch(channelName);
            }

            // 步骤3：无节目数据 → 显示兜底文案
            if (epgList == null || epgList.isEmpty()) {
                SettingsActivity.logOperation("【EPG匹配】未找到任何节目数据");
                setEpgEmpty();
                return;
            }

            // 日志：记录找到的节目数量
            SettingsActivity.logOperation("【EPG匹配】找到 " + epgList.size() + " 条节目数据");

            // 步骤4：筛选今日的节目（按星期/今日标签过滤）
            List<Channel.EpgItem> todayEpg = filterTodayPrograms(epgList);
            if (todayEpg.isEmpty()) {
                SettingsActivity.logOperation("【EPG匹配】筛选后今天的节目为空");
                setEpgEmpty();
                return;
            }
            SettingsActivity.logOperation("【EPG匹配】筛选出今天的节目：" + todayEpg.size() + " 条");

            // 步骤5：按节目开始时间排序（确保时间顺序正确）
            sortProgramsByTime(todayEpg);

            // 步骤6：匹配当前正在播放的节目 + 下一档节目
            String now = getNowTimeStr(); // 获取当前时间（HH:mm）
            Channel.EpgItem currentProgram = null; // 当前节目
            Channel.EpgItem nextProgram = null;   // 下一档节目
            int currentIndex = -1;                // 当前节目索引

            // 遍历今日节目，匹配当前时间段的节目
            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String startTime = item.time; // 节目开始时间
                // 节目结束时间：取下一个节目的开始时间，最后一个节目默认23:59
                String endTime = (i + 1 < todayEpg.size()) ? todayEpg.get(i + 1).time : "23:59";

                // 判断当前时间是否在节目时间段内
                if (isTimeInRange(now, startTime, endTime)) {
                    currentProgram = item;
                    currentIndex = i;
                    // 下一档节目：当前索引+1（需判断是否越界）
                    if (i + 1 < todayEpg.size()) {
                        nextProgram = todayEpg.get(i + 1);
                    }
                    break; // 找到后立即退出循环
                }
            }

            // 日志：记录匹配结果
            if (currentProgram != null) {
                SettingsActivity.logOperation("【EPG匹配】当前节目：" + currentProgram.title);
            } else {
                SettingsActivity.logOperation("【EPG匹配】未找到当前播放的节目");
            }

            // 步骤7：更新UI展示（当前节目+下一档节目）
            updateCurrentProgramInfo(currentProgram, currentIndex, todayEpg, now);
            updateNextProgramInfo(nextProgram, currentIndex, todayEpg);

        } catch (Exception e) {
            // 异常处理：记录日志 + 兜底显示
            e.printStackTrace();
            SettingsActivity.logOperation("【EPG匹配】异常：" + e.getMessage());
            setEpgEmpty();
        }
    }

    // ====================================================================
    // 2026-06-25 新增：EPG模糊匹配（解决精确匹配失败问题）
    // ====================================================================

    /**
     * EPG模糊匹配逻辑（精确匹配失败后触发）
     * <p>
     * 匹配策略（优先级从高到低）：
     * 1. 去除所有空格后完全匹配
     * 2. 忽略大小写匹配
     * 3. 包含匹配（频道名包含EPG名 / EPG名包含频道名）
     * <p>
     * 注意：当前为占位实现，需根据EpgManager实际API完善
     *
     * @param channelName 原始频道名（如："CCTV 1"、"湖南卫视"）
     * @return 匹配到的节目列表，匹配失败返回null
     */
    private List<Channel.EpgItem> tryFuzzyMatch(String channelName) {
        // 空值校验
        if (channelName == null || channelName.isEmpty()) {
            return null;
        }

        try {
            // 预处理：去除所有空格 + 转小写（统一匹配规则）
            String cleanChannelName = channelName.replaceAll("\\s+", "").toLowerCase();

            // 【待实现】遍历所有EPG频道名进行模糊匹配
            // 需确认EpgManager是否提供"获取所有频道名"的API，示例逻辑：
            // 1. 获取所有EPG频道名列表：List<String> allEpgChannelNames = EpgManager.getInstance().getAllChannelNames();
            // 2. 遍历列表，按匹配策略筛选：
            //    for (String epgName : allEpgChannelNames) {
            //        String cleanEpgName = epgName.replaceAll("\\s+", "").toLowerCase();
            //        if (cleanChannelName.equals(cleanEpgName) // 去空格完全匹配
            //            || cleanChannelName.contains(cleanEpgName) // 包含匹配
            //            || cleanEpgName.contains(cleanChannelName)) {
            //            return EpgManager.getInstance().getEpg(epgName); // 返回匹配到的节目列表
            //        }
            //    }

            // 临时日志：标记功能未启用
            SettingsActivity.logOperation("【EPG匹配】模糊匹配功能暂未启用（需确认 EpgManager 接口）");
            return null;

        } catch (Exception e) {
            // 异常日志：记录模糊匹配失败原因
            SettingsActivity.logOperation("【EPG匹配】模糊匹配异常：" + e.getMessage());
            return null;
        }
    }

    // ====================================================================
    // EPG辅助方法：筛选今日节目
    // ====================================================================

    /**
     * 从EPG列表中筛选今日的节目
     * <p>
     * 筛选规则：
     * 1. 匹配"今天"标签
     * 2. 匹配当前星期（如：周一、周二...周日）
     *
     * @param epgList 原始EPG节目列表
     * @return 今日节目列表（为空则返回空列表）
     */
    private List<Channel.EpgItem> filterTodayPrograms(List<Channel.EpgItem> epgList) {
        List<Channel.EpgItem> todayEpg = new ArrayList<>();
        // 获取当前星期（Calendar.DAY_OF_WEEK：1=周日，2=周一...7=周六）
        Calendar cal = Calendar.getInstance();
        int weekDay = cal.get(Calendar.DAY_OF_WEEK);
        // 星期映射表（适配EPG数据的星期文案）
        String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String todayWeekDay = weekMap[weekDay - 1];

        // 遍历筛选
        for (Channel.EpgItem item : epgList) {
            // 跳过星期字段为空的节目
            if (item.dayName == null) continue;
            String dayName = item.dayName.trim();
            // 匹配"今天"或当前星期
            if ("今天".equals(dayName) || todayWeekDay.equals(dayName)) {
                todayEpg.add(item);
            }
        }

        return todayEpg;
    }

    // ====================================================================
    // EPG辅助方法：按开始时间排序
    // ====================================================================

    /**
     * 按节目开始时间升序排序（确保节目按时间顺序展示）
     *
     * @param programList 待排序的节目列表
     */
    private void sortProgramsByTime(List<Channel.EpgItem> programList) {
        Collections.sort(programList, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                // 按时间字符串自然排序（HH:mm格式可直接比较）
                return o1.time.compareTo(o2.time);
            }
        });
    }

    // ====================================================================
    // EPG辅助方法：更新当前节目信息UI
    // ====================================================================

    /**
     * 更新当前节目信息到UI
     * <p>
     * 核心逻辑：
     * 1. 节目名称展示
     * 2. 时间范围（开始-结束）展示
     * 3. 播放进度计算（百分比）
     * 4. 已播放时长格式化（分/时+分）
     *
     * @param currentProgram 当前节目（为空则显示兜底文案）
     * @param currentIndex   当前节目索引
     * @param todayEpg       今日节目列表
     * @param now            当前时间（HH:mm）
     */
    private void updateCurrentProgramInfo(Channel.EpgItem currentProgram, int currentIndex,
                                          List<Channel.EpgItem> todayEpg, String now) {
        if (currentProgram != null) {
            // 1. 更新当前节目名称
            tvCurrentProgramName.setText(currentProgram.title);

            // 2. 计算节目时间范围（开始-结束）
            String startTime = currentProgram.time;
            String endTime = (currentIndex + 1 < todayEpg.size()) ? todayEpg.get(currentIndex + 1).time : "23:59";
            if (tvCurrentTimeRange != null) {
                tvCurrentTimeRange.setText(startTime + " - " + endTime);
            }

            // 3. 计算播放进度（毫秒级计算，避免精度丢失）
            long nowMillis = timeToMillis(now);       // 当前时间毫秒
            long startMillis = timeToMillis(startTime); // 节目开始毫秒
            long endMillis = timeToMillis(endTime);   // 节目结束毫秒

            // 仅当结束时间>开始时间时计算进度（避免除以0）
            if (endMillis > startMillis && progressProgram != null) {
                // 进度百分比 = (当前时间-开始时间) / (结束时间-开始时间) * 100
                int progress = (int) ((nowMillis - startMillis) * 100 / (endMillis - startMillis));
                // 进度值边界处理（确保0≤progress≤100）
                progress = Math.max(0, Math.min(100, progress));
                progressProgram.setProgress(progress);

                // 4. 计算已播放时长并格式化
                long playedMs = nowMillis - startMillis; // 已播放毫秒
                int playedMin = (int) (playedMs / 1000 / 60); // 转换为分钟
                if (tvRemainingTime != null) {
                    // 格式化：≥60分钟显示"X时X分"，否则显示"X分钟"
                    if (playedMin >= 60) {
                        int hour = playedMin / 60;
                        int minute = playedMin % 60;
                        tvRemainingTime.setText("已播放" + hour + "时" + minute + "分");
                    } else {
                        tvRemainingTime.setText("已播放" + playedMin + "分钟");
                    }
                }
            }
        } else {
            // 无当前节目：显示兜底文案
            tvCurrentProgramName.setText("暂无节目信息");
            if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
            if (progressProgram != null) progressProgram.setProgress(0);
            if (tvRemainingTime != null) tvRemainingTime.setText("");
        }
    }

    // ====================================================================
    // EPG辅助方法：更新下一档节目信息UI
    // ====================================================================

    /**
     * 更新下一档节目信息到UI
     * <p>
     * 展示规则：
     * 1. 有下一档节目：显示"开始-结束 节目名称"
     * 2. 无下一档节目：显示兜底文案
     * 3. 下一档时间范围TextView置空（合并到名称栏展示）
     *
     * @param nextProgram  下一档节目（为空则显示兜底文案）
     * @param currentIndex 当前节目索引
     * @param todayEpg     今日节目列表
     */
    private void updateNextProgramInfo(Channel.EpgItem nextProgram, int currentIndex,
                                       List<Channel.EpgItem> todayEpg) {
        if (nextProgram != null && tvNextProgramName != null) {
            // 计算下一档节目时间范围
            String nextStart = nextProgram.time;
            String nextEnd = (currentIndex + 2 < todayEpg.size()) ? todayEpg.get(currentIndex + 2).time : "23:59";
            // 拼接展示文案："开始-结束 节目名称"
            String nextFullText = nextStart + " - " + nextEnd + "  " + nextProgram.title;
            tvNextProgramName.setText(nextFullText);
            // 下一档时间范围TextView置空（合并展示）
            if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        } else {
            // 无下一档节目：显示兜底文案
            if (tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
            if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        }
    }

    // ====================================================================
    // EPG辅助方法：兜底显示（无节目数据时）
    // ====================================================================

    /**
     * EPG信息兜底显示（无节目数据/匹配失败时）
     * <p>
     * 逻辑：清空所有EPG相关UI，设置默认文案/空值
     */
    private void setEpgEmpty() {
        if (tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if (tvNextProgramName != null) tvNextProgramName.setText("");
        if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        if (progressProgram != null) progressProgram.setProgress(0);
        if (tvRemainingTime != null) tvRemainingTime.setText("");
    }

    // ====================================================================
    // 4. 节目进度定时更新（启动/停止）
    // ====================================================================

    /**
     * 启动节目进度定时更新（每分钟更新一次）
     * <p>
     * 注意：启动前先移除旧任务，避免重复调度
     */
    public void startProgressUpdate() {
        handler.removeCallbacks(updateProgramProgressRunnable);
        handler.postDelayed(updateProgramProgressRunnable, PROGRAM_PROGRESS_INTERVAL);
    }

    /**
     * 停止节目进度定时更新（如切台/退出直播时）
     */
    public void stopProgressUpdate() {
        handler.removeCallbacks(updateProgramProgressRunnable);
    }

    // ====================================================================
    // 5. 时间工具方法（统一时间处理逻辑）
    // ====================================================================

    /**
     * 获取当前时间字符串（格式：HH:mm，如：19:30）
     *
     * @return 格式化后的当前时间
     */
    private String getNowTimeStr() {
        Calendar cal = Calendar.getInstance();
        return String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), // 24小时制小时
                cal.get(Calendar.MINUTE));     // 分钟
    }

    /**
     * 判断当前时间是否在[开始时间, 结束时间)区间内
     * <p>
     * 注意：区间为左闭右开（包含开始时间，不包含结束时间）
     *
     * @param now   当前时间（HH:mm）
     * @param start 开始时间（HH:mm）
     * @param end   结束时间（HH:mm）
     * @return true=在区间内，false=不在区间内
     */
    private boolean isTimeInRange(String now, String start, String end) {
        try {
            // 空值校验
            if (now == null || start == null || end == null) {
                return false;
            }
            // 格式校验（必须包含":"）
            if (!now.contains(":") || !start.contains(":") || !end.contains(":")) {
                return false;
            }
            // 字符串比较（HH:mm格式可直接按字典序比较）
            return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            // 异常默认返回false
            return false;
        }
    }

    /**
     * 将时间字符串（HH:mm）转换为当天的毫秒数
     * <p>
     * 用途：用于计算时间差（如播放进度、已播放时长）
     *
     * @param timeStr 时间字符串（HH:mm）
     * @return 转换后的毫秒数（转换失败返回0）
     */
    private long timeToMillis(String timeStr) {
        try {
            // 拆分小时和分钟
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());

            // 构建Calendar对象（仅设置小时和分钟，秒/毫秒置0）
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            // 返回毫秒数
            return cal.getTimeInMillis();
        } catch (Exception e) {
            // 转换异常返回0
            return 0;
        }
    }

    // ====================================================================
    // 6. 资源释放（防止内存泄漏）
    // ====================================================================

    /**
     * 释放所有资源（退出直播时调用）
     * <p>
     * 释放逻辑：
     * 1. 移除所有Handler定时任务
     * 2. 清空频道缓存
     * 3. 置空上下文和所有UI引用（断开引用链，便于GC回收）
     */
    public void release() {
        // 移除所有定时任务
        handler.removeCallbacks(hideInfoBarRunnable);
        handler.removeCallbacks(hideChannelNumRunnable);
        handler.removeCallbacks(updateProgramProgressRunnable);

        // 清空缓存和引用
        currentChannel = null;
        context = null;
        tvChannelNum = null;
        infoBar = null;
        tvChannelName = null;
        tvTagFhd = null;
        tvTagAudio = null;
        tvBitrate = null;
        tvCurrentProgramName = null;
        tvCurrentTimeRange = null;
        progressProgram = null;
        tvRemainingTime = null;
        tvNextProgramName = null;
        tvNextTimeRange = null;
    }
}
