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
 * 【2026-06-26 修改：新增 onPipBack() 回调实现】
 * 【修改说明】
 * TvRemoteManager.OnRemoteActionListener 接口新增了 onPipBack() 抽象方法，
 * SettingsActivity 中的匿名实现类需要覆盖这个方法，否则编译报错。
 * 设置页面不处理画中画返回键，返回 false 交给系统处理。
 * 
 * 【2026-06-26 修改：新增 onRequestPlayFocus() 回调实现】
 * 【修改说明】
 * TvRemoteManager.OnRemoteActionListener 接口新增了 onRequestPlayFocus() 抽象方法，
 * SettingsActivity 中的匿名实现类需要覆盖这个方法，否则编译报错。
 * 设置页面用不到请求播放焦点，空实现即可。
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    /**
     * 开关控件集合
     * 【说明】所有带 Switch 开关的设置项
     */
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    /**
     * ✅ 画中画开关控件（2026-06-22 新增）
     * 【作用】控制画中画功能的开启/关闭
     * 【对应布局ID】R.id.sw_pip
     */
    private Switch sw_pip;
    /**
     * 纯文本点击项
     * 【说明】没有开关，点击后弹出对话框的设置项
     */
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    /**
     * ✅ 解码器选择 - 当前值显示（2026-06-25 新增）
     * 【作用】显示当前解码器模式（自动/硬解/软解）
     * 【对应布局ID】R.id.tv_decoder_mode
     */
    private TextView tv_decoder_mode;
    /**
     * 开机自启状态描述文本
     * 【作用】显示开机自启的当前状态（已开启/已关闭/不支持等）
     */
    private TextView tv_boot_status;
    // ====================== 配置相关 ======================
    /**
     * SharedPreferences 配置存储
     * 【作用】轻量级本地存储，保存用户的设置项
     * 【文件名】app_settings
     */
    private SharedPreferences sp;
    // ====================================================================
    // ✅ 新增：遥控器统一管理器
    // ====================================================================
    /**
     * 遥控器统一管理器
     *
     * 【功能】
     * 统一管理设置页面的所有遥控器按键操作
     *
     * 【为什么用统一管理器？】
     * 1. 所有按键逻辑集中管理，不分散
     * 2. 新增/删除设置项只需要调整列表，不用改按键逻辑
     * 3. 自带完整的操作日志，方便排查问题
     * 4. 和 MainActivity、ChannelPanelController 用同一套体系
     */
    private TvRemoteManager remoteManager;
    /**
     * 可聚焦的设置项列表（按从上到下的顺序排列）
     *
     * 【说明】
     * 所有需要遥控器焦点的 View 都加到这个列表里，
     * 遥控器上下键就按这个顺序移动焦点。
     *
     * 【为什么用列表？】
     * 新增/删除设置项只需要调整这个列表，
     * 不用修改任何按键处理逻辑，非常方便。
     */
    private List<View> settingsItemList = new ArrayList<>();
    /**
     * 滚动视图（用于滚动到可见区域）
     * 【作用】当焦点移动到屏幕外时，自动滚动让用户看到
     */
    private ScrollView scrollView;
    // ====================================================================
    // 管理器相关（全部拆分后）
    // ====================================================================
    /**
     * 开机自启管理器
     * 【作用】管理开机自启功能的开启/关闭、状态显示
     */
    private BootStartManager bootStartManager;
    /**
     * 自动更新管理器
     * 【作用】管理直播源的自动更新（每天凌晨4点）
     */
    private AutoUpdateManager autoUpdateManager;
    /**
     * 订阅源对话框管理器
     * 【作用】管理多订阅源/多节目单的历史记录
     */
    private SourceDialogManager sourceDialogManager;
    /**
     * 扫码管理器
     * 【作用】显示二维码，支持手机扫码添加直播源
     */
    private QRCodeManager qrCodeManager;
    /**
     * 网页后台管理器
     * 【作用】启动本地 HTTP 服务器，支持网页端管理
     */
    private WebServerManager webServerManager;
    /**
     * 网页后台端口号
     * 【默认值】10481
     */
    private static final int WEB_SERVER_PORT = 10481;
    /**
     * 当前网页后台访问地址
     * 【格式】http://IP:端口
     */
    private String currentWebUrl;
    // ====================================================================
    // 应用更新管理器
    // ====================================================================
    /**
     * 应用更新管理器
     * 【作用】检查更新、下载安装包、自动安装
     */
    private UpdateManager updateManager;
    // ====================== SP Key 常量 ======================
    /**
     * 自定义直播源地址的存储 Key
     */
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    /**
     * 自定义 EPG 节目单地址的存储 Key
     */
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    // ====================================================================
    // 全局日志系统（加回兼容层）
    // ====================================================================
    /**
     * 解析&播放日志（静态，全局可访问）
     * 【作用】记录播放器的解析、缓冲、播放等详细日志
     * 
     * 【2026-06-25 优化说明】
     * 原来每次 log() 都会 new 一个 StringBuilder，
     * 从 LogManager 全量复制内容，效率很低。
     * 
     * 优化后：直接追加到本地的 PLAY_LOG，
     * 同时也写到 LogManager，保持两套同步。
     * 避免了每次都全量复制的开销。
     */
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    /**
     * 操作日志（静态，全局可访问）
     * 【作用】记录用户的所有操作行为，方便排查问题
     * 
     * 【2026-06-25 优化说明】
     * 同上，优化了 logOperation() 方法，
     * 避免每次都 new StringBuilder。
     * 直接追加到本地的 OPERATION_LOG，
     * 同时也写到 LogManager，保持两套同步。
     */
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();
    /**
     * 记录播放日志（静态方法，全局可调用）
     * 
     * @param msg 日志内容
     * 
     * 【2026-06-25 优化】
     * 原来的实现：
     *   LogManager.log(msg);
     *   PLAY_LOG = new StringBuilder(LogManager.getPlayLog());
     * 
     * 问题：每次打日志都 new 一个 StringBuilder，
     * 而且 getPlayLog() 可能也是 new 的，
     * 全量复制内容，效率低，增加 GC 压力。
     * 
     * 优化后的实现：
     *   直接追加到本地的 PLAY_LOG，
     *   同时也写到 LogManager，保持两套同步。
     *   只追加，不全量复制，效率高很多。
     */
    public static void log(String msg) {
        // 先写到 LogManager（保持兼容）
        LogManager.log(msg);
        // 再追加到本地的 PLAY_LOG（直接追加，不全量复制）
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder();
        }
        PLAY_LOG.append(msg).append("\n");
    }
    /**
     * 记录操作日志（静态方法，全局可调用）
     * 
     * @param msg 日志内容
     * 
     * 【2026-06-25 优化】
     * 同上，优化了实现，避免每次都 new StringBuilder。
     * 直接追加到本地的 OPERATION_LOG，
     * 同时也写到 LogManager，保持两套同步。
     */
    public static void logOperation(String msg) {
        // 先写到 LogManager（保持兼容）
        LogManager.logOperation(msg);
        // 再追加到本地的 OPERATION_LOG（直接追加，不全量复制）
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuilder();
        }
        OPERATION_LOG.append(msg).append("\n");
    }
    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ====================================================================
        // ✅ 2026-06-25 优化：全面屏设置（加 try-catch 崩溃防护）
        // ====================================================================
        // 【为什么用旧 API（setSystemUiVisibility）？】
        // 虽然 Android 11+ 已废弃，但所有系统版本都能用，兼容性最好。
        // 新 API（WindowInsetsController）在某些厂商定制系统上可能有兼容性问题，
        // 导致进入设置页面直接崩溃。
        // 【防护措施】加 try-catch，即使全面屏设置失败也不能崩溃。
        try {
            applyFullScreen();
        } catch (Exception e) {
            // 兜底：全面屏设置失败也不能让页面崩溃
        }
        // ====================================================================
        // 刘海屏/挖孔屏适配
        // ====================================================================
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) {
            // 兜底：刘海屏适配失败也不能崩溃
        }
        // ====================================================================
        // 彻底清除背景变暗（三重保险）
        // ====================================================================
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) {
            // 兜底：清除背景变暗失败也不能崩溃
        }
        // ===== 窗口设置 =====
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        // ====================================================================
        // 点击左侧空白区域关闭设置
        // ====================================================================
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        // ===== 初始化 SharedPreferences =====
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        // ===== 绑定控件 =====
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        // ====================================================================
        // ✅ 绑定画中画开关控件（2026-06-22 新增）
        // ====================================================================
        sw_pip = findViewById(R.id.sw_pip);
        // ====================================================================
        // ✅ 绑定解码器选择 - 当前值显示（2026-06-25 新增）
        // ====================================================================
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        // 获取 ScrollView（用于滚动到可见区域）
        scrollView = findViewById(R.id.settings_content);
        // ====================================================================
        // 初始化所有管理器
        // ====================================================================
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);
        // ====================================================================
        // ✅ 新增：初始化设置项列表（遥控器焦点顺序）
        // ====================================================================
        initSettingsItemList();
        // ====================================================================
        // ✅ 新增：初始化遥控器管理器
        // ====================================================================
        initRemoteManager();
        // ===== 日志查看按钮 =====
        findViewById(R.id.log_viewer).setOnClickListener(v -> {
            showLogDialog();
        });
        findViewById(R.id.log_operation).setOnClickListener(v -> {
            showOperationLogDialog();
        });
        // ====================================================================
        // 开机自启（委托给 BootStartManager）
        // ====================================================================
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
        // ====================================================================
        // 2. 节目单开关
        // ====================================================================
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            logOperation("【设置】节目单" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // ====================================================================
        // 自动更新源（委托给 AutoUpdateManager）
        // ====================================================================
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
        // ====================================================================
        // 4. 换台反转
        // ====================================================================
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            logOperation("【设置】换台反转" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // ====================================================================
        // 5. 数字选台
        // ====================================================================
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            logOperation("【设置】数字选台" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // ====================================================================
        // ✅ 画中画开关（2026-06-22 新增）
        // ====================================================================
        /**
         * 【功能说明】
         * 开启后，用户按 Home 键退到后台时，自动进入画中画小窗播放；
         * 关闭后，退到后台不会进入小窗，正常暂停播放。
         *
         * 【存储 Key】pip_enable
         * 【默认值】false（默认关闭，用户手动开启）
         *
         * 【兼容性说明】
         * 仅 Android 8.0 (API 26) 及以上系统支持画中画功能，
         * 低版本系统即使开启开关也不会生效（PictureInPictureManager 会自动判断）。
         *
         * 【联动说明】
         * MainActivity 的 loadSettings() 方法会读取这个开关，
         * 同步到 PictureInPictureManager 中控制画中画行为。
         */
        // 从本地读取画中画开关状态（默认关闭）
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        // 画中画设置项点击事件
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            // 切换开关状态（点击整个项也能切换，不只是点开关）
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            // 保存到本地配置
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            // 记录操作日志
            logOperation("【设置】画中画（后台小窗播放）" + (isChecked ? "已开启" : "已关闭"));
            // Toast 提示用户
            if (isChecked) {
                Toast.makeText(this, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "画中画已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        // ====================================================================
        // ✅ 解码器选择（2026-06-25 新增，2026-06-26 修改：改用系统软解）
        // ====================================================================
        /**
         * 【功能说明】
         * 选择播放器使用的解码器模式：
         * - 自动（推荐）：硬解优先，卡顿自动切换到系统软解
         * - 硬解：强制使用系统硬解码器
         * - 软解（兼容性好）：优先使用系统软件解码器
         *
         * 【存储 Key】decoder_mode
         * 【默认值】auto（自动模式）
         * 【可选值】auto / hard / soft
         *
         * 【为什么需要手动切换？】
         * 有些频道硬解会卡，软解反而流畅；
         * 有些频道软解性能不够，硬解更好。
         * 给用户手动选择的权利，适配不同的源和设备。
         *
         * 【联动说明】
         * 切换后发送广播 "com.tv.live.DECODER_MODE_CHANGED"，
         * MainActivity 收到广播后，立即应用新的解码器模式，
         * 并重新加载当前频道。
         *
         * 【2026-06-26 修改说明】
         * 原来用的是 FFmpeg 软解，现在改用系统自带的软解。
         * 系统软解虽然性能不如 FFmpeg，但胜在稳定、无需额外依赖、集成简单。
         */
        // 从本地读取解码器模式（默认自动）
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        // 解码器设置项点击事件
        findViewById(R.id.item_decoder).setOnClickListener(v -> {
            showDecoderModeDialog();
            logOperation("【设置】打开解码器选择");
        });
        // ====================================================================
        // 检查更新（真正的版本检测 + 自动下载安装）
        // ====================================================================
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
        });
        // ===== 其他点击事件 =====
        initListeners();
        // ===== 启动网页后台 =====
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
        logOperation("【设置】打开设置页面");
    }
    // ====================================================================
    // ✅ 2026-06-25 优化：全面屏设置（旧 API + try-catch 防护）
    // ====================================================================
    /**
     * 应用全面屏设置（隐藏状态栏 + 导航栏）
     * 
     * 【为什么用旧 API（setSystemUiVisibility）？】
     * 虽然 Android 11+ 已废弃这个方法，但所有系统版本都能用，兼容性最好。
     * 新 API（WindowInsetsController）在某些厂商定制系统上可能有兼容性问题，
     * 导致进入页面直接崩溃。
     * 
     * 【崩溃防护】
     * 加 try-catch，即使全面屏设置失败也不能让页面崩溃。
     * 大不了就是状态栏/导航栏没隐藏，至少页面能正常打开。
     */
    private void applyFullScreen() {
        try {
            // 统一用旧 API，兼容性最好（所有 Android 版本都支持）
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        } catch (Exception e) {
            // 兜底：全面屏设置失败也不能崩溃
            // 记录日志，方便排查
            logOperation("【设置】全面屏适配失败：" + e.getMessage());
        }
    }
    // ====================================================================
    // ✅ 新增：初始化设置项列表（遥控器焦点顺序）
    // ====================================================================
    /**
     * 初始化设置项列表
     *
     * 【重要】按页面从上到下的顺序添加，遥控器上下键就按这个顺序移动
     *
     * 【为什么用列表？】
     * 新增/删除设置项只需要调整这个列表，
     * 不用修改任何按键处理逻辑，非常方便。
     *
     * 【怎么新增设置项？】
     * 1. 在布局里添加新的设置项
     * 2. 在 initViews 里 findViewById
     * 3. 在这个方法里 add 到列表里（按顺序）
     * 4. 搞定！遥控器自动支持
     *
     * 【2026-06-20 优化：加上焦点变化监听器，支持手机点击时光标跟随】
     * 【原来的问题】
     * 手机点击设置项时，光标（高亮）不会跟着移动，
     * 因为点击只触发了 OnClickListener，没有更新焦点位置。
     * 【优化方案】
     * 1. 给每个设置项设置 focusableInTouchMode=true（手机点击时也能获得焦点）
     * 2. 给每个设置项设置 OnFocusChangeListener（焦点变化时自动更新高亮）
     * 这样无论是遥控器操作还是手机点击，光标都会跟着移动。
     *
     * 【2026-06-22 修改：添加画中画设置项到焦点列表】
     * 【位置】放在"数字选台"之后，"屏幕比例"之前，符合播放相关设置的逻辑顺序
     * 
     * 【2026-06-25 修改：添加解码器选择设置项到焦点列表】
     * 【位置】放在"画中画"之后，"屏幕比例"之前，属于播放相关设置
     * 
     * 【2026-06-25 优化：精简 OnFocusChangeListener 里的日志】
     * 原来每次焦点变化都会输出一条日志，
     * 现在改成只在焦点位置真正变化时输出，避免日志过多。
     */
    private void initSettingsItemList() {
        settingsItemList.clear();
        // 按页面从上到下的顺序添加
        settingsItemList.add(findViewById(R.id.item_boot));           // 1. 开机自启
        settingsItemList.add(findViewById(R.id.item_epg));            // 2. 节目单开关
        settingsItemList.add(findViewById(R.id.item_auto_update));    // 3. 自动更新源
        settingsItemList.add(findViewById(R.id.item_reverse));        // 4. 换台反转
        settingsItemList.add(findViewById(R.id.item_num_channel));    // 5. 数字选台
        // ====================================================================
        // ✅ 画中画设置项（2026-06-22 新增，第6项）
        // ====================================================================
        // 【位置说明】放在数字选台之后，屏幕比例之前
        // 【原因】画中画属于播放相关设置，和数字选台、换台反转归为一类
        settingsItemList.add(findViewById(R.id.item_pip));            // 6. 画中画（后台小窗播放）
        // ====================================================================
        // ✅ 解码器选择设置项（2026-06-25 新增，第7项）
        // ====================================================================
        // 【位置说明】放在画中画之后，屏幕比例之前
        // 【原因】解码器属于播放相关设置，和画中画、数字选台归为一类
        settingsItemList.add(findViewById(R.id.item_decoder));        // 7. 解码器选择
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));     // 8. 屏幕比例
        settingsItemList.add(findViewById(R.id.tv_custom_source));    // 9. 自定义订阅源
        settingsItemList.add(findViewById(R.id.tv_custom_epg));       // 10. 自定义节目单
        settingsItemList.add(findViewById(R.id.tv_multi_source));     // 11. 多订阅源
        settingsItemList.add(findViewById(R.id.tv_multi_epg));        // 12. 多节目单
        settingsItemList.add(findViewById(R.id.tv_qr_code));          // 13. 扫码添加
        settingsItemList.add(findViewById(R.id.log_viewer));          // 14. 查看解析日志
        settingsItemList.add(findViewById(R.id.log_operation));       // 15. 操作日志
        settingsItemList.add(findViewById(R.id.item_check_update));   // 16. 检查更新
        // 移除 null 的项（防止有的 View 找不到）
        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
        }
        // ====================================================================
        // ✅ 2026-06-20 新增：给每个设置项设置焦点变化监听器
        // ====================================================================
        // 【作用】
        // 无论是遥控器操作还是手机点击，只要焦点变化了，高亮就会跟着更新。
        //
        // 【为什么要加 focusableInTouchMode？】
        // Android 默认情况下，触摸模式下（手机点击）View 不会获得焦点，
        // 只有遥控器/键盘操作时才会获得焦点。
        // 设置 focusableInTouchMode=true 后，手机点击也能获得焦点。
        //
        // 【为什么要加 OnFocusChangeListener？】
        // 焦点变化时自动更新高亮，不用在每个点击事件里都写一遍更新代码。
        //
        // 【注意】
        // 这里会和遥控器的 updateSettingsFocus() 重复调用，
        // 但是没关系，重复调用不会有问题，只是多输出一次日志而已。
        //
        // 【2026-06-25 优化】
        // 原来每次焦点变化都输出一条日志，太频繁了。
        // 现在改成只在焦点位置真正变化时才记录日志，
        // 而且只记录一条，不记录每条 item 的状态。
        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item != null) {
                // ✅ 支持触摸模式下获得焦点（手机点击时也能获得焦点）
                // 【为什么需要？】
                // Android 默认触摸模式下 View 不会获得焦点，
                // 只有遥控器/键盘操作时才会获得焦点。
                // 设置这个属性后，手机点击也能触发焦点变化。
                item.setFocusableInTouchMode(true);
                // ✅ 设置焦点变化监听器
                // 【作用】
                // 当 View 获得焦点时，更新遥控器管理器的焦点位置，
                // 并更新高亮显示，保持遥控器和实际焦点位置一致。
                //
                // 【2026-06-25 优化】
                // 增加判断：只有当焦点位置和当前记录的不一样时，
                // 才更新 remoteManager 和调用 updateSettingsFocus()，
                // 避免重复调用和重复日志。
                item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus && remoteManager != null) {
                            // 只有当焦点位置真正变化时，才更新（避免重复调用）
                            int currentPos = remoteManager.getSettingsFocusPosition();
                            if (currentPos != position) {
                                // 获得焦点时，更新遥控器管理器的焦点位置
                                // 保持 remoteManager 和实际焦点位置一致
                                remoteManager.setSettingsFocusPosition(position);
                                // 更新高亮显示
                                updateSettingsFocus();
                                // 记录操作日志（只记录一条，不记录每条 item 的状态）
                                logOperation("【设置】焦点移动到第 " + (position + 1) + " 项（点击）");
                            }
                        }
                    }
                });
            }
        }
    }
    // ====================================================================
    // ✅ 新增：初始化遥控器管理器
    // ====================================================================
    /**
     * 初始化遥控器管理器
     *
     * 【集成说明】
     * 1. 创建 TvRemoteManager 实例
     * 2. 设置为 SETTINGS_MODE（设置模式）
     * 3. 设置设置项总数
     * 4. 设置回调监听器，处理各种按键操作
     * 5. 默认聚焦第一项
     * 
     * 【2026-06-25 优化】
     * 1. onSettingsMoveUp/onSettingsMoveDown 增加操作日志
     * 2. onSettingsFocusChanged 里增加判断，避免和 onSettingsMoveUp/Down 重复调用
     *
     * 【2026-06-26 修改：新增 onPipBack() 回调实现】
     * 【修改说明】
     * TvRemoteManager.OnRemoteActionListener 接口新增了 onPipBack() 抽象方法，
     * SettingsActivity 中的匿名实现类需要覆盖这个方法，否则编译报错。
     * 设置页面不处理画中画返回键，返回 false 交给系统处理。
     * 
     * 【2026-06-26 修改：新增 onRequestPlayFocus() 回调实现】
     * 【修改说明】
     * TvRemoteManager.OnRemoteActionListener 接口新增了 onRequestPlayFocus() 抽象方法，
     * SettingsActivity 中的匿名实现类需要覆盖这个方法，否则编译报错。
     * 设置页面用不到请求播放焦点，空实现即可。
     */
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
        public boolean onPipBack() {
            return false;
        }

        @Override
        public void onRequestPlayFocus() {}

        // 补齐数字选台抽象方法，解决编译报错
        @Override
        public void onChannelNumberSelected(int channelIndex) {}
        @Override
        public void onShowChannelNumber(String number) {}
        @Override
        public void onHideChannelNumber() {}
    });
    updateSettingsFocus();
}
    // ====================== 其他点击事件初始化 ======================
    /**
     * 初始化纯文本项的点击事件
     * 【说明】没有开关，点击后弹出对话框的设置项
     */
    private void initListeners() {
        // 屏幕比例
        tv_screen_ratio.setOnClickListener(v -> {
            showRatioDialog();
            logOperation("【设置】打开屏幕比例设置");
        });
        // 自定义订阅源
        tv_custom_source.setOnClickListener(v -> {
            showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE);
            logOperation("【设置】打开自定义订阅源");
        });
        // 自定义节目单
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG);
            logOperation("【设置】打开自定义节目单");
        });
        // 多订阅源
        tv_multi_source.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });
        // 多节目单
        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });
        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
            logOperation("【设置】打开扫码管理");
        });
    }
    // ====================================================================
    // ✅ 新增：按键事件处理（直接调用 TvRemoteManager）
    // ====================================================================
    /**
     * 按键事件处理
     *
     * 【直接调用 TvRemoteManager】
     * 所有按键都交给 remoteManager.dispatchKeyEvent() 统一处理，
     * 不需要在这里写任何按键逻辑，全部在回调里处理。
     *
     * 【为什么这么设计？】
     * 1. 按键逻辑统一管理，不分散在 Activity 里
     * 2. 新增按键功能只需要改 TvRemoteManager，不用改 Activity
     * 3. 和 MainActivity、ChannelPanelController 用同一套体系
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 直接交给遥控器管理器处理
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    // ====================================================================
    // ✅ 2026-06-21 优化：统一三种状态样式，和列表完全一致
    // ====================================================================
    /**
     * 更新设置项焦点高亮显示
     *
     * 【2026-06-21 优化：从两种状态改成三种状态，和列表完全统一】
     *
     * 【原来的两种状态】
     * 1. 高亮状态：蓝色文字 + 浅蓝色背景
     * 2. 普通状态：白色文字 + 透明背景
     *
     * 【现在的三种状态】
     * 1. ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景（当前选中的设置项）
     * 2. ✅ 焦点状态：蓝色文字 + 常规 + 透明背景（遥控器焦点所在的项）
     * 3. ✅ 未选中状态：白色文字 + 常规 + 透明背景（普通项）
     *
     * 【为什么改成三种状态？】
     * 和频道分组、频道列表、日期列表、节目单列表保持一致的样式体系，
     * 整个应用的高亮样式统一，用户体验一致。
     *
     * 【判断优先级】
     * 选中状态 > 焦点状态 > 未选中状态
     * 如果一个项既是选中又是焦点，显示选中样式
     *
     * 【处理两种类型的设置项】
     * 1. TextView 类型：比如"屏幕比例"、"自定义订阅源"等
     * 2. ViewGroup 类型：比如"开机自启"、"检查更新"等（LinearLayout 包裹文字和开关）
     * 
     * 【2026-06-25 优化：精简日志】
     * 原来每次移动焦点，遍历15个设置项，每个项都输出一条日志，
     * 一次移动输出17+条日志，日志爆炸。
     * 
     * 现在改成：只输出一条日志（当前焦点位置），
     * 去掉遍历中的每条日志，大大减少日志量。
     */
    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        // 获取当前选中位置（遥控器管理器记录的位置）
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        // ✅ 2026-06-25 优化：只输出一条日志（精简日志量）
        // 原来每次移动焦点输出17+条，现在只输出1条
        logOperation("【设置遥控】焦点更新 → 第 " + (selectedPosition + 1) + " 项");
        // ====================================================================
        // 遍历所有设置项，分别设置对应的样式
        // ====================================================================
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == selectedPosition) {
                // ================================================================
                // ✅ 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                // ================================================================
                // 【说明】当前选中的设置项，最明显的样式
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
                // 请求焦点（让系统知道焦点在哪）
                item.requestFocus();
                // 滚动到可见区域
                scrollToView(item);
            } else if (item.isFocused()) {
                // ================================================================
                // ✅ 焦点状态：蓝色文字 + 常规 + 透明背景
                // ================================================================
                // 【说明】遥控器焦点所在的项，文字变蓝提示焦点位置
                // 背景透明，不会和选中状态冲突
                setItemStyle(item, "#40A9FF", Typeface.NORMAL, Color.TRANSPARENT);
            } else {
                // ================================================================
                // ✅ 未选中状态：白色文字 + 常规 + 透明背景
                // ================================================================
                // 【说明】普通项，默认样式
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }
    }
    // ====================================================================
    // ✅ 2026-06-21 新增：辅助方法 - 设置单个设置项的样式
    // ====================================================================
    /**
     * 设置单个设置项的样式（文字颜色 + 字重 + 背景色）
     *
     * 【作用】
     * 统一封装设置项样式的逻辑，避免在 updateSettingsFocus() 里重复写代码。
     *
     * 【处理两种类型的设置项】
     * 1. TextView 类型：直接设置文字颜色和字重
     * 2. ViewGroup 类型：找到第一个 TextView，设置文字颜色和字重
     *
     * @param item 设置项 View
     * @param textColor 文字颜色（十六进制字符串，如 "#40A9FF"）
     * @param typeface 字重（Typeface.BOLD 或 Typeface.NORMAL）
     * @param bgColor 背景色（如 0x3340A9FF 或 Color.TRANSPARENT）
     */
    private void setItemStyle(View item, String textColor, int typeface, int bgColor) {
        // 设置背景色
        item.setBackgroundColor(bgColor);
        // 设置文字颜色和字重
        if (item instanceof TextView) {
            // 情况 A：当前项就是 TextView（简单项，比如"屏幕比例"）
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typeface);
        } else if (item instanceof ViewGroup) {
            // 情况 B：当前项是 ViewGroup（复杂项，比如"开机自启"，里面有文字和开关）
            // 找第一个 TextView，设置文字颜色和字重
            TextView tv = findFirstTextView((ViewGroup) item);
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor));
                tv.setTypeface(null, typeface);
            }
        }
    }
        // ====================================================================
    // ✅ 2026-06-20 新增：辅助方法 - 在 ViewGroup 中找到第一个 TextView
    // ====================================================================
    /**
     * 在 ViewGroup 中递归查找第一个 TextView
     *
     * 【作用】
     * 对于复杂的设置项（比如开机自启，LinearLayout 里有文字和开关），
     * 找到里面的标题 TextView，用来设置文字颜色。
     *
     * 【为什么用递归？】
     * 因为有的布局可能嵌套多层（比如 LinearLayout 里又套了一个 LinearLayout），
     * 递归查找能确保找到第一个 TextView。
     *
     * @param viewGroup 要查找的 ViewGroup
     * @return 找到的第一个 TextView，如果没找到返回 null
     */
    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;
        // 遍历所有子 View
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                // 找到了，直接返回
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                // 子 View 也是 ViewGroup，递归查找
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        // 没找到
        return null;
    }
    // ====================================================================
    // 辅助方法：滚动到指定 View 可见
    // ====================================================================
    /**
     * 滚动到指定 View，让它显示在可见区域内
     *
     * 【作用】
     * 当焦点移动到屏幕外的项时，自动滚动，让用户能看到焦点在哪里。
     *
     * 【滚动规则】
     * - 如果 View 在可见区域上方：滚动到顶部（留 50dp 边距）
     * - 如果 View 在可见区域下方：滚动到底部（留 50dp 边距）
     * - 如果 View 已经在可见区域内：不滚动
     *
     * @param view 要滚动到的 View
     */
    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;
        // 计算 View 在 ScrollView 中的位置
        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();
        // 如果 View 在当前可见区域上方，滚动到顶部
        if (viewTop < scrollView.getScrollY()) {
            scrollView.smoothScrollTo(0, viewTop - 50);
        }
        // 如果 View 在当前可见区域下方，滚动到底部
        else if (viewBottom > scrollView.getScrollY() + scrollViewHeight) {
            scrollView.smoothScrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }
    // ====================================================================
    // ✅ 新增：辅助方法 - 处理设置项点击
    // ====================================================================
    /**
     * 处理设置项点击/选中
     *
     * @param position 选中项的位置索引
     *
     * 【说明】
     * 模拟点击事件，触发该 View 的 OnClickListener，
     * 这样就不用重复写一遍点击逻辑了。
     */
    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item == null) return;
        // 模拟点击（触发 OnClickListener）
        item.performClick();
        logOperation("【设置遥控】选中第 " + (position + 1) + " 项");
    }
    // ====================== 屏幕比例对话框 ======================
    /**
     * 显示屏幕比例选择对话框
     * 【选项】全屏、填充、原始
     */
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
    // ====================================================================
    // ✅ 解码器选择对话框（2026-06-25 新增，2026-06-26 修改：改用系统软解）
    // ====================================================================
    /**
     * 显示解码器选择对话框
     * 【选项】自动（推荐）、硬解、软解（兼容性好）
     * 
     * 【功能说明】
     * - 自动（推荐）：硬解优先，卡顿自动切换到系统软解
     * - 硬解：强制使用系统硬解码器，性能好但兼容性一般
     * - 软解（兼容性好）：优先使用系统软件解码器，兼容性好
     * 
     * 【联动说明】
     * 选择后：
     * 1. 保存到 SharedPreferences
     * 2. 更新显示文字
     * 3. 记录操作日志
     * 4. 发送广播，通知 MainActivity 立即应用新的解码器
     * 5. Toast 提示用户
     * 
     * 【2026-06-26 修改说明】
     * 原来用的是 FFmpeg 软解，现在改用系统自带的软解。
     * 把选项文字从"软解（FFmpeg）"改成"软解（兼容性好）"。
     */
    private void showDecoderModeDialog() {
        // ✅ 2026-06-26 修改：把"软解（FFmpeg）"改成"软解（兼容性好）"
        final String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        final String[] modeValues = {"auto", "hard", "soft"};
        // 找到当前选中的位置
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modeValues.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("解码器选择")
                .setSingleChoiceItems(modes, checkedItem, (d, which) -> {
                    // 保存选择
                    String selectedMode = modeValues[which];
                    sp.edit().putString("decoder_mode", selectedMode).apply();
                    // 更新显示文字
                    updateDecoderModeText(selectedMode);
                    // 记录操作日志
                    logOperation("【设置】解码器选择：" + modes[which]);
                    // 发送广播，通知 MainActivity 切换解码器
                    // 【为什么用广播？】
                    // SettingsActivity 和 MainActivity 是两个独立的 Activity，
                    // 用广播可以解耦，不需要互相持有引用。
                    // MainActivity 收到广播后，立即应用新的解码器模式，
                    // 并重新加载当前频道。
                    sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
                    // 关闭对话框
                    d.dismiss();
                    // Toast 提示用户
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在重新加载…",
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }
    /**
     * 更新解码器模式显示文字
     * 
     * @param mode 解码器模式（auto/hard/soft）
     */
    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard":
                tv_decoder_mode.setText("硬解");
                break;
            case "soft":
                tv_decoder_mode.setText("软解");
                break;
            case "auto":
            default:
                tv_decoder_mode.setText("自动");
                break;
        }
    }
    // ====================== 输入对话框（自定义源/节目单） ======================
    /**
     * 显示输入对话框
     * 【用途】自定义订阅源、自定义节目单
     *
     * @param title 对话框标题
     * @param hint 输入框提示文字
     * @param key  存储的 SharedPreferences Key
     */
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
    // ====================================================================
    // 日志对话框（加回兼容层）
    // ====================================================================
    /**
     * 显示操作日志对话框
     * 【内容】记录用户的所有操作行为
     * 【特点】最新的日志显示在最上面（倒序）
     * 
     * 【2026-06-25 优化：统一清空日志顺序】
     * 原来先清空本地 OPERATION_LOG，再清 LogManager。
     * 现在改成先清 LogManager，再清本地缓存，
     * 保持和 logOperation() 方法一致的顺序（先写 LogManager 再写本地）。
     */
    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。");
        } else {
            String originalLog = OPERATION_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
                        // 倒序排列（最新的在最上面）
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
            // ✅ 2026-06-25 优化：先清 LogManager，再清本地缓存
            // 保持和 logOperation() 一致的顺序
            LogManager.clearOperationLog();
            if (OPERATION_LOG != null) {
                OPERATION_LOG.setLength(0);
            }
            logOperation("【设置】操作日志已清空");
            Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }
    /**
 * 显示解析&播放日志对话框
 * 【内容】记录播放器的解析、缓冲、播放等详细日志
 * 【特点】最新的日志显示在最上面（倒序）
 * 【新增】自动分析直播源卡顿日志，卡顿关键字标红并汇总卡顿原因
 * 【2026-06-25 优化：统一清空日志顺序】
 * 同上，先清 LogManager，再清本地缓存。
 */
private void showLogDialog() {
    ScrollView scrollView = new ScrollView(this);
    TextView tv = new TextView(this);
    if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
        tv.setText("暂无日志内容，请先播放一个频道再查看。");
    } else {
        String originalLog = PLAY_LOG.toString();
        String[] lines = originalLog.split("\n");
        StringBuilder reversedLog = new StringBuilder();
        // 卡顿原因汇总
        StringBuilder lagSummary = new StringBuilder();
        lagSummary.append("========== 卡顿原因分析汇总 ==========\n");
        boolean existLagLog = false;

        // 倒序遍历日志，筛选卡顿相关日志
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // 卡顿、缓冲、解码、网络相关关键词
            boolean isLagLine = line.contains("缓冲") || line.contains("卡顿")
                    || line.contains("超时") || line.contains("解码失败")
                    || line.contains("帧率下降") || line.contains("网络延迟")
                    || line.contains("丢包") || line.contains("buffer underflow")
                    || line.contains("frame drop") || line.contains("硬解切换");

            if (isLagLine) {
                existLagLog = true;
                lagSummary.append(line).append("\n");
            }
            reversedLog.append(line).append("\n");
        }

        // 拼接汇总+完整日志
        StringBuilder fullContent = new StringBuilder();
        if (existLagLog) {
            fullContent.append(lagSummary).append("\n========== 完整播放日志 ==========\n");
        } else {
            fullContent.append("========== 卡顿原因分析汇总 ==========\n未检测到卡顿相关日志\n\n========== 完整播放日志 ==========\n");
        }
        fullContent.append(reversedLog);

        // 关键字标红高亮
        SpannableString spLog = new SpannableString(fullContent.toString());
        String[] lagKeys = {"缓冲", "卡顿", "超时", "解码失败", "帧率下降", "网络延迟", "丢包"};
        for (String key : lagKeys) {
            int pos = fullContent.indexOf(key);
            while (pos != -1) {
                spLog.setSpan(new ForegroundColorSpan(Color.RED), pos, pos + key.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                pos = fullContent.indexOf(key, pos + key.length());
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
        // ✅ 2026-06-25 优化：先清 LogManager，再清本地缓存
        LogManager.clearPlayLog();
        if (PLAY_LOG != null) {
            PLAY_LOG.setLength(0);
        }
        logOperation("【设置】解析日志已清空");
        Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
    });
    builder.show();
}
    // ====================================================================
    // 窗口焦点变化时，重新隐藏状态栏
    // ====================================================================
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                // 重新设置全面屏（隐藏状态栏 + 导航栏）
                applyFullScreen();
                // 清除背景变暗
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
                layoutParams.dimAmount = 0f;
                getWindow().setAttributes(layoutParams);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            } catch (Exception e) {
                // 兜底：窗口焦点变化时的设置失败也不能崩溃
            }
        }
    }
    // ====================== onDestroy 生命周期 ======================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        // 停止网页后台
        if (webServerManager != null) {
            webServerManager.stop();
        }
        // 释放更新管理器
        if (updateManager != null) {
            updateManager.release();
        }
        // 释放遥控器管理器
        remoteManager = null;
        settingsItemList.clear();
        settingsItemList = null;
    }
}
       
