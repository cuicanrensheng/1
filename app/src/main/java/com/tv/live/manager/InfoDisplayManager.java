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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 信息展示管理器【内存泄漏修复完整版】
 */
public class InfoDisplayManager {
    // 定时延时常量
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;

    // 修复：弱引用上下文，移除强Context
    private WeakReference<Context> contextRef;

    // UI控件
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

    // EPG缓存
    private Channel.EpgItem lastCurrItem;
    private Channel.EpgItem lastNextItem;
    private Channel currentPlayChannel;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========== 静态弱引用Runnable，彻底消除匿名内部类泄漏 ==========
    private static class HideInfoBarRunnable implements Runnable {
        private final WeakReference<InfoDisplayManager> mgrRef;
        public HideInfoBarRunnable(InfoDisplayManager mgr) {
            this.mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void run() {
            InfoDisplayManager manager = mgr.get();
            if (manager != null && manager.infoBar != null) {
                manager.infoBar.setVisibility(View.GONE);
            }
        }
    }
    private final Runnable hideInfoBarTask = new HideInfoBarRunnable(this);

    private static class HideChannelNumRunnable implements Runnable {
        private final WeakReference<InfoDisplayManager> mgrRef;
        public HideChannelNumRunnable(InfoDisplayManager mgr) {
            this.mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void run() {
            InfoDisplayManager manager = mgr.get();
            if (manager != null && manager.tvChannelNum != null) {
                manager.tvChannelNum.setVisibility(View.GONE);
            }
        }
    }
    private final Runnable hideChannelNumTask = new HideChannelNumRunnable(this);

    private static class RefreshProgressRunnable implements Runnable {
        private final WeakReference<InfoDisplayManager> mgrRef;
        public RefreshProgressRunnable(InfoDisplayManager mgr) {
            this.mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void run() {
            InfoDisplayManager manager = mgr.get();
            if (manager == null) return;
            if (manager.currentPlayChannel != null) {
                manager.updateEpgInternal(manager.currentPlayChannel);
            }
            manager.mainHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    }
    private final Runnable refreshProgressTask = new RefreshProgressRunnable(this);

    // ========== 构造：弱引用包装ApplicationContext ==========
    public InfoDisplayManager(Context context,
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
                              TextView tvNextTimeRange) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
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
        this.tvNextProgramName = tvNextProgram;
        this.tvNextTimeRange = tvNextTimeRange;

        if (tvTagAudio != null) {
            tvTagAudio.setText("立体声");
        }
    }

    // 统一安全获取上下文
    private Context getContext() {
        return contextRef != null ? contextRef.get() : null;
    }

    // ========== 频道数字显示 ==========
    public void showChannelNum(int num) {
        if (tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.postDelayed(hideChannelNumTask, CHANNEL_NUM_HIDE_DELAY);
    }

    public void hideChannelNum() {
        if (tvChannelNum == null) return;
        mainHandler.removeCallbacks(hideChannelNumTask);
        tvChannelNum.setVisibility(View.GONE);
    }

    // ========== 底部信息栏 ==========
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        if (infoBar == null || channel == null) return;
        currentPlayChannel = channel;
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.postDelayed(hideInfoBarTask, INFO_BAR_HIDE_DELAY);

        if (tvChannelName != null) tvChannelName.setText(channel.getName());
        updateLiveInfo(liveInfo);
        updateEpgInternal(channel);
        startProgressLoop();
    }

    public void hideInfoBar() {
        if (infoBar == null) return;
        mainHandler.removeCallbacks(hideInfoBarTask);
        infoBar.setVisibility(View.GONE);
    }

    public void updateLiveInfo(TVPlayerManager.LiveInfo info) {
        if (info == null) return;
        if (tvTagFhd != null) {
            tvTagFhd.setText(parseQualityText(info.resolution));
        }
        if (tvBitrate != null) {
            tvBitrate.setText(info.bitrate);
        }
    }

    private String parseQualityText(String resolution) {
        if (resolution == null || resolution.isEmpty()) return "未知";
        try {
            String[] split = resolution.split("×");
            if (split.length >= 2) {
                int height = Integer.parseInt(split[1].trim());
                if (height >= 1080) return "FHD";
                else if (height >= 720) return "HD";
                else return "SD";
            }
        } catch (Exception e) {
            SettingsActivity.log("【分辨率解析异常】" + resolution + " err:" + e.getMessage());
        }
        return resolution;
    }

    // ========== EPG更新逻辑 ==========
    public void updateEpgInfo(Channel channel) {
        if (channel == null) return;
        currentPlayChannel = channel;
        updateEpgInternal(channel);
    }

    private void updateEpgInternal(Channel channel) {
        if (channel == null || tvCurrentProgramName == null) return;
        String channelName = channel.getName();
        try {
            SettingsActivity.logOperation("【EPG匹配】开始匹配频道:" + channelName);
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);
            if ((epgList == null || epgList.isEmpty()) && channelName != null) {
                SettingsActivity.logOperation("【EPG匹配】精确匹配为空，执行模糊匹配");
                epgList = fuzzyMatchEpg(channelName);
            }
            if (epgList == null || epgList.size() == 0) {
                SettingsActivity.logOperation("【EPG匹配】未获取节目，复用缓存");
                if (lastCurrItem != null) {
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                } else {
                    setEpgEmptyUi();
                }
                return;
            }
            List<Channel.EpgItem> todayEpg = filterTodayEpg(epgList);
            if (todayEpg.isEmpty()) {
                SettingsActivity.logOperation("【EPG匹配】今日无节目，复用缓存");
                if (lastCurrItem != null) {
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                } else {
                    setEpgEmptyUi();
                }
                return;
            }
            sortEpgByTime(todayEpg);
            String nowTime = getCurrentTimeStr();
            Channel.EpgItem currItem = null;
            Channel.EpgItem nextItem = null;
            int currIndex = -1;
            for (int i = 0; i < todayEpg.size(); i++) {
                Channel.EpgItem item = todayEpg.get(i);
                String start = item.time;
                String end = (i + 1 < todayEpg.size()) ? todayEpg.get(i + 1).time : "23:59";
                if (timeBetween(nowTime, start, end)) {
                    currItem = item;
                    currIndex = i;
                    if (i + 1 < todayEpg.size()) nextItem = todayEpg.get(i + 1);
                    break;
                }
            }
            lastCurrItem = currItem;
            lastNextItem = nextItem;
            refreshCurrProgramUi(currItem, currIndex, todayEpg, nowTime);
            refreshNextProgramUi(nextItem, currIndex, todayEpg);
        } catch (Exception e) {
            e.printStackTrace();
            SettingsActivity.logOperation("【EPG匹配异常】" + e.getMessage());
            if (lastCurrItem != null) {
                refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
            } else {
                setEpgEmptyUi();
            }
        }
    }

    private List<Channel.EpgItem> fuzzyMatchEpg(String rawName) {
        if (rawName == null || rawName.isEmpty()) return null;
        try {
            String clean = rawName.replaceAll("\\s+", "").toLowerCase();
            SettingsActivity.logOperation("【EPG模糊匹配】待完善");
        } catch (Exception e) {
            SettingsActivity.logOperation("【EPG模糊匹配失败】" + e.getMessage());
        }
        return null;
    }

    private List<Channel.EpgItem> filterTodayEpg(List<Channel.EpgItem> source) {
        List<Channel.EpgItem> res = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int weekNum = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekArr = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String todayWeek = weekArr[weekNum - 1];
        for (Channel.EpgItem item : source) {
            if (item.dayName == null) continue;
            String day = item.dayName.trim();
            if ("今天".equals(day) || todayWeek.equals(day)) {
                res.add(item);
            }
        }
        return res;
    }

    private void sortEpgByTime(List<Channel.EpgItem> list) {
        Collections.sort(list, (o1, o2) -> o1.time.compareTo(o2.time));
    }

    private void refreshCurrProgramUi(Channel.EpgItem currItem, int currIdx, List<Channel.EpgItem> todayList, String now) {
        if (currItem != null) {
            tvCurrentProgramName.setText(currItem.title);
            String start = currItem.time;
            String end = (currIdx + 1 < todayList.size()) ? todayList.get(currIdx + 1).time : "23:59";
            if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText(start + " - ");
            long nowMs = timeToMs(now, false, 0);
            long sMs = timeToMs(start, false, 0);
            long eMs = timeToMs(end, true, sMs);
            if (progressProgram != null) {
                long totalDuration = eMs - sMs;
                long played = nowMs - sMs;
                int progress = 0;
                if (totalDuration > 0) {
                    progress = (int) (played * 100 / totalDuration);
                    progress = Math.max(0, Math.min(100, progress));
                } else {
                    SettingsActivity.logOperation("【进度异常】时长非法 start=" + start + " end=" + end + " total=" + totalDuration);
                }
                progressProgram.setProgress(progress);
                progressProgram.invalidate();
            }
            if (tvRemainingTime != null) {
                long played = nowMs - sMs;
                long playedSec = played / 1000;
                long validSec = playedSec % (24 * 3600);
                long playedMin = validSec / 60;
                if (playedMin >= 60) {
                    int h = (int) (playedMin / 60);
                    int m = (int) (playedMin % 60);
                    tvRemaining.setText("已播放" + h + "时" + m + "分");
                } else {
                    tvRemaining.setText("已播放" + playedMin + "分钟");
                }
            }
        } else {
            tvCurrentProgramName.setText("暂无节目信息");
            if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
            if (progressProgram != null) progressProgram.setProgress(0);
            if (tvRemainingTime != null) tvRemainingTime.setText("");
        }
    }

    private void refreshNextProgramUi(Channel.EpgItem nextItem, int currIdx, List<Channel.EpgItem> todayList) {
        if (nextItem != null && tvNextProgramName != null && tvNextTimeRange != null) {
            String s = nextItem.time;
            String e = (currIdx + 2 < todayList.size()) ? todayList.get(currIdx + 2).time : "23:59";
            tvNextTimeRange.setText(s + " - " + e);
            tvNextProgramName.setText(nextItem.title);
        } else {
            if (lastNextItem != null) {
                String s = lastNextItem.time;
                String e = "23:59";
                tvNextTime.setText(s + " - " + e);
                tvNextProgramName.setText(lastNextItem.title);
            } else {
                if (tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
                if (tvNextTimeRange != null) tvNextTimeRange.setText("");
            }
        }
    }

    private void setEpgEmptyUi() {
        if (tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if (tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if (tvNextProgramName != null) tvNextProgramName.setText("");
        if (tvNextTimeRange != null) tvNextTimeRange.setText("");
        if (progressProgram != null) progressProgram.setProgress(0);
        if (tvRemainingTime != null) tvRemainingTime.setText("");
    }

    // 定时刷新控制
    public void startProgressLoop() {
        mainHandler.removeCallbacks(refreshProgressTask);
        mainHandler.postDelayed(refreshProgressTask, PROGRAM_PROGRESS_INTERVAL);
    }

    public void stopProgressLoop() {
        mainHandler.removeCallbacks(refreshProgressTask);
    }

    // 时间工具
    private String getCurrentTimeStr() {
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        return String.format("%02d:%02d", h, m);
    }

    private boolean timeBetween(String now, String start, String end) {
        try {
            long nowMs = timeToMs(now, false, 0);
            long startMs = timeToMs(start, false, 0);
            long endMs = timeToMs(end, true, startMs);
            return nowMs >= startMs && nowMs < endMs;
        } catch (Exception e) {
            SettingsActivity.logOperation("【时段匹配异常】" + e.getMessage());
            return false;
        }
    }

    private long timeToMs(String timeStr, boolean isEndTime, long startMs) {
        try {
            String[] split = timeStr.split(":");
            int h = Integer.parseInt(split[0].trim());
            int m = Integer.parseInt(split[1].trim());
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long ms = cal.getTimeInMillis();
            if (isEndTime && ms <= startMs) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
                ms = cal.getTimeInMillis();
            }
            return ms;
        } catch (Exception e) {
            SettingsActivity.logOperation("【时间转换失败】" + timeStr + " err:" + e.getMessage());
            return 0;
        }
    }

    // ========== 规范完整release方法 ==========
    public void release() {
        // 1 清空所有Handler延迟任务
        mainHandler.removeCallbacksAndMessages(null);

        // 2 清空EPG缓存数据
        currentPlayChannel = null;
        lastCurrItem = null;
        lastNextItem = null;

        // 3 清空弱引用上下文
        if (contextRef != null) {
            contextRef.clear();
            contextRef = null;
        }

        // 4 全部UI控件置空，切断View强引用
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
        tvNextTimeRange;
    }
}
