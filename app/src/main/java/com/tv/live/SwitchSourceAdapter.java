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
        void onSelect(int position);
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

        // 显示完整链接
        tvUrl.setText(item.url);
        tvUrl.setSingleLine(false);
        tvUrl.setEllipsize(null);

        // 🟢 关键修复：如果当前选中了该项，单选按钮打勾
        rbSelect.setChecked(position == selectedPosition);

        // 🟢 关键修复：如果是默认源，隐藏删除按钮；普通源显示删除按钮
        if (item.isDefault) {
            btnDelete.setVisibility(View.GONE);
        } else {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onDelete(position);
                }
            });
        }

        // 点击整行，触发切换选中
        convertView.setOnClickListener(v -> {
            setSelectedPosition(position);
            if (deleteListener != null) {
                deleteListener.onSelect(position);
            }
        });

        return convertView;
    }
}
