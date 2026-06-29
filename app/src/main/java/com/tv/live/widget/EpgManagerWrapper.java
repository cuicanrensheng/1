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
 * 核心功能：
 * 1. 管理ListView展示指定频道、指定日期的节目单
 * 2. 处理节目单的筛选、排序、播放状态标记
 * 3. 实现节目回看、预约提醒功能
 * 4. 处理ListView的选中/焦点/播放中状态的UI样式
 * 样式更新规则
 * 1、焦点选中条目（最高优先级）：蓝色字体+加粗+浅蓝色半透明背景
 * 2、播放中条目（仅今日首位，无焦点）：蓝色字体、不加粗、透明无背景
 * 3、普通条目：白色常规文字、透明背景
 * 4、非今日完全不渲染播放中蓝色样式
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

    /**
     * 构造方法
     * @param context 上下文（MainActivity）
     * @param lvEpg 展示EPG的ListView控件
     */
    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        // 关闭Item获取焦点（避免焦点冲突，统一由ListView管理选中状态）
        lvEpg.setItemsCanFocus(false);
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

        // 监听ListView焦点变化，刷新样式
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
            // 获取当前频道的原始EPG列表
            List<Channel.EpgItem> epgList;
            try {
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel.getName());
                epgList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
            } catch (Exception e) {
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                epgList = new ArrayList<>();
            }
            SettingsActivity.log("【EPG包装】📋 原始节目数：" + epgList.size());
            
            // 打印EPG包含的所有日期（用于调试）
            if (epgList.size() > 0) {
                Set<String> dayNames = new HashSet<>();
                for (Channel.EpgItem item : epgList) {
                    dayNames.add(item.dayName);
                }
                SettingsActivity.log("【EPG包装】📅 EPG包含日期：" + dayNames);
            }

            // 筛选后的目标节目列表
            List<Channel.EpgItem> data = new ArrayList<>();
            if (epgList != null && !epgList.isEmpty()) {
                // ========== 步骤1：计算目标日期（双重兼容：中文描述+周几） ==========
                String targetDay; // 目标日期描述（今天/明天/后天/周几）
                String targetWeekDay = null; // 目标日期对应的周几（用于兼容匹配）
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex); // 计算目标日期（当前日期+dateIndex天）
                int w = cal.get(Calendar.DAY_OF_WEEK); // 获取周几（1=周日，2=周一...7=周六）
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[w - 1]; // 转换为中文周几
                
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
                    targetDay = weekDay; // 超过后天直接用周几
                }
                SettingsActivity.log("【EPG包装】🎯 目标日期：" + targetDay
                        + "，对应周几：" + weekDay
                        + (targetWeekDay != null ? "，兼容匹配：" + targetDay + " 或 " + targetWeekDay : ""));

                // ========== 步骤2：双重兼容筛选节目 ==========
                int matchCount = 0;
                for (Channel.EpgItem item : epgList) {
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

                // ========== 步骤4：计算节目结束时间 + 标记播放中节目 ==========
                if (dateIndex == 0) {
                    String now = getNow(); // 获取当前时间（HH:mm）
                    Channel.EpgItem playing = null; // 播放中节目缓存
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        
                        // 处理节目开始时间格式（移除可能的"-结束时间"后缀）
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-")) {
                            curr.time = curr.time.split("-")[0].trim();
                        }
                        
                        // 计算结束时间：默认取下一个节目的开始时间，最后一个节目则+1小时
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size()) {
                                Channel.EpgItem next = data.get(i + 1);
                                epgEndTimeMap.put(curr, next.time.contains("-") ? next.time.split("-")[0].trim() : next.time);
                            } else {
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                            }
                        }
                        
                        // 标记播放中状态
                        curr.isPlaying = false;
                        String currEnd = epgEndTimeMap.get(curr);
                        if (isTimeBetween(now, curr.time, currEnd)) {
                            curr.isPlaying = true;
                            playing = curr;
                            playingIndex = i;
                        }
                    }
                    
                    // ========== 步骤5：播放中节目置顶 ==========
                    if (playing != null && playingIndex > 0) {
                        data.remove(playing);
                        data.add(0, playing);
                        playingIndex = 0; // 重置播放中索引为0
                    }
                } else {
                    // 非今日不处理播放中状态
                    playingIndex = -1;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-")) {
                            curr.time = curr.time.split("-")[0].trim();
                        }
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size()) {
                                epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                            } else {
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                            }
                        }
                        curr.isPlaying = false;
                    }
                }
            }

            // ========== 主线程更新UI（必须在主线程操作控件） ==========
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                SettingsActivity.log("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());
                // 初始化适配器或更新适配器数据
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                // 边界保护：选中位置超出范围时重置
                if (selectedPosition >= finalData.size()) {
                    selectedPosition = Math.max(0, finalData.size() - 1);
                }
                // 定位到播放中节目或选中位置
                if (playingIndex >= 0) {
                    lvEpg.setSelection(playingIndex);
                    selectedPosition = playingIndex;
                } else {
                    lvEpg.setSelection(selectedPosition);
                }
                // 刷新适配器
                adapter.notifyDataSetChanged();
                SettingsActivity.log("【EPG包装】✅ UI更新完成");
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
     * 给指定时间加1小时（用于最后一个节目的结束时间）
     * @param hm 时间字符串（HH:mm）
     * @return 加1小时后的时间（HH:mm）
     */
    private String addOneHour(String hm) {
        try {
            if (hm == null || !hm.contains(":")) return "23:59"; // 格式异常默认返回23:59
            hm = hm.trim();
            // 移除可能的"-结束时间"后缀
            if (hm.contains("-")) hm = hm.split("-")[0].trim();
            String[] arr = hm.split(":");
            int h = Integer.parseInt(arr[0].trim());
            int m = Integer.parseInt(arr[1].trim());
            
            // 日历工具类计算+1小时
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.add(Calendar.MINUTE, 60);
            
            // 格式化返回（补零）
            return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        } catch (Exception e) {
            return "23:59"; // 异常默认返回23:59
        }
    }

    /**
     * 获取当前时间（HH:mm格式）
     * @return 格式化后的当前时间
     */
    private String getNow() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    /**
     * 注册节目提醒广播接收器
     * 接收ACTION_REMINDER广播并弹出Toast提示
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
     * 负责节目单Item的UI渲染和交互逻辑
     */
    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx; // 上下文
        private Channel currentChannel; // 当前频道
        private List<Channel.EpgItem> list; // 节目列表数据
        private final LayoutInflater inflater; // 布局填充器
        private int dayIndex; // 日期索引
        // 完整时间格式化器（用于回看功能的时间参数）
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        /**
         * 适配器构造方法
         * @param ctx 上下文
         * @param currentChannel 当前频道
         * @param list 节目列表数据
         * @param dayIndex 日期索引
         */
        public EpgAdapter(Context ctx, Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            super(ctx, R.layout.item_epg, list);
            this.ctx = ctx;
            this.currentChannel = currentChannel;
            this.list = list;
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIndex;
        }

        /**
         * 更新适配器数据
         * @param currentChannel 当前频道
         * @param list 新的节目列表
         * @param dayIndex 日期索引
         */
        public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            this.currentChannel = currentChannel;
            this.list.clear();
            this.list.addAll(list);
            this.dayIndex = dayIndex;
            notifyDataSetChanged(); // 刷新UI
        }

        /**
         * 核心方法：渲染每个Item的UI
         * @param position Item位置
         * @param convertView 复用的View（优化性能）
         * @param parent 父容器（ListView）
         * @return 渲染后的Item View
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // ViewHolder模式（避免重复findViewById，提升性能）
            ViewHolder holder;
            if (convertView == null) {
                // 初始化Item布局
                convertView = inflater.inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tv_dayName = convertView.findViewById(R.id.tv_dayName); // 日期文本
                holder.tv_time = convertView.findViewById(R.id.tv_time); // 时间区间文本
                holder.tv_title = convertView.findViewById(R.id.tv_title); // 节目标题文本
                holder.tv_action = convertView.findViewById(R.id.tv_action); // 操作按钮（播放中/回看/预约）
                convertView.setTag(holder); // 缓存ViewHolder
            } else {
                holder = (ViewHolder) convertView.getTag(); // 复用ViewHolder
            }

            // 边界保护
            if (position < 0 || position >= list.size()) {
                return convertView;
            }

            // 获取当前位置的节目数据
            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item); // 获取节目结束时间
            
            // 填充基础文本数据
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime); // 时间区间：开始-结束
            holder.tv_title.setText(item.title);

            // ========== 重置所有样式（彻底清空缓存） ==========
            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);
            convertView.setSelected(false);

            // ========== 状态判断：焦点选中/播放中/普通 ==========
            // 规则1：焦点选中条目（最高优先级）
            boolean isFocused = (position == selectedPosition) && lvEpg.hasFocus();
            // 规则2：播放中条目（仅今日）
            boolean isPlaying = item.isPlaying && dayIndex == 0;

            if (isFocused) {
                // 焦点选中样式：蓝色字体+加粗+浅蓝色半透明背景
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (isPlaying) {
                // 播放中样式：蓝色字体、不加粗、透明背景
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            // ========== 操作按钮逻辑 ==========
            // 预约/回看按钮的唯一标识：频道名_节目位置
            String key = currentChannel.getName() + "_" + position;
            // 判断节目是否已过期（开始时间早于当前时间）
            boolean isPast = false;
            try { isPast = item.time.compareTo(getNow()) < 0; } catch (Exception ignored) {}

            if (dayIndex == 0) {
                if (item.isPlaying) {
                    // 状态1：播放中
                    holder.tv_action.setText("播放中");
                    holder.tv_action.setBackgroundColor(0xFFFF9800); // 橙色背景
                    holder.tv_action.setEnabled(false); // 禁用点击
                    holder.tv_action.setOnClickListener(null);
                } else if (isPast) {
                    // 状态2：已过期 → 回看功能
                    holder.tv_action.setText("回看");
                    holder.tv_action.setBackgroundColor(0xFF607D8B); // 灰色背景
                    holder.tv_action.setEnabled(true); // 启用点击
                    // 回看点击事件
                    holder.tv_action.setOnClickListener(v -> {
                        try {
                            // 获取频道播放地址
                            String liveUrl = currentChannel.getPlayUrl();
                            if (TextUtils.isEmpty(liveUrl)) {
                                Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            // 计算回看日期（当前日期+日期索引）
                            Calendar playDay = Calendar.getInstance();
                            playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                            // 解析节目开始时间
                            String[] startHm = item.time.split(":");
                            Calendar startCal = (Calendar) playDay.clone();
                            startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0].trim()));
                            startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1].trim()));
                            startCal.set(Calendar.SECOND, 0);
                            // 解析节目结束时间
                            String[] endHm = endTime.split(":");
                            Calendar endCal = (Calendar) playDay.clone();
                            endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                            endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                            endCal.set(Calendar.SECOND, 0);
                            // 格式化开始/结束时间为yyyyMMddHHmmss
                            String startStr = sdfFull.format(startCal.getTime());
                            String endStr = sdfFull.format(endCal.getTime());
                            // 拼接回看地址（替换PLTV为TVOD，添加时间参数）
                            String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                            catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr + "-" + endStr;
                            // 调用播放器播放回看地址
                            ((MainActivity) ctx).mPlayerManager.playUrl(catchUrl);
                            Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    // 状态3：未过期 → 预约功能
                    holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                    // 已预约：灰色背景；未预约：绿色背景
                    holder.tv_action.setBackgroundColor(bookedSet.contains(key) ? 0xFF607D8B : 0xFF4CAF50);
                    holder.tv_action.setEnabled(true); // 启用点击
                    // 预约/取消预约点击事件
                    holder.tv_action.setOnClickListener(v -> {
                        if (bookedSet.contains(key)) {
                            // 取消预约
                            bookedSet.remove(key);
                            Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                        } else {
                            // 新增预约
                            bookedSet.add(key);
                            Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                        }
                        notifyDataSetChanged(); // 刷新UI
                    });
                }
            } else {
                // 非今日仅显示预约功能
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

        /**
         * ViewHolder类（缓存Item中的控件）
         * 避免每次getView都调用findViewById，提升列表滑动性能
         */
        private class ViewHolder {
            TextView tv_dayName; // 日期文本控件
            TextView tv_time; // 时间区间文本控件
            TextView tv_title; // 节目标题文本控件
            TextView tv_action; // 操作按钮控件（播放中/回看/预约）
        }
    }
}
