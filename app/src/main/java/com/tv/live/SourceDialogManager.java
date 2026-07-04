package com.tv.live;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
/**
 * 多源对话框管理器
 *
 * 【职责】
 * 负责所有多源管理对话框的 UI 交互，包括：
 * 1. 显示多源列表对话框
 * 2. 搜索功能
 * 3. 添加/编辑/删除/排序
 * 4. 导入/导出
 * 5. 设为默认/刷新/切换自动更新
 *
 * 【为什么拆分？】
 * 原来的 showHistoryDialog() 方法有 200+ 行，
 * 是 SettingsActivity 里最大的一个方法，代码太臃肿。
 * 拆出来后职责清晰，更好维护。
 *
 * 【业务逻辑委托】
 * 所有数据操作都委托给 SourceManager，
 * 这里只负责 UI 展示和用户交互。
 *
 * 【使用方式】
 * SourceDialogManager dialogManager = new SourceDialogManager(context, sp);
 * dialogManager.showHistoryDialog("直播源历史", "live_history");
 */
public class SourceDialogManager {
    // ====================== 常量 ======================
    /** 自定义直播源地址 Key */
    private static final String KEY_CUSTOM_LIVE = "custom_live";
    /** 自定义节目单地址 Key */
    private static final String KEY_CUSTOM_EPG = "custom_epg";
    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;
    /** SharedPreferences */
    private final SharedPreferences sp;
    /** 多源列表适配器 */
    private SourceAdapter adapter;
    // ====================== 构造函数 ======================
    /**
     * 构造函数
     * @param context 上下文
     * @param sp SharedPreferences 实例
     */
    public SourceDialogManager(Context context, SharedPreferences sp) {
        this.context = context;
        this.sp = sp;
    }
    // ====================================================================
    // 1. 显示多源管理对话框
    // ====================================================================
    public void showHistoryDialog(String title, final String key) {
        final SourceManager sourceManager = new SourceManager(context, key);
        // 弹窗初始化：一次性读取全量数据，不长期持有列表引用
        List<SourceManager.SourceItem> fullSourceList = sourceManager.getAllSources();
        if (fullSourceList.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage("暂无记录，是否添加一个？")
                    .setPositiveButton("添加", (d, w) -> {
                        SourceManager tempManager = new SourceManager(context, key);
                        SourceAdapter tempAdapter = new SourceAdapter(context, new ArrayList<>());
                        showAddSourceDialog(title, key, tempManager, tempAdapter, "");
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        // 初始化适配器，每次新建临时列表，不复用全局集合
        adapter = new SourceAdapter(context, new ArrayList<>(fullSourceList));
        adapter.setOnDeleteClickListener(position -> {
            List<SourceManager.SourceItem> freshList = sourceManager.getAllSources();
            if (position < 0 || position >= freshList.size()) return;
            SourceManager.SourceItem item = freshList.get(position);
            int realPos = sourceManager.indexOfUrl(item.url);
            new AlertDialog.Builder(context)
                    .setTitle("确认删除")
                    .setMessage("确定要删除「" + item.name + "」吗？")
                    .setPositiveButton("删除", (d, w) -> {
                        sourceManager.removeSource(realPos);
                        refreshAllSource(sourceManager, adapter, "");
                        adapter.setSelectedPosition(-1);
                        LogManager.logOperation("【设置】删除源：" + item.name);
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        // 匹配当前默认源，设置选中标记
        String currentUrl = sp.getString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, "");
        int selectedIndex = sourceManager.indexOfUrl(currentUrl);
        if (selectedIndex >= 0) {
            adapter.setSelectedPosition(selectedIndex);
        }
        final String finalTitle = title + "（共" + fullSourceList.size() + "个）";
        final EditText searchEt = new EditText(context);
        searchEt.setHint("🔍 搜索源名称或地址");
        searchEt.setTextSize(14);
        searchEt.setSingleLine(true);
        searchEt.setPadding(40, 20, 40, 20);
        searchEt.setBackgroundColor(0xFFEEEEEE);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(finalTitle);
        builder.setCustomTitle(searchEt);
        builder.setAdapter(adapter, null);
        // 添加按钮
        builder.setPositiveButton("➕ 添加", (dialog, which) -> {
            showAddSourceDialog(title, key, sourceManager, adapter, searchEt.getText().toString());
        });
        // 操作菜单按钮
        builder.setNeutralButton("⚙ 操作", (dialog, which) -> {
            final int pos = adapter.getSelectedPosition();
            List<SourceManager.SourceItem> freshAll = sourceManager.getAllSources();
            if (pos < 0 || pos >= freshAll.size()) {
                Toast.makeText(context, "请先选择一项", Toast.LENGTH_SHORT).show();
                return;
            }
            final SourceManager.SourceItem selectedItem = freshAll.get(pos);
            final String[] options = {
                    "✏️ 编辑",
                    "⭐ 设为默认",
                    "⬆ 移到顶部",
                    "⬇ 移到底部",
                    "🔄 刷新此源",
                    selectedItem.autoUpdate ? "🔕 关闭自动更新" : "🔔 开启自动更新",
                    "🗑 删除",
                    "📋 导出全部",
                    "📥 导入",
                    "🧹 清空全部"
            };
            new AlertDialog.Builder(context)
                    .setTitle("操作")
                    .setItems(options, (d, w) -> {
                        List<SourceManager.SourceItem> freshList = sourceManager.getAllSources();
                        int realPos = sourceManager.indexOfUrl(selectedItem.url);
                        switch (w) {
                            case 0: // 编辑
                                showEditSourceDialog(title, key, realPos, selectedItem, sourceManager, adapter, searchEt.getText().toString());
                                break;
                            case 1: // 设为默认
                                sourceManager.setDefault(realPos);
                                refreshAllSource(sourceManager, adapter, searchEt.getText().toString());
                                LogManager.logOperation("【设置】设为默认源：" + selectedItem.name);
                                Toast.makeText(context, "已设为默认", Toast.LENGTH_SHORT).show();
                                break;
                            case 2: // 置顶
                                sourceManager.moveToTop(realPos);
                                refreshAllSource(sourceManager, adapter, searchEt.getText().toString());
                                adapter.setSelectedPosition(0);
                                LogManager.logOperation("【设置】移到顶部：" + selectedItem.name);
                                Toast.makeText(context, "已移到顶部", Toast.LENGTH_SHORT).show();
                                break;
                            case 3: // 置底
                                sourceManager.moveToBottom(realPos);
                                refreshAllSource(sourceManager, adapter, searchEt.getText().toString());
                                LogManager.logOperation("【设置】移到底部：" + selectedItem.name);
                                Toast.makeText(context, "已移到底部", Toast.LENGTH_SHORT).show();
                                break;
                            case 4: // 刷新单个源
                                sp.edit().putString(key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG, selectedItem.url).apply();
                                context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                                LogManager.logOperation("【设置】刷新单个源：" + selectedItem.name);
                                Toast.makeText(context, "正在刷新…", Toast.LENGTH_SHORT).show();
                                break;
                            case 5: // 切换自动更新
                                sourceManager.toggleAutoUpdate(realPos);
                                refreshAllSource(sourceManager, adapter, searchEt.getText().toString());
                                Toast.makeText(context, "自动更新已切换", Toast.LENGTH_SHORT).show();
                                break;
                            case 6: // 删除
                                new AlertDialog.Builder(context)
                                        .setTitle("确认删除")
                                        .setMessage("确定要删除「" + selectedItem.name + "」吗？")
                                        .setPositiveButton("删除", (dd, ww) -> {
                                            sourceManager.removeSource(realPos);
                                            refreshAllSource(sourceManager, adapter, searchEt.getText().toString());
                                            adapter.setSelectedPosition(-1);
                                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                            case 7: // 导出
                                String exportText = sourceManager.exportToText();
                                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("sources", exportText));
                                Toast.makeText(context, "已复制全部到剪贴板", Toast.LENGTH_SHORT).show();
                                break;
                            case 8: // 导入
                                showImportDialog(title, key, sourceManager, adapter, searchEt);
                                break;
                            case 9: // 清空全部
                                new AlertDialog.Builder(context)
                                        .setTitle("确认清空")
                                        .setMessage("全部记录将被删除，无法恢复！")
                                        .setPositiveButton("全部清空", (dd, ww) -> {
                                            sourceManager.clearAll();
                                            refreshAllSource(sourceManager, adapter, "");
                                            Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                        }
                    })
                    .show();
        });
        builder.setNegativeButton("关闭", null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        // 搜索监听
        searchEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int end) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                refreshAllSource(sourceManager, adapter, s.toString());
                dialog.setTitle(title + "（共" + sourceManager.search(s.toString()).size() + "个）");
            }
        });
        // 列表点击切换源
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            List<SourceManager.SourceItem> freshList = sourceManager.getAllSources();
            if (position >= freshList.size()) return;
            SourceManager.SourceItem item = freshList.get(position);
            String saveKey = key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG;
            sp.edit().putString(saveKey, item.url).apply();
            // 点击自动置顶
            int realPos = sourceManager.indexOfUrl(item.url);
            if (realPos > 0) {
                sourceManager.moveToTop(realPos);
            }
            context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
            refreshAllSource(sourceManager, adapter, searchEt.getText().toString());
            adapter.setSelectedPosition(0);
            Toast.makeText(context, "切换完成，正在刷新", Toast.LENGTH_SHORT).show();
        });
    }
    /**
     * 核心刷新方法：每次重新查询数据，新建过滤列表，不污染原始内存集合
     */
    private void refreshAllSource(SourceManager sourceManager, SourceAdapter adapter, String keyword) {
        List<SourceManager.SourceItem> filterResult = sourceManager.search(keyword);
        adapter.clear();
        adapter.addAll(filterResult);
        adapter.notifyDataSetChanged();
    }
    // 添加源弹窗
    private void showAddSourceDialog(String title, final String key, SourceManager sourceManager, SourceAdapter adapter, String searchKey) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);
        final EditText nameEt = new EditText(context);
        nameEt.setHint("源名称（如：主源、备用源）");
        nameEt.setTextSize(14);
        nameEt.setSingleLine(true);
        layout.addView(nameEt);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 20, 0, 0);
        final EditText urlEt = new EditText(context);
        urlEt.setHint("源地址 URL");
        urlEt.setTextSize(14);
        urlEt.setSingleLine(true);
        urlEt.setLayoutParams(params);
        layout.addView(urlEt);
        new AlertDialog.Builder(context)
                .setTitle("添加" + title.replace("历史", ""))
                .setView(layout)
                .setPositiveButton("添加", (dialog, which) -> {
                    String name = nameEt.getText().toString().trim();
                    String url = urlEt.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean success = sourceManager.addSource(name, url);
                    if (!success) {
                        Toast.makeText(context, "该源已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String saveKey = key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG;
                    sp.edit().putString(saveKey, url).apply();
                    context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    refreshAllSource(sourceManager, adapter, searchKey);
                    Toast.makeText(context, "添加成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // 编辑源弹窗
    private void showEditSourceDialog(String title, final String key, int position, SourceManager.SourceItem oldItem, SourceManager sourceManager, SourceAdapter adapter, String searchKey) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);
        final EditText nameEt = new EditText(context);
        nameEt.setText(oldItem.name);
        nameEt.setTextSize(14);
        nameEt.setSingleLine(true);
        layout.addView(nameEt);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 20, 0, 0);
        final EditText urlEt = new EditText(context);
        urlEt.setText(oldItem.url);
        urlEt.setTextSize(14);
        urlEt.setSingleLine(true);
        urlEt.setLayoutParams(params);
        layout.addView(urlEt);
        new AlertDialog.Builder(context)
                .setTitle("编辑" + title.replace("历史", ""))
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = nameEt.getText().toString().trim();
                    String url = urlEt.getText().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (TextUtils.isEmpty(name)) name = "未命名";
                    sourceManager.updateSource(position, name, url);
                    String currentKey = key.contains("live") ? KEY_CUSTOM_LIVE : KEY_CUSTOM_EPG;
                    String currentUrl = sp.getString(currentKey, "");
                    if (currentUrl.equals(oldItem.url)) {
                        sp.edit().putString(currentKey, url).apply();
                        context.sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    }
                    refreshAllSource(sourceManager, adapter, searchKey);
                    Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // 导入弹窗
    private void showImportDialog(String title, final String key, SourceManager sourceManager, SourceAdapter adapter, EditText searchEt) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (!cm.hasPrimaryClip() || cm.getPrimaryClip().getText() == null || cm.getPrimaryClip().getText().toString().trim().isEmpty()) {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = cm.getPrimaryClip().getText().toString().trim();
        String[] lines = text.split("\n");
        int count = 0;
        for (String line : lines) {
            if (line.trim().contains("http")) count++;
        }
        new AlertDialog.Builder(context)
                .setTitle("确认导入")
                .setMessage("检测到 " + count + " 个源，是否导入？")
                .setPositiveButton("导入", (dialog, which) -> {
                    sourceManager.importFromText(text);
                    refreshAllSource(sourceManager, adapter, searchEt.getText().toString());
                    Toast.makeText(context, "导入完成", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
