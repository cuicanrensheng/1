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
 * 样式更新规则
 * 1、焦点选中条目（最高优先级）：蓝色字体+加粗+浅蓝色半透明背景
 * 2、播放中条目（仅今日首位，无焦点）：蓝色字体、不加粗、透明无背景
 * 3、普通条目：白色常规文字、透明背景
 * 4、非今日完全不渲染播放中蓝色样式
 * 已按规范优化：区分今日/非今日、增加各类边界防护、时间预处理
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
    // 当前选中的日期索引（0=今天，1=明天，2=后天，>2=对应周几）
    private int selectDayIndex = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        // 关闭Item获取焦点（避免焦点冲突，统一由ListView管理选中状态）
        lvEpg.setItemsCanFocus(true);
        // 设置ListView为单选模式
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 设置ListView项选中监听器：更新选中位置并刷新适配器
        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedPosition = -1;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        });

        lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        });

        // 注册节目提醒广播接收器
        registerReminderReceiver();
    }

    /**
     * 刷新指定日期的节目单（核心方法）
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
        SettingsActivity.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() + "，日期索引：" + dateIndex);

        // 重置播放中节目索引和选中日期索引
        playingIndex = -1;
        selectDayIndex = dateIndex;
        // 清空节目结束时间映射表
        epgEndTimeMap.clear();

        // 子线程处理EPG数据（避免主线程阻塞）
        new Thread(() -> {
            List<Channel.EpgItem> tempList;
            try {
                List<Channel.EpgItem> raw = EpgManager.getInstance().getEpg(currentChannel.getName());
                // 空值防护
                tempList = raw == null ? new ArrayList<>() : new ArrayList<>(raw);
            } catch (Exception e) {
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                tempList = new ArrayList<>();
            }
            SettingsActivity.log("【EPG包装】📋 原始节目数：" + tempList.size());

            // 筛选后的目标节目列表
            List<Channel.EpgItem> data = new ArrayList<>();
            if (!tempList.isEmpty()) {
                // ========== 步骤1：计算目标日期（双重兼容：中文描述+周几） ==========
                String targetDay;
                String targetWeekDay = null;
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                int w = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[w - 1];

                // 日期描述适配
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
                SettingsActivity.log("【EPG包装】🎯 目标日期：" + targetDay
                        + "，对应周几：" + weekDay
                        + (targetWeekDay != null ? "，兼容匹配：" + targetDay + " 或 " + targetWeekDay : ""));

                // ========== 步骤2：双重兼容筛选节目 ==========
                int matchCount = 0;
                for (Channel.EpgItem item : tempList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    // 匹配目标日期描述 或 对应的周几（兼容不同数据源的日期格式）
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

                // ========== 步骤3：按节目开始时间排序 ==========
                Collections.sort(data, Comparator.comparing(o -> o.time));

                // ========== 步骤4：区分今日/非今日分别计算时间、标记播放 ==========
                if (dateIndex == 0) {
                    String now = getNow();
                    Channel.EpgItem playing = null;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        // 先预处理时间，分割去除后缀
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();

                        // 计算结束时间
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size())
                                epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                            else
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                        }

                        // 标记播放状态
                        curr.isPlaying = false;
                        String currEnd = epgEndTimeMap.get(curr);
                        if (isTimeBetween(now, curr.time, currEnd)) {
                            curr.isPlaying = true;
                            playing = curr;
                            playingIndex = i;
                        }
                    }

                    // 步骤5：仅今日播放节目置顶
                    if (playing != null && playingIndex > 0) {
                        data.remove(playing);
                        data.add(0, playing);
                        playingIndex = 0;
                    }
                } else {
                    // 明天/后天等非今日：不判断播放状态，仅计算结束时间
                    playingIndex = -1;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();

                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size())
                                epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                            else
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                        }
                        curr.isPlaying = false;
                    }
                }
            }

            // ========== 主线程更新UI（必须在主线程操作控件） ==========
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                SettingsActivity.logOperation("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());
                // 初始化适配器或更新适配器数据
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }

                // 新增：选中下标越界保护
                if (selectedPosition >= finalData.size()) {
                    selectedPosition = Math.max(0, finalData.size() - 1);
                }
                lvEpg.setSelection(selectedPosition);
                adapter.notifyDataSetChanged();
                SettingsActivity.logOperation("【EPG包装】✅ UI更新完成");
            });
        }).start();
    }

    /**
     * 判断当前时间是否在[start, end)时间段内
     * @param now 当前时间（HH:mm）
     * @param start 开始时间（HH:mm）
     * @param end 结束时间（HH:mm）
     * @return true=在时间段内，false=不在
     */
    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) return false;
            // 校验时间格式（必须包含":"），并比较时间字符串
            return now.contains(":") && start.contains(":") && end.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 给指定时间加1小时（用于最后一个节目）
     * @param hm 时间字符串（HH:mm）
     * @return 加1小时后的时间
     */
    private String addOneHour(String hm) {
        try {
            if (hm == null || !hm.contains(":")) return "23:59";
            hm = hm.trim();
            if (hm.contains("-")) hm = hm.split("-")[0].trim();
            String[] arr = hm.split(":");
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
     * 获取当前时间 HH:mm
     */
    private String getNow() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    /**
     * 注册节目提醒广播接收器
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

    /**
     * EPG列表适配器（自定义ArrayAdapter）
     */
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
            notifyDataSetChanged();
        }

        /**
         * 核心渲染Item
         */
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

            // 新增：边界保护，防止下标越界空白
            if (position < 0 || position >= list.size()) {
                return convertView;
            }

            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);
            holder.tv_day.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);

            // 样式重置+判断
            boolean isSelected = (position == selectedPosition || item.isPlaying);
            boolean hasItemFocus = convertView.isFocused();
            // 统一重置基础样式
            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);
            convertView.setSelected(false);

            if (isSelected) {
                // 选中/播放中：蓝色字体+加粗+半透背景
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (hasItemFocus) {
                // 单独焦点样式
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(0x4440A9FF);
            }

            // 按钮逻辑
            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            try { isPast = item.time.compareTo(getNow()) < 0; } catch (Exception ignored) {}

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
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
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
