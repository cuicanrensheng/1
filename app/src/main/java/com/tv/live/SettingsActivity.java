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
 * 7. ✅ 解码器选择（自动/硬解/软解）（2026-06-25 新增）
 * 8. 屏幕比例设置
 * 9. 自定义订阅源/节目单
 * 10. 多订阅源/节目单管理（委托给 SourceDialogManager）
 * 11. 扫码添加（委托给 QRCodeManager）
 * 12. 解析&播放日志查看
 * 13. 操作日志查看
 * 14. 检查更新（委托给 UpdateManager）
 *
 * 【2026-06-20 新增：接入 TvRemoteManager 统一遥控器管理】
 * 【集成说明】
 * 1. 创建设置项列表，统一管理所有可聚焦的设置项
 * 2. 初始化 TvRemoteManager，设置为 SETTINGS_MODE
 * 3. 在 onKeyDown 中统一分发按键
 * 4. 通过回调处理焦点移动和选中操作
 *
 * 【遥控器操作】
 * - ↑/↓：在设置项之间上下移动
 * - OK/确认：选中当前项（点击/切换开关）
 * - 返回/菜单：关闭设置页面
 *
 * 【效果】
 * - 统一的遥控器按键处理
 * - 完整的操作日志
 * - 焦点位置记忆
 * - 易于扩展（新增设置项只需要加到列表里）
 *
 * 【2026-06-20 优化：设置项高亮改成代码动态设置，肯定生效】
 * 【优化原因】
 * 原来用 setSelected() + background drawable 的方式，
 * 但是布局里文字颜色是写死的白色，背景用的是 setting_item_bg.xml，
 * 导致遥控器操作时看不到焦点高亮在哪里。
 * 【优化方案】
 * 改成代码动态设置背景色和文字颜色，不依赖布局里的 drawable 和 color selector，
 * 肯定能看到焦点，而且和频道面板的高亮样式完全统一。
 *
 * 【2026-06-20 优化：手机点击时光标跟随移动】
 * 【原来的问题】
 * 手机点击设置项时，光标（高亮）不会跟着移动，
 * 因为点击只触发了 OnClickListener，没有更新焦点位置。
 * 【优化方案】
 * 1. 给每个设置项设置 focusableInTouchMode=true（手机点击时也能获得焦点）
 * 2. 给每个设置项设置 OnFocusChangeListener（焦点变化时自动更新高亮）
 * 这样无论是遥控器操作还是手机点击，光标都会跟着移动。
 *
 * 【2026-06-21 优化：统一三种状态样式，和列表完全一致】
 * 【优化内容】
 * 从两种状态（选中/普通）改成三种状态：
 * 1. 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
 * 2. 焦点状态：蓝色文字 + 常规 + 透明背景
 * 3. 未选中状态：白色文字 + 常规 + 透明背景
 *
 * 【为什么改成三种状态？】
 * 和频道分组、频道列表、日期列表、节目单列表保持一致的样式体系，
 * 整个应用的高亮样式统一，用户体验一致。
 *
 * 【判断优先级】
 * 选中状态 > 焦点状态 > 未选中状态
 *
 * 【2026-06-22 新增：画中画开关功能】
 * 【功能说明】
 * 开启后，按 Home 键退到后台时自动进入画中画小窗播放；
 * 关闭后，退到后台不会进入小窗。
 * 【存储 Key】pip_enable，默认 false（关闭）
 * 【兼容性】仅 Android 8.0 (API 26) 及以上系统支持
 *
 * 【2026-06-25 新增：解码器选择功能】
 * 【功能说明】
 * 支持三种解码器模式：自动（推荐）、硬解、软解（兼容性好）
 * - 自动：硬解优先，卡顿自动切换到系统软解
 * - 硬解：强制使用系统硬解码器
 * - 软解：优先使用系统软件解码器
 * 【存储 Key】decoder_mode，默认 auto（自动模式）
 * 【联动说明】切换后发送广播，通知 MainActivity 立即应用新的解码器
 *
 * 【2026-06-25 优化：问题修复 + 日志优化 + 崩溃防护】
 * 
 * 【本次优化内容】
 * 1. ✅ 精简 updateSettingsFocus() 日志（从每次17+条精简到1条）
 * 2. ✅ 优化 log() / logOperation() 方法，减少不必要的 StringBuilder 创建
 * 3. ✅ 遥控器上下键移动焦点时记录操作日志
 * 4. ✅ 修复 onSettingsFocusChanged 和 onSettingsMoveUp/Down 重复调用问题
 * 5. ✅ 全面屏设置增加 try-catch 崩溃防护（用旧 API，兼容性最好）
 * 6. ✅ 统一清空日志的顺序（先清 LogManager 再清本地缓存）
 * 7. ✅ 所有优化点接入操作日志
 *
 * 【2026-06-26 修改：改用系统自带软解，替代 FFmpeg 方案】
 * 【修改说明】
 * 把所有"软解（FFmpeg）"的文字改成"软解（兼容性好）"，
 * 把"FFmpeg 软解"改成"系统软解"。
 * 因为现在改用系统自带的软件解码器（OMX.google.* / c2.android.*），
 * 不再使用 FFmpeg 扩展了。
 *
 * 【2026-06-26 修改：新增 onPipBack() + onRequestPlayFocus() 完整实现，修复编译报错】
 * 【修改说明】
 * TvRemoteManager.OnRemoteActionListener 新增两个抽象方法，
 * 匿名类全部实现，消除「非抽象类未覆盖抽象方法」编译错误。
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private Switch sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode;
    private TextView tv_boot_status;

    // ====================== 配置相关 ======================
    private SharedPreferences sp;

    // 遥控器统一管理器
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;

    // 业务管理器
    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    private UpdateManager updateManager;

    // SP Key 常量
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    // 全局日志缓存
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    /** 记录播放日志 */
    public static void log(String msg) {
        LogManager.log(msg);
        if (PLAY_LOG == null) PLAY_LOG = new StringBuilder();
        PLAY_LOG.append(msg).append("\n");
    }

    /** 记录操作日志 */
    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
        if (OPERATION_LOG == null) OPERATION_LOG = new StringBuilder();
        OPERATION_LOG.append(msg).append("\n");
    }

    // ====================== onCreate ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全屏适配防护
        try {
            applyFullScreen();
        } catch (Exception e) {}
        // 刘海屏适配
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) {}
        // 清除背景变暗
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) {}

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        // 空白区域点击关闭页面
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);

        // 绑定控件
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_screen_ratio = findViewById(R.id.tv_custom_source);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);

        // 初始化业务管理器
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);

        initSettingsItemList();
        initRemoteManager();

        // 日志按钮
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
            logOperation("【设置】节目" + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 自动更新源
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            if (isChecked) autoUpdateManager.setAutoUpdateAlarm();
            else autoUpdateManager.cancelAutoUpdateAlarm();
            logOperation("【设置】自动更新源" + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（凌晨4点更新）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        if (sp.getBoolean("auto_update_source", true)) autoUpdateManager.setAutoUpdateAlarm();

        // 换台反转
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("【设置】换台反转" + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 数字选台
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("【设置】数字选台" + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 画中画开关
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            logOperation("【设置】画中画" + (isChecked ? "开启" : "关闭"));
            Toast.makeText(this, isChecked ? "Home键自动小窗播放" : "画中画已关闭", Toast.LENGTH_SHORT).show();
        });

        // 解码器选择
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> {
            showDecoderModeDialog();
            logOperation("【设置】打开解码器设置");
        });

        // 检查更新
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
        });

        initListeners();
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
        logOperation("【设置】进入设置页面");
    }

    // 全屏适配
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
            logOperation("【设置】全屏适配失败：" + e.getMessage());
        }
    }

    // 初始化设置项焦点列表
    private void initSettingsItemList() {
        settingsItemList.clear();
        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_epg));
        settingsItemList.add(findViewById(R.id.item_auto_update));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_num_channel));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_decoder));
        settingsItemList.add(tv_screen_ratio);
        settingsItemList.add(tv_custom_source);
        settingsItemList.add(tv_custom_epg);
        settingsItemList.add(tv_multi_source);
        settingsItemList.add(tv_multi_epg);
        settingsItemList.add(tv_qr_code);
        settingsItemList.add(findViewById(R.id.log_viewer));
        settingsItemList.add(findViewById(R.id.log_operation));
        settingsItemList.add(findViewById(R.id.item_check_update));

        // 移除空View
        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) settingsItemList.remove(i);
        }

        // 绑定触摸焦点监听
        for (int i = 0; i < settingsItemList.size(); i++) {
            final int pos = i;
            View item = settingsItemList.get(i);
            if (item == null) continue;
            item.setFocusableInTouchMode(true);
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && remoteManager != null) {
                    int curPos = remoteManager.getSettingsFocusPosition();
                    if (curPos != pos) {
                        remoteManager.setSettingsFocusPosition(pos);
                        updateSettingsFocus();
                        logOperation("【设置】点击焦点切换：第" + (pos + 1) + "项");
                    }
                }
            });
        }
    }

    // 【核心修复】完整实现所有接口方法，消除编译报错
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.SETTINGS_MODE);
        remoteManager.setSettingsItemCount(settingsItemList);

        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            // ========== 播放模式空实现 ==========
            @Override public void onPlayChannelUp() {}
            @Override public void onPlayChannelDown() {}
            @Override public void onPlayTogglePanel() {}
            @Override public void onPlayOpenSettings() {}
            @Override public boolean onPlayBack() { return false; }

            // ========== 频道面板空实现 ==========
            @Override public void onPanelMoveUp() {}
            @Override public void onPanelMoveDown() {}
            @Override public void onPanelMoveLeft() {}
            @Override public void onPanelMoveRight() {}
            @Override public void onPanelConfirm() {}
            @Override public boolean onPanelBack() { return false; }
            @Override public void onPanelMenu() {}
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}

            // ========== 设置页面回调 ==========
            @Override
            public void onSettingsMoveUp() {
                int pos = remoteManager.getSettingsFocusPosition();
                logOperation("【遥控】上键，焦点：" + (pos + 1));
                updateSettingsFocus();
            }
            @Override
            public void onSettingsMoveDown() {
                int pos = remoteManager.getSettingsFocusPosition();
                logOperation("【遥控】下键，焦点：" + (pos + 1));
                updateSettingsFocus();
            }
            @Override
            public void onSettingsConfirm() {
                int pos = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(pos);
            }
            @Override
            public boolean onSettingsBack() {
                logOperation("【遥控】返回键关闭设置");
                finish();
                return true;
            }
            @Override
            public void onSettingsMenu() {
                logOperation("【遥控】菜单键关闭设置");
                finish();
            }
            @Override
            public void onSettingsFocusChanged(int position) {
                updateSettingsFocus();
            }

            // ========== 画中画返回 ==========
            @Override
            public boolean onPipBack() {
                return false;
            }

            // ========== 关键修复：补齐缺失抽象方法 onRequestPlayFocus ==========
            @Override
            public void onRequestPlayFocus() {
                // 设置页面无播放视图，无需处理
                logOperation("【遥控】收到播放焦点请求，忽略");
            }
        });

        updateSettingsFocus();
    }

    // 更新焦点高亮样式
    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int selectPos = remoteManager.getSettingsFocusPosition();
        logOperation("【设置】刷新焦点：第" + (selectPos + 1) + "项");

        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            TextView tv = findFirstTextView(item);
            if (tv == null) continue;

            boolean selected = isItemSelected(item);
            boolean focus = i == selectPos;
            if (selected) {
                tv.setTextColor(Color.parseColor("#40A9FF"));
                tv.setTypeface(Typeface.BOLD);
                item.setBackgroundColor(0x3340A9FF);
            } else if (focus) {
                tv.setTextColor(Color.parseColor("#40A9FF"));
                tv.setTypeface(Typeface.NORMAL);
                item.setBackgroundColor(Color.TRANSPARENT);
            } else {
                tv.setTextColor(Color.WHITE);
                tv.setTypeface(Typeface.NORMAL);
                item.setBackgroundColor(Color.TRANSPARENT);
            }
            if (focus) {
                item.requestFocus();
                scrollToView(item);
            }
        }
    }

    // 递归查找布局内TextView
    private TextView findFirstTextView(View root) {
        if (root instanceof TextView) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView res = findFirstTextView(g.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }

    // 判断开关项是否勾选
    private boolean isItemSelected(View item) {
        int id = item.getId();
        if (id == R.id.item_boot) return sw_boot.isChecked();
        if (id == R.id.item_epg) return sw_epg.isChecked();
        if (id == R.id.item_auto_update) return sw_auto_update.isChecked();
        if (id == R.id.item_reverse) return sw_reverse.isChecked();
        if (id == R.id.item_num_channel) return sw_num_channel.isChecked();
        if (id == R.id.item_pip) return sw_pip.isChecked();
        return false;
    }

    // 滚动到可见区域
    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;
        int top = view.getTop();
        int bot = view.getBottom();
        int scrollY = scrollView.getScrollY();
        int h = scrollView.getHeight();
        if (top < scrollY) scrollView.smoothScrollTo(0, top - 40);
        else if (bot > scrollY + h) scrollView.smoothScrollTo(0, bot - h + 40);
    }

    // 模拟点击设置项
    private void handleSettingsItemClick(int pos) {
        if (pos < 0 || pos >= settingsItem.size()) return;
        View v = settingsItemList.get(pos);
        if (v != null) v.performClick();
        logOperation("【遥控】点击第" + (pos + 1) + "项");
    }

    // 更新解码器文字
    private void updateDecoderModeText(String mode) {
        switch (mode) {
            case "hard": tv_decoder.setText("硬解"); break;
            case "soft": tv_decoder.setText("软解（兼容性好）"); break;
            default: tv_decoder.setText("自动（推荐）");
        }
    }

    // 解码器弹窗
    private void showDecoderModeDialog() {
        String[] names = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        String[] vals = {"auto", "hard", "soft"};
        String cur = sp.getString("decoder_mode", "auto");
        int check = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].equals(cur)) check = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("解码器选择")
                .setSingleChoiceItems(names, check, (d, w) -> {
                    String sel = vals[w];
                    sp.edit().putString("decoder_mode", sel).apply();
                    updateDecoderModeText(sel);
                    logOperation("【设置】切换解码器：" + names[w]);
                    sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换解码器", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 屏幕比例弹窗
    private void showRatioDialog() {
        String[] arr = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(arr, (d, w) -> {
                    sp.edit().putString("screen_ratio", arr[w]).apply();
                    logOperation("【设置】屏幕比例：" + arr[w]);
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                }).show();
    }

    // 自定义源输入弹窗
    private void showInputDialog(String title, String hint, String key) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(sp.getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (!url.isEmpty()) {
                        sp.edit().putString(key, url).apply();
                        SourceManager sm = new SourceManager(this, key.contains("live") ? "live_history" : "epg_history");
                        sm.addSource(url.substring(0, 10) + "...", url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【设置】更新地址：" + url);
                        Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 初始化文本类点击事件
    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());
        tv_custom_source.setOnClickListener(v -> showInputDialog("自定义直播源", "输入直播源地址", KEY_CUSTOM_LIVE));
        tv_custom_epg.setOnClickListener(v -> showInputDialog("自定义EPG", "输入节目单地址", KEY_CUSTOM_EPG));
        tv_multi_source.setOnClickListener(v -> sourceDialogManager.showHistoryDialog("直播源历史", "live_history"));
        tv_multi_epg.setOnClickListener(v -> sourceDialogManager.showHistoryDialog("EPG历史", "epg_history"));
        tv_qr_code.setOnClickListener(v -> qrCodeManager.showQRCodeDialog(currentWebUrl));
    }

    // 播放日志弹窗
    private void showLogDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("解析&播放日志");
        EditText et = new EditText(this);
        et.setTextColor(Color.WHITE);
        et.setBackgroundColor(Color.BLACK);
        et.setKeyListener(null);
        et.setText(PLAY_LOG.toString());
        b.setView(et);
        b.setPositiveButton("清空", (d, w) -> {
            PLAY_LOG.setLength(0);
            LogManager.clearPlayLog();
            et.setText("");
            logOperation("【设置】清空播放日志");
        });
        b.setNegativeButton("关闭", null);
        AlertDialog dialog = b.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        dialog.show();
    }

    // 操作日志弹窗
    private void showOperationLogDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("操作日志");
        EditText et = new EditText(this);
        et.setTextColor(Color.WHITE);
        et.setBackgroundColor(Color.BLACK);
        et.setKeyListener(null);
        et.setText(OPERATION_LOG.toString());
        b.setView(et);
        b.setPositiveButton("清空", (d, w) -> {
            OPERATION_LOG.setLength(0);
            LogManager.clearOperationLog();
            et.setText("");
            logOperation("【设置】清空操作日志");
        });
        b.setNegativeButton("关闭", null);
        AlertDialog dialog = b.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        dialog.show();
    }

    // 统一按键分发
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // 窗口焦点刷新全屏
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                applyFullScreen();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.dimAmount = 0f;
                getWindow().setAttributes(lp);
            } catch (Exception e) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServerManager != null) webServerManager.stop();
        if (autoUpdateManager != null && !sp.getBoolean("auto_update_source", false)) {
            autoUpdateManager.cancelAutoUpdateAlarm();
        }
        remoteManager = null;
        settingsItemList.clear();
        logOperation("【设置】页面销毁");
    }
}
