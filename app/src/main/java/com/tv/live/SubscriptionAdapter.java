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
    private String defaultUrl = null; // 记录当前选中的默认源

    public interface OnActionListener {
        void onSwitch(int position); // 切换选中
        void onDelete(int position); // 删除
    }

    public SubscriptionAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    public void setDefaultUrl(String defaultUrl) {
        this.defaultUrl = defaultUrl;
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

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_subscription_list, parent, false);
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) {
            return convertView;
        }

        TextView tvCheck = convertView.findViewById(R.id.tv_check);
        TextView tvUrl = convertView.findViewById(R.id.tv_url);
        Button btnCopy = convertView.findViewById(R.id.btn_copy);
        Button btnDelete = convertView.findViewById(R.id.btn_delete);

        // 双行显示逻辑：名称 + 换行 + 地址
        String displayText = item.name;
        if (item.url != null && !item.url.isEmpty()) {
            displayText += "\n" + item.url;
        } else {
            displayText += "\n(未找到链接地址)";
        }
        tvUrl.setText(displayText);

        // =================================================================
        // 🛡️ 核心逻辑：强制保护 UrlConfig 中的源（绝不显示删除按钮）
        // =================================================================
        boolean isProtected = item.url != null && !item.url.isEmpty() &&
                (item.url.equals(UrlConfig.LIVE_URL) || item.url.equals(UrlConfig.EPG_URL));

        if (isProtected) {
            // 如果是 UrlConfig 声明的默认源，永远隐藏删除按钮
            btnDelete.setVisibility(View.GONE);
        } else {
            // 备用源：正常显示删除按钮
            btnDelete.setVisibility(View.VISIBLE);
        }
        // =================================================================

        // 处理选中状态 (蓝色高亮)
        boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            tvCheck.setVisibility(View.VISIBLE);
            tvUrl.setTextColor(0xFF40A9FF);
            convertView.setBackgroundColor(0x3340A9FF);
        } else {
            tvCheck.setVisibility(View.GONE);
            tvUrl.setTextColor(0xFFFFFFFF);
            convertView.setBackgroundColor(0x333545);
        }

        // 复制按钮
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
            android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
        });

        // 删除按钮（只对备用源有效）
        btnDelete.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });

        // 🎯 整行点击切换：不管是默认源还是备用源，点一下就能切换过去！
        convertView.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onSwitch(position);
            }
        });

        return convertView;
    }
}
