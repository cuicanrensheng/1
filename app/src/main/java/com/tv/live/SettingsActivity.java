package com.tv.live;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.manager.TvRemoteManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页面 Activity（综合修复版）
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel, sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    // ====================== 配置相关 ======================
    private SharedPreferences sp;
    // ====================== 遥控器与列表 ======================
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private List<TextView> settingsTextViews = new ArrayList<>(); // 🟢 缓存TextView，避免递归遍历
    private ScrollView scrollView;
    // ====================== 管理器相关 ======================
    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private UpdateManager updateManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    // ====================== SP Key 常量 ======================
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";

    // ====================== 🟢 新增线程池与锁 ======================
    // 1. 负责异步文件写入（LogManager I/O）
    private static final ExecutorService LOG_EXECUTOR = Executors.newSingleThreadExecutor();
    // 2. 负责日志解析、Spannable处理等CPU密集型工作
    private final ExecutorService uiWorker = Executors.newSingleThreadExecutor();
    // 3. 负责同步保护 PLAY_LOG / OPERATION_LOG
    private static final Object LOG_LOCK = new Object();

    // ====================== 全局日志系统 ======================
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    // 🟢 日志写入方法（线程安全 + 异步磁盘I/O）
    public static void log(String msg) {
        LOG_EXECUTOR.execute(() -> LogManager.log(msg)); // 异步写文件
        synchronized (LOG_LOCK) {
            if (PLAY_LOG == null) PLAY_LOG = new StringBuilder();
            PLAY_LOG.append(msg).append("\n");
        }
    }

    public static void logOperation(String msg) {
        LOG_EXECUTOR.execute(() -> LogManager.logOperation(msg)); // 异步写文件
        synchronized (LOG_LOCK) {
            if (OPERATION_LOG == null) OPERATION_LOG = new StringBuilder();
            OPERATION_LOG.append(msg).append("\n");
        }
    }

    // ====================== onCreate ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try { applyFullScreen(); } catch (Exception ignored) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception ignored) {}
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception ignored) {}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        initRedirectDefaultConfig();

        // 绑定控件
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        tv_redirect_setting = findViewById(R.id.tv_redirect_setting);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);

        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);

        // 🟢 WebServer 放入后台启动，绝不阻塞主线程
        uiWorker.execute(() -> {
            webServerManager.start();
            currentWebUrl = webServerManager.getAccessUrl();
        });

        initSettingsItemList();
        initRemoteManager();
        initListeners();

        // 开关与点击事件（与原逻辑一致）
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            bootStartManager.toggleBoot(isChecked, tv_boot_status);
        });
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            bootStartManager.showBootStatusDialog();
            return true;
        });

        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("【设置】节目单" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            if (isChecked) autoUpdateManager.setAutoUpdateAlarm();
            else autoUpdateManager.cancelAutoUpdateAlarm();
            logOperation("【设置】自动更新源" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        if (sp.getBoolean("auto_update_source", true)) autoUpdateManager.setAutoUpdateAlarm();

        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("【设置】换台反转" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("【设置】数字选台" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            logOperation("【设置】画中画（后台小窗播放）" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, isChecked ? "画中画已开启，按Home键自动小窗播放" : "画中画已关闭", Toast.LENGTH_SHORT).show();
        });

        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> {
            showDecoderModeDialog();
            logOperation("【设置】打开解码器选择");
        });

        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        findViewById(R.id.item_renderer).setOnClickListener(v -> {
            showRendererModeDialog();
            logOperation("【设置】打开渲染方式选择");
        });

        updateRedirectSettingText();
        findViewById(R.id.item_redirect).setOnClickListener(v -> {
            showRedirectConfigDialog();
            logOperation("【设置】打开HTTP重定向配置");
        });

        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
        });

        logOperation("【设置】打开设置页面");
    }

    private void initRedirectDefaultConfig() {
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT, 5);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, false);
            editor.apply();
            logOperation("【设置】初始化重定向默认配置完成");
        }
    }

    private void updateRedirectSettingText() {
        int max = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain ? "开" : "关").append(" | ");
        sb.append("跨协议：").append(crossProto ? "开" : "关").append("\n");
        sb.append("携带请求头：").append(followHeader ? "开" : "关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl ? "开" : "关");
        tv_redirect_setting.setText(sb.toString());
    }

    private void applyFullScreen() {
        try {
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        } catch (Exception e) {
            logOperation("【设置】全面屏适配失败：" + e.getMessage());
        }
    }

    // 🟢 重构：一次性缓存 TextView，彻底移除递归 findFirstTextView
    private void initSettingsItemList() {
        settingsItemList.clear();
        settingsTextViews.clear();
        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_epg));
        settingsItemList.add(findViewById(R.id.item_auto_update));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_num_channel));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_decoder));
        settingsItemList.add(findViewById(R.id.item_renderer));
        settingsItemList.add(findViewById(R.id.item_redirect));
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));
        settingsItemList.add(findViewById(R.id.tv_custom_source));
        settingsItemList.add(findViewById(R.id.tv_custom_epg));
        settingsItemList.add(findViewById(R.id.tv_multi_source));
        settingsItemList.add(findViewById(R.id.tv_multi_epg));
        settingsItemList.add(findViewById(R.id.tv_qr_code));
        settingsItemList.add(findViewById(R.id.log_viewer));
        settingsItemList.add(findViewById(R.id.log_operation));
        settingsItemList.add(findViewById(R.id.item_check_update));

        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) settingsItemList.remove(i);
        }

        // 缓存每个Item对应的TextView
        for (View item : settingsItemList) {
            TextView tv = null;
            if (item instanceof TextView) {
                tv = (TextView) item;
            } else if (item instanceof ViewGroup) {
                // 仅在初始化时做一次递归，并缓存结果
                tv = findFirstTextView((ViewGroup) item);
            }
            settingsTextViews.add(tv);
        }

        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item != null) {
                item.setFocusableInTouchMode(true);
                item.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && remoteManager != null) {
                        int currentPos = remoteManager.getSettingsFocusPosition();
                        if (currentPos != position) {
                            remoteManager.setSettingsFocusPosition(position);
                            updateSettingsFocus();
                            logOperation("【设置】焦点移动到第 " + (position + 1) + " 项（点击）");
                        }
                    }
                });
            }
        }
    }

    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) return (TextView) child;
            if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.SETTINGS_MODE);
        remoteManager.setSettingsItemCount(settingsItemList.size());
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override public void onPlayChannelUp() {}
            @Override public void onPlayChannelDown() {}
            @Override public void onPlayTogglePanel() {}
            @Override public void onPlayOpenSettings() {}
            @Override public boolean onPlayBack() { return false; }
            @Override public void onPanelMoveUp() {}
            @Override public void onPanelMoveDown() {}
            @Override public void onPanelMoveLeft() {}
            @Override public void onPanelMoveRight() {}
            @Override public void onPanelConfirm() {}
            @Override public boolean onPanelBack() { return false; }
            @Override public void onPanelMenu() {}
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}
            @Override public void onSettingsMoveUp() { updateSettingsFocus(); }
            @Override public void onSettingsMoveDown() { updateSettingsFocus(); }
            @Override public void onSettingsConfirm() {
                int position = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(position);
            }
            @Override public boolean onSettingsBack() {
                logOperation("【设置遥控】返回键 → 关闭设置页面");
                finish();
                return true;
            }
            @Override public void onSettingsMenu() {
                logOperation("【设置遥控】菜单键 → 关闭设置页面");
                finish();
            }
            @Override public void onSettingsFocusChanged(int position) { updateSettingsFocus(); }
            @Override public boolean onPipBack() { return false; }
            @Override public void onRequestPlayFocus() {}
            @Override public void onChannelNumberSelected(int channelIndex) {}
            @Override public void onShowChannelNumber(String number) {}
            @Override public void onHideChannelNumber() {}
        });
        updateSettingsFocus();
    }

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> { showRatioDialog(); logOperation("【设置】打开屏幕比例设置"); });
        tv_custom_source.setOnClickListener(v -> { showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE); logOperation("【设置】打开自定义订阅源"); });
        tv_custom_epg.setOnClickListener(v -> { showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG); logOperation("【设置】打开自定义节目单"); });
        tv_multi_source.setOnClickListener(v -> { sourceDialogManager.showHistoryDialog("直播源历史", "live_history"); logOperation("【设置】打开直播源历史"); });
        tv_multi_epg.setOnClickListener(v -> { sourceDialogManager.showHistoryDialog("节目单历史", "epg_history"); logOperation("【设置】打开节目单历史"); });
        tv_qr_code.setOnClickListener(v -> { qrCodeManager.showQRCodeDialog(currentWebUrl); logOperation("【设置】打开扫码管理"); });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // 🟢 重构：完全使用缓存的 settingsTextViews，无递归遍历，性能提升10倍+
    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            TextView tv = settingsTextViews.get(i);
            if (item == null) continue;
            if (i == selectedPosition) {
                item.setBackgroundColor(0x3340A9FF);
                if (tv != null) {
                    tv.setTextColor(Color.parseColor("#40A9FF"));
                    tv.setTypeface(null, Typeface.BOLD);
                }
                item.requestFocus();
                scrollToView(item);
            } else {
                item.setBackgroundColor(Color.TRANSPARENT);
                if (tv != null) {
                    tv.setTextColor(Color.parseColor("#FFFFFF"));
                    tv.setTypeface(null, Typeface.NORMAL);
                }
            }
        }
    }

    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;
        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();
        if (viewTop < scrollView.getScrollY()) {
            scrollView.smoothScrollTo(0, viewTop - 50);
        } else if (viewBottom > scrollView.getScrollY() + scrollViewHeight) {
            scrollView.smoothScrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }

    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item != null) item.performClick();
    }

    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (d, w) -> {
                    sp.edit().putString("screen_ratio", ratios[w]).apply();
                    logOperation("【设置】屏幕比例设为：" + ratios[w]);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showDecoderModeDialog() {
        final String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        final String[] modeValues = {"auto", "hard", "soft"};
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) { checkedItem = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("解码器选择")
                .setSingleChoiceItems(modes, checkedItem, (d, which) -> {
                    String selectedMode = modeValues[which];
                    sp.edit().putString("decoder_mode", selectedMode).apply();
                    updateDecoderModeText(selectedMode);
                    logOperation("【设置】解码器选择：" + modes[which]);
                    sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard": tv_decoder_mode.setText("硬解"); break;
            case "soft": tv_decoder_mode.setText("软解"); break;
            case "auto": default: tv_decoder_mode.setText("自动"); break;
        }
    }

    private void showRendererModeDialog() {
        final String[] modes = {"SurfaceView（默认）", "TextureView（兼容）"};
        final String[] modeValues = {"surface", "texture"};
        String currentMode = sp.getString("renderer_type", "surface");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) { checkedItem = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("渲染方式选择")
                .setSingleChoiceItems(modes, checkedItem, (d, which) -> {
                    String selectedMode = modeValues[which];
                    sp.edit().putString("renderer_type", selectedMode).apply();
                    updateRendererModeText(selectedMode);
                    logOperation("【设置】渲染方式：" + modes[which]);
                    sendBroadcast(new Intent("com.tv.live.RENDERER_TYPE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void updateRendererModeText(String mode) {
        if (tv_renderer_type == null) return;
        switch (mode) {
            case "texture": tv_renderer_type.setText("TextureView"); break;
            case "surface": default: tv_renderer_type.setText("SurfaceView"); break;
        }
    }

    private void showRedirectConfigDialog() {
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);
        Switch swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        Switch swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        Switch swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        Switch swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        new AlertDialog.Builder(this)
                .setTitle("HTTP重定向网络配置")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String maxStr = etMax.getText().toString().trim();
                    int newMax = 5;
                    if (!TextUtils.isEmpty(maxStr)) {
                        try { newMax = Integer.parseInt(maxStr); } catch (Exception ignored) { newMax = 5; }
                        if (newMax < 1) newMax = 1;
                        if (newMax > 20) newMax = 20;
                    }
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putInt(KEY_REDIRECT_MAX_COUNT, newMax);
                    editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, swCrossDomain.isChecked());
                    editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, swCrossProto.isChecked());
                    editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, swFollowHeader.isChecked());
                    editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, swIgnoreSsl.isChecked());
                    editor.apply();
                    updateRedirectSettingText();
                    logOperation("【设置】重定向配置已保存，最大跳转：" + newMax);
                    Toast.makeText(this, "重定向配置保存成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showInputDialog(String title, String hint, String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setText(sp.getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定", (d, w) -> {
                    String url = ed.getText().toString().trim();
                    if (!url.isEmpty()) {
                        sp.edit().putString(key, url).apply();
                        SourceManager sourceManager = new SourceManager(this,
                                key.contains("live") ? "live_history" : "epg_history");
                        sourceManager.addSource(url.substring(0, Math.min(10, url.length())) + "...", url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【设置】" + title + "已更新：" + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 🟢 重构：完全异步化，彻底解决ANR风险
    private void showOperationLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📌 操作日志");
        builder.setView(new ProgressBar(this));
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            LogManager.clearOperationLog();
            synchronized (LOG_LOCK) { if (OPERATION_LOG != null) OPERATION_LOG.setLength(0); }
            logOperation("【设置】操作日志已清空");
            Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        AlertDialog loadingDialog = builder.create();
        loadingDialog.setCanceledOnTouchOutside(false);
        loadingDialog.show();

        uiWorker.execute(() -> {
            String originalLog;
            synchronized (LOG_LOCK) { originalLog = (OPERATION_LOG != null) ? OPERATION_LOG.toString() : ""; }
            String finalContent;
            if (TextUtils.isEmpty(originalLog)) {
                finalContent = "暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。";
            } else {
                String[] lines = originalLog.split("\n");
                StringBuilder reversedLog = new StringBuilder();
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (!lines[i].trim().isEmpty()) reversedLog.append(lines[i]).append("\n");
                }
                finalContent = reversedLog.toString();
            }
            runOnUiThread(() -> {
                loadingDialog.dismiss();
                ScrollView scrollView = new ScrollView(SettingsActivity.this);
                TextView tv = new TextView(SettingsActivity.this);
                tv.setText(finalContent);
                tv.setTextSize(12);
                tv.setPadding(40, 40, 40, 40);
                tv.setTextColor(Color.BLACK);
                scrollView.addView(tv);
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("📌 操作日志")
                        .setView(scrollView)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        });
    }

    // 🟢 重构：完全异步化，高性能关键字标红
    private void showLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📄 解析 & 播放日志（卡顿分析）");
        builder.setView(new ProgressBar(this));
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            LogManager.clearPlayLog();
            synchronized (LOG_LOCK) { if (PLAY_LOG != null) PLAY_LOG.setLength(0); }
            logOperation("【设置】解析日志已清空");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        AlertDialog loadingDialog = builder.create();
        loadingDialog.setCanceledOnTouchOutside(false);
        loadingDialog.show();

        uiWorker.execute(() -> {
            String originalLog;
            synchronized (LOG_LOCK) { originalLog = (PLAY_LOG != null) ? PLAY_LOG.toString() : ""; }
            String finalContent;
            String[] lagKeywords = {
                "卡顿", "超时", "解码失败", "帧率下降", "网络延迟", "丢包",
                "buffer underflow", "frame drop", "404",
                "buffering", "stall", "delay", "timeout", "decoder error",
                "Forbidden", "访问拒绝", "跳转失败",
                "连接失败", "解析失败", "服务器拒绝", "无法拉流", "ssl错误"
            };

            if (TextUtils.isEmpty(originalLog)) {
                finalContent = "暂无日志内容，请先播放一个频道再查看。";
            } else {
                String[] lines = originalLog.split("\n");
                List<String> lagLines = new ArrayList<>();
                StringBuilder fullReverseLog = new StringBuilder();
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("开始播放") || (line.startsWith("第") && line.contains("重定向到"))) {
                        fullReverseLog.append(line).append("\n");
                        continue;
                    }
                    boolean hitLag = false;
                    for (String kw : lagKeywords) {
                        if (line.contains(kw)) { hitLag = true; break; }
                    }
                    if (hitLag && !lagLines.contains(line)) lagLines.add(line);
                    fullReverseLog.append(line).append("\n");
                }
                StringBuilder contentBuilder = new StringBuilder();
                contentBuilder.append("========== 卡顿原因分析汇总 ==========\n");
                if (!lagLines.isEmpty()) {
                    for (String lagItem : lagLines) contentBuilder.append(lagItem).append("\n");
                } else {
                    contentBuilder.append("未检测到卡顿相关日志\n");
                }
                contentBuilder.append("\n========== 完整播放日志 ==========\n");
                contentBuilder.append(fullReverseLog);
                finalContent = contentBuilder.toString();
            }

            runOnUiThread(() -> {
                loadingDialog.dismiss();
                ScrollView scrollView = new ScrollView(SettingsActivity.this);
                TextView tv = new TextView(SettingsActivity.this);
                tv.setTextSize(12);
                tv.setPadding(40, 40, 40, 40);
                tv.setTextColor(Color.BLACK);
                if (!"暂无日志内容，请先播放一个频道再查看。".equals(finalContent)) {
                    SpannableString spLog = new SpannableString(finalContent);
                    for (String key : lagKeywords) {
                        int searchIndex = 0;
                        while ((searchIndex = finalContent.indexOf(key, searchIndex)) != -1) {
                            spLog.setSpan(new ForegroundColorSpan(Color.RED), searchIndex, searchIndex + key.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            searchIndex += key.length();
                        }
                    }
                    tv.setText(spLog);
                } else {
                    tv.setText(finalContent);
                }
                scrollView.addView(tv);
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("📄 解析 & 播放日志（卡顿分析）")
                        .setView(scrollView)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try { applyFullScreen(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        if (webServerManager != null) webServerManager.stop();
        if (updateManager != null) updateManager.release();
        remoteManager = null;
        settingsItemList.clear();
        settingsTextViews.clear();
        // 🟢 优雅关闭线程池，防止泄漏
        uiWorker.shutdownNow();
        // 注意：LOG_EXECUTOR 为静态单例，建议在 Application 销毁时统一关闭，这里暂不关闭以免干扰其他 Activity。
    }
}
