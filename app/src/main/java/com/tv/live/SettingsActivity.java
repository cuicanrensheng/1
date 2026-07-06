package com.tv.live;

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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.tv.live.manager.TvRemoteManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity
 *
 * 【功能清单】省略...
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private SwitchCompat sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel, sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    // ====================== 配置相关 ======================
    private SharedPreferences sp;
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private List<TextView> cachedItemTitleTexts = new ArrayList<>(); // 🟢【优化】缓存主标题文本
    private ScrollView scrollView;
    // ====================== 管理器相关 ======================
    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    private UpdateManager updateManager;
    
    // ====================== SP Key 常量 ======================
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";
    
    // ====================== 🟢【修复1】日志系统改造 - 限容环形缓存 ======================
    public static class FixedSizeLogBuffer {
        private final StringBuilder buffer = new StringBuilder();
        private final int maxCapacity = 1024 * 50; // 限制最大内存容量为 50KB
        private final Object lock = new Object();

        public void append(String msg) {
            if (msg == null) return;
            synchronized (lock) {
                if (buffer.length() + msg.length() > maxCapacity) {
                    // 超出容量，截断前半部分（保留后 40KB）
                    buffer.delete(0, buffer.length() - (maxCapacity / 2));
                }
                buffer.append(msg).append("\n");
            }
        }

        public String getAndClear() {
            synchronized (lock) {
                String content = buffer.toString();
                buffer.setLength(0);
                return content;
            }
        }

        public int length() {
            synchronized (lock) {
                return buffer.length();
            }
        }
    }

    public static final FixedSizeLogBuffer PLAY_LOG = new FixedSizeLogBuffer();
    public static final FixedSizeLogBuffer OPERATION_LOG = new FixedSizeLogBuffer();

    public static void log(String msg) {
        LogManager.log(msg);
        PLAY_LOG.append(msg);
    }

    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
        OPERATION_LOG.append(msg);
    }
    // ===============================================================

    // 🟢【优化】锁变量，防止 ScrollView 死循环
    private boolean isScrolling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            applyFullScreen();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
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

        initSettingsItemList();
        initRemoteManager();

        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialog());

        // --- 开关逻辑 ---
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
            logOperation("【设置】画中画" + (isChecked ? "已开启" : "已关闭"));
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

        initListeners();

        // 🟢【优化】WebServer 抛到子线程启动，避免阻塞 UI
        new Thread(() -> {
            webServerManager.start();
            currentWebUrl = webServerManager.getAccessUrl();
        }).start();

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
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            editor.putString(KEY_USER_AGENT_MODE, "exo");
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
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        String uaLabel = "exo".equals(uaMode) ? "ExoPlayer" : "VLC";

        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain ? "开" : "关").append(" | ");
        sb.append("跨协议：").append(crossProto ? "开" : "关").append("\n");
        sb.append("携带请求头：").append(followHeader ? "开" : "关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl ? "开" : "关").append(" | ");
        sb.append("授权令牌：").append(sendCookie ? "开" : "关").append(" | ");
        sb.append("UA：").append(uaLabel);
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
        } catch (Exception ignored) {}
    }

    private void initSettingsItemList() {
        settingsItemList.clear();
        cachedItemTitleTexts.clear(); // 🟢 清空缓存

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

        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item != null) {
                // 🟢【优化】预处理，缓存该 Item 下的首个子 TextView，大幅减少焦点切换时的查找开销
                TextView titleTv = findFirstTextView(item);
                cachedItemTitleTexts.add(titleTv);

                item.setFocusableInTouchMode(true);
                item.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && remoteManager != null) {
                        remoteManager.setSettingsFocusPosition(position);
                        updateSettingsFocus();
                    }
                });
            } else {
                cachedItemTitleTexts.add(null);
            }
        }
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
        tv_screen_ratio.setOnClickListener(v -> {
            showRatioDialog();
            logOperation("【设置】打开屏幕比例设置");
        });
        tv_custom_source.setOnClickListener(v -> {
            showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE);
            logOperation("【设置】打开自定义订阅源");
        });
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG);
            logOperation("【设置】打开自定义节目单");
        });
        tv_multi_source.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });
        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
            logOperation("【设置】打开扫码管理");
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        if (selectedPosition < 0 || selectedPosition >= settingsItemList.size()) return;

        View item = settingsItemList.get(selectedPosition);
        if (item == null) return;

        // 🟢【防死锁】当前焦点已经是这个 Item 了，直接跳过，防止无限循环
        if (item.isFocused()) return;

        for (int i = 0; i < settingsItemList.size(); i++) {
            View v = settingsItemList.get(i);
            if (v == null) continue;
            if (i == selectedPosition) {
                // 🟢【防死锁】使用 post 延迟一帧请求焦点，打破同步锁
                setItemStyle(i, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
                v.post(() -> {
                    if (v.isAttachedToWindow()) v.requestFocus();
                });
                scrollToView(v);
            } else {
                setItemStyle(i, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }
    }

    // 🟢【优化】直接接收索引，使用预缓存的 TextView，彻底消灭 findFirstTextView 遍历
    private void setItemStyle(int index, String textColor, int typefaceStyle, int bgColor) {
        if (index < 0 || index >= settingsItemList.size()) return;
        View item = settingsItemList.get(index);
        if (item == null) return;
        item.setBackgroundColor(bgColor);

        TextView tv = cachedItemTitleTexts.get(index);
        if (tv != null) {
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typefaceStyle);
        }
    }

    private TextView findFirstTextView(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findFirstTextView(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    // 🟢【优化】增加 isScrolling 锁，防止滚动动画冲突导致的死锁和卡顿
    private void scrollToView(View view) {
        if (scrollView == null || view == null || isScrolling) return;
        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();

        if (viewTop < scrollView.getScrollY() || viewBottom > scrollView.getScrollY() + scrollViewHeight) {
            isScrolling = true;
            int targetY = viewTop < scrollView.getScrollY() ? viewTop - 50 : viewBottom - scrollViewHeight + 50;
            scrollView.smoothScrollTo(0, targetY);
            // 延迟解锁
            scrollView.postDelayed(() -> isScrolling = false, 400);
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
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        final String[] currentUaMode = {sp.getString(KEY_USER_AGENT_MODE, "exo")};

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);
        SwitchCompat swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        SwitchCompat swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        SwitchCompat swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        SwitchCompat swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        SwitchCompat swSendCookie = dialogView.findViewById(R.id.sw_send_cookie);
        LinearLayout llUserAgent = dialogView.findViewById(R.id.ll_user_agent);
        TextView tvUserAgentStatus = dialogView.findViewById(R.id.tv_user_agent_status);
        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC播放器");

        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        llUserAgent.setOnClickListener(v -> {
            final String[] uaOptions = {"ExoPlayer默认", "VLC播放器"};
            final String[] uaValues = {"exo", "vlc"};
            int checkedItem = 0;
            for (int i = 0; i < uaValues.length; i++) {
                if (uaValues[i].equals(currentUaMode[0])) { checkedItem = i; break; }
            }
            new AlertDialog.Builder(this)
                    .setTitle("UA切换")
                    .setSingleChoiceItems(uaOptions, checkedItem, (d, which) -> {
                        currentUaMode[0] = uaValues[which];
                        tvUserAgentStatus.setText(uaOptions[which]);
                        d.dismiss();
                    }).show();
        });

        new AlertDialog.Builder(this)
                .setTitle("HTTP重定向网络配置")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String maxStr = etMax.getText().toString().trim();
                    int newMax = 5;
                    if (!TextUtils.isEmpty(maxStr)) {
                        try {
                            newMax = Integer.parseInt(maxStr);
                            if (newMax < 1) newMax = 1;
                            if (newMax > 20) newMax = 20;
                        } catch (Exception ignored) { newMax = 5; }
                    }
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putInt(KEY_REDIRECT_MAX_COUNT, newMax);
                    editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, swCrossDomain.isChecked());
                    editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, swCrossProto.isChecked());
                    editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, swFollowHeader.isChecked());
                    editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, swIgnoreSsl.isChecked());
                    editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, swSendCookie.isChecked());
                    editor.putString(KEY_USER_AGENT_MODE, currentUaMode[0]);
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
                        SourceManager sourceManager = new SourceManager(this, key.contains("live") ? "live_history" : "epg_history");
                        sourceManager.addSource(url.substring(0, Math.min(10, url.length())) + "...", url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【设置】" + title + "已更新：" + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 🟢【修复 ANR】极简防 ANR 日志弹窗逻辑
    private void showOperationLogDialog() {
        showLogDialogAsync(false);
    }

    private void showLogDialog() {
        showLogDialogAsync(true);
    }

    private void showLogDialogAsync(final boolean isPlayLog) {
        final String rawLog = isPlayLog ? PLAY_LOG.getAndClear() : OPERATION_LOG.getAndClear();
        if (TextUtils.isEmpty(rawLog)) {
            Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            // 将耗时的字符串反序和拼接操作放在子线程，防止低端电视 ANR
            String processedLog;
            String[] lines = rawLog.split("\n");
            StringBuilder reversed = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) reversed.append(lines[i]).append("\n");
            }
            processedLog = reversed.toString();

            runOnUiThread(() -> {
                ScrollView scrollView = new ScrollView(SettingsActivity.this);
                TextView tv = new TextView(SettingsActivity.this);
                tv.setText(processedLog);
                tv.setTextSize(12);
                tv.setPadding(40, 40, 40, 40);
                tv.setTextColor(Color.BLACK);
                scrollView.addView(tv);

                AlertDialog.Builder builder = new AlertDialog.Builder(SettingsActivity.this);
                builder.setTitle(isPlayLog ? "📄 解析 & 播放日志" : "📌 操作日志");
                builder.setView(scrollView);
                builder.setPositiveButton("关闭", null);
                builder.setNeutralButton("清空日志", (dialog, which) -> {
                    Toast.makeText(SettingsActivity.this, "日志已清空", Toast.LENGTH_SHORT).show();
                });
                builder.show();
            });
        }).start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                applyFullScreen();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
                layoutParams.dimAmount = 0f;
                getWindow().setAttributes(layoutParams);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        if (webServerManager != null) webServerManager.stop();
        if (updateManager != null) updateManager.release();
        
        remoteManager = null;
        scrollView = null;
        settingsItemList.clear();
        settingsItemList = null;
        cachedItemTitleTexts.clear();
        cachedItemTitleTexts = null;
    }
}
