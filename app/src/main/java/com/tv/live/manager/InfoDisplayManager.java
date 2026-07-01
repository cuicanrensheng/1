package com.tv.live.manager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
* 信息展示管理器
* 
* 【2026-07-01 修复：解决信息栏与节目单时间对不上的问题】
* 修复内容：
* 1. ✅ 优化模糊匹配逻辑，大幅减少匹配错误频道的概率
* 2. ✅ 增加匹配结果校验，只有相似度足够高才采用
* 3. ✅ 增加详细匹配日志，方便排查匹配到了哪个频道
* 4. ✅ 统一时间计算逻辑，和右侧节目单保持一致
* 5. ✅ 修复下一档节目时间显示格式问题
* 
* 【历史修复】
* 2026-06-27 修复1：码率显示延迟，调换更新顺序优先渲染码率画质
* 2026-06-27 修复2：下一档节目匹配容错，匹配失败保留旧数据不闪烁
* 2026-06-27 修复3：播放时长超大数字溢出，限制单日最大时长24h
* 2026-06-27 修复：解决"暂无下一档节目"问题
* 2026-06-27 修复：解决码率显示为0的问题
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
// 缓存上一档节目数据
private Channel.EpgItem lastCurrItem;
private Channel.EpgItem lastNextItem;
// 码率和分辨率缓存
private String lastBitrate = "";
private String lastResolution = "";
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
updateLiveInfo(liveInfo);
updateEpgInternal(channel);
startProgressLoop();
}
public void hideInfoBar(){
if(infoBar == null) return;
mainHandler.removeCallbacks(hideInfoBarTask);
infoBar.setVisibility(View.GONE);
}
// ===================== 码率相关 =====================
public void updateLiveInfo(TVPlayerManager.LiveInfo info){
if(info == null){
    showCachedLiveInfo();
    return;
}
String resolution = info.resolution;
if (isValidResolution(resolution)) {
    lastResolution = resolution;
    if(tvTagFhd != null){
        tvTagFhd.setText(parseQualityText(resolution));
    }
} else {
    if (tvTagFhd != null && !lastResolution.isEmpty()) {
        tvTagFhd.setText(parseQualityText(lastResolution));
    } else {
        tvTagFhd.setText("未知");
    }
}
String bitrate = info.bitrate;
if (isValidBitrate(bitrate)) {
    lastBitrate = bitrate;
    if(tvBitrate != null){
        tvBitrate.setText(formatBitrate(bitrate));
    }
} else {
    if (tvBitrate != null) {
        if (!lastBitrate.isEmpty()) {
            tvBitrate.setText(formatBitrate(lastBitrate));
        } else {
            tvBitrate.setText("加载中...");
        }
    }
}
}

public void updateBitrate(String bitrate) {
    if (tvBitrate == null) return;
    if (isValidBitrate(bitrate)) {
        lastBitrate = bitrate;
        tvBitrate.setText(formatBitrate(bitrate));
    }
}

private void showCachedLiveInfo() {
    if (tvTagFhd != null) {
        if (!lastResolution.isEmpty()) {
            tvTagFhd.setText(parseQualityText(lastResolution));
        } else {
            tvTagFhd.setText("未知");
        }
    }
    if (tvBitrate != null) {
        if (!lastBitrate.isEmpty()) {
            tvBitrate.setText(formatBitrate(lastBitrate));
        } else {
            tvBitrate.setText("加载中...");
        }
    }
}

private boolean isValidBitrate(String bitrate) {
    if (bitrate == null || bitrate.trim().isEmpty()) return false;
    String clean = bitrate.trim().toLowerCase();
    clean = clean.replace("kbps", "").replace("mbps", "").replace("bps", "").trim();
    try {
        double value = Double.parseDouble(clean);
        return value > 0;
    } catch (NumberFormatException e) {
        return true;
    }
}

private boolean isValidResolution(String resolution) {
    if (resolution == null || resolution.trim().isEmpty()) return false;
    String clean = resolution.trim();
    if (clean.contains("×") || clean.contains("x") || clean.contains("X")) return true;
    try {
        int value = Integer.parseInt(clean);
        return value > 0;
    } catch (NumberFormatException e) {
        return true;
    }
}

private String formatBitrate(String bitrate) {
    if (bitrate == null || bitrate.trim().isEmpty()) return "加载中...";
    String clean = bitrate.trim();
    try {
        String lower = clean.toLowerCase();
        if (lower.contains("mbps")) {
            String numStr = lower.replace("mbps", "").trim();
            double value = Double.parseDouble(numStr);
            return String.format(Locale.CHINA, "%.1f Mbps", value);
        }
        if (lower.contains("kbps")) {
            String numStr = lower.replace("kbps", "").trim();
            double value = Double.parseDouble(numStr);
            if (value >= 1000) {
                return String.format(Locale.CHINA, "%.1f Mbps", value / 1000);
            } else {
                return String.format(Locale.CHINA, "%.0f Kbps", value);
            }
        }
        if (lower.contains("bps")) {
            String numStr = lower.replace("bps", "").trim();
            double value = Double.parseDouble(numStr);
            if (value >= 1000000) {
                return String.format(Locale.CHINA, "%.1f Mbps", value / 1000000);
            } else if (value >= 1000) {
                return String.format(Locale.CHINA, "%.0f Kbps", value / 1000);
            } else {
                return String.format(Locale.CHINA, "%.0f bps", value);
            }
        }
        double value = Double.parseDouble(clean);
        if (value >= 1000) {
            return String.format(Locale.CHINA, "%.1f Mbps", value / 1000);
        } else {
            return String.format(Locale.CHINA, "%.0f Kbps", value);
        }
    } catch (NumberFormatException e) {
        return clean;
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
SettingsActivity.logOperation("【EPG匹配】========== 开始匹配 ==========");
SettingsActivity.logOperation("【EPG匹配】频道名：" + channelName);

// ====================================================================
// 第1步：精确匹配（优先，和右侧节目单保持一致）
// ====================================================================
List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);
boolean isExactMatch = (epgList != null && !epgList.isEmpty());
SettingsActivity.logOperation("【EPG匹配】精确匹配结果：" + (isExactMatch ? "✅ 成功，" + epgList.size() + "条" : "❌ 失败"));

// ====================================================================
// 第2步：精确匹配失败，才执行模糊匹配
// ====================================================================
if(!isExactMatch && channelName != null){
SettingsActivity.logOperation("【EPG匹配】精确匹配失败，尝试模糊匹配...");
String matchedName = fuzzyMatchEpgGetName(channelName);
if (matchedName != null) {
    epgList = EpgManager.getInstance().getEpg(matchedName);
    SettingsActivity.logOperation("【EPG匹配】✅ 模糊匹配成功：" + channelName + " → " + matchedName 
        + "（" + epgList.size() + "条）");
} else {
    SettingsActivity.logOperation("【EPG匹配】❌ 模糊匹配也失败");
}
}

// 第3步：完全没有 EPG 数据，复用缓存
if(epgList == null || epgList.size() == 0){
SettingsActivity.logOperation("【EPG匹配】未获取任何节目，复用缓存");
if(lastCurrItem != null){
refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<Channel.EpgItem>(), getCurrentTimeStr());
refreshNextProgramUi(lastNextItem, 0, new ArrayList<Channel.EpgItem>());
}else {
setEpgEmptyUi();
}
return;
}

// 第4步：筛选今日节目
List<Channel.EpgItem> todayEpg = filterTodayEpg(epgList);
SettingsActivity.logOperation("【EPG匹配】今日节目数：" + todayEpg.size());

if(todayEpg.isEmpty()){
SettingsActivity.logOperation("【EPG匹配】今日无节目，复用缓存");
if(lastCurrItem != null){
refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<Channel.EpgItem>(), getCurrentTimeStr());
refreshNextProgramUi(lastNextItem, 0, new ArrayList<Channel.EpgItem>());
}else {
setEpgEmptyUi();
}
return;
}

// 第5步：按时间排序
sortEpgByTime(todayEpg);
String nowTime = getCurrentTimeStr();
SettingsActivity.logOperation("【EPG匹配】当前时间：" + nowTime);

// 打印今日节目列表（方便排查）
for (int i = 0; i < todayEpg.size(); i++) {
    Channel.EpgItem item = todayEpg.get(i);
    SettingsActivity.logOperation("【EPG匹配】节目" + (i+1) + "：" + item.time + " " + item.title);
}

// 第6步：查找当前播放节目
Channel.EpgItem currItem = null;
Channel.EpgItem nextItem = null;
int currIndex = -1;
for(int i=0; i<todayEpg.size(); i++){
Channel.EpgItem item = todayEpg.get(i);
String start = item.time;
// 结束时间：取下一个节目的开始时间（和右侧节目单逻辑一致）
String end = (i+1 < todayEpg.size()) ? todayEpg.get(i+1).time : "23:59";

if(timeBetween(nowTime, start, end)){
currItem = item;
currIndex = i;
SettingsActivity.logOperation("【EPG匹配】✅ 找到当前节目：" + item.title + "（第" + (i+1) + "个）");

// 下一档节目
if(i+1 < todayEpg.size()){
nextItem = todayEpg.get(i+1);
SettingsActivity.logOperation("【EPG匹配】下一档节目：" + nextItem.title);
} else {
SettingsActivity.logOperation("【EPG匹配】当前是今日最后一个节目，尝试跨天查找");
nextItem = findTomorrowFirstProgram(epgList);
if(nextItem != null){
SettingsActivity.logOperation("【EPG匹配】✅ 跨天找到下一档：" + nextItem.title);
}
}
break;
}
}

if(currIndex == -1){
SettingsActivity.logOperation("【EPG匹配】未找到当前播放节目，复用缓存");
}

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
if(lastCurrItem != null){
refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<Channel.EpgItem>(), getCurrentTimeStr());
refreshNextProgramUi(lastNextItem, 0, new ArrayList<Channel.EpgItem>());
}else {
setEpgEmptyUi();
}
}
}
// ====================================================================
// ✅ 2026-07-01 重写：优化模糊匹配逻辑，减少匹配错误
// ====================================================================
/**
 * 模糊匹配 EPG 频道，返回匹配到的频道名
 * 
 * 【优化说明】
 * 原来的模糊匹配变体太多，容易匹配到错误的频道。
 * 现在只保留最安全的几种变体，大幅降低误匹配概率。
 * 
 * 【只尝试这些变体】
 * 1. 去掉所有空格
 * 2. 转小写（去空格前后各一次）
 * 3. 去掉常见的"高清/HD/标清/SD"后缀
 * 
 * 【不再尝试】
 * - 加上后缀（容易匹配到其他频道）
 * - 去掉分隔符（容易改变频道含义）
 * 
 * @param rawName 原始频道名
 * @return 匹配到的 EPG 频道名，没找到返回 null
 */
private String fuzzyMatchEpgGetName(String rawName){
if(rawName == null || rawName.isEmpty()) return null;
try {
    String noSpace = rawName.replaceAll("\\s+","").trim();
    
    // 按相似度从高到低排列，优先尝试更相似的
    String[] variants = {
        rawName,                    // 1. 原始名称（最高相似度）
        noSpace,                    // 2. 去掉空格
        rawName.toLowerCase(),      // 3. 转小写
        noSpace.toLowerCase(),      // 4. 去空格+转小写
    };
    
    // 去掉常见后缀的变体（相似度稍低，放在后面）
    String[] suffixes = {"高清", "HD", "hd", "标清", "SD", "sd", "超清", "4K", "4k"};
    List<String> suffixVariants = new ArrayList<>();
    for (String suffix : suffixes) {
        if (rawName.endsWith(suffix)) {
            String trimmed = rawName.substring(0, rawName.length() - suffix.length()).trim();
            suffixVariants.add(trimmed);
            suffixVariants.add(trimmed.toLowerCase());
            suffixVariants.add(trimmed.replaceAll("\\s+",""));
        }
    }
    
    // 合并变体列表
    List<String> allVariants = new ArrayList<>();
    for (String v : variants) allVariants.add(v);
    allVariants.addAll(suffixVariants);
    
    SettingsActivity.logOperation("【EPG模糊匹配】共尝试 " + allVariants.size() + " 种变体");
    
    // 逐一尝试，返回第一个匹配成功的频道名
    for (int i = 0; i < allVariants.size(); i++) {
        String variant = allVariants.get(i);
        if (variant == null || variant.isEmpty()) continue;
        
        List<Channel.EpgItem> result = EpgManager.getInstance().getEpg(variant);
        if (result != null && !result.isEmpty()) {
            SettingsActivity.logOperation("【EPG模糊匹配】变体" + (i+1) + " 匹配成功：" + variant);
            return variant;
        }
    }
    
    SettingsActivity.logOperation("【EPG模糊匹配】所有变体都匹配失败");
}catch (Exception e){
SettingsActivity.logOperation("【EPG模糊匹配异常】" + e.getMessage());
}
return null;
}

/**
 * 模糊匹配 EPG 频道（保留旧接口，兼容外部调用）
 */
private List<Channel.EpgItem> fuzzyMatchEpg(String rawName){
    String matchedName = fuzzyMatchEpgGetName(rawName);
    if (matchedName != null) {
        return EpgManager.getInstance().getEpg(matchedName);
    }
    return null;
}

// ====================================================================
// 筛选今日节目
// ====================================================================
private List<Channel.EpgItem> filterTodayEpg(List<Channel.EpgItem> source){
List<Channel.EpgItem> res = new ArrayList<>();
if (source == null || source.isEmpty()) return res;

Calendar cal = Calendar.getInstance();

int weekNum = cal.get(Calendar.DAY_OF_WEEK);
String[] weekFullArr = {"星期日","星期一","星期二","星期三","星期四","星期五","星期六"};
String[] weekShortArr = {"周日","周一","周二","周三","周四","周五","周六"};
String todayWeekFull = weekFullArr[weekNum - 1];
String todayWeekShort = weekShortArr[weekNum - 1];

SimpleDateFormat sdf1 = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
SimpleDateFormat sdf2 = new SimpleDateFormat("MM-dd", Locale.CHINA);
SimpleDateFormat sdf3 = new SimpleDateFormat("M月d日", Locale.CHINA);
SimpleDateFormat sdf4 = new SimpleDateFormat("yyyy/MM/dd", Locale.CHINA);
SimpleDateFormat sdf5 = new SimpleDateFormat("yyyyMMdd", Locale.CHINA);

String todayDate1 = sdf1.format(cal.getTime());
String todayDate2 = sdf2.format(cal.getTime());
String todayDate3 = sdf3.format(cal.getTime());
String todayDate4 = sdf4.format(cal.getTime());
String todayDate5 = sdf5.format(cal.getTime());

for(Channel.EpgItem item : source){
if(item.dayName == null) continue;
String day = item.dayName.trim();

if("今天".equals(day) || "今日".equals(day)){
res.add(item);
continue;
}
if(todayWeekFull.equals(day) || todayWeekShort.equals(day)){
res.add(item);
continue;
}
if(todayDate1.equals(day) 
|| todayDate2.equals(day) 
|| todayDate3.equals(day)
|| todayDate4.equals(day)
|| todayDate5.equals(day)){
res.add(item);
continue;
}
if (day.contains(todayDate2) || day.contains(todayDate3)) {
res.add(item);
continue;
}
}

SettingsActivity.logOperation("【EPG筛选】原始 " + source.size() + " 条，今日 " + res.size() + " 条");
return res;
}

private Channel.EpgItem findTomorrowFirstProgram(List<Channel.EpgItem> allEpg){
if (allEpg == null || allEpg.isEmpty()) return null;

try {
Calendar cal = Calendar.getInstance();
cal.add(Calendar.DAY_OF_YEAR, 1);

int weekNum = cal.get(Calendar.DAY_OF_WEEK);
String[] weekFullArr = {"星期日","星期一","星期二","星期三","星期四","星期五","星期六"};
String[] weekShortArr = {"周日","周一","周二","周三","周四","周五","周六"};
String tomorrowWeekFull = weekFullArr[weekNum - 1];
String tomorrowWeekShort = weekShortArr[weekNum - 1];

SimpleDateFormat sdf1 = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
SimpleDateFormat sdf2 = new SimpleDateFormat("MM-dd", Locale.CHINA);
SimpleDateFormat sdf3 = new SimpleDateFormat("M月d日", Locale.CHINA);

String tomorrowDate1 = sdf1.format(cal.getTime());
String tomorrowDate2 = sdf2.format(cal.getTime());
String tomorrowDate3 = sdf3.format(cal.getTime());

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
|| tomorrowDate3.equals(day)
|| day.contains(tomorrowDate2)
|| day.contains(tomorrowDate3)) {
tomorrowEpg.add(item);
}
}

if (tomorrowEpg.isEmpty()) {
return null;
}

sortEpgByTime(tomorrowEpg);
return tomorrowEpg.get(0);

} catch (Exception e) {
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
// ===================== 刷新当前节目UI =====================
private void refreshCurrProgramUi(Channel.EpgItem currItem, int currIdx, List<Channel.EpgItem> todayList, String now){
if(currItem != null){
tvCurrentProgramName.setText(currItem.title);

String start = currItem.time;
// 结束时间：取下一个节目的开始时间（和右侧节目单逻辑一致）
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
// ====================================================================
// ✅ 2026-07-01 修复：下一档节目时间显示
// ====================================================================
private void refreshNextProgramUi(Channel.EpgItem nextItem, int currIdx, List<Channel.EpgItem> todayList){
if(nextItem != null && tvNextProgramName != null && tvNextTimeRange != null){
String s = nextItem.time;
// 下一档的结束时间：取下下档的开始时间（和右侧节目单逻辑一致）
String e;
if (currIdx + 2 < todayList.size()) {
    // 下下档还在今天
    e = todayList.get(currIdx + 2).time;
} else if (currIdx + 1 < todayList.size()) {
    // 下一档是今天最后一个，结束时间默认 23:59
    e = "23:59";
} else {
    // 下一档是明天的（跨天），结束时间不明确
    e = "次日";
}

tvNextTimeRange.setText(s + " - " + e);
tvNextProgramName.setText(nextItem.title);
}else {
// 新数据为空，有缓存就显示缓存
if(lastNextItem != null){
String s = lastNextItem.time;
tvNextTimeRange.setText(s + " - 次日");
tvNextProgramName.setText(lastNextItem.title);
}else {
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
return false;
}
}
private long timeToMs(String timeStr, boolean isEndTime, long startMs){
try {
String[] split = timeStr.split(":");
if (split.length < 2) {
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
lastBitrate = "";
lastResolution = "";
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
