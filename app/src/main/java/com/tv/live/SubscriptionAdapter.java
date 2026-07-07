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

        String displayText = item.name;
        if (item.url != null && !item.url.isEmpty()) {
            displayText += "\n" + item.url;
        } else {
            displayText += "\n(未找到链接地址)";
        }
        tvUrl.setText(displayText);

        // =================================================================
        // 🛡️ 核心逻辑：只要是 UrlConfig 里的地址，永远不显示删除按钮
        // =================================================================
        boolean isProtected = item.url != null && !item.url.isEmpty() &&
                (item.url.equals(UrlConfig.LIVE_URL) || item.url.equals(UrlConfig.EPG_URL));

        if (isProtected) {
            btnDelete.setVisibility(View.GONE);
        } else {
            btnDelete.setVisibility(View.VISIBLE);
        }

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

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
            android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
        });

        btnDelete.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });

        convertView.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onSwitch(position);
            }
        });

        return convertView;
    }
}
