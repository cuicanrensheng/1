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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.manager.TvRemoteManager;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity
 *
 * 【功能清单】
 * 1. 开机自启开关（委托给 BootStartManager）
 * 2. 节目单开关
 * 3. 自动更新源（委托给 AutoUpdateManager）
 * 4. 换台反转
 * 5. 数字选台
 * 6. 画中画（后台小窗播放）开关
 * 7. ✅ 解码器选择（自动/硬解/软解）
 * 8. ✅ 渲染方式选择（SurfaceView/TextureView）（2026-07-02 新增）
 * 9. ✅ HTTP重定向网络配置（2026-07-03 新增）
 * 10. 屏幕比例设置
 * 11. 自定义订阅源/节目单
 * 12. 多订阅源/节目单管理（委托给 SourceDialogManager）
 * 13. 扫码添加（委托给 QRCodeManager）
 * 14. 解析&播放日志查看
 * 15. 操作日志查看
 * 16. 检查更新（委托给 UpdateManager）
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel, sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode;
    // 🆕 渲染方式当前值显示
    private TextView tv_renderer_type;
    // 🆕 重定向设置文本显示控件
    private TextView tv_redirect_setting;
    private TextView tv_boot_status;
    // ====================== 配置相关 ======================
    private SharedPreferences sp;
    // ====================================================================
    // 遥控器统一管理器
    // ====================================================================
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;
    // ====================================================================
    // 管理器相关
    // ====================================================================
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
    // ====================== 🆕 重定向配置存储Key ======================
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    // 🟢【新增】Cookie播放授权令牌 Key
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    // 🟢【新增】UA切换 Key
    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";
    // ====================================================================
    // 全局日志系统
    // ====================================================================
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();
    public static void log(String msg) {
        LogManager.log(msg);
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder(); // 原错误 PLAY → PLAY_LOG
        }
        PLAY_LOG.append(msg).append("\n");
    }
    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuilder();
        }
        OPERATION_LOG.append(msg).append("\n");
    }
    // ====================== onCreate ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            applyFullScreen();
        } catch (Exception e) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) { }
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        // 🆕 初始化重定向默认配置（首次打开自动写入默认值）
        initRedirectDefaultConfig();

        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        // 🆕 绑定渲染方式控件
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        // 🆕 绑定重定向设置显示控件
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
        // 开机自启
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
        // 节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("【设置】节目单" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // 自动更新源
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            if (isChecked) {
                autoUpdateManager.setAutoUpdateAlarm();
            } else {
                autoUpdateManager.cancelAutoUpdateAlarm();
            }
            logOperation("【设置】自动更新源" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        if (sp.getBoolean("auto_update_source", true)) {
            autoUpdateManager.setAutoUpdateAlarm();
        }
        // 换台反转
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("【设置】换台反转" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // 数字选台
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("【设置】数字选台" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // 画中画
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            logOperation("【设置】画中画（后台小窗播放）" + (isChecked ? "已开启" : "已关闭"));
            if (isChecked) {
                Toast.makeText(this, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "画中画已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        // 解码器选择
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> {
            showDecoderModeDialog();
            logOperation("【设置】打开解码器选择");
        });
        // 🆕 渲染方式选择
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        findViewById(R.id.item_renderer).setOnClickListener(v -> {
            showRendererModeDialog();
            logOperation("【设置】打开渲染方式选择");
        });
        // 🆕 重定向网络设置点击事件
        updateRedirectSettingText();
        findViewById(R.id.item_redirect).setOnClickListener(v -> {
            showRedirectConfigDialog();
            logOperation("【设置】打开HTTP重定向配置");
        });
        // 检查更新
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
        });
        initListeners();
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
        logOperation("【设置】打开设置页面");
    }

    /** 🆕 初始化重定向默认配置，首次进入写入默认值 */
    private void initRedirectDefaultConfig() {
        // 判断是否已存在key，不存在则写入默认值
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT,5);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL,false);
            // 🟢【新增】保存 Cookie授权令牌 默认值（默认开）
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            // 🟢【新增】保存 UA 默认值 "exo"
            editor.putString(KEY_USER_AGENT_MODE, "exo");
            editor.apply();
            logOperation("【设置】初始化重定向默认配置完成");
        }
    }

    /** 🆕 更新重定向设置摘要文本 */
    private void updateRedirectSettingText() {
        int max = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        // 🟢【新增】读取 UA 模式
        String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        String uaLabel = "exo".equals(uaMode) ? "ExoPlayer" : "浏览器";
        
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain?"开":"关").append(" | ");
        // 🔄 修复：在这里添加换行符，防止文字过长覆盖左侧标题
        sb.append("跨协议：").append(crossProto?"开":"关").append("\n");
        sb.append("携带请求头：").append(followHeader?"开":"关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl?"开":"关").append(" | ");
        sb.append("授权令牌：").append(sendCookie?"开":"关");
        // 🟢【新增】将 UA 状态拼接到 UI
        sb.append(" | UA：").append(uaLabel);
        
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

    private void initSettingsItemList() {
        settingsItemList.clear();
        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_epg));
        settingsItemList.add(findViewById(R.id.item_auto_update));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_num_channel));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_decoder));
        // 🆕 将渲染方式加入焦点列表
        settingsItemList.add(findViewById(R.id.item_renderer));
        // 🆕 重定向设置项加入焦点列表
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
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
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
            @Override
            public void onSettingsMoveUp() {
                int newPos = remoteManager.getSettingsFocusPosition();
                logOperation("【设置遥控】上键 → 移动到第 " + (newPos + 1) + " 项");
                updateSettingsFocus();
            }
            @Override
            public void onSettingsMoveDown() {
                int newPos = remoteManager.getSettingsFocusPosition();
                logOperation("【设置遥控】下键 → 移动到第 " + (newPos + 1) + " 项");
                updateSettingsFocus();
            }
            @Override
            public void onSettingsConfirm() {
                int position = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(position);
            }
            @Override
            public boolean onSettingsBack() {
                logOperation("【设置遥控】返回键 → 关闭设置页面");
                finish();
                return true;
            }
            @Override
            public void onSettingsMenu() {
                logOperation("【设置遥控】菜单键 → 关闭设置页面");
                finish();
            }
            @Override
            public void onSettingsFocusChanged(int position) {
                updateSettingsFocus();
            }
            @Override
            public boolean onPipBack() { return false; }
            @Override
            public void onRequestPlayFocus() {}
            @Override
            public void onChannelNumberSelected(int channelIndex) {}
            @Override
            public void onShowChannelNumber(String number) {}
            @Override
            public void onHideChannelNumber() {}
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
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        logOperation("【设置遥控】焦点更新 → 第 " + (selectedPosition + 1) + " 项");
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == selectedPosition) {
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
                item.requestFocus();
                scrollToView(item);
            } else if (item.isFocused()) {
                setItemStyle(item, "#40A9FF", Typeface.NORMAL, Color.TRANSPARENT);
            } else {
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }
    }
        private void setItemStyle(View item, String textColor, int typefaceStyle, int bgColor) {
        item.setBackgroundColor(bgColor);
        if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor)); // 修复 text → textColor
            tv.setTypeface(null, typefaceStyle);
        } else if (item instanceof ViewGroup) {
            TextView tv = findFirstTextView((ViewGroup) item);
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor)); // 修复 text → textColor
                tv.setTypeface(null, typefaceStyle);
            }
        }
    }

    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
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
        if (item == null) return;
        item.performClick();
        logOperation("【设置遥控】选中第 " + (position + 1) + " 项");
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
        // 🟢 修复核心：补齐第三个参数 "soft"，避免选择软解时数组越界崩溃！
        final String[] modeValues = {"auto", "hard", "soft"}; 
        
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
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

    // ====================================================================
    // 🆕 渲染方式选择弹窗与更新
    // ====================================================================
    private void showRendererModeDialog() {
        final String[] modes = {"SurfaceView（默认）", "TextureView（兼容）"};
        final String[] modeValues = {"surface", "texture"};
        String currentMode = sp.getString("renderer_type", "surface");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("渲染方式选择")
                .setSingleChoiceItems(modes, checkedItem, (d, which) -> {
                    String selectedMode = modeValues[which];
                    sp.edit().putString("renderer_type", selectedMode).apply();
                    updateRendererModeText(selectedMode);
                    logOperation("【设置】渲染方式：" + modes[which]);
                    // 发送广播，通知播放器立刻切换渲染方式
                    sendBroadcast(new Intent("com.tv.live.RENDERER_TYPE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void updateRendererModeText(String mode) {
        if (tv_renderer_type == null) return;
        switch (mode) {
            case "texture":
                tv_renderer_type.setText("TextureView");
                break;
            case "surface":
            default:
                tv_renderer_type.setText("SurfaceView");
                break;
        }
    }

    // ====================================================================
    // 🆕 重定向配置弹窗（开关+数字输入）
    // ====================================================================
    private void showRedirectConfigDialog() {
        // 读取当前配置
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        // 🟢【新增】读取 UA 模式
        String currentUaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        
        // 构建弹窗布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);
        Switch swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        Switch swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        Switch swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        Switch swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        Switch swSendCookie = dialogView.findViewById(R.id.sw_send_cookie);
        // 🟢【新增】绑定 UA 切换控件
        LinearLayout llUserAgent = dialogView.findViewById(R.id.ll_user_agent);
        TextView tvUserAgentStatus = dialogView.findViewById(R.id.tv_user_agent_status);
        tvUserAgentStatus.setText("exo".equals(currentUaMode) ? "ExoPlayer默认" : "浏览器");
        
        // 限制输入数字1-20
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        // 从SharedPreferences读取到的实际状态，填入弹窗的开关控件中！
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        // 🟢【新增】UA 切换点击弹窗事件
        llUserAgent.setOnClickListener(v -> {
            final String[] uaOptions = {"ExoPlayer默认", "浏览器"};
            final String[] uaValues = {"exo", "browser"};
            int checkedItem = 0;
            for (int i = 0; i < uaValues.length; i++) {
                if (uaValues[i].equals(currentUaMode)) {
                    checkedItem = i;
                    break;
                }
            }
            new AlertDialog.Builder(this)
                .setTitle("UA切换")
                .setSingleChoiceItems(uaOptions, checkedItem, (d, which) -> {
                    currentUaMode = uaValues[which];
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
                            // 限制范围1~20
                            if(newMax < 1) newMax = 1;
                            if(newMax > 20) newMax = 20;
                        }catch (Exception ignored){
                            newMax =5;
                        }
                    }
                    // 保存全部配置
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putInt(KEY_REDIRECT_MAX_COUNT, newMax);
                    editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, swCrossDomain.isChecked());
                    editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, swCrossProto.isChecked());
                    editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, swFollowHeader.isChecked());
                    editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, swIgnoreSsl.isChecked());
                    editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, swSendCookie.isChecked());
                    // 🟢【新增】保存 UA 状态
                    editor.putString(KEY_USER_AGENT_MODE, currentUaMode);
                    editor.apply();
                    // 更新界面摘要
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

    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。");
        } else {
            String originalLog = OPERATION_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📌 操作日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            LogManager.clearOperationLog();
            if (OPERATION_LOG != null) {
                OPERATION_LOG.setLength(0);
            }
            logOperation("【设置】操作日志已清空");
            Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }
    
    private void showLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
            tv.setText("暂无日志内容，请先播放一个频道再查看。");
        } else {
            String originalLog = PLAY_LOG.toString();
            String[] lines = originalLog.split("\n");
            List<String> lagLines = new ArrayList<>();
            StringBuilder fullReverseLog = new StringBuilder();
            // 扩充网络/HTTP异常关键字，覆盖403、Forbidden、各类HTTP报错与卡顿场景
            String[] lagKeywords = {
                    // 播放卡顿、缓冲、解码类关键词
                    "卡顿", "超时", "解码失败", "帧率下降", "网络延迟", "丢包",
                    "buffer underflow", "frame drop", "404",
                    "buffering", "stall", "delay", "timeout", "decoder error",
                    // HTTP网络异常错误码&提示词
                    "Forbidden", "访问拒绝", "跳转失败", 
                    "连接失败", "解析失败", "服务器拒绝", "无法拉流", "ssl错误"
            };

            // 倒序遍历全部日志
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                // 🟢 【新增修复】主动过滤掉正常的播放/重定向日志，防止误判为卡顿
                if (line.startsWith("开始播放") || (line.startsWith("第") && line.contains("重定向到"))) {
                    fullReverseLog.append(line).append("\n");
                    continue;
                }

                boolean hitLag = false;
                for (String kw : lagKeywords) {
                    if (line.contains(kw)) {
                        hitLag = true;
                        break;
                    }
                }
                // 去重存入卡顿汇总列表
                if (hitLag && !lagLines.contains(line)) {
                    lagLines.add(line);
                }
                fullReverseLog.append(line).append("\n");
            }

            // 拼接展示文本
            StringBuilder fullContent = new StringBuilder();
            fullContent.append("========== 卡顿原因分析汇总 ==========\n");
            if (!lagLines.isEmpty()) {
                for (String lagItem : lagLines) {
                    fullContent.append(lagItem).append("\n");
                }
            } else {
                fullContent.append("未检测到卡顿相关日志\n");
            }
            fullContent.append("\n========== 完整播放日志 ==========\n");
            fullContent.append(fullReverseLog);

            // 全局关键字标红
            SpannableString spLog = new SpannableString(fullContent.toString());
            String totalText = fullContent.toString();
            for (String key : lagKeywords) {
                int searchIndex = 0;
                while ((searchIndex = totalText.indexOf(key, searchIndex)) != -1) {
                    spLog.setSpan(
                            new ForegroundColorSpan(Color.RED),
                            searchIndex,
                            searchIndex + key.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    searchIndex += key.length();
                }
            }
            tv.setText(spLog);
        }
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📄 解析 & 播放日志（卡顿分析）");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            LogManager.clearPlayLog();
            if (PLAY_LOG != null) {
                PLAY_LOG.setLength(0);
            }
            logOperation("【设置】解析日志已清空");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
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
            } catch (Exception e) { }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        if (webServerManager != null) {
            webServerManager.stop();
        }
        if (updateManager != null) {
            updateManager.release();
        }
        remoteManager = null;
        settingsItemList.clear();
        settingsItemList = null;
    }
}
