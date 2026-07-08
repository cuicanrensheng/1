package com.tv.live;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

public class SubscriptionAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private OnActionListener actionListener;

    // 🟢 核心优化：将颜色提取为常量，避免在 getView 中反复解析
    private static final int COLOR_SELECTED = 0xFF40A9FF;
    private static final int COLOR_SELECTED_BG = 0x3340A9FF;
    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_NORMAL_BG = 0x333545;

    public interface OnActionListener {
        void onSwitch(int position);
        void onDelete(int position);
    }

    public SubscriptionAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.actionListener = listener;
    }

    // 🟢 单例监听器：在适配器层面只创建一次，极大减少 GC 抖动
    private final View.OnClickListener clickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            // 通过 Tag 获取绑定的位置
            Object tag = v.getTag();
            if (!(tag instanceof Integer)) return;
            int pos = (Integer) tag;
            if (pos < 0 || pos >= getCount()) return;

            int id = v.getId();
            if (id == R.id.btn_delete) {
                if (actionListener != null) actionListener.onDelete(pos);
            } else if (id == R.id.btn_copy) {
                SourceManager.SourceItem item = getItem(pos);
                if (item != null && item.url != null) {
                    ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
                    android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
                }
            } else {
                // 默认整行点击切换
                if (actionListener != null) actionListener.onSwitch(pos);
            }
        }
    };

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_subscription_list, parent, false);
            holder = new ViewHolder();
            holder.tvCheck = convertView.findViewById(R.id.tv_check);
            holder.tvUrl = convertView.findViewById(R.id.tv_url);
            holder.btnCopy = convertView.findViewById(R.id.btn_copy);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);

            // 🟢 将单例监听器绑定到各个视图，仅此一次
            holder.btnCopy.setOnClickListener(clickListener);
            holder.btnDelete.setOnClickListener(clickListener);
            convertView.setOnClickListener(clickListener);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) {
            return convertView;
        }

        // 🟢 将当前位置存储在 View 的 Tag 中，供单例监听器读取
        holder.btnCopy.setTag(position);
        holder.btnDelete.setTag(position);
        convertView.setTag(position);

        String displayText = item.name;
        if (item.url != null && !item.url.isEmpty()) {
            displayText += "\n" + item.url;
        } else {
            displayText += "\n(未找到链接地址)";
        }
        holder.tvUrl.setText(displayText);

        // =================================================================
        // 🛡️ 核心逻辑：只要是 UrlConfig 里的地址，永远不显示删除按钮
        // =================================================================
        boolean isProtected = item.url != null && !item.url.isEmpty() &&
                (item.url.equals(UrlConfig.LIVE_URL) || item.url.equals(UrlConfig.EPG_URL));

        if (isProtected) {
            holder.btnDelete.setVisibility(View.GONE);
        } else {
            holder.btnDelete.setVisibility(View.VISIBLE);
        }

        boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            holder.tvCheck.setVisibility(View.VISIBLE);
            holder.tvUrl.setTextColor(COLOR_SELECTED);
            convertView.setBackgroundColor(COLOR_SELECTED_BG);
        } else {
            holder.tvCheck.setVisibility(View.GONE);
            holder.tvUrl.setTextColor(COLOR_NORMAL);
            convertView.setBackgroundColor(COLOR_NORMAL_BG);
        }

        return convertView;
    }

    // 🟢 静态内部类 ViewHolder，彻底杜绝 findViewById 的重复调用
    private static class ViewHolder {
        TextView tvCheck;
        TextView tvUrl;
        Button btnCopy;
        Button btnDelete;
    }
}
