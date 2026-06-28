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

import java.lang.ref.WeakReference;
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
 * EPG 节目单包装管理器【内存泄漏修复完整版】
 * 样式更新规则
 * 1、焦点选中条目（最高优先级）：蓝色字体+加粗+浅蓝色半透明背景
 * 2、播放中条目（仅今日首位，无焦点）：蓝色字体、不加粗、透明无背景
 * 3、普通条目：白色常规文字、透明背景
 * 4、非今日完全不渲染播放中蓝色样式
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final WeakReference<Context> ctxRef;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private ReminderReceiver reminderReceiver;

    private int selectedPosition = 0;
    private int playingIndex = -1;
    private int selectDayIndex = 0;

    // ===================== 全部静态弱引用监听器/广播/点击事件 =====================
    // ListView条目选中监听
    private static class ItemSelectListener implements AdapterView.OnItemSelectedListener {
        private final WeakReference<EpgManagerWrapper> mgrRef;
        public ItemSelectListener(EpgManagerWrapper mgr) { mgrRef = new WeakReference<>(mgr); }
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            EpgManagerWrapper wrapper = mgr.get();
            if (wrapper == null) return;
            wrapper.selectedPosition = pos;
            if (wrapper.adapter != null) wrapper.adapter.notifyDataSetChanged();
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            EpgManagerWrapper wrapper = mgr.get();
            if (wrapper == null) return;
            wrapper.selectedPosition = -1;
            if (wrapper.adapter != null) wrapper.adapter.notifyDataSetChanged();
        }
    }

    // ListView焦点变化监听
    private static class ListFocusListener implements View.OnFocusChangeListener {
        private final WeakReference<EpgManagerWrapper> mgrRef;
        public ListFocusListener(EpgManagerWrapper mgr) { mgrRef = new WeakReference<>(mgr); }
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            EpgManagerWrapper wrapper = mgr.get();
            if (wrapper != null && wrapper.adapter != null) wrapper.adapter.notifyDataSetChanged();
        }
    }

    // 节目提醒广播接收器
    private static class ReminderReceiver extends BroadcastReceiver {
        private final WeakReference<Context> ctxRef;
        public ReminderReceiver(Context ctx) { ctxRef = new WeakReference<>(ctx); }
        @Override
        public void onReceive(Context context, Intent intent) {
            Context ctx = ctxRef.get();
            if (ctx == null || !ACTION_REMINDER.equals(intent.getAction())) return;
            String title = intent.getStringExtra("title");
            Toast.makeText(ctx, "节目提醒：" + title, Toast.LENGTH_LONG).show();
        }
    }

    // 回看按钮点击
    private static class PlaybackClick implements View.OnClickListener {
        private final WeakReference<EpgManagerWrapper> mgrRef;
        private final Channel channel;
        private final Channel.EpgItem item;
        private final int dayIdx;
        private final WeakReference<Context> ctxRef;

        public PlaybackClick(EpgManagerWrapper wrapper, Context ctx, Channel ch, Channel.EpgItem epgItem, int dayIndex) {
            mgrRef = new WeakReference<>(wrapper);
            ctxRef = new WeakReference<>(ctx);
            channel = ch;
            item = epgItem;
            dayIdx = dayIndex;
        }

        @Override
        public void onClick(View v) {
            Context ctx = ctxRef.get();
            EpgManagerWrapper wrapper = mgr.get();
            if (ctx == null || wrapper == null || channel == null || item == null) return;
            try {
                String liveUrl = channel.getPlayUrl();
                if (TextUtils.isEmpty(liveUrl)) {
                    Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                Calendar playDay = Calendar.getInstance();
                playDay.add(Calendar.DAY_OF_YEAR, dayIdx);
                String[] startHm = item.time.split(":");
                Calendar startCal = (Calendar) playDay.clone();
                startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0].trim()));
                startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1].trim()));
                startCal.set(Calendar.SECOND, 0);

                String endStr = wrapper.epgEndTimeMap.get(item);
                String[] endHm = endStr.split(":");
                Calendar endCal = (Calendar) playDay.clone();
                endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                endCal.set(Calendar.SECOND, 0);

                SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);
                String startText = sdfFull.format(startCal.getTime());
                String endText = sdfFull.format(endCal.getTime());

                String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                catchUrl += catchUrl.contains("?") ? "&playseek=" + startText + "-" + endText : "?playseek=" + startText + "-" + endText;

                if (ctx instanceof MainActivity) {
                    ((MainActivity) ctx).mPlayer.playUrl(catchUrl);
                }
                Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 预约按钮点击
    private static class BookClick implements View.OnClickListener {
        private final WeakReference<EpgManagerWrapper> mgrRef;
        private final String key;
        private final String title;

        public BookClick(EpgManagerWrapper wrapper, String bookKey, String epgTitle) {
            mgrRef = new WeakReference<>(wrapper);
            key = bookKey;
            title = epgTitle;
        }

        @Override
        public void onClick(View v) {
            EpgManagerWrapper wrapper = mgr.get();
            if (wrapper == null) return;
            Context ctx = wrapper.ctxRef.get();
            if (ctx == null) return;
            if (wrapper.bookedSet.contains(key)) {
                wrapper.bookedSet.remove(key);
                Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
            } else {
                wrapper.bookedSet.add(key);
                Toast.makeText(ctx, "已预约：" + title, Toast.LENGTH_SHORT).show();
            }
            if (wrapper.adapter != null) wrapper.adapter.notifyDataSetChanged();
        }
    }

    // 构造：弱引用包装ApplicationContext
    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.ctxRef = new WeakReference<>(context.getApplicationContext());
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(true);
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        // 替换匿名监听为静态弱引用类
        lvEpg.setOnItemSelectedListener(new ItemSelectListener(this));
        lvEpg.setOnFocusChangeListener(new ListFocusListener(this));
        registerReminderReceiver();
    }

    // 安全获取上下文
    private Context getCtx() {
        return ctx != null ? ctxRef.get() : null;
    }

    private void registerReminderReceiver() {
        Context ctx = getCtx();
        if (ctx == null) return;
        reminderReceiver = new ReminderReceiver(ctx);
        ctx.registerReceiver(reminderReceiver, new IntentFilter(ACTION_REMINDER));
    }

    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        Context ctx = getCtx();
        if (ctx == null || currentChannel == null) {
            SettingsActivity.log("【EPG包装】❌ Context/频道为空，终止刷新");
            return;
        }
        SettingsActivity.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() + "，日期索引：" + dateIndex);
        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();

        new Thread(() -> {
            List<Channel.EpgItem> originEpgList;
            try {
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel.getName());
                originEpgList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
            } catch (Exception e) {
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                originEpgList = new ArrayList<>();
            }
            SettingsActivity.log("【EPG包装】📋 原始节目数：" + originEpgList.size());
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
                SettingsActivity.log("【EPG包装】🎯 目标日期：" + targetDay + "，对应周几：" + weekDay);
                int matchCount = 0;
                for (Channel.EpgItem item : originEpgList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);
                    if (match) {
                        data.add(item);
                        matchCount++;
                    }
                }
                SettingsActivity.log("【EPG包装】✅ 筛选后节目数：" + matchCount);
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
                    if (playing != null && playingIndex > 0) {
                        data.remove(playing);
                        data.add(0, playing);
                        playingIndex = 0;
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
            Context uiCtx = getCtx();
            if (uiCtx instanceof MainActivity) {
                ((MainActivity) uiCtx).runOnUiThread(() -> {
                    SettingsActivity.logOperation("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());
                    if (adapter == null) {
                        adapter = new EpgAdapter(this, uiCtx, finalChannel, finalData, selectDayIndex);
                        lvEpg.setAdapter(adapter);
                    } else {
                        adapter.setData(finalChannel, finalData, selectDayIndex);
                    }
                    if (selectedPosition >= finalData.size()) {
                        selectedPosition = Math.max(0, finalData.size() - 1);
                    }
                    lvEpg.setSelection(selectedPosition);
                    adapter.notifyDataSetChanged();
                    SettingsActivity.logOperation("【EPG包装】✅ UI更新完成");
                });
            }
        }).start();
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

    // ========== 标准完整release 全部资源释放 ==========
    public void release() {
        // 1 解绑ListView所有监听
        if (lvEpg != null) {
            lvEpg.setOnItemSelectedListener(null);
            lvEpg.setOnFocusChangeListener(null);
            lvEpg.setAdapter(null);
        }

        // 2 注销广播接收器
        Context ctx = getCtx();
        if (ctx != null && reminderReceiver != null) {
            try {
                ctx.unregisterReceiver(reminderReceiver);
            } catch (Exception ignored) {}
            reminderReceiver = null;
        }

        // 3 清空弱引用上下文
        if (ctxRef != null) ctxRef.clear();

        // 4 清空缓存集合、适配器
        bookedSet.clear();
        epgEndTimeMap.clear();
        adapter = null;

        // 5 重置所有状态标记
        selectedPosition = 0;
        playingIndex = -1;
        selectDayIndex = 0;
    }

    // EPG适配器（内部类，内部点击全部使用静态弱引用点击类，无lambda匿名）
    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final WeakReference<EpgManagerWrapper> wrapperRef;
        private final WeakReference<Context> ctxRef;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;

        public EpgAdapter(EpgManagerWrapper wrapper, Context ctx, Channel ch, List<Channel.EpgItem> data, int dayIdx) {
            super(ctx, R.layout.item_epg, data);
            this.wrapperRef = new WeakReference<>(wrapper);
            this.ctxRef = new WeakReference<>(ctx);
            this.currentChannel = ch;
            this.list = data;
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIdx;
        }

        public void setData(Channel ch, List<Channel.EpgItem> data, int dayIdx) {
            this.currentChannel = ch;
            this.list.clear();
            this.list.addAll(data);
            this.dayIndex = dayIdx;
            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            EpgManagerWrapper wrapper = wrapperRef.get();
            Context ctx = ctxRef.get();
            if (wrapper == null || ctx == null) return convertView;

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

            if (position < 0 || position >= list.size()) return convertView;
            Channel.EpgItem item = list.get(position);
            String endTime = wrapper.epgEndTimeMap.get(item);

            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);

            // 重置样式
            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);

            boolean isFocused = (position == wrapper.selectedPosition) && lvEpg.hasFocus();
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
            try { isPast = item.time.compareTo(getNow()) < 0; } catch (Exception ignored) {}

            holder.tv_action.setOnClickListener(null);
            if (dayIndex == 0) {
                if (item.isPlaying) {
                    holder.tv_action.setText("播放中");
                    holder.tv_action.setBackgroundColor(0xFFFF9800);
                    holder.tv_action.setEnabled(false);
                } else if (isPast) {
                    holder.tv_action.setText("回看");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(true);
                    // 使用静态弱引用回看点击类，替换lambda匿名
                    holder.tv_action.setOnClickListener(new PlaybackClick(wrapper, ctx, currentChannel, item, dayIndex));
                } else {
                    holder.tv_action.setText("预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                    holder.tv_action.setOnClickListener(new BookClick(wrapper, key, item.title));
                }
            } else {
                holder.tv_action.setText("预约");
                holder.tv_action.setBackgroundColor(0xFF4CAF50);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(new BookClick(wrapper, key, item.title));
            }
            return convertView;
        }

        private class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
