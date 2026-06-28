package com.tv.live.widget;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.Channel;
import com.tv.live.R;
import com.tv.live.SettingsActivity;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表管理器【内存泄漏修复完整版】
 */
public class ChannelListManager {
    // 弱引用存储上下文与ListView，取消强持有
    private final WeakReference<Context> ctxRef;
    private final WeakReference<ListView> lvRef;

    private int selectedPosition = 0;
    private int currentPlayIndex = 0;
    private boolean hasFocus = false;

    // 业务监听器
    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public interface OnChannelLongClickListener {
        boolean onChannelLongClick(String channelName, int position);
    }
    private OnChannelLongClickListener onChannelLongClickListener;

    // ===================== 静态弱引用监听器（消除匿名泄漏） =====================
    private static class ItemClickListener implements AdapterView.OnItemClickListener {
        private final WeakReference<ChannelListManager> mgrRef;
        public ItemClickListener(ChannelListManager mgr) {
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
            ChannelListManager manager = mgr.get();
            if (manager == null) return;
            manager.selectedPosition = pos;
            ArrayAdapter<?> adapter = (Array<?>) parent.getAdapter();
            if (adapter != null) adapter.notifyDataSetChanged();
            if (manager.onChannelClickListener != null) {
                manager.onChannelClickListener.onChannelClick(pos);
            }
        }
    }

    private static class ItemLongClickListener implements AdapterView.OnItemLongClickListener {
        private final WeakReference<ChannelListManager> mgrRef;
        public ItemLongClickListener(ChannelListManager mgr) {
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
            ChannelListManager manager = mgr.get();
            if (manager == null) return false;
            SettingsActivity.logOperation("【列表】长按事件触发，position=" + pos
                    + ", listener=" + (manager.onChannelLongClickListener != null ? "已设置" : "未设置"));
            if (manager.onChannelLongClickListener == null) return false;
            String channelName = null;
            if (parent.getAdapter() != null && pos < parent.getAdapter().getCount()) {
                Object item = parent.getItemAtPosition(pos);
                if (item != null) channelName = item.toString();
            }
            SettingsActivity.logOperation("【列表】长按回调，channelName=" + channelName);
            boolean res = manager.onChannelLongClickListener.onChannelLongClick(channelName, pos);
            SettingsActivity.logOperation("【列表】长按回调结果=" + res);
            return res;
        }
    }

    private static class ItemSelectListener implements AdapterView.OnItemSelectedListener {
        private final WeakReference<ChannelListManager> mgrRef;
        public ItemSelectListener(ChannelListManager mgr) {
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            ChannelListManager manager = mgr.get();
            if (manager == null) return;
            manager.selectedPosition = pos;
            ArrayAdapter<?> adapter = (Array<?>) parent.getAdapter();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    }

    // 构造：弱引用包装Context、ListView
    public ChannelListManager(Context context, ListView lvChannelList) {
        this.ctxRef = new WeakReference<>(context.getApplicationContext());
        this.lvRef = new WeakReference<>(lvChannelList);
        ListView lv = lvRef.get();
        if (lv == null) return;
        lv.setItemsCanFocus(false);
        // 全部使用静态弱引用监听器，不再写匿名内部类
        lv.setOnItemClickListener(new ItemClickListener(this));
        lv.setOnItemLongClickListener(new ItemLongClickListener(this));
        lv.setOnItemSelectedListener(new ItemSelectListener(this));
    }

    // 安全获取上下文
    private Context getCtx() {
        return ctx != null ? ctxRef.get() : null;
    }
    // 安全获取ListView
    private ListView getLv() {
        return lvRef != null ? lvRef.get() : null;
    }

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    public void setOnChannelLongClickListener(OnChannelLongClickListener listener) {
        this.onChannelLongClickListener = listener;
        SettingsActivity.logOperation("【列表】setOnChannelLongClickListener 被调用，listener="
                + (listener != null ? "已设置" : "null"));
    }

    public void setFocused(boolean focused) {
        if (this.hasFocus == focused) return;
        this.hasFocus = focused;
        ListView lv = getLv();
        if (lv != null && lv.getAdapter() != null) {
            ((ArrayAdapter<?>) lv.getAdapter()).notifyDataSetChanged();
        }
    }

    public boolean isFocused() {
        return hasFocus;
    }

    // 加载全部频道
    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        ListView lv = getLv();
        Context ctx = getCtx();
        if (lv == null || ctx == null || channelSourceList == null || channelSourceList.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());
        this.selectedPosition = currentPlayIndex;
        this.currentPlayIndex = currentPlayIndex;
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx, R.layout.item_channel, names) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(ctx).inflate(R.layout.item_channel, parent, false);
                }
                TextView tvIndex = convertView.findViewById(R.id.tv_index);
                TextView tvChannel = convertView.findViewById(R.id.tv_channel);
                if (pos == currentPlayIndex) {
                    tvIndex.setText("▶");
                } else {
                    tvIndex.setText(String.valueOf(pos + 1));
                }
                tvChannel.setText(getItem(pos));
                tvChannel.setTextSize(16);
                // 焦点+选中样式逻辑
                if (pos == selectedPosition) {
                    if (hasFocus) {
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(0x3340A9FF);
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    } else {
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    }
                } else {
                    tvChannel.setTextColor(Color.WHITE);
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    tvIndex.setTextColor(Color.parseColor("#888888"));
                }
                return convertView;
            }
        };
        lv.setAdapter(adapter);
        lv.setSelection(selectedPosition);
    }

    // 按分组加载频道
    public void setChannelsByGroup(List<Channel> channelSourceList, String group, int currentPlayIndex) {
        ListView lv = getLv();
        Context ctx = getCtx();
        if (lv == null || ctx == null || channelSourceList == null || channelSourceList.isEmpty()) return;
        List<String> names = new ArrayList<>();
        int realIndex = 0;
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel c = channelSourceList.get(i);
            if (group == null || group.isEmpty() || group.equals(c.getGroup())) {
                names.add(c.getName());
                if (i == currentPlayIndex) realIndex = names.size() - 1;
            }
        }
        this.selectedPosition = realIndex;
        this.currentPlayIndex = realIndex;
        ArrayAdapter<String> adapter = buildChannelAdapter(ctx, names);
        lv.setAdapter(adapter);
        lv.setSelection(selectedPosition);
    }

    // 收藏/筛选频道
    public void setFilteredChannels(List<Channel> filteredChannels, String currentPlayChannelName) {
        ListView lv = getLv();
        Context ctx = getCtx();
        if (lv == null || ctx == null) return;
        SettingsActivity.logOperation("【列表】setFilteredChannels 被调用，列表大小="
                + (filteredChannels == null ? "null" : filteredChannels.size())
                + ", 当前频道=" + currentPlayChannelName);
        List<String> names = new ArrayList<>();
        int playIndex = 0;
        if (filteredChannels != null) {
            for (int i = 0; i < filteredChannels.size(); i++) {
                Channel c = filteredChannels.get(i);
                names.add(c.getName());
                if (currentPlayChannelName != null && currentPlayChannelName.equals(c.getName())) {
                    playIndex = i;
                }
            }
        }
        this.selectedPosition = playIndex;
        this.currentPlayIndex = playIndex;
        ArrayAdapter<String> adapter = buildChannelAdapter(ctx, names);
        lv.setAdapter(adapter);
        lv.setSelection(selectedPosition);
    }

    // 复用适配器构造
    private ArrayAdapter<String> buildChannelAdapter(Context ctx, List<String> names) {
        return new ArrayAdapter<String>(ctx, R.layout.item_channel, names) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(ctx).inflate(R.layout.item_channel, parent, false);
                }
                TextView tvIndex = convertView.findViewById(R.id.tv_index);
                TextView tvChannel = convertView.findViewById(R.id.tv_channel);
                if (pos == currentPlayIndex && names.size() > 0) {
                    tvIndex.setText("▶");
                } else {
                    tvIndex.setText(String.valueOf(pos + 1));
                }
                tvChannel.setText(getItem(pos));
                tvChannel.setTextSize(16);
                if (pos == selectedPosition) {
                    if (hasFocus) {
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(0x3340A9FF);
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    } else {
                        tvChannel.setTextColor(Color.parseColor("#40A9FF"));
                        tvChannel.setTypeface(null, Typeface.BOLD);
                        convertView.setBackgroundColor(Color.TRANSPARENT);
                        tvIndex.setTextColor(Color.parseColor("#40A9FF"));
                    }
                } else {
                    tvChannel.setTextColor(Color.WHITE);
                    tvChannel.setTypeface(null, Typeface.NORMAL);
                    convertView.setBackgroundColor(Color.TRANSPARENT);
                    tvIndex.setTextColor(Color.parseColor("#888888"));
                }
                return convertView;
            }
        };
    }

    // ========== 标准release 完整资源释放 ==========
    public void release() {
        // 1. 清空业务回调监听器
        onChannelClickListener = null;
        onChannelLongClickListener = null;

        // 2. 解绑ListView所有监听，切断View持有引用
        ListView lv = getLv();
        if (lv != null) {
            lv.setOnItemClickListener(null);
            lv.setOnItemLongClickListener(null);
            lv.setOnItemSelectedListener(null);
            lv.setAdapter(null);
        }

        // 3. 清空弱引用，帮助GC回收
        if (ctxRef != null) ctxRef.clear();
        if (lvRef != null) lvRef.clear();

        // 4. 重置全部状态变量
        selectedPosition = 0;
        currentPlayIndex = 0;
        hasFocus = false;
    }
}
