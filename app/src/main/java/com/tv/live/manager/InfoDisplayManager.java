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
 * 信息展示管理器
 *
 * 【职责】
 * 统一管理所有信息展示相关的 UI 组件，包括：
 * 1. 频道号显示（右上角弹出）
 * 2. 底部信息栏（频道名、画质、音频、码率、节目信息等）
 * 3. EPG 节目单数据计算和展示（当前节目、下一个节目、进度、剩余时间）
 *
 * 【2026-06-25 优化：增加 EPG 详细日志 + 模糊匹配】
 * 【优化原因】
 * 信息栏弹出时经常看不到节目信息，排查困难。
 * 增加详细日志后，可以在设置页面的操作日志里看到匹配过程。
 */
public class InfoDisplayManager {

    // ====================== 常量 ======================
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;

    // ====================== 视图引用 ======================
    private Context context;
    private TextView tvChannelNum;
    private View infoBar;
    private TextView tvChannelName;
    private TextView tvTagFhd;
    private TextView tvTagAudio;
    private TextView tvBitrate;
    private TextView tvCurrentProgramName;
    private TextView tvCurrentTimeRange;
    private ProgressBar progressProgram;
    private TextView tvRemainingTime;
    private TextView tvNextProgramName;
    private TextView tvNextTimeRange;

    // ====================== 状态相关 ======================
    private Handler handler = new Handler(Looper.getMainLooper());
    private Channel currentChannel;

    private final Runnable hideInfoBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (infoBar != null) {
                infoBar.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable hideChannelNumRunnable = new Runnable() {
        @Override
        public void run() {
            if (tvChannelNum != null) {
                tvChannelNum.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable updateProgramProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentChannel != null) {
                updateEpgInfoInternal(currentChannel);
            }
            handler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };

    // ====================== 构造函数 ======================
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
    this.context = context.getApplicationContext();
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

    // 立体声标签固定展示，无需直播流返回音频字段
    if (tvTagAudio != null) {
        tvTagAudio.setText("立体声");
    }
}
  
    // ====================================================================
    // 1. 频道号相关
    // ====================================================================
    public void showChannelNum(int num) {
        if (tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideChannelNumRunnable);
        handler.postDelayed(hideChannelNumRunnable, CHANNEL_NUM_HIDE_DELAY);
    }

    public void hideChannelNum() {
        if (tvChannelNum == null) return;
        handler.removeCallbacks(hideChannelNumRunnable);
        tvChannelNum.setVisibility(View.GONE);
    }

    // ====================================================================
    // 2. 信息栏相关
    // ====================================================================
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        if (infoBar == null || channel == null) return;

        currentChannel = channel;
        infoBar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfoBarRunnable);
        handler.postDelayed(hideInfoBarRunnable, INFO_BAR_HIDE_DELAY);

        if (tvChannelName != null) {
            tvChannelName.setText(channel.getName());
        }

        updateLiveInfo(liveInfo);
        updateEpgInfoInternal(channel);
        startProgressUpdate();
    }

    public void hideInfoBar() {
        if (infoBar == null) return;
        handler.removeCallbacks(hideInfoBarRunnable);
        infoBar.setVisibility(View.GONE);
    }
public void updateLiveInfo(TVPlayerManager.LiveInfo info) {
    if (info == null) return;
    // 画质标签：自动解析直播流分辨率
    if (tvTagFhd != null) {
        tvTagFhd.setText(calculateQualityTag(info.resolution));
    }
    // 实时码率：自动从直播流 LiveInfo.bitrate 获取
    if (tvBitrate != null) tvBitrate.setText(info.bitrate);
}

    // ====================================================================
    // 根据分辨率计算画质标签
    // ====================================================================
    private String calculateQualityTag(String resolution) {
        if (resolution == null || resolution.isEmpty()) {
            return "未知";
        }

        try {
            String[] parts = resolution.split("×");
            if (parts.length >= 2) {
                int height = Integer.parseInt(parts[1].trim());

                if (height >= 1080) {
                    return "FHD";
                } else if (height >= 720) {
                    return "HD";
                } else {
                    return "SD";
                }
            }
        } catch (Exception e) {
            SettingsActivity.log("【信息栏】解析分辨率失败：" + resolution);
        }

        return resolution;
    }

    // ====================================================================
    // 3. EPG 节目信息相关
    // ====================================================================
    public void updateEpgInfo(Channel channel) {
        if (channel == null) return;
        currentChannel = channel;
        updateEpgInfoInternal(channel);
    }

    /**
     * 更新 EPG 节目信息（内部实现）
     *
     * 【2026-06-25 优化：增加详细日志 + 模糊匹配】
     * 【优化说明】
     * 1. 增加详细日志，方便排查为什么匹配不到节目
     * 2. 精确匹配失败后，尝试模糊匹配（去掉空格）
     */
    private void updateEpgInfoInternal(Channel channel) {
        if (channel == null || tvCurrentProgramName == null) {
            return;
        }

        try {
            String channelName = channel.getName();
            SettingsActivity.logOperation("【EPG匹配】开始匹配频道：" + channelName);

            // 从 EpgManager 获取该频道的所有节目
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);

            // ? 2026-06-25 新增：精确匹配失败后，尝试模糊匹配
            if ((epgList == null || epgList.isEmpty()) && channelName != null) {
                SettingsActivity.logOperation("【EPG匹配】精确匹配失败，尝试模糊匹配...");
                epgList = tryFuzzyMatch(channelName);
            }

            if (epgList == null || epgList.isEmpty()) {
                SettingsActivity.logOperation("【EPG匹配】? 未找到任何节目数据");
                setEpgEmpty();
                return;
            }

            SettingsActivity.logOperation("【EPG匹配】? 找到 " + epgList.size() + " 条节目数据");

            // 筛选今天的节目
            List<Channel.EpgItem> todayEpg = filterTodayPrograms(epgList);
            if (todayEpg.isEmpty()) {
                SettingsActivity.logOperation("【EPG匹配】? 筛选后今天的节目为空");
                setEpgEmpty();
                return;
            }

            SettingsActivity.logOperation("【EPG匹配】筛选出今天的节目：" + todayEpg.size() + " 条");

            // 按开始时间排序
            sortProgramsByTime(todayEpg);

            // 找到当前正在播放的节目
            String now = getNowTimeStr();
            Channel.EpgItem currentProgram = null;
            Channel.EpgItem nextProgram = null;
            int currentIndex = -1;

            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String startTime = item.time;
                String endTime;

                if (i + 1 < todayEpg.size()) {
                    endTime = todayEpg.get(i + 1).time;
                } else {
                    endTime = "23:59";
                }

                if (isTimeInRange(now, startTime, endTime)) {
                    currentProgram = item;
                    currentIndex = i;
                    if (i + 1 < todayEpg.size()) {
                        nextProgram = todayEpg.get(i + 1);
                    }
                    break;
                }
            }

            if (currentProgram != null) {
                SettingsActivity.logOperation("【EPG匹配】? 当前节目：" + currentProgram.title);
            } else {
                SettingsActivity.logOperation("【EPG匹配】? 未找到当前播放的节目");
            }

            updateCurrentProgramInfo(currentProgram, currentIndex, todayEpg, now);
            updateNextProgramInfo(nextProgram, currentIndex, todayEpg);

        } catch (Exception e) {
            e.printStackTrace();
            SettingsActivity.logOperation("【EPG匹配】异常：" + e.getMessage());
            setEpgEmpty();
        }
    }

    // ====================================================================
    // ? 2026-06-25 新增：模糊匹配
    // ====================================================================
    /**
     * 尝试模糊匹配 EPG 节目
     *
     * 【匹配策略】
     * 1. 去掉所有空格后匹配
     * 2. 忽略大小写
     * 3. 包含匹配（频道名包含 EPG 名，或 EPG 名包含频道名）
     *
     * @param channelName 频道名
     * @return 匹配到的节目列表，匹配失败返回 null
     */
    private List<Channel.EpgItem> tryFuzzyMatch(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return null;
        }

        try {
            // 去掉所有空格，转小写
            String cleanChannelName = channelName.replaceAll("\\s+", "").toLowerCase();

            // 遍历所有 EPG 频道名，尝试模糊匹配
            // 注意：这里只是示例，实际需要根据 EpgManager 的 API 调整
            // 如果 EpgManager 没有提供获取所有频道名的方法，可以先注释掉
            // 等确认 API 后再启用

            // 临时返回 null，等确认 EpgManager 接口后再实现
            SettingsActivity.logOperation("【EPG匹配】模糊匹配功能暂未启用（需确认 EpgManager 接口）");
            return null;

        } catch (Exception e) {
            SettingsActivity.logOperation("【EPG匹配】模糊匹配异常：" + e.getMessage());
            return null;
        }
    }

    // 筛选今天的节目
    private List<Channel.EpgItem> filterTodayPrograms(List<Channel.EpgItem> epgList) {
        List<Channel.EpgItem> todayEpg = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int w = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String todayWeekDay = weekMap[w - 1];

        for (Channel.EpgItem item : epgList) {
            if (item.dayName == null) continue;
            String dayName = item.dayName.trim();
            if ("今天".equals(dayName) || todayWeekDay.equals(dayName)) {
                todayEpg.add(item);
            }
        }

        return todayEpg;
    }

    // 按开始时间排序
    private void sortProgramsByTime(List<Channel.EpgItem> programList) {
        Collections.sort(programList, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                return o1.time.compareTo(o2.time);
            }
        });
    }

    // 更新当前节目信息
private void updateCurrentProgramInfo(Channel.EpgItem currentProgram, int currentIndex,
                                      List<Channel.EpgItem> todayEpg, String now) {
    if (currentProgram != null) {
        // 当前节目名（EPG接口自动获取）
        tvCurrentProgramName.setText(currentProgram.title);

        // 自动从EPG读取节目起止时间
        String startTime = currentProgram.time;
        String endTime = (currentIndex + 1 < todayEpg.size()) ? todayEpg.get(currentIndex + 1).time : "23:59";
        if (tvCurrentTimeRange != null) {
            tvCurrentTimeRange.setText(startTime + " - " + endTime);
        }

        long nowMillis = timeToMillis(now);
        long startMillis = timeToMillis(startTime);
        long endMillis = timeToMillis(endTime);

        if (endMillis > startMillis && progressProgram != null) {
            // 自动计算播放进度百分比 (当前-开播)/(结束-开播)*100
            int progress = (int) ((nowMillis - startMillis) * 100 / (endMillis - startMillis));
            progress = Math.max(0, Math.min(100, progress));
            progressProgram.setProgress(progress);

            // 毫秒自动格式化已播放时长
            long playedMs = nowMillis - startMillis;
            int playedMin = (int) (playedMs / 1000 / 60);
            if (tvRemainingTime != null) {
                if (playedMin >= 60) {
                    int h = playedMin / 60;
                    int m = playedMin % 60;
                    tvRemainingTime.setText("已播放" + h + "时" + m + "分");
                } else {
                    tvRemainingTime.setText("已播放" + playedMin + "分钟");
                }
            }
        }
    } else {
        tvCurrentProgramName.setText("暂无节目信息");
        if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if (progressProgram != null) progressProgram.setProgress(0);
        if (tvRemainingTime != null) tvRemainingTime.setText("");
    }
}

    // 更新下一个节目信息
private void updateNextProgramInfo(Channel.EpgItem nextProgram, int currentIndex,
                                   List<Channel.EpgItem> todayEpg) {
    if (nextProgram != null && tvNextProgramName != null) {
        // 自动读取下一档EPG时间
        String nextStart = nextProgram.time;
        String nextEnd = (currentIndex + 2 < todayEpg.size()) ? todayEpg.get(currentIndex + 2).time : "23:59";
        // 拼接格式：时段 + 节目名称，全部自动从EPG获取
        String nextFullText = nextStart + " - " + nextEnd + "  " + nextProgram.title;
        tvNextProgramName.setText(nextFullText);
        // 单独时间控件清空，合并展示无需分开
        if (tvNextTimeRange != null) tvNextTimeRange.setText("");
    } else {
        if (tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
        if (tvNextTimeRange != null) tvNextTimeRange.setText("");
    }
}

    // 设置 EPG 为空的兜底显示
    private void setEpgEmpty() {
        if (tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if (tvNextProgramName != null) tvNextProgramName.setText("");
        if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        if (progressProgram != null) progressProgram.setProgress(0);
        if (tvRemainingTime != null) tvRemainingTime.setText("");
    }

    // ====================================================================
    // 4. 节目进度定时更新
    // ====================================================================
    public void startProgressUpdate() {
        handler.removeCallbacks(updateProgramProgressRunnable);
        handler.postDelayed(updateProgramProgressRunnable, PROGRAM_PROGRESS_INTERVAL);
    }

    public void stopProgressUpdate() {
        handler.removeCallbacks(updateProgramProgressRunnable);
    }

    // ====================================================================
    // 5. 时间工具方法
    // ====================================================================
    private String getNowTimeStr() {
        Calendar cal = Calendar.getInstance();
        return String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE));
    }

    private boolean isTimeInRange(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) {
                return false;
            }
            if (!now.contains(":") || !start.contains(":") || !end.contains(":")) {
                return false;
            }
            return now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    private long timeToMillis(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    // ====================================================================
    // 6. 资源释放
    // ====================================================================
    public void release() {
        handler.removeCallbacks(hideInfoBarRunnable);
        handler.removeCallbacks(hideChannelNumRunnable);
        handler.removeCallbacks(updateProgramProgressRunnable);

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
