package com.tv.live.widget;

import android.content.Context;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表管理器
 * 完全依赖系统原生焦点导航，样式由 XML 选择器控制
 */
public class ChannelListManager {
    private final ListView lvChannelList;
    private int selectedPosition = 0;
    private int currentPlayIndex = 0;

    public interface OnChannelClickListener {
        void onChannelClick(int position);
    }
    private OnChannelClickListener onChannelClickListener;

    public interface OnChannelLongClickListener {
        boolean onChannelLongClick(String channelName, int position);
    }
    private OnChannelLongClickListener onChannelLongClickListener;

    public ChannelListManager(Context context, ListView lvChannelList) {
        this.lvChannelList = lvChannelList;
        lvChannelList.setItemsCanFocus(false);

        lvChannelList.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            if (onChannelClickListener != null) {
                onChannelClickListener.onChannelClick(position);
            }
        });

        lvChannelList.setOnItemLongClickListener((parent, view, position, id) -> {
            if (onChannelLongClickListener != null) {
                String channelName = null;
                if (parent.getAdapter() != null && position < parent.getAdapter().getCount()) {
                    Object item = parent.getAdapter().getItem(position);
                    if (item != null) {
                        channelName = item.toString();
                    }
                }
                return onChannelLongClickListener.onChannelLongClick(channelName, position);
            }
            return false;
        });

        lvChannelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                ((ArrayAdapter<?>) parent.getAdapter()).notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // 删除 setFocused() 和 isFocused()

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.onChannelClickListener = listener;
    }

    public void setOnChannelLongClickListener(OnChannelLongClickListener listener) {
        this.onChannelLongClickListener = listener;
    }

    // ====================================================================
    // 显示全部频道
    // ====================================================================
    public void setChannels(List<Channel> channelSourceList, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) names.add(c.getName());

        selectedPosition = currentPlayIndex;
        this.currentPlayIndex = currentPlayIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == currentPlayIndex) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                // =========================================================
                // ✅【补充：加粗逻辑】
                // 当列表拥有焦点，且当前条目为选中项时，字体加粗
                // =========================================================
                boolean hasFocus = lvChannelList.hasFocus();
                if (position == selectedPosition && hasFocus) {
                    holder.tvChannel.setTypeface(null, Typeface.BOLD);
                } else {
                    holder.tvChannel.setTypeface(null, Typeface.NORMAL);
                }

                // 🟢【核心修复】显式设置选中状态，让 XML 选择器的 state_selected 生效！
                convertView.setSelected(position == selectedPosition);

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    // ====================================================================
    // 按分组显示频道
    // ====================================================================
    public void setChannelsByGroup(List<Channel> channelSourceList, String group, int currentPlayIndex) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        List<String> names = new ArrayList<>();
        int realIndex = 0;
        for (int i = 0; i < channelSourceList.size(); i++) {
            Channel c = channelSourceList.get(i);
            if (group == null || group.isEmpty() || group.equals(c.getGroup())) {
                names.add(c.getName());
                if (i == currentPlayIndex) {
                    realIndex = names.size() - 1;
                }
            }
        }

        selectedPosition = realIndex;
        this.currentPlayIndex = realIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == currentPlayIndex) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                // =========================================================
                // ✅【补充：加粗逻辑】
                // =========================================================
                boolean hasFocus = lvChannelList.hasFocus();
                if (position == selectedPosition && hasFocus) {
                    holder.tvChannel.setTypeface(null, Typeface.BOLD);
                } else {
                    holder.tvChannel.setTypeface(null, Typeface.NORMAL);
                }

                // 🟢【核心修复】显式设置选中状态！
                convertView.setSelected(position == selectedPosition);

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    // ====================================================================
    // 显示筛选后的频道列表
    // ====================================================================
    public void setFilteredChannels(List<Channel> filteredChannels, String currentPlayChannelName) {
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

        selectedPosition = playIndex;
        this.currentPlayIndex = playIndex;
        final int finalPlayIndex = playIndex;

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(lvChannelList.getContext(),
                R.layout.item_channel, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_channel, parent, false);
                    holder = new ViewHolder();
                    holder.tvIndex = convertView.findViewById(R.id.tv_index);
                    holder.tvChannel = convertView.findViewById(R.id.tv_channel);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (position == finalPlayIndex && names.size() > 0) {
                    holder.tvIndex.setText("▶");
                } else {
                    holder.tvIndex.setText(String.valueOf(position + 1));
                }

                holder.tvChannel.setText(getItem(position));
                holder.tvChannel.setTextSize(16);

                // =========================================================
                // ✅【补充：加粗逻辑】
                // =========================================================
                boolean hasFocus = lvChannelList.hasFocus();
                if (position == selectedPosition && hasFocus) {
                    holder.tvChannel.setTypeface(null, Typeface.BOLD);
                } else {
                    holder.tvChannel.setTypeface(null, Typeface.NORMAL);
                }

                // 🟢【核心修复】显式设置选中状态！
                convertView.setSelected(position == selectedPosition);

                return convertView;
            }
        };

        lvChannelList.setAdapter(adapter);
        lvChannelList.setSelection(selectedPosition);
    }

    private static class ViewHolder {
        TextView tvIndex;
        TextView tvChannel;
    }
}
