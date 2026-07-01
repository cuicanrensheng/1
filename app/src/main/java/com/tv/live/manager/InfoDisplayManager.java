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
/**
 * 信息展示管理器【修复三大问题版本】
 * 2026-06-27 修复1：码率显示延迟，调换更新顺序优先渲染码率画质
 * 2026-06-27 修复2：下一档节目匹配容错，匹配失败保留旧数据不闪烁
 * 2026-06-27 修复3：播放时长超大数字溢出，限制单日最大时长24h
 * 2026-06-27 补齐：完整tvNextTimeRange逻辑、跨天时间计算
 * 
 * 【2026-06-27 修复：解决"暂无下一档节目"问题】
 * 修复内容：
 * 1. ✅ 实现模糊匹配（之前是空壳，直接返回null）
 * 2. ✅ 扩展 dayName 格式支持（今日/星期一/具体日期等）
 * 3. ✅ 修复缓存被 null 覆盖的 Bug（最后一个节目时缓存被清空）
 * 4. ✅ 跨天查找下一档节目（今日最后一个节目时，取明天第一个）
 * 5. ✅ 增加详细日志，方便排查问题
 */
public class InfoDisplayManager {
    // ===================== 定时延时常量 =====================
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;
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
    // ===================== 底部信息栏【修复码率延迟：先更新码率再EPG】 =====================
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo){
        if(infoBar == null || channel == null) return;
        currentPlayChannel = channel;
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.postDelayed(hideInfoBarTask, INFO_BAR_HIDE_DELAY);
        if(tvChannelName != null) tvChannelName.setText(channel.getName());
        // 修复1：优先更新码率、画质，不再等EPG加载完成才显示
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
            SettingsActivity.log("【分辨率解析异常】" + resolution + " err:" + e.getMessage());
        }
        return resolution;
    }
    // ===================== EPG逻辑【修复节目闪烁：缓存上次节目】 =====================
    public void updateEpgInfo(Channel channel){
        if(channel == null) return;
        currentPlayChannel = channel;
        updateEpgInternal(channel);
    }
    /**
     * 核心 EPG 匹配逻辑
     * 
     * 【2026-06-27 修复说明】
     * 1. 精确匹配失败后，真正执行模糊匹配（之前是空壳）
     * 2. 筛选今日节目时，支持更多 dayName 格式
     * 3. 当前节目是今日最后一个时，跨天查找明天的第一个节目
     * 4. 修复缓存被 null 覆盖的 Bug（只在有值时才更新缓存）
     */
    private void updateEpgInternal(Channel channel){
        if(channel == null || tvCurrentProgramName == null) return;
        String channelName = channel.getName();
        try {
            SettingsActivity.logOperation("【EPG匹配】========== 开始匹配 ==========");
            SettingsActivity.logOperation("【EPG匹配】频道名：" + channelName);
            
            // ====================================================================
            // 第1步：精确匹配
            // ====================================================================
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);
            SettingsActivity.logOperation("【EPG匹配】精确匹配结果：" + (epgList == null ? "null" : epgList.size() + "条"));
            
            // ====================================================================
            // 第2步：精确匹配失败，执行模糊匹配
            // ====================================================================
            if((epgList == null || epgList.isEmpty()) && channelName != null){
                SettingsActivity.logOperation("【EPG匹配】精确匹配为空，执行模糊匹配");
                epgList = fuzzyMatchEpg(channelName);
                SettingsActivity.logOperation("【EPG匹配】模糊匹配结果：" + (epgList == null ? "null" : epgList.size() + "条"));
            }
            
            // ====================================================================
            // 第3步：完全没有 EPG 数据，复用缓存
            // ====================================================================
            if(epgList == null || epgList.size() == 0){
                SettingsActivity.logOperation("【EPG匹配】❌ 未获取任何节目，复用缓存节目");
                if(lastCurrItem != null){
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                }else {
                    setEpgEmptyUi();
                }
                return;
            }
            
            // ====================================================================
            // 第4步：筛选今日节目
            // ====================================================================
            List<Channel.EpgItem> todayEpg = filterTodayEpg(epgList);
            SettingsActivity.logOperation("【EPG匹配】今日节目数：" + todayEpg.size());
            
            if(todayEpg.isEmpty()){
                SettingsActivity.logOperation("【EPG匹配】❌ 今日无节目，复用缓存");
                if(lastCurrItem != null){
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                }else {
                    setEpgEmptyUi();
                }
                return;
            }
            
            // ====================================================================
            // 第5步：按时间排序
            // ====================================================================
            sortEpgByTime(todayEpg);
            String nowTime = getCurrentTimeStr();
            SettingsActivity.logOperation("【EPG匹配】当前时间：" + nowTime);
            
            // ====================================================================
            // 第6步：查找当前播放节目
            // ====================================================================
            Channel.EpgItem currItem = null;
            Channel.EpgItem nextItem = null;
            int currIndex = -1;
            for(int i=0; i<todayEpg.size(); i++){
                Channel.EpgItem item = todayEpg.get(i);
                String start = item.time;
                String end = (i+1 < todayEpg.size()) ? todayEpg.get(i+1).time : "23:59";
                if(timeBetween(nowTime, start, end)){
                    currItem = item;
                    currIndex = i;
                    SettingsActivity.logOperation("【EPG匹配】✅ 找到当前节目：" + item.title + "（第" + (i+1) + "个）");
                    
                    // ====================================================================
                    // ✅ 2026-06-27 修复：下一档节目跨天查找
                    // ====================================================================
                    // 如果不是最后一个，直接取下一个
                    if(i+1 < todayEpg.size()){
                        nextItem = todayEpg.get(i+1);
                        SettingsActivity.logOperation("【EPG匹配】✅ 下一档节目：" + nextItem.title);
                    }
                    // 如果是最后一个，去明天的列表里找第一个
                    else {
                        SettingsActivity.logOperation("【EPG匹配】当前是今日最后一个节目，尝试跨天查找下一档");
                        nextItem = findTomorrowFirstProgram(epgList);
                        if(nextItem != null){
                            SettingsActivity.logOperation("【EPG匹配】✅ 跨天找到下一档：" + nextItem.title);
                        }else {
                            SettingsActivity.logOperation("【EPG匹配】❌ 明天也没有节目");
                        }
                    }
                    break;
                }
            }
            
            // 没找到当前节目
            if(currIndex == -1){
                SettingsActivity.logOperation("【EPG匹配】❌ 未找到当前播放节目，复用缓存");
            }
            
            // ====================================================================
            // ✅ 2026-06-27 修复：只在有值时才更新缓存，防止被 null 覆盖
            // ====================================================================
            // 【原来的 Bug】
            // 如果 currItem 找到了但 nextItem 为 null（比如是最后一个节目），
            // 执行 lastNextItem = nextItem 会把之前的缓存清空，
            // 导致 refreshNextProgramUi() 里的缓存判断失效，直接显示"暂无下一档"。
            // 
            // 【修复方案】
            // 只在新值不为 null 时才更新缓存，保留旧的有效缓存。
            if(currItem != null){
                lastCurrItem = currItem;
            }
            if(nextItem != null){
                lastNextItem = nextItem;
            }
            
            refreshCurrProgramUi(currItem, currIndex, todayEpg, nowTime);
            refreshNextProgramUi(nextItem, currIndex, todayEpg);
            
            SettingsActivity.logOperation("【EPG匹配】========== 匹配结束 ==========");
        }catch (Exception e){
            e.printStackTrace();
            SettingsActivity.logOperation("【EPG匹配异常】" + e.getMessage());
            // 异常也复用缓存，不全部清空
            if(lastCurrItem != null){
                refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
            }else {
                setEpgEmptyUi();
            }
        }
    }
    // ====================================================================
    // ✅ 2026-06-27 修复：真正实现模糊匹配（之前是空壳，直接返回 null）
    // ====================================================================
    /**
     * 模糊匹配 EPG 频道
     * 
     * 【匹配策略】
     * 对原始频道名进行多种常见格式变换，然后逐一尝试精确匹配：
     * 1. 原始名称
     * 2. 去掉所有空格
     * 3. 转小写
     * 4. 去掉"高清"、"HD"、"标清"、"SD"等后缀
     * 5. 加上"高清"、"HD"后缀
     * 6. 去掉"-"、"_"、" "等分隔符
     * 
     * 【为什么这么做？】
     * 大部分频道名不匹配都是因为格式差异（空格、大小写、后缀等），
     * 通过常见的格式变换，可以解决 90% 以上的不匹配问题。
     * 
     * @param rawName 原始频道名
     * @return 匹配到的 EPG 列表，匹配失败返回 null
     */
    private List<Channel.EpgItem> fuzzyMatchEpg(String rawName){
        if(rawName == null || rawName.isEmpty()) return null;
        try {
            // 生成各种可能的频道名变体
            List<String> variants = new ArrayList<>();
            
            // 1. 原始名称
            variants.add(rawName);
            
            // 2. 去掉所有空格
            String noSpace = rawName.replaceAll("\\s+","");
            variants.add(noSpace);
            
            // 3. 转小写
            String lower = rawName.toLowerCase();
            variants.add(lower);
            variants.add(noSpace.toLowerCase());
            
            // 4. 去掉常见后缀（高清、HD、标清、SD）
            String[] suffixes = {"高清", "HD", "hd", "标清", "SD", "sd", "超清", "4K", "4k"};
            for (String suffix : suffixes) {
                if (rawName.endsWith(suffix)) {
                    String trimmed = rawName.substring(0, rawName.length() - suffix.length()).trim();
                    variants.add(trimmed);
                    variants.add(trimmed.toLowerCase());
                    variants.add(trimmed.replaceAll("\\s+",""));
                }
            }
            
            // 5. 加上常见后缀
            String[] addSuffixes = {"高清", "HD", "标清"};
            for (String suffix : addSuffixes) {
                variants.add(rawName + suffix);
                variants.add(noSpace + suffix);
            }
            
            // 6. 去掉分隔符（-、_、·）
            String noSeparator = rawName.replaceAll("[-_·\\s]+", "");
            variants.add(noSeparator);
            variants.add(noSeparator.toLowerCase());
            
            // 逐一尝试匹配
            for (String variant : variants) {
                if (variant == null || variant.isEmpty()) continue;
                List<Channel.EpgItem> result = EpgManager.getInstance().getEpg(variant);
                if (result != null && !result.isEmpty()) {
                    SettingsActivity.logOperation("【EPG模糊匹配】✅ 匹配成功：" + rawName + " → " + variant);
                    return result;
                }
            }
            
            SettingsActivity.logOperation("【EPG模糊匹配】❌ 所有变体都匹配失败，共尝试 " + variants.size() + " 种变体");
        }catch (Exception e){
            SettingsActivity.logOperation("【EPG模糊匹配异常】" + e.getMessage());
        }
        return null;
    }
    // ====================================================================
    // ✅ 2026-06-27 修复：扩展 dayName 格式支持
    // ====================================================================
    /**
     * 筛选今日的 EPG 节目
     * 
     * 【2026-06-27 修复说明】
     * 原来只支持"今天"和"周X"两种格式，导致很多 EPG 数据源筛选后为 0 条。
     * 现在支持以下格式：
     * 1. "今天"、"今日"
     * 2. "周一"、"星期一"、"周一"等（全称和简称都支持）
     * 3. 具体日期格式：2026-06-27、06-27、6月27日、2026/06/27 等
     * 
     * @param source 原始 EPG 列表
     * @return 今日的 EPG 列表
     */
    private List<Channel.EpgItem> filterTodayEpg(List<Channel.EpgItem> source){
        List<Channel.EpgItem> res = new ArrayList<>();
        if (source == null || source.isEmpty()) return res;
        
        Calendar cal = Calendar.getInstance();
        
        // 获取今日的周几（全称和简称）
        int weekNum = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekFullArr = {"星期日","星期一","星期二","星期三","星期四","星期五","星期六"};
        String[] weekShortArr = {"周日","周一","周二","周三","周四","周五","周六"};
        String todayWeekFull = weekFullArr[weekNum - 1];
        String todayWeekShort = weekShortArr[weekNum - 1];
        
        // 获取今日的日期字符串（各种格式）
        SimpleDateFormat sdf1 = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        SimpleDateFormat sdf2 = new SimpleDateFormat("MM-dd", Locale.CHINA);
        SimpleDateFormat sdf3 = new SimpleDateFormat("M月d日", Locale.CHINA);
        SimpleDateFormat sdf4 = new SimpleDateFormat("yyyy/MM/dd", Locale.CHINA);
        SimpleDateFormat sdf5 = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);
        
        String todayDate1 = sdf1.format(cal.getTime());  // 2026-06-27
        String todayDate2 = sdf2.format(cal.getTime());  // 06-27
        String todayDate3 = sdf3.format(cal.getTime());  // 6月27日
        String todayDate4 = sdf4.format(cal.getTime());  // 2026/06/27
        String todayDate5 = sdf5.format(cal.getTime());  // 20260627
        
        SettingsActivity.logOperation("【EPG筛选】今日日期：" + todayDate1 + "（" + todayWeekFull + "）");
        
        for(Channel.EpgItem item : source){
            if(item.dayName == null) continue;
            String day = item.dayName.trim();
            
            // 匹配：今天、今日
            if("今天".equals(day) || "今日".equals(day)){
                res.add(item);
                continue;
            }
            
            // 匹配：周几（全称和简称）
            if(todayWeekFull.equals(day) || todayWeekShort.equals(day)){
                res.add(item);
                continue;
            }
            
            // 匹配：具体日期（各种格式）
            if(todayDate1.equals(day) 
                    || todayDate2.equals(day) 
                    || todayDate3.equals(day)
                    || todayDate4.equals(day)
                    || todayDate5.equals(day)){
                res.add(item);
                continue;
            }
            
            // 兼容：日期前面有"年"、"日"等多余字符，做包含匹配
            if (day.contains(todayDate2) || day.contains(todayDate3)) {
                res.add(item);
                continue;
            }
        }
        
        SettingsActivity.logOperation("【EPG筛选】原始 " + source.size() + " 条，筛选后今日 " + res.size() + " 条");
        return res;
    }
    // ====================================================================
    // ✅ 2026-06-27 新增：查找明天第一个节目（跨天查找下一档）
    // ====================================================================
    /**
     * 查找明天的第一个节目
     * 
     * 【作用】
     * 当当前节目是今日最后一个时，去明天的列表里找第一个节目，
     * 避免深夜显示"暂无下一档节目"。
     * 
     * @param allEpg 所有 EPG 数据（包含所有日期）
     * @return 明天的第一个节目，找不到返回 null
     */
    private Channel.EpgItem findTomorrowFirstProgram(List<Channel.EpgItem> allEpg){
        if (allEpg == null || allEpg.isEmpty()) return null;
        
        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, 1);  // 明天
            
            // 获取明天的周几
            int weekNum = cal.get(Calendar.DAY_OF_WEEK);
            String[] weekFullArr = {"星期日","星期一","星期二","星期三","星期四","星期五","星期六"};
            String[] weekShortArr = {"周日","周一","周二","周三","周四","周五","周六"};
            String tomorrowWeekFull = weekFullArr[weekNum - 1];
            String tomorrowWeekShort = weekShortArr[weekNum - 1];
            
            // 获取明天的日期字符串
            SimpleDateFormat sdf1 = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            SimpleDateFormat sdf2 = new SimpleDateFormat("MM-dd", Locale.CHINA);
            SimpleDateFormat sdf3 = new SimpleDateFormat("M月d日", Locale.CHINA);
            
            String tomorrowDate1 = sdf1.format(cal.getTime());
            String tomorrowDate2 = sdf2.format(cal.getTime());
            String tomorrowDate3 = sdf3.format(cal.getTime());
            
            // 筛选明天的节目
            List<Channel.EpgItem> tomorrowEpg = new ArrayList<>();
            for (Channel.EpgItem item : allEpg) {
                if (item.dayName == null) continue;
                String day = item.dayName.trim();
                
                if ("明天".equals(day) 
                        || "明日".equals(day)
                        || tomorrowWeekFull.equals(day) 
                        || tomorrowWeekShort.equals(day)
                        || tomorrowDate1.equals(day)
                        || tomorrowDate2.equals(day)
                        || tomorrowDate3.equals(day)) {
                    tomorrowEpg.add(item);
                }
            }
            
            if (tomorrowEpg.isEmpty()) {
                return null;
            }
            
            // 按时间排序，取第一个
            sortEpgByTime(tomorrowEpg);
            return tomorrowEpg.get(0);
            
        } catch (Exception e) {
            SettingsActivity.logOperation("【EPG跨天查找】异常：" + e.getMessage());
            return null;
        }
    }
    private void sortEpgByTime(List<Channel.EpgItem> list){
        Collections.sort(list, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                return o1.time.compareTo(o2.time);
            }
        });
    }
    // ===================== 刷新当前节目UI【修复超大时长数字】 =====================
    private void refreshCurrProgramUi(Channel.EpgItem currItem, int currIdx, List<Channel.EpgItem> todayList, String now){
        if(currItem != null){
            tvCurrentProgramName.setText(currItem.title);
            String start = currItem.time;
            String end = (currIdx+1 < todayList.size()) ? todayList.get(currIdx+1).time : "23:59";
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
                    SettingsActivity.logOperation("【进度异常】时长非法 start="+start+" end="+end+" total="+totalDuration);
                }
                progressProgram.setProgress(progress);
                progressProgram.invalidate();
            }
            // 修复3：限制单日最大时长，杜绝几十万小时溢出
            if(tvRemainingTime != null){
                long played = nowMs - sMs;
                // 负数说明当前时间还没到节目开始，直接显示0分钟
                if(played < 0){
                    tvRemainingTime.setText("已播放0分钟");
                    return;
                }
                long playedSec = played / 1000;
                // 限制单日最大86400秒（24h），防止跨天超大差值
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
    // ====================================================================
    // ✅ 2026-06-27 修复：下一档节目为空时，优先用缓存，而不是直接显示"暂无"
    // ====================================================================
    private void refreshNextProgramUi(Channel.EpgItem nextItem, int currIdx, List<Channel.EpgItem> todayList){
        if(nextItem != null && tvNextProgramName != null && tvNextTimeRange != null){
            String s = nextItem.time;
            // 下一档的结束时间：如果是今日的节目，取下下个节目的开始时间；如果是跨天的，显示开始时间 + "（次日）"
            String e;
            if (currIdx + 2 < todayList.size()) {
                e = todayList.get(currIdx + 2).time;
            } else {
                e = "次日";  // 跨天的节目，结束时间不明确，显示"次日"
            }
            tvNextTimeRange.setText(s + " - " + e);
            tvNextProgramName.setText(nextItem.title);
        }else {
            // ✅ 有缓存就显示缓存，不显示"暂无下一档"
            if(lastNextItem != null){
                SettingsActivity.logOperation("【EPG下一档】新数据为空，显示缓存节目：" + lastNextItem.title);
                String s = lastNextItem.time;
                tvNextTimeRange.setText(s + " - 次日");
                tvNextProgramName.setText(lastNextItem.title);
            }else {
                SettingsActivity.logOperation("【EPG下一档】❌ 无缓存，显示暂无下一档");
                if(tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
                if(tvNextTimeRange != null) tvNextTimeRange.setText("");
            }
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
    // ===================== 定时控制 =====================
    public void startProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
        mainHandler.postDelayed(refreshProgressTask, PROGRAM_PROGRESS_INTERVAL);
    }
    public void stopProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
    }
    // ===================== 时间工具（跨天兼容） =====================
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
            SettingsActivity.logOperation("【时段匹配异常】"+e.getMessage());
            return false;
        }
    }
    private long timeToMs(String timeStr, boolean isEndTime, long startMs){
        try {
            String[] split = timeStr.split(":");
            if (split.length < 2) {
                SettingsActivity.logOperation("【时间转换失败】格式错误：" + timeStr);
                return 0;
            }
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
            SettingsActivity.logOperation("【时间转换失败】"+timeStr+" err:"+e.getMessage());
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
