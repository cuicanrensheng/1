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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 信息展示管理器【修复三大问题版本】
 * 2026-06-27 修复1：码率显示延迟，调换更新顺序优先渲染码率画质
 * 2026-06-27 修复2：下一档节目匹配容错，匹配失败保留旧数据不闪烁
 * 2026-06-27 修复3：播放时长超大数字溢出，限制单日最大时长24h
 * 2026-06-27 补齐：完整tvNextTimeRange逻辑、跨天时间计算
 * 2026-07-01 修复4：适配EPG "HH:mm - HH:mm" 时间格式，避免数字解析崩溃
 * 2026-07-01 优化5：数据未加载时显示"节目单加载中..."，避免空白
 */
public class InfoDisplayManager {
    // ===================== 定时延时常量 =====================
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long PROGRAM_PROGRESS_INTERVAL = 30000; // 改为30秒

    // ===================== UI控件引用 =====================
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

    // 缓存上一档节目数据，匹配失败不闪烁
    private Channel.EpgItem lastCurrItem;
    private Channel.EpgItem lastNextItem;

    // ===================== 调度变量 =====================
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Channel currentPlayChannel;

    private final Runnable hideInfoBarTask = new Runnable() {
        @Override
        public void run() {
            if(infoBar != null) infoBar.setVisibility(View.GONE);
        }
    };

    private final Runnable hideChannelNumTask = new Runnable() {
        @Override
        public void run() {
            if(tvChannelNum != null) tvChannelNum.setVisibility(View.GONE);
        }
    };

    private final Runnable refreshProgressTask = new Runnable() {
        @Override
        public void run() {
            if(currentPlayChannel != null){
                updateEpgInternal(currentPlayChannel);
            }
            mainHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };

    // ===================== 构造方法 =====================
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
                              TextView tvNextTimeRange){
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
        if(tvTagAudio != null){
            tvTagAudio.setText("立体声");
        }
    }

    // ===================== 频道数字弹窗 =====================
    public void showChannelNum(int num){
        if(tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.postDelayed(hideChannelNumTask, CHANNEL_NUM_HIDE_DELAY);
    }

    public void hideChannelNum(){
        if(tvChannelNum == null) return;
        mainHandler.removeCallbacks(hideChannelNumTask);
        tvChannelNum.setVisibility(View.GONE);
    }

    // ===================== 底部信息栏 =====================
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo){
        if(infoBar == null || channel == null) return;
        currentPlayChannel = channel;
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.postDelayed(hideInfoBarTask, INFO_BAR_HIDE_DELAY);
        if(tvChannelName != null) tvChannelName.setText(channel.getName());
        // 优先更新码率、画质
        updateLiveInfo(liveInfo);
        // 后处理EPG节目信息
        updateEpgInternal(channel);
        startProgressLoop();
    }

    public void hideInfoBar(){
        if(infoBar == null) return;
        mainHandler.removeCallbacks(hideInfoBarTask);
        infoBar.setVisibility(View.GONE);
    }

    public void updateLiveInfo(TVPlayerManager.LiveInfo info){
        if(info == null) return;
        if(tvTagFhd != null){
            tvTagFhd.setText(parseQualityText(info.resolution));
        }
        if(tvBitrate != null){
            tvBitrate.setText(info.bitrate);
        }
    }

    private String parseQualityText(String resolution){
        if(resolution == null || resolution.isEmpty()) return "未知";
        try {
            String[] split = resolution.split("×");
            if(split.length >= 2){
                int height = Integer.parseInt(split[1].trim());
                if(height >= 1080) return "FHD";
                else if(height >=720) return "HD";
                else return "SD";
            }
        }catch (Exception e){
            // 🟢【已注释】SettingsActivity.log("【分辨率解析异常】" + resolution + " err:" + e.getMessage());
        }
        return resolution;
    }

    // ===================== EPG逻辑 =====================
    public void updateEpgInfo(Channel channel){
        if(channel == null) return;
        currentPlayChannel = channel;
        updateEpgInternal(channel);
    }

    private void updateEpgInternal(Channel channel){
        if(channel == null || tvCurrentProgramName == null) return;
        String channelName = channel.getName();
        try {
            // 🟢【已注释】SettingsActivity.logOperation("【EPG匹配】开始匹配频道:" + channelName);

            // 如果是初次启动，EpgManager 数据可能还在异步加载中
            // 建议在 EpgManager 中加一个 getChannelEpgMapSize() 方法检测是否就绪，这里为了安全直接捕获异常
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);

            // 如果数据还没加载好，显示“加载中”避免空白
            if (EpgManager.getInstance().getChannelEpgMapSize() == 0) {
                setEpgLoadingUi();
                return;
            }

            if(epgList == null || epgList.isEmpty()){
                // 🟢【已注释】SettingsActivity.logOperation("【EPG匹配】未获取节目，复用缓存节目");
                if(lastCurrItem != null){
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                }else {
                    setEpgEmptyUi();
                }
                return;
            }

            List<Channel.EpgItem> todayEpg = filterTodayEpg(epgList);
            if(todayEpg.isEmpty()){
                // 🟢【已注释】SettingsActivity.logOperation("【EPG匹配】今日无节目，复用缓存");
                if(lastCurrItem != null){
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                }else {
                    setEpgEmptyUi();
                }
                return;
            }

            sortEpgByTime(todayEpg);
            String nowTime = getCurrentTimeStr();
            Channel.EpgItem currItem = null;
            Channel.EpgItem nextItem = null;
            int currIndex = -1;

            for(int i=0; i<todayEpg.size(); i++){
                Channel.EpgItem item = todayEpg.get(i);
                String start = extractTimeSegment(item.time, false);
                String end = (i+1 < todayEpg.size()) ? extractTimeSegment(todayEpg.get(i+1).time, false) : "23:59";
                if(timeBetween(nowTime, start, end)){
                    currItem = item;
                    currIndex = i;
                    if(i+1 < todayEpg.size()) nextItem = todayEpg.get(i+1);
                    break;
                }
            }

            // 更新当前节目缓存，但下一档节目只在找到当前节目时才更新
            lastCurrItem = currItem;
            if (currItem != null) {
                lastNextItem = nextItem; // 防止空档期将下一档缓存清空
            }

            refreshCurrProgramUi(currItem, currIndex, todayEpg, nowTime);
            refreshNextProgramUi(nextItem, currIndex, todayEpg);

        }catch (Exception e){
            e.printStackTrace();
            // 🟢【已注释】SettingsActivity.logOperation("【EPG匹配异常】" + e.getMessage());
            // 异常也复用缓存
            if(lastCurrItem != null){
                refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
            }else {
                setEpgEmptyUi();
            }
        }
    }

    // ===================== 时间格式提取工具（核心修复） =====================
    /**
     * 从 "HH:mm - HH:mm" 格式中分离出开始或结束时间点
     * @param fullTime 原始时间字符串
     * @param isEnd 是否提取结束时间（true取后一段，false取前一段）
     * @return 格式化后的 "HH:mm" 字符串
     */
    private String extractTimeSegment(String fullTime, boolean isEnd) {
        if (fullTime == null || fullTime.trim().isEmpty()) return "";
        String trimmed = fullTime.trim();
        if (trimmed.contains(" - ")) {
            String[] parts = trimmed.split(" - ");
            if (parts.length >= 2) {
                return isEnd ? parts[1].trim() : parts[0].trim();
            }
        }
        return trimmed;
    }

    // ===================== 日期过滤与排序 =====================
    private List<Channel.EpgItem> filterTodayEpg(List<Channel.EpgItem> source){
        List<Channel.EpgItem> res = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int weekNum = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekArr = {"周日","周一","周二","周三","周四","周五","周六"};
        String todayWeek = weekArr[weekNum - 1];
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String todayDate = sdf.format(cal.getTime());

        for(Channel.EpgItem item : source){
            if(item.dayName == null) continue;
            String day = item.dayName.trim();
            // 兼容三种格式：今天 / 周几 / yyyy-MM-dd
            if ("今天".equals(day) || todayWeek.equals(day) || todayDate.equals(day)) {
                res.add(item);
            }
        }
        return res;
    }

    private void sortEpgByTime(List<Channel.EpgItem> list){
        Collections.sort(list, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                String t1 = (o1.time != null) ? extractTimeSegment(o1.time, false) : "";
                String t2 = (o2.time != null) ? extractTimeSegment(o2.time, false) : "";
                return t1.compareTo(t2);
            }
        });
    }

    // ===================== 刷新当前节目UI =====================
    private void refreshCurrProgramUi(Channel.EpgItem currItem, int currIdx, List<Channel.EpgItem> todayList, String now){
        if(currItem != null){
            tvCurrentProgramName.setText(currItem.title);
            String start = extractTimeSegment(currItem.time, false); // 提取起始时间
            String end = (currIdx+1 < todayList.size()) ? extractTimeSegment(todayList.get(currIdx+1).time, false) : "23:59";
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText(start + " - " + end);
            long nowMs = timeToMs(now, false, 0);
            long sMs = timeToMs(start, false, 0);
            long eMs = timeToMs(end, true, sMs);
            if(progressProgram != null){
                long totalDuration = eMs - sMs;
                long played = nowMs - sMs;
                int progress = 0;
                if(totalDuration > 0){
                    progress = (int) (played * 100 / totalDuration);
                    progress = Math.max(0, Math.min(100, progress));
                }else {
                    // 🟢【已注释】SettingsActivity.logOperation("【进度异常】时长非法 start="+start+" end="+end+" total="+totalDuration);
                }
                progressProgram.setProgress(progress);
                progressProgram.invalidate();
            }
            if(tvRemainingTime != null){
                long played = nowMs - sMs;
                if(played < 0){
                    tvRemainingTime.setText("已播放0分钟");
                    return;
                }
                long playedSec = played / 1000;
                long validSec = playedSec % (24 * 3600);
                long playedMin = validSec / 60;
                if(playedMin >= 60){
                    int h = (int) (playedMin / 60);
                    int m = (int) (playedMin % 60);
                    tvRemainingTime.setText("已播放"+h+"时"+m+"分");
                }else {
                    tvRemainingTime.setText("已播放"+playedMin+"分钟");
                }
            }
        }else {
            tvCurrentProgramName.setText("暂无节目信息");
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
            if(progressProgram != null) {
                progressProgram.setProgress(0);
                progressProgram.invalidate();
            }
            if(tvRemainingTime != null) tvRemainingTime.setText("");
        }
    }

    private void refreshNextProgramUi(Channel.EpgItem nextItem, int currIdx, List<Channel.EpgItem> todayList){
        Channel.EpgItem displayItem = (nextItem != null) ? nextItem : lastNextItem;

        if(displayItem != null && tvNextProgramName != null && tvNextTimeRange != null){
            String s = extractTimeSegment(displayItem.time, false);
            String e = (nextItem != null && currIdx + 2 < todayList.size()) ? extractTimeSegment(todayList.get(currIdx+2).time, false) : "23:59";
            tvNextTimeRange.setText(s + " - " + e);
            tvNextProgramName.setText(displayItem.title);
        }else {
            if(tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
            if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        }
    }

    private void setEpgEmptyUi(){
        if(tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if(tvNextProgramName != null) tvNextProgramName.setText("");
        if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        if(progressProgram != null) progressProgram.setProgress(0);
        if(tvRemainingTime != null) tvRemainingTime.setText("");
    }

    // 加载中的占位提示
    private void setEpgLoadingUi(){
        if(tvCurrentProgramName != null) tvCurrentProgramName.setText("节目单加载中...");
        if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if(tvNextProgramName != null) tvNextProgramName.setText("");
        if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        if(progressProgram != null) progressProgram.setProgress(0);
        if(tvRemainingTime != null) tvRemainingTime.setText("");
    }

    // ===================== 定时控制 =====================
    public void startProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
        mainHandler.postDelayed(refreshProgressTask, PROGRAM_PROGRESS_INTERVAL);
    }

    public void stopProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
    }

    // ===================== 时间工具 =====================
    private String getCurrentTimeStr(){
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        return String.format("%02d:%02d", h, m);
    }

    private boolean timeBetween(String now, String start, String end){
        try {
            if (now == null || start == null || end == null) return false;
            long nowMs = timeToMs(now, false, 0);
            long startMs = timeToMs(start, false, 0);
            long endMs = timeToMs(end, true, startMs);
            return nowMs >= startMs && nowMs < endMs;
        }catch (Exception e){
            // 🟢【已注释】SettingsActivity.logOperation("【时段匹配异常】"+e.getMessage());
            return false;
        }
    }

    // 核心时间转换修复：先调用 extractTimeSegment 预处理
    private long timeToMs(String timeStr, boolean isEndTime, long startMs){
        try {
            String targetTime = extractTimeSegment(timeStr, isEndTime);
            if (targetTime.isEmpty()) return 0;
            String[] split = targetTime.split(":");
            int h = Integer.parseInt(split[0].trim());
            int m = Integer.parseInt(split[1].trim());
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long ms = cal.getTimeInMillis();
            if(isEndTime && ms <= startMs){
                cal.add(Calendar.DAY_OF_MONTH, 1);
                ms = cal.getTimeInMillis();
            }
            return ms;
        }catch (Exception e){
            // 🟢【已注释】SettingsActivity.logOperation("【时间转换失败】"+timeStr+" err:"+e.getMessage());
            return 0;
        }
    }

    // ===================== 资源释放 =====================
    public void release(){
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.removeCallbacks(refreshProgressTask);
        currentPlayChannel = null;
        lastCurrItem = null;
        lastNextItem = null;
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
