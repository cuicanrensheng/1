package com.tv.live;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.tv.live.manager.TvRemoteManager;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private Switch sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode; // 解码器显示文本
    private TextView tv_boot_status;
    
    // ====================== 配置相关 ======================
    private SharedPreferences sp;
    
    // 遥控器统一管理器
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;
    
    // 管理器相关
    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    
    // 应用更新管理器
    private UpdateManager updateManager;
    
    // ====================== SP Key 常量 ======================
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    
    // 全局日志系统
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();
    
    // 日志记录方法
    public static void log(String msg) {
        LogManager.log(msg);
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder();
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
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全面屏设置
        try {
            applyFullScreen();
        } catch (Exception e) {
            logOperation("【设置】全面屏适配失败：" + e.getMessage());
        }
        
        // 刘海屏适配
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) {
            // 兜底
        }
        
        // 清除背景变暗
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) {
            // 兜底
        }
        
        // 窗口设置
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        
        // 点击空白区域关闭设置
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());
        
        // 初始化SharedPreferences
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        
        // 绑定控件
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        
        // 获取ScrollView
        scrollView = findViewById(R.id.settings_content);
        
        // 初始化管理器
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);
        
        // 初始化设置项列表
        initSettingsItemList();
        
        // 初始化遥控器管理器
        initRemoteManager();
        
        // 日志查看按钮
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialog());
        
        // 开机自启设置
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
        
        // 画中画开关
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
        
        // 检查更新
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
        });
        
        // 初始化其他监听
        initListeners();
        
        // 启动网页后台
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
        logOperation("【设置】打开设置页面");
    }
    
    // 全面屏设置
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
    
    // 初始化设置项列表
    private void initSettingsItemList() {
        settingsItemList.clear();
        // 添加设置项（按顺序）
        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_epg));
        settingsItemList.add(findViewById(R.id.item_auto_update));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_num_channel));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_decoder));
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));
        settingsItemList.add(findViewById(R.id.tv_custom_source));
        settingsItemList.add(findViewById(R.id.tv_custom_epg));
        settingsItemList.add(findViewById(R.id.tv_multi_source));
        settingsItemList.add(findViewById(R.id.tv_multi_epg));
        settingsItemList.add(findViewById(R.id.tv_qr_code));
        settingsItemList.add(findViewById(R.id.log_viewer));
        settingsItemList.add(findViewById(R.id.log_operation));
        settingsItemList.add(findViewById(R.id.item_check_update));
        
        // 移除null项
        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
        }
        
        // 设置焦点监听
        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item != null) {
                item.setFocusableInTouchMode(true);
                item.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && remoteManager != null) {
                        // 修复：更新焦点位置
                        remoteManager.setCurrentFocusPosition(position);
                        updateSettingsFocus();
                    }
                });
            }
        }
    }
    
    // 初始化遥控器管理器
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager(this);
        // 修复1：setSettingsItemCount需要传入列表大小（整数），而不是列表本身
        remoteManager.setSettingsItemCount(settingsItemList.size());
        
        // 设置遥控器事件监听
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override
            public boolean onKeyUp() {
                moveFocusUp();
                logOperation("【遥控器】上键，焦点上移");
                return true;
            }
            
            @Override
            public boolean onKeyDown() {
                moveFocusDown();
                logOperation("【遥控器】下键，焦点下移");
                return true;
            }
            
            @Override
            public boolean onKeyOk() {
                clickCurrentFocusItem();
                logOperation("【遥控器】确认键，点击当前焦点项");
                return true;
            }
            
            @Override
            public boolean onKeyBack() {
                finish();
                logOperation("【遥控器】返回键，关闭设置页面");
                return true;
            }
            
            @Override
            public boolean onPipBack() {
                // 设置页面不处理画中画返回键
                return false;
            }
        });
    }
    
    // 移动焦点向上
    private void moveFocusUp() {
        int currentPos = remoteManager.getCurrentFocusPosition();
        // 修复2：变量名错误 settingsItem -> settingsItemList
        if (currentPos <= 0 || currentPos >= settingsItemList.size()) return;
        
        View prevItem = settingsItemList.get(currentPos - 1);
        prevItem.requestFocus();
        scrollToView(prevItem);
    }
    
    // 移动焦点向下
    private void moveFocusDown() {
        int currentPos = remoteManager.getCurrentFocusPosition();
        // 修复2：变量名错误 settingsItem -> settingsItemList
        if (currentPos < 0 || currentPos >= settingsItemList.size() - 1) return;
        
        View nextItem = settingsItemList.get(currentPos + 1);
        nextItem.requestFocus();
        scrollToView(nextItem);
    }
    
    // 点击当前焦点项
    private void clickCurrentFocusItem() {
        int currentPos = remoteManager.getCurrentFocusPosition();
        // 修复2：变量名错误 settingsItem -> settingsItemList
        if (currentPos < 0 || currentPos >= settingsItemList.size()) return;
        
        View item = settingsItemList.get(currentPos);
        item.performClick();
    }
    
    // 滚动到指定View
    private void scrollToView(View view) {
        if (scrollView != null && view != null) {
            scrollView.smoothScrollTo(0, view.getTop());
        }
    }
    
    // 更新设置项焦点样式
    private void updateSettingsFocus() {
        int currentFocusPos = remoteManager.getCurrentFocusPosition();
        
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            
            TextView tv = null;
            // 找到item中的TextView
            if (item instanceof TextView) {
                tv = (TextView) item;
            } else if (item instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) item;
                for (int j = 0; j < vg.getChildCount(); j++) {
                    View child = vg.getChildAt(j);
                    if (child instanceof TextView) {
                        tv = (TextView) child;
                        break;
                    }
                }
            }
            
            if (tv != null) {
                boolean isSelected = (i == currentFocusPos && item.isSelected());
                boolean hasFocus = (i == currentFocusPos && item.hasFocus());
                
                // 修复3：Typeface设置错误 - 使用正确的setTypeface重载方法
                if (isSelected) {
                    // 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                    tv.setTextColor(Color.parseColor("#007AFF"));
                    tv.setTypeface(null, Typeface.BOLD); // 修复：第一个参数传null，第二个传样式
                    tv.setBackgroundColor(Color.parseColor("#33007AFF"));
                } else if (hasFocus) {
                    // 焦点状态：蓝色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.parseColor("#007AFF"));
                    tv.setTypeface(null, Typeface.NORMAL); // 修复
                    tv.setBackgroundColor(Color.TRANSPARENT);
                } else {
                    // 未选中状态：白色文字 + 常规 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL); // 修复
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
            }
        }
        
        logOperation("【设置】更新焦点样式，当前焦点位置：" + currentFocusPos);
    }
    
    // 更新解码器模式显示文本
    private void updateDecoderModeText(String mode) {
        // 修复4：变量名错误 tv_decoder -> tv_decoder_mode
        switch (mode) {
            case "hard":
                tv_decoder_mode.setText("硬解");
                break;
            case "soft":
                tv_decoder_mode.setText("软解（兼容性好）");
                break;
            default:
                tv_decoder_mode.setText("自动（推荐）");
                break;
        }
    }
    
    // 显示解码器选择对话框
    private void showDecoderModeDialog() {
        String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        String currentMode = sp.getString("decoder_mode", "auto");
        
        int selectedIndex = 0;
        if ("hard".equals(currentMode)) {
            selectedIndex = 1;
        } else if ("soft".equals(currentMode)) {
            selectedIndex = 2;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("选择解码器")
                .setSingleChoiceItems(modes, selectedIndex, (dialog, which) -> {
                    String newMode = "auto";
                    switch (which) {
                        case 1:
                            newMode = "hard";
                            break;
                        case 2:
                            newMode = "soft";
                            break;
                    }
                    
                    // 保存新的解码器模式
                    sp.edit().putString("decoder_mode", newMode).apply();
                    updateDecoderModeText(newMode);
                    
                    // 发送广播通知MainActivity更新解码器
                    Intent intent = new Intent("com.tv.live.DECODER_MODE_CHANGED");
                    intent.putExtra("mode", newMode);
                    sendBroadcast(intent);
                    
                    logOperation("【设置】切换解码器模式为：" + newMode);
                    Toast.makeText(this, "解码器已切换为：" + modes[which], Toast.LENGTH_SHORT).show();
                    
                    dialog.dismiss();
                })
                .show();
    }
    
    // 显示日志对话框
    private void showLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("解析&播放日志");
        
        EditText etLog = new EditText(this);
        etLog.setText(PLAY_LOG.toString());
        etLog.setFocusable(true);
        etLog.setFocusableInTouchMode(true);
        
        builder.setView(etLog);
        builder.setPositiveButton("清空", (dialog, which) -> {
            PLAY_LOG.setLength(0);
            LogManager.clearPlayLog();
            etLog.setText("");
            logOperation("【设置】清空解析&播放日志");
        });
        builder.setNegativeButton("关闭", null);
        builder.show();
    }
    
    // 显示操作日志对话框
    private void showOperationLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("操作日志");
        
        EditText etLog = new EditText(this);
        etLog.setText(OPERATION_LOG.toString());
        etLog.setFocusable(true);
        etLog.setFocusableInTouchMode(true);
        
        builder.setView(etLog);
        builder.setPositiveButton("清空", (dialog, which) -> {
            OPERATION_LOG.setLength(0);
            LogManager.clearOperationLog();
            etLog.setText("");
            logOperation("【设置】清空操作日志");
        });
        builder.setNegativeButton("关闭", null);
        builder.show();
    }
    
    // 初始化其他监听
    private void initListeners() {
        // 屏幕比例
        tv_screen_ratio.setOnClickListener(v -> {
            // 实现屏幕比例选择逻辑
            logOperation("【设置】打开屏幕比例选择");
        });
        
        // 自定义订阅源
        tv_custom_source.setOnClickListener(v -> {
            // 实现自定义订阅源逻辑
            logOperation("【设置】打开自定义订阅源");
        });
        
        // 自定义节目单
        tv_custom_epg.setOnClickListener(v -> {
            // 实现自定义节目单逻辑
            logOperation("【设置】打开自定义节目单");
        });
        
        // 多订阅源
        tv_multi_source.setOnClickListener(v -> {
            sourceDialogManager.showSourceDialog();
            logOperation("【设置】打开多订阅源管理");
        });
        
        // 多节目单
        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showEpgDialog();
            logOperation("【设置】打开多节目单管理");
        });
        
        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
            logOperation("【设置】打开扫码添加");
        });
    }
    
    // 按键事件处理
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null) {
            return remoteManager.handleKeyEvent(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 停止网页后台
        if (webServerManager != null) {
            webServerManager.stop();
        }
        logOperation("【设置】关闭设置页面");
    }
}
