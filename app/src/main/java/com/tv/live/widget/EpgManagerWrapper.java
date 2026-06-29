package com.tv.live.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.MainActivity;
import com.tv.live.R;
import com.tv.live.SettingsActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EPG（电子节目指南）管理器包装类
 * 修复点：解决节目部分显示/空白/错乱问题
 * 1. ListView复用残留彻底清空控件
 * 2. split分割时间安全兼容，无数组越界
 * 3. UI刷新顺序修正（先刷新再滚动）
 * 4. 日期字段全量去空格，匹配不丢失节目
 * 5. 所有时间操作异常兜底，不丢失条目
 * 6. 自动识别下一档节目，刷新直接定位快速展示
 */
public class EpgManagerWrapper {
    // 展示EPG的ListView控件
    private final ListView lvEpg;
    // 上下文对象（关联MainActivity）
    private final Context context;
    // EPG列表适配器
    private EpgAdapter adapter;
    // 已预约节目的标识集合（key：频道名_节目位置）
    private final Set<String> bookedSet = new HashSet<>();
    // 节目结束时间映射表（key：EpgItem，value：结束时间）
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    // 节目提醒广播动作常量
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    // ListView当前选中的位置
    private int selectedPosition = 0;
    // 当前正在播放的节目在列表中的索引
    private int playingIndex = -1;
    // 自动缓存下一档节目索引（刷新自动定位，实现快速出现）
    private int nextPlayIndex = -1;
    // 当前选中的日期索引（0=今天，1=明天，2=后天，>2=对应周几）
    private int selectDayIndex = 0;
    // 当前频道缓存
    private Channel cacheChannel;

    /**
     * 构造方法
     * @param context 上下文（MainActivity）
     * @param lvEpg 展示EPG的ListView控件
     */
    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        // 关闭Item自身焦点，解决焦点冲突空白
        lvEpg.setItemsCanFocus(false);
        // 设置ListView为单选模式
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 设置ListView项选中监听器：更新选中位置并刷新适配器
        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (parent.getAdapter() != null) {
                    ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 注册节目提醒广播接收器
        registerReminderReceiver();
    }

    /**
     * 遥控器/按键调用：手动跳转到下一档节目
     */
    public void jumpNextProgram() {
        if (adapter == null || adapter.getCount() <= 0 || nextPlayIndex == -1) {
            Toast.makeText(context, "暂无下一档节目", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedPosition = nextPlayIndex;
        lvEpg.setSelection(nextPlayIndex);
        adapter.notifyDataSetChanged();
    }

    /**
     * 刷新指定日期的节目单（核心修复方法，新增自动计算下一档）
     * @param currentChannel 当前选中的频道
     * @param channelSourceList 频道源列表（暂未使用，预留扩展）
     * @param dateIndex 日期索引：0=今天，1=明天，2=后天，>2=对应周几
     */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        // 空频道校验
        if (currentChannel == null) {
            SettingsActivity.log("【EPG包装】❌ refresh被调用，但currentChannel为空");
            return;
        }
        cacheChannel = currentChannel;
        SettingsActivity.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() + "，日期索引：" + dateIndex);

        // 重置所有标记索引
        playingIndex = -1;
        nextPlayIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();

        // 子线程处理耗时数据（不阻塞UI）
        new Thread(() -> {
            List<Channel.EpgItem> epgList;
            try {
                epgList = new ArrayList<>(EpgManager.getInstance().getEpg(currentChannel.getName()));
            } catch (Exception e) {
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                epgList = new ArrayList<>();
            }
            SettingsActivity.log("【EPG包装】📋 原始节目数：" + epgList.size());

            // 打印EPG包含的所有日期（用于调试）
            if (epgList.size() > 0) {
                Set<String> dayNames = new HashSet<>();
                for (Channel.EpgItem item : epgList) {
                    if (!TextUtils.isEmpty(item.dayName)) {
                        dayNames.add(item.dayName.trim());
                    }
                }
                SettingsActivity.log("【EPG包装】📅 EPG包含日期：" + dayNames);
            }

            // 筛选后的目标节目列表
            List<Channel.EpgItem> data = new ArrayList<>();
            if (epgList != null && !epgList.isEmpty()) {
                // ========== 步骤1：计算目标日期 ==========
                String targetDay;
                String targetWeekDay = null;
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                int w = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[w - 1];

                if (dateIndex == 0) {
                    targetDay = "今天";
                    targetWeekDay = weekDay;
                } else if (dateIndex == 1) {
                    targetDay = "明天";
                    targetWeekDay = weekDay;
                } else if (dateIndex == 2) {
                    targetDay = "后天";
                    targetWeekDay = weekDay;
                } else {
                    targetDay = weekDay;
                }
                SettingsActivity.log("【EPG包装】🎯 目标日期：" + targetDay + "，对应周几：" + weekDay);

                // ========== 步骤2：筛选节目（修复：dayName全量去空格） ==========
                int matchCount = 0;
                for (Channel.EpgItem item : epgList) {
                    if (TextUtils.isEmpty(item.dayName)) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) {
                        match = targetWeekDay.equals(dayName);
                    }
                    if (match) {
                        data.add(item);
                        matchCount++;
                    }
                }
                SettingsActivity.log("【EPG包装】✅ 筛选后节目数：" + matchCount);

                // ========== 步骤3：按时间升序排序 ==========
                Collections.sort(data, Comparator.comparing(o -> o.time));

                // ========== 步骤4：安全计算时间，标记播放/下一档 ==========
                String now = getNow();
                Channel.EpgItem playing = null;
                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);
                    // 修复：安全分割时间，兼容无"-"格式
                    String rawTime = TextUtils.isEmpty(curr.time) ? "" : curr.time.trim();
                    String showStart = rawTime;
                    if (rawTime.contains("-")) {
                        showStart = rawTime.split("-")[0].trim();
                    }
                    curr.time = showStart;

                    // 预存结束时间（安全处理，无数组越界）
                    String endTime;
                    if (i + 1 < data.size()) {
                        Channel.EpgItem nextItem = data.get(i + 1);
                        String nextRaw = TextUtils.isEmpty(nextItem.time) ? "" : nextItem.time;
                        String nextStart = nextRaw.contains("-") ? nextRaw.split("-")[0].trim() : nextRaw;
                        endTime = nextStart;
                    } else {
                        endTime = addOneHour(curr.time);
                    }
                    epgEndTimeMap.put(curr, endTime);

                    // 标记播放中
                    curr.isPlaying = false;
                    if (isTimeBetween(now, curr.time, endTime)) {
                        curr.isPlaying = true;
                        playing = curr;
                        playingIndex = i;
                    }
                }

                // ========== 步骤5：自动识别下一档节目（仅今天生效） ==========
                if (dateIndex == 0) {
                    if (playingIndex != -1) {
                        nextPlayIndex = playingIndex + 1;
                        if (nextPlayIndex >= data.size()) nextPlayIndex = -1;
                    } else {
                        // 无正在播放，取第一个未开始节目作为下一档
                        for (int i = 0; i < data.size(); i++) {
                            String t = data.get(i).time;
                            if (t.compareTo(now) > 0) {
                                nextPlayIndex = i;
                                break;
                            }
                        }
                    }
                } else {
                    nextPlayIndex = -1;
                }

                // ========== 步骤6：播放节目置顶，同步修正下一档下标 ==========
                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0;
                    if (nextPlayIndex != -1) nextPlayIndex--;
                }
            }

            // ========== 主线程UI（修复：先刷新再滚动） ==========
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                SettingsActivity.log("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size()
                        + " 播放：" + playingIndex + " 下一档：" + nextPlayIndex);
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                // 修复刷新顺序：先刷新数据，再滚动定位
                adapter.notifyDataSetChanged();
                // 优先自动定位下一档，快速展示
                int targetPos = 0;
                if (nextPlayIndex != -1) {
                    targetPos = nextPlayIndex;
                } else if (playingIndex != -1) {
                    targetPos = playingIndex;
                }
                targetPos = Math.min(targetPos, finalData.size() - 1);
                selectedPosition = targetPos;
                lvEpg.setSelection(targetPos);
                SettingsActivity.log("【EPG包装】✅ UI刷新完成，自动定位下一档");
            });
        }).start();
    }

    /**
     * 安全时间区间判断，全异常兜底
     */
    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (TextUtils.isEmpty(now) || TextUtils.isEmpty(start) || TextUtils.isEmpty(end))
                return false;
            return now.contains(":") && start.contains(":") && end.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安全时间+1小时，兼容各种异常格式
     */
    private String addOneHour(String hm) {
        try {
            if (TextUtils.isEmpty(hm) || !hm.contains(":")) return "23:59";
            String clean = hm.trim();
            if (clean.contains("-")) clean = clean.split("-")[0].trim();
            String[] arr = clean.split(":");
            int h = Integer.parseInt(arr[0].trim());
            int m = Integer.parseInt(arr[1].trim());
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.add(Calendar.MINUTE, 60);
            return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        } catch (Exception e) {
            return "23:59";
        }
    }

    /**
     * 获取当前HH:mm时间
     */
    private String getNow() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    /**
     * 注册提醒广播
     */
    private void registerReminderReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String title = intent.getStringExtra("title");
                    Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }

    // ===================== 适配器【核心修复：getView强制清空所有控件，解决复用空白错乱】 =====================
    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        public EpgAdapter(Context ctx, Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            super(ctx, R.layout.item_epg, list);
            this.ctx = ctx;
            this.currentChannel = currentChannel;
            this.list = list;
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIndex;
        }

        public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            this.currentChannel = currentChannel;
            this.list.clear();
            this.list.addAll(list);
            this.dayIndex = dayIndex;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tv_dayName = convertView.findViewById(R.id.tv_dayName);
                holder.tv_time = convertView.findViewById(R.id.tv_time);
                holder.tv_title = convertView.findViewById(R.id.tv_title);
                holder.tv_action = convertView.findViewById(R.id.tv_action);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // ===================== 【关键修复】复用前强制清空所有控件状态 =====================
            holder.tv_dayName.setText("");
            holder.tv_time.setText("");
            holder.tv_title.setText("");
            holder.tv_action.setText("");
            holder.tv_action.setOnClickListener(null);
            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);
            // ==========================================================================

            // 边界保护
            if (position < 0 || position >= list.size()) return convertView;
            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);
            // 空值兜底，避免setText(null)空白
            String dayText = TextUtils.isEmpty(item.dayName) ? "" : item.dayName;
            String timeText = (TextUtils.isEmpty(item.time) ? "" : item.time) + "-" + (TextUtils.isEmpty(endTime) ? "23:59" : endTime);
            String titleText = TextUtils.isEmpty(item.title) ? "" : item.title;
            holder.tv_dayName.setText(dayText);
            holder.tv_time.setText(timeText);
            holder.tv_title.setText(title);

            // 样式判断
            boolean isSelected = (position == selectedPosition || item.isPlaying);
            if (isSelected) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (convertView.isFocused()) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(0x4440A9FF);
            }

            // 预约key
            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            try {
                if (!TextUtils.isEmpty(item.time)) {
                    isPast = item.time.compareTo(getNow()) < 0;
                }
            } catch (Exception ignored) {}

            // 按钮逻辑
            if (item.isPlaying) {
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            } else if (isPast) {
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF607D8B);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    try {
                        String liveUrl = currentChannel.getPlayUrl();
                        if (TextUtils.isEmpty(liveUrl)) {
                            Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Calendar playDay = Calendar.getInstance();
                        playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                        String[] startHm = item.time.split(":");
                        Calendar startCal = (Calendar) playDay.clone();
                        startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0].trim()));
                        startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1].trim()));
                        startCal.set(Calendar.SECOND, 0);
                        String[] endHm = endTime.split(":");
                        Calendar endCal = (Calendar) playDay.clone();
                        endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                        endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                        endCal.set(Calendar.SECOND, 0);
                        String startStr = sdfFull.format(startCal.getTime());
                        String endStr = sdfFull.format(endCal.getTime());
                        String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                        catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr;
                        ((MainActivity) ctx).mPlayerManager.playUrl(catchUrl);
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                holder.tv_action.setBackgroundColor(bookedSet.contains(key) ? 0xFF607D8B : 0xFF4CAF50);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    if (bookedSet.contains(key)) {
                        bookedSet.remove(key);
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                    } else {
                        bookedSet.add(key);
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT);
                    }
                    notifyDataSetChanged();
                });
            }
            return convertView;
        }

        class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
