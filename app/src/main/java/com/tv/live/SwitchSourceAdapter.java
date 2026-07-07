package com.tv.live;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.List;

public class SwitchSourceAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private OnDeleteClickListener deleteListener;

    public interface OnDeleteClickListener {
        void onDelete(int position);
    }

    public SwitchSourceAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.deleteListener = listener;
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
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_switch_source, parent, false);
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) return convertView;

        RadioButton rbSelect = convertView.findViewById(R.id.rb_select);
        TextView tvUrl = convertView.findViewById(R.id.tv_url);
        Button btnDelete = convertView.findViewById(R.id.btn_delete_source);

        // 设置完整链接（不截断）
        tvUrl.setText(item.url);
        tvUrl.setSingleLine(false);
        tvUrl.setEllipsize(null);

        // 选中状态
        rbSelect.setChecked(position == selectedPosition);

        // 默认源不显示删除按钮（且不可点击）
        if (item.isDefault) {
            btnDelete.setVisibility(View.GONE);
        } else {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setFocusable(true);
            btnDelete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onDelete(position);
                }
            });
        }

        // 点击整个条目触发切换
        convertView.setOnClickListener(v -> {
            setSelectedPosition(position);
            if (deleteListener != null) {
                deleteListener.onDelete(-1); // 用 -1 表示是切换操作，不是删除
            }
        });

        return convertView;
    }
}
