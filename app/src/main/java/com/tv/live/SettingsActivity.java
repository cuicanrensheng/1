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
 * 【2026-06-26 修改：新增 onPipBack() 回调实现】
 * 【修改说明】
 * TvRemoteManager.OnRemoteActionListener 接口新增了 onPipBack() 抽象方法，
 * SettingsActivity 中的匿名实现类需要覆盖这个方法，否则编译报错。
 * 设置页面不处理画中画返回键，返回 false 交给系统处理。
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
                            int currentFocusPos = remoteManager.getCurrentFocusPosition();
                            if (currentFocusPos != position) {
                                remoteManager.setCurrentFocusPosition(position);
                                updateSettingsFocus(position);
                                logOperation("【设置】焦点移动到：" + getSettingsItemName(position));
                            }
                        }
                    }
                });
            }
        }
    }
    
    /**
     * 初始化遥控器管理器
     */
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager(TvRemoteManager.MODE_SETTINGS, this);
        remoteManager.setFocusableViewList(settingsItemList);
        remoteManager.setScrollView(scrollView);
        
        // 设置遥控器按键监听
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override
            public boolean onKeyUp() {
                logOperation("【设置】遥控器上键，焦点上移");
                return remoteManager.moveFocusUp();
            }
            
            @Override
            public boolean onKeyDown() {
                logOperation("【设置】遥控器下键，焦点下移");
                return remoteManager.moveFocusDown();
            }
            
            @Override
            public boolean onKeyOk() {
                View currentItem = remoteManager.getCurrentFocusView();
                if (currentItem != null) {
                    logOperation("【设置】遥控器确认键，点击项：" + getSettingsItemName(remoteManager.getCurrentFocusPosition()));
                    currentItem.performClick();
                    return true;
                }
                return false;
            }
            
            @Override
            public boolean onKeyBack() {
                logOperation("【设置】遥控器返回键，关闭设置页面");
                finish();
                return true;
            }
            
            @Override
            public boolean onKeyMenu() {
                logOperation("【设置】遥控器菜单键，关闭设置页面");
                finish();
                return true;
            }
            
            @Override
            public boolean onPipBack() {
                // 设置页面不处理画中画返回键，交给系统
                return false;
            }
            
            @Override
            public void onFocusChanged(int newPosition) {
                updateSettingsFocus(newPosition);
            }
        });
    }
    
    /**
     * 更新设置项焦点高亮显示
     * @param focusPosition 焦点位置
     */
    private void updateSettingsFocus(int focusPosition) {
        // 精简日志：只输出一条焦点变化日志
        logOperation("【设置】更新焦点位置：" + focusPosition + "（" + getSettingsItemName(focusPosition) + "）");
        
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            
            TextView textView = null;
            if (item instanceof TextView) {
                textView = (TextView) item;
            } else {
                // 如果是布局容器，找里面的 TextView
                textView = item.findViewById(R.id.tv_item_text);
            }
            
            if (textView == null) continue;
            
            // 判断状态优先级：选中 > 焦点 > 未选中
            boolean isSelected = isItemSelected(item);
            boolean isFocused = (i == focusPosition);
            
            if (isSelected) {
                // 选中状态：蓝色文字 + 加粗 + 浅蓝色背景
                textView.setTextColor(Color.parseColor("#1E90FF"));
                textView.setTypeface(Typeface.DEFAULT_BOLD);
                item.setBackgroundColor(Color.parseColor("#331E90FF"));
            } else if (isFocused) {
                // 焦点状态：蓝色文字 + 常规 + 透明背景
                textView.setTextColor(Color.parseColor("#1E90FF"));
                textView.setTypeface(Typeface.DEFAULT);
                item.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // 未选中状态：白色文字 + 常规 + 透明背景
                textView.setTextColor(Color.WHITE);
                textView.setTypeface(Typeface.DEFAULT);
                item.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }
    
    /**
     * 判断设置项是否为选中状态
     * @param item 设置项View
     * @return 是否选中
     */
    private boolean isItemSelected(View item) {
        // 根据不同设置项类型判断选中状态
        if (item.getId() == R.id.item_boot) {
            return sw_boot.isChecked();
        } else if (item.getId() == R.id.item_epg) {
            return sw_epg.isChecked();
        } else if (item.getId() == R.id.item_auto_update) {
            return sw_auto_update.isChecked();
        } else if (item.getId() == R.id.item_reverse) {
            return sw_reverse.isChecked();
        } else if (item.getId() == R.id.item_num_channel) {
            return sw_num_channel.isChecked();
        } else if (item.getId() == R.id.item_pip) {
            return sw_pip.isChecked();
        }
        // 非开关项默认未选中
        return false;
    }
    
    /**
     * 获取设置项名称（用于日志）
     * @param position 位置
     * @return 名称
     */
    private String getSettingsItemName(int position) {
        if (position < 0 || position >= settingsItemList.size()) {
            return "未知项";
        }
        View item = settingsItemList.get(position);
        if (item == null) return "空项";
        
        switch (item.getId()) {
            case R.id.item_boot: return "开机自启";
            case R.id.item_epg: return "节目单开关";
            case R.id.item_auto_update: return "自动更新源";
            case R.id.item_reverse: return "换台反转";
            case R.id.item_num_channel: return "数字选台";
            case R.id.item_pip: return "画中画";
            case R.id.item_decoder: return "解码器选择";
            case R.id.tv_screen_ratio: return "屏幕比例";
            case R.id.tv_custom_source: return "自定义订阅源";
            case R.id.tv_custom_epg: return "自定义节目单";
            case R.id.tv_multi_source: return "多订阅源";
            case R.id.tv_multi_epg: return "多节目单";
            case R.id.tv_qr_code: return "扫码添加";
            case R.id.log_viewer: return "查看解析日志";
            case R.id.log_operation: return "操作日志";
            case R.id.item_check_update: return "检查更新";
            default: return "设置项" + position;
        }
    }
    
    /**
     * 更新解码器模式显示文本
     * @param mode 解码器模式（auto/hard/soft）
     */
    private void updateDecoderModeText(String mode) {
        String text;
        switch (mode) {
            case "hard":
                text = "硬解";
                break;
            case "soft":
                text = "软解（兼容性好）";
                break;
            case "auto":
            default:
                text = "自动（推荐）";
                break;
        }
        tv_decoder_mode.setText(text);
    }
    
    /**
     * 显示解码器选择对话框
     */
    private void showDecoderModeDialog() {
        String[] items = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        switch (currentMode) {
            case "hard":
                checkedItem = 1;
                break;
            case "soft":
                checkedItem = 2;
                break;
            case "auto":
            default:
                checkedItem = 0;
                break;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择解码器模式");
        builder.setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
            String newMode;
            switch (which) {
                case 1:
                    newMode = "hard";
                    break;
                case 2:
                    newMode = "soft";
                    break;
                case 0:
                default:
                    newMode = "auto";
                    break;
            }
            
            // 保存新的解码器模式
            sp.edit().putString("decoder_mode", newMode).apply();
            // 更新显示
            updateDecoderModeText(newMode);
            // 记录日志
            logOperation("【设置】解码器模式切换为：" + newMode + "（" + items[which] + "）");
            // 发送广播通知MainActivity更新解码器
            sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
            
            dialog.dismiss();
            Toast.makeText(this, "解码器已切换为：" + items[which], Toast.LENGTH_SHORT).show();
        });
        
        builder.setNegativeButton("取消", null);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        dialog.show();
        
        // 设置对话框文字颜色
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setTextColor(Color.WHITE);
        }
        for (int i = 0; i < items.length; i++) {
            TextView textView = (TextView) dialog.getListView().getChildAt(i);
            if (textView != null) {
                textView.setTextColor(Color.WHITE);
            }
        }
    }
    
    /**
     * 显示播放日志对话框
     */
    private void showLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("解析&播放日志");
        
        EditText editText = new EditText(this);
        editText.setTextColor(Color.WHITE);
        editText.setBackgroundColor(Color.BLACK);
        editText.setText(PLAY_LOG.toString());
        editText.setKeyListener(null); // 不可编辑
        editText.setPadding(20, 20, 20, 20);
        
        builder.setView(editText);
        builder.setPositiveButton("清空", (dialog, which) -> {
            PLAY_LOG.setLength(0);
            LogManager.clearPlayLog();
            editText.setText("");
            logOperation("【设置】清空解析&播放日志");
        });
        builder.setNegativeButton("关闭", null);
        
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        dialog.show();
    }
    
    /**
     * 显示操作日志对话框
     */
    private void showOperationLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("操作日志");
        
        EditText editText = new EditText(this);
        editText.setTextColor(Color.WHITE);
        editText.setBackgroundColor(Color.BLACK);
        editText.setText(OPERATION_LOG.toString());
        editText.setKeyListener(null); // 不可编辑
        editText.setPadding(20, 20, 20, 20);
        
        builder.setView(editText);
        builder.setPositiveButton("清空", (dialog, which) -> {
            OPERATION_LOG.setLength(0);
            LogManager.clearOperationLog();
            editText.setText("");
            logOperation("【设置】清空操作日志");
        });
        builder.setNegativeButton("关闭", null);
        
        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        dialog.show();
    }
    
    /**
     * 初始化其他点击事件
     */
    private void initListeners() {
        // 屏幕比例
        tv_screen_ratio.setOnClickListener(v -> {
            logOperation("【设置】打开屏幕比例选择");
            // 屏幕比例选择逻辑（省略，根据实际业务实现）
            Toast.makeText(this, "屏幕比例设置（待实现）", Toast.LENGTH_SHORT).show();
        });
        
        // 自定义订阅源
        tv_custom_source.setOnClickListener(v -> {
            logOperation("【设置】打开自定义订阅源");
            sourceDialogManager.showCustomSourceDialog(KEY_CUSTOM_LIVE);
        });
        
        // 自定义节目单
        tv_custom_epg.setOnClickListener(v -> {
            logOperation("【设置】打开自定义节目单");
            sourceDialogManager.showCustomSourceDialog(KEY_CUSTOM_EPG);
        });
        
        // 多订阅源
        tv_multi_source.setOnClickListener(v -> {
            logOperation("【设置】打开多订阅源管理");
            sourceDialogManager.showMultiSourceDialog(KEY_CUSTOM_LIVE);
        });
        
        // 多节目单
        tv_multi_epg.setOnClickListener(v -> {
            logOperation("【设置】打开多节目单管理");
            sourceDialogManager.showMultiSourceDialog(KEY_CUSTOM_EPG);
        });
        
        // 扫码添加
        tv_qr_code.setOnClickListener(v -> {
            logOperation("【设置】打开扫码添加");
            qrCodeManager.showQRCodeDialog(currentWebUrl);
        });
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 交给遥控器管理器处理按键
        if (remoteManager != null && remoteManager.handleKeyDown(keyCode, event)) {
            return true;
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
        // 取消自动更新闹钟（如果需要）
        if (autoUpdateManager != null && !sp.getBoolean("auto_update_source", true)) {
            autoUpdateManager.cancelAutoUpdateAlarm();
        }
        logOperation("【设置】关闭设置页面");
    }
}
