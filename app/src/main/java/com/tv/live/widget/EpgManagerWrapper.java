package com.tv.live.widget;

import com.tv.live.manager.ChannelPanelController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.MainActivity;
import com.tv.live.R;

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
 * EPG 节目单包装管理器
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;
    private int playingIndex = -1;
    private int selectDayIndex = 0;
    private String currentChannelName = "";
    
    // 🛠️【修复】将局部变量提升为成员变量，保证 unregisterReceiver 能匹配
    private BroadcastReceiver reminderReceiver; 

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(true);
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
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
        registerReminderReceiver();
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) return;
        currentChannelName = currentChannel.getName();
        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();
        new Thread(() -> {
            List<Channel.EpgItem> originEpgList;
            try {
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel.getName());
                originEpgList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
            } catch (Exception e) {
                originEpgList = new ArrayList<>();
            }
            List<Channel.EpgItem> data = new ArrayList<>();
            if (!originEpgList.isEmpty()) {
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
                for (Channel.EpgItem item : originEpgList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);
                    if (match) {
                        data.add(item);
                    }
                }
                Collections.sort(data, Comparator.comparing(o -> o.time));
                if (dateIndex == 0) {
                    String now = getNow();
                    Channel.EpgItem playing = null;
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
                        String currEnd = epgEndTimeMap.get(curr);
                        if (isTimeBetween(now, curr.time, currEnd)) {
                            curr.isPlaying = true;
                            playing = curr;
                            playingIndex = i;
                        }
                    }
                } else {
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
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                if (selectedPosition >= finalData.size()) {
                    selectedPosition = Math.max(0, finalData.size() - 1);
                }
                lvEpg.setSelection(selectedPosition);
                adapter.notifyDataSetChanged();
                scrollToCurrentProgram(finalData);
            });
        }).start();
    }

    private void scrollToCurrentProgram(List<Channel.EpgItem> epgList) {
        if (epgList == null || epgList.isEmpty() || selectDayIndex != 0) {
            return;
        }
        String now = getNow();
        for (int i = 0; i < epgList.size(); i++) {
            Channel.EpgItem item = epgList.get(i);
            String start = item.time;
            String end = epgEndTimeMap.get(item);
            if (start != null && end != null && isTimeBetween(now, start, end)) {
                final int scrollPos = i;
                lvEpg.post(() -> {
                    lvEpg.setSelection(scrollPos);
                    lvEpg.setSelectionFromTop(scrollPos, lvEpg.getHeight() / 2);
                });
                break;
            }
        }
    }

    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) return false;
            return now.contains(":") && start.contains(":") && end.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

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

    private String getNow() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    // 🛠️【修复】使用成员变量 reminderReceiver
    private void registerReminderReceiver() {
        reminderReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String title = intent.getStringExtra("title");
                    Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REMINDER);
        ContextCompat.registerReceiver(context, reminderReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // =========================================================================
    // 🟢 新增方法：供 RemoteKeyHandler 调用
    // =========================================================================

    /**
     * 设置选中位置
     */
    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        if (lvEpg != null && lvEpg.getAdapter() != null) {
            lvEpg.setSelection(position);
            ((ArrayAdapter<?>) lvEpg.getAdapter()).notifyDataSetChanged();
        }
    }

    /**
     * 获取指定位置的节目
     */
    public Object getProgramAt(int position) {
        if (adapter != null && position >= 0 && position < adapter.getCount()) {
            return adapter.getItem(position);
        }
        return null;
    }

    /**
     * 判断是否可回看
     */
    public boolean isCatchUpAvailable(Object program) {
        if (!(program instanceof Channel.EpgItem)) return false;
        Channel.EpgItem item = (Channel.EpgItem) program;
        // 只在今天且节目已过去时可回看
        return selectDayIndex == 0 && item != null && !item.isPlaying && isPastTime(item.time);
    }

    /**
     * 开始回看
     */
    public void startCatchUp(Object program) {
        if (!(program instanceof Channel.EpgItem)) return;
        Channel.EpgItem item = (Channel.EpgItem) program;
        // 调用回看逻辑
        // 具体实现参考 EpgAdapter 中的回看逻辑
        // 这里可以通过回调或广播触发回看
        Toast.makeText(context, "开始回看：" + item.title, Toast.LENGTH_SHORT).show();
    }

    /**
     * 停止回看
     */
    public void stopCatchUp() {
        // 停止回看逻辑
        Toast.makeText(context, "已退出回看模式", Toast.LENGTH_SHORT).show();
    }

    /**
     * 判断是否可预约
     */
    public boolean isReservable(Object program) {
        if (!(program instanceof Channel.EpgItem)) return false;
        Channel.EpgItem item = (Channel.EpgItem) program;
        // 未来节目可预约
        return item != null && !item.isPlaying && !isPastTime(item.time);
    }

    /**
     * 切换预约状态
     */
    public boolean toggleReservation(Object program) {
        if (!(program instanceof Channel.EpgItem)) return false;
        Channel.EpgItem item = (Channel.EpgItem) program;
        String key = currentChannelName + "_" + getItemIndex(item);
        if (bookedSet.contains(key)) {
            bookedSet.remove(key);
            return false;
        } else {
            bookedSet.add(key);
            return true;
        }
    }

    /**
     * 刷新数据
     */
    public void notifyDataSetChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 判断时间是否已过去
     */
    private boolean isPastTime(String time) {
        if (TextUtils.isEmpty(time)) return false;
        String now = getNow();
        try {
            return time.compareTo(now) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取节目在列表中的索引
     */
    private int getItemIndex(Channel.EpgItem item) {
        if (adapter == null) return -1;
        for (int i = 0; i < adapter.getCount(); i++) {
            Channel.EpgItem curr = adapter.getItem(i);
            if (curr != null && curr.title != null && curr.title.equals(item.title)) {
                return i;
            }
        }
        return -1;
    }

    // =========================================================================

    // 🛠️【新增】释放资源切断引用
    public void release() {
        if (context != null && reminderReceiver != null) {
            try {
                context.unregisterReceiver(reminderReceiver);
            } catch (Exception ignored) {}
            reminderReceiver = null;
        }
        if (adapter != null) {
            adapter.clear();
            adapter = null;
        }
        bookedSet.clear();
        epgEndTimeMap.clear();
        if (lvEpg != null) {
            lvEpg.setAdapter(null);
            lvEpg.setOnItemSelectedListener(null);
            lvEpg.setOnFocusChangeListener(null);
        }
        context = null;
    }

    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;
        private String currentNowStr;
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        private final View.OnClickListener actionClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Object tag = v.getTag();
                if (!(tag instanceof ItemActionTag)) return;
                ItemActionTag actionTag = (ItemActionTag) tag;

                Channel.EpgItem item = actionTag.item;
                String key = actionTag.key;

                if (actionTag.isPast) {
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
                        String endTime = epgEndTimeMap.get(item);
                        String[] endHm = endTime.split(":");
                        Calendar endCal = (Calendar) playDay.clone();
                        endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                        endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                        endCal.set(Calendar.SECOND, 0);
                        String startStr = sdfFull.format(startCal.getTime());
                        String endStr = sdfFull.format(endCal.getTime());
                        String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                        catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr + "-" + endStr;

                        if (ctx instanceof MainActivity) {
                            MainActivity activity = (MainActivity) ctx;
                            ChannelPanelController controller = activity.getChannelPanelController();
                            if (controller != null && controller.isPanelOpen()) {
                                controller.hidePanel();
                            }
                            activity.setCatchUpMode(true);
                            activity.showExoController();
                            activity.mPlayerManager.playUrl(catchUrl);
                        }
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    if (bookedSet.contains(key)) {
                        bookedSet.remove(key);
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                    } else {
                        bookedSet.add(key);
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                    }
                    updateActionButtonState(v, actionTag);
                }
            }
        };

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
            this.currentNowStr = getNow();
            notifyDataSetChanged();
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

            if (position < 0 || position >= list.size()) {
                return convertView;
            }

            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);

            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);
            convertView.setSelected(false);

            boolean isFocused = (position == selectedPosition) && lvEpg.hasFocus();
            boolean isPlaying = item.isPlaying && dayIndex == 0;

            if (isFocused) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (isPlaying) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            
            if (dayIndex == 0) {
                if (currentNowStr == null) currentNowStr = getNow();
                try {
                    if (item.time != null) {
                        isPast = item.time.compareTo(currentNowStr) < 0;
                    }
                } catch (Exception ignored) {}
            }

            ItemActionTag tag = new ItemActionTag();
            tag.item = item;
            tag.key = key;
            tag.isPast = isPast;
            holder.tv_action.setTag(tag);
            holder.tv_action.setOnClickListener(actionClickListener);

            if (dayIndex == 0) {
                if (item.isPlaying) {
                    holder.tv_action.setText("播放中");
                    holder.tv_action.setBackgroundColor(0xFFFF9800);
                    holder.tv_action.setEnabled(false);
                } else if (isPast) {
                    holder.tv_action.setText("回看");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(true);
                } else {
                    holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                }
            } else {
                holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                holder.tv_action.setBackgroundColor(0xFF4CAF50);
                holder.tv_action.setEnabled(true);
            }

            return convertView;
        }

        private void updateActionButtonState(View rootView, ItemActionTag tag) {
            TextView actionBtn = rootView.findViewById(R.id.tv_action);
            if (actionBtn == null) return;
            if (tag.isPast) {
                // 回看按钮无状态变化
            } else {
                boolean isBooked = bookedSet.contains(tag.key);
                actionBtn.setText(isBooked ? "已预约" : "预约");
                actionBtn.setBackgroundColor(0xFF4CAF50);
            }
        }

        private class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }

        private class ItemActionTag {
            Channel.EpgItem item;
            String key;
            boolean isPast;
        }
    }
}
