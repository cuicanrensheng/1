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

/**
 * 订阅管理列表适配器
 * 用于「直播源订阅」和「节目单订阅」弹窗中的列表展示
 */
public class SubscriptionAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private OnActionListener actionListener;

    public interface OnActionListener {
        void onSwitch(int position); // 切换选中
        void onDelete(int position); // 删除
    }

    public SubscriptionAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    /**
     * 设置当前选中的位置
     * @param position 选中项的索引，-1 表示无选中
     */
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
        // 🛡️ 如果当前位置无效，直接返回视图（后续不设置点击事件，避免无效回调）
        if (item == null) {
            return convertView;
        }

        TextView tvCheck = convertView.findViewById(R.id.tv_check);
        TextView tvUrl = convertView.findViewById(R.id.tv_url);
        Button btnCopy = convertView.findViewById(R.id.btn_copy);
        Button btnDelete = convertView.findViewById(R.id.btn_delete);

        // 设置 URL 文本
        tvUrl.setText(item.url);

        // 处理选中状态
        boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            tvCheck.setVisibility(View.VISIBLE);
            tvUrl.setTextColor(0xFF40A9FF); // 蓝色高亮
            convertView.setBackgroundColor(0x3340A9FF);
        } else {
            tvCheck.setVisibility(View.GONE);
            tvUrl.setTextColor(0xFFFFFFFF); // 白色
            convertView.setBackgroundColor(0x333545);
        }

        // 复制按钮点击事件
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("source_url", item.url));
            android.widget.Toast.makeText(getContext(), "已复制地址", android.widget.Toast.LENGTH_SHORT).show();
        });

        // 🛡️ 删除按钮点击事件 - 增加位置有效性检查，防止因数据变化导致 position 无效而崩溃
        btnDelete.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onDelete(position);
            }
        });

        // 🛡️ 整行点击切换 - 同样增加位置有效性检查
        convertView.setOnClickListener(v -> {
            if (actionListener != null && position >= 0 && position < getCount()) {
                actionListener.onSwitch(position);
            }
        });

        return convertView;
    }
}
