package com.tv.live;

import android.text.Spannable;
import android.text.SpannableString;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity
 * 移除 TvRemoteManager，自己处理按键
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private SwitchCompat sw_boot, sw_reverse, sw_pip;
    private TextView tv_screen_ratio, tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    private TextView tv_channel_line;
    private TextView tv_resolution_status;
    private View itemResolution;
    
    private View itemLog;
    private TextView tv_log_status;

    private View itemVersionInfo;
    private TextView tv_version_short;
    
    private LinearLayout itemLiveSubscribe, itemEpgSubscribe;
    
    private SharedPreferences sp;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;
    
    private BootStartManager bootStartManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    private UpdateManager updateManager;
    
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";
    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    // ====================== 焦点管理 ====================
    private int settingsFocusPosition = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable focusUpdateRunnable;

    // ====================== 控件复用 ====================
    private android.util.SparseArray<TextView> itemTextViews = new android.util.SparseArray<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try { applyFullScreen(); } catch (Exception e) { }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
        
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        initRedirectDefaultConfig();

        sp.edit().putBoolean("epg_enable", true).apply();
        sp.edit().putBoolean("number_channel_enable", true).apply();

        sw_boot = findViewById(R.id.sw_boot);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        tv_redirect_setting = findViewById(R.id.tv_redirect_setting);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);
        
        itemResolution = findViewById(R.id.item_resolution);
        tv_resolution_status = findViewById(R.id.tv_resolution_status);

        itemLog = findViewById(R.id.item_log);
        tv_log_status = findViewById(R.id.tv_log_status);

        itemVersionInfo = findViewById(R.id.item_version_info);
        tv_version_short = findViewById(R.id.tv_version_short);
        
        bootStartManager = new BootStartManager(this, sp);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);
        
        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);

        initSettingsItemList();  // 初始化设置项列表

        tv_channel_line = findViewById(R.id.tv_channel_line);
        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        tv_channel_line.setText(getLineName(currentLineIndex));
        findViewById(R.id.item_channel_line).setOnClickListener(v -> showChannelLineDialog());

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

        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            if (isChecked) {
                Toast.makeText(this, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "画中画已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> showDecoderModeDialog());
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        findViewById(R.id.item_renderer).setOnClickListener(v -> showRendererModeDialog());
        updateRedirectSettingText();
        findViewById(R.id.item_redirect).setOnClickListener(v -> showRedirectConfigDialog());
        findViewById(R.id.item_check_update).setOnClickListener(v -> updateManager.checkUpdate());
        
        itemResolution.setOnClickListener(v -> showResolutionDialog());

        itemVersionInfo.setOnClickListener(v -> showVersionInfoDialog());
        
        initListeners();
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        SourceManager liveManager = new SourceManager(this, "live_history");
        if (liveManager.size() == 0) {
            liveManager.addSource("默认直播源", UrlConfig.LIVE_URL);
        }
        SourceManager epgManager = new SourceManager(this, "epg_history");
        if (epgManager.size() == 0) {
            epgManager.addSource("默认节目单", UrlConfig.EPG_URL);
        }

        // 初始化焦点
        settingsFocusPosition = 0;
        updateSettingsFocus();
    }

    // ==================== 设置项列表初始化 ====================
    private void initSettingsItemList() {
        settingsItemList.clear();
        itemTextViews.clear();

        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_channel_line));
        settingsItemList.add(findViewById(R.id.item_decoder));
        settingsItemList.add(findViewById(R.id.item_renderer));
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));
        settingsItemList.add(itemResolution);
        settingsItemList.add(findViewById(R.id.item_redirect));
        settingsItemList.add(itemLiveSubscribe);
        settingsItemList.add(itemEpgSubscribe);
        settingsItemList.add(findViewById(R.id.item_check_update));
        settingsItemList.add(itemLog);
        settingsItemList.add(itemVersionInfo);

        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
        }

        // 缓存每个项内的 TextView
        for (int i = 0; i < settingsItemList.size(); i++) {
            View view = settingsItemList.get(i);
            if (view instanceof TextView) {
                itemTextViews.put(i, (TextView) view);
            } else if (view instanceof ViewGroup) {
                TextView tv = findFirstTextView((ViewGroup) view);
                if (tv != null) {
                    itemTextViews.put(i, tv);
                }
            }
        }

        // 给每个 item 设置焦点变化监听（用于更新样式）
        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item != null) {
                item.setFocusableInTouchMode(true);
                item.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && settingsFocusPosition != position) {
                        settingsFocusPosition = position;
                        updateSettingsFocus();
                    }
                });
            }
        }
    }

    // ==================== 焦点样式更新 ====================
    private void updateSettingsFocus() {
        if (settingsFocusPosition < 0 || settingsFocusPosition >= settingsItemList.size()) return;

        View target = settingsItemList.get(settingsFocusPosition);
        if (target == null) return;

        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == settingsFocusPosition) {
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
            } else {
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }

        if (focusUpdateRunnable != null) {
            mainHandler.removeCallbacks(focusUpdateRunnable);
        }
        focusUpdateRunnable = () -> {
            scrollToView(target);
            target.requestFocus();
        };
        mainHandler.post(focusUpdateRunnable);
    }

    private void setItemStyle(View item, String textColor, int typefaceStyle, int bgColor) {
        item.setBackgroundColor(bgColor);
        if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typefaceStyle);
        } else {
            int index = settingsItemList.indexOf(item);
            TextView tv = null;
            if (index >= 0 && itemTextViews != null) {
                tv = itemTextViews.get(index);
            }
            if (tv == null) {
                if (item instanceof ViewGroup) {
                    tv = findFirstTextView((ViewGroup) item);
                }
            }
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor));
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
        int currScroll = scrollView.getScrollY();

        if (viewTop < currScroll) {
            scrollView.scrollTo(0, Math.max(0, viewTop - 50));
        } else if (viewBottom > currScroll + scrollViewHeight) {
            scrollView.scrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }

    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item == null) return;
        item.performClick();
    }

    // ==================== 按键处理 ====================
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();

        // 菜单/帮助/设置键 → 关闭
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_HELP || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            finish();
            return true;
        }

        // 上下键移动焦点
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (settingsFocusPosition > 0) {
                settingsFocusPosition--;
                updateSettingsFocus();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (settingsFocusPosition < settingsItemList.size() - 1) {
                settingsFocusPosition++;
                updateSettingsFocus();
            }
            return true;
        }

        // 确认键执行点击
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            handleSettingsItemClick(settingsFocusPosition);
            return true;
        }

        // 返回键关闭
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    // ==================== 以下保留所有原本的方法（对话框、设置项点击等） ====================
    // 注意：删除了 initRemoteManager() 和所有 remoteManager 引用

    private void showVersionInfoDialog() { /* 原样 */ }
    private String getLineName(int index) { /* 原样 */ }
    private void showDarkSingleChoiceDialog(String title, String[] items, int checkedItem, java.util.function.Consumer<Integer> onSelected) { /* 原样 */ }
    private void showChannelLineDialog() { /* 原样 */ }
    private void initRedirectDefaultConfig() { /* 原样 */ }
    private void updateRedirectSettingText() { /* 原样 */ }
    private void applyFullScreen() { /* 原样 */ }
    private void initListeners() { /* 原样，删除了 remoteManager 相关 */ }
    private void showResolutionDialog() { /* 原样 */ }
    private void showSubscriptionDialog(String spKey, String title) { /* 原样 */ }
    private void showRatioDialog() { /* 原样 */ }
    private void showDecoderModeDialog() { /* 原样 */ }
    private void updateDecoderModeText(String mode) { /* 原样 */ }
    private void showRendererModeDialog() { /* 原样 */ }
    private void updateRendererModeText(String mode) { /* 原样 */ }
    private void showRedirectConfigDialog() { /* 原样 */ }

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
        if (webServerManager != null) {
            webServerManager.stop();
        }
        if (updateManager != null) {
            updateManager.release();
        }
        mainHandler.removeCallbacksAndMessages(null);
        settingsItemList.clear();
        settingsItemList = null;
        itemTextViews.clear();
        itemTextViews = null;
    }
}
