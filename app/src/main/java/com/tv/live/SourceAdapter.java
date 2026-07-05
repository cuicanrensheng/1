package com.tv.live;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;

/**
 * 多源列表适配器
 */
public class SourceAdapter extends ArrayAdapter<SourceManager.SourceItem> {
    private final Context context;
    private int selectedPosition = -1;
    private OnDeleteClickListener onDeleteClickListener;

    // 🟢 优化：将颜色提取为常量，避免在 getView 中反复解析字符串
    private static final int COLOR_FOCUS = Color.parseColor("#40A9FF");
    private static final int COLOR_FOCUS_BG = 0x3340A9FF;
    private static final int COLOR_HOVER_BG = 0x4440A9FF;

    public interface OnDeleteClickListener {
        void onDelete(int position);
    }

    public SourceAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, R.layout.item_settings, items);
        this.context = context;
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.onDeleteClickListener = listener;
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_settings, parent, false);
        }

        SourceManager.SourceItem item = getItem(position);
        
        // 🟢 优化：增加空指针保护，防止在列表数据刷新时偶发崩溃
        if (item == null) {
            return convertView;
        }

        TextView tv = convertView.findViewById(R.id.tv_setting_item);
        TextView indexTv = convertView.findViewById(R.id.tv_index);
        Button deleteBtn = convertView.findViewById(R.id.btn_delete);

        indexTv.setText((position + 1) + ". ");

        StringBuilder displayText = new StringBuilder();
        displayText.append(item.name);
        if (item.isDefault) displayText.append("  ⭐");
        displayText.append("\n").append(item.url);
        if (!item.autoUpdate) displayText.append("  🔕");

        tv.setText(displayText.toString());
        tv.setTextSize(14);
        tv.setLineSpacing(4, 1);
        tv.setSingleLine(false);
        tv.setEllipsize(null);

        final int pos = position;
        deleteBtn.setOnClickListener(v -> {
            if (onDeleteClickListener != null) onDeleteClickListener.onDelete(pos);
        });
        deleteBtn.setClickable(true);
        deleteBtn.setFocusable(false);

        // 设置选中/高亮/普通三种状态的样式
        if (position == selectedPosition) {
            tv.setTextColor(COLOR_FOCUS);
            indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundColor(COLOR_FOCUS_BG);
        } else if (convertView.isFocused()) {
            tv.setTextColor(COLOR_FOCUS);
            indexTv.setTextColor(COLOR_FOCUS);
            convertView.setBackgroundColor(COLOR_HOVER_BG);
        } else {
            tv.setTextColor(Color.WHITE);
            indexTv.setTextColor(Color.WHITE);
            convertView.setBackgroundColor(Color.TRANSPARENT);
        }

        return convertView;
    }
}
