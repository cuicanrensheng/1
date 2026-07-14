package com.tv.live.manager;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.live.Channel;
import com.tv.live.ChannelPanelController;
import com.tv.live.MainActivity;
import com.tv.live.SettingsActivity;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.List;

/**
 * 遥控器按键处理器 - 统一处理所有面板按键
 * 
 * 支持两种模式：
 * 1. 频道界面控制面板模式 (CHANNEL_PANEL)
 * 2. 设置面板模式 (SETTINGS_PANEL)
 * 
 * 完全独立于触摸事件，只处理遥控器按键逻辑
 */
public class RemoteKeyHandler {

    // ===================== 模式定义 =====================
    public enum Mode {
        CHANNEL_PANEL,   // 频道界面控制面板
        SETTINGS_PANEL   // 设置面板
    }

    // ===================== 引用 =====================
    private MainActivity mainActivity;
    private ChannelPanelController controller;
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private SettingsActivity settingsActivity;
    
    private View llLeftPanel;
    private View llRightPanel;
    private TextView btnShowEpg;
    private TextView btnBackGroup;
    
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;
    private ListView lvDate;
    private ListView lvEpg;
    
    // 设置面板的滚动视图
    private ScrollView scrollView;
    private int settingsItemCount = 0;

    // ===================== 状态 =====================
    private Mode currentMode = Mode.CHANNEL_PANEL;
    
    /** 频道面板当前所在区域：left / right */
    private String currentPanel = "left";
    
    /** 频道面板当前焦点视图类型 */
    private String currentFocusView = "";
    
    /** 回看模式标记 */
    private boolean isCatchUpMode = false;
    
    /** 设置面板焦点位置 */
    private int settingsFocusPosition = 0;

    /** 长按OK计时器 */
    private long okPressStartTime = 0;
    private boolean isOkLongPress = false;
    private static final long OK_LONG_PRESS_DURATION = 3000; // 3秒

    /** 上下文 */
    private Context context;

    // ===================== 构造函数 =====================
    /**
     * 频道面板模式构造函数
     */
    public RemoteKeyHandler(
            MainActivity mainActivity,
            ChannelPanelController controller,
            GroupListManager groupListManager,
            ChannelListManager channelListManager,
            ChannelListManager channelListManagerEpg,
            DateListManager dateListManager,
            EpgManagerWrapper epgManagerWrapper,
            View llLeftPanel,
            View llRightPanel,
            TextView btnShowEpg,
            TextView btnBackGroup,
            ListView lvGroup,
            ListView lvChannelList,
            ListView lvChannelListEpg,
            ListView lvDate,
            ListView lvEpg,
            Context context) {
        this.mainActivity = mainActivity;
        this.controller = controller;
        this.groupListManager = groupListManager;
        this.channelListManager = channelListManager;
        this.channelListManagerEpg = channelListManagerEpg;
        this.dateListManager = dateListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        this.llLeftPanel = llLeftPanel;
        this.llRightPanel = llRightPanel;
        this.btnShowEpg = btnShowEpg;
        this.btnBackGroup = btnBackGroup;
        this.lvGroup = lvGroup;
        this.lvChannelList = lvChannelList;
        this.lvChannelListEpg = lvChannelListEpg;
        this.lvDate = lvDate;
        this.lvEpg = lvEpg;
        this.settingsActivity = null;
        this.context = context;
        this.currentMode = Mode.CHANNEL_PANEL;
    }

    /**
     * 设置面板模式构造函数
     */
    public RemoteKeyHandler(
            SettingsActivity settingsActivity,
            ScrollView scrollView,
            int settingsItemCount,
            Context context) {
        this.mainActivity = null;
        this.controller = null;
        this.groupListManager = null;
        this.channelListManager = null;
        this.channelListManagerEpg = null;
        this.dateListManager = null;
        this.epgManagerWrapper = null;
        this.llLeftPanel = null;
        this.llRightPanel = null;
        this.btnShowEpg = null;
        this.btnBackGroup = null;
        this.lvGroup = null;
        this.lvChannelList = null;
        this.lvChannelListEpg = null;
        this.lvDate = null;
        this.lvEpg = null;
        this.settingsActivity = settingsActivity;
        this.scrollView = scrollView;
        this.settingsItemCount = settingsItemCount;
        this.context = context;
        this.currentMode = Mode.SETTINGS_PANEL;
    }

    // ===================== 核心分发方法 =====================
    /**
     * 处理所有遥控器按键
     * @param keyCode 按键码
     * @param event 按键事件
     * @return true 表示按键已处理，false 表示未处理
     */
    public boolean dispatchKeyEvent(int keyCode, KeyEvent event) {
        // 长按OK检测
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (okPressStartTime == 0) {
                    okPressStartTime = System.currentTimeMillis();
                    isOkLongPress = false;
                }
                // 检查是否达到长按时间
                if (System.currentTimeMillis() - okPressStartTime >= OK_LONG_PRESS_DURATION) {
                    if (!isOkLongPress) {
                        isOkLongPress = true;
                        return handleOkLongPress();
                    }
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                // 如果是短按（未触发长按），处理普通OK键
                if (!isOkLongPress) {
                    boolean handled = handleOk(getCurrentFocusView());
                    okPressStartTime = 0;
                    return handled;
                }
                okPressStartTime = 0;
                isOkLongPress = false;
                return true;
            }
        }

        // 根据当前模式分发按键
        switch (currentMode) {
            case CHANNEL_PANEL:
                return dispatchChannelPanelKey(keyCode);
            case SETTINGS_PANEL:
                return dispatchSettingsPanelKey(keyCode);
            default:
                return false;
        }
    }

    // =========================================================================
    // 频道面板按键处理
    // =========================================================================

    private boolean dispatchChannelPanelKey(int keyCode) {
        // 如果面板未显示，不处理任何按键
        if (controller == null || !controller.isPanelOpen()) {
            return false;
        }

        // 获取当前焦点所在的视图
        View currentFocus = getCurrentFocusView();
        if (currentFocus == null) {
            setDefaultFocus();
            return true;
        }

        // 根据按键类型分发处理
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return handleChannelUp(currentFocus);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return handleChannelDown(currentFocus);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return handleChannelLeft(currentFocus);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handleChannelRight(currentFocus);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // OK键已在dispatchKeyEvent中处理，这里不再重复
                return true;
            case KeyEvent.KEYCODE_BACK:
                return handleChannelBack();
            default:
                return false;
        }
    }

    // 频道面板 - 上键
    private boolean handleChannelUp(View currentFocus) {
        // 回看模式下按上键：退出回看并切台（上键切上一个频道）
        if (isCatchUpMode) {
            exitCatchUpAndSwitchChannel(true);
            return true;
        }

        if (currentFocus instanceof ListView) {
            ListView lv = (ListView) currentFocus;
            int pos = lv.getSelectedItemPosition();
            if (pos == -1) pos = lv.getFirstVisiblePosition();
            int count = lv.getCount();

            if (pos > 0) {
                lv.setSelection(pos - 1);
                notifySelectionChanged(lv, pos - 1);
                return true;
            } else if (count > 0) {
                // 到顶部循环到底部
                lv.setSelection(count - 1);
                notifySelectionChanged(lv, count - 1);
                return true;
            }
        }
        return false;
    }

    // 频道面板 - 下键
    private boolean handleChannelDown(View currentFocus) {
        // 回看模式下按下键：退出回看并切台（下键切下一个频道）
        if (isCatchUpMode) {
            exitCatchUpAndSwitchChannel(false);
            return true;
        }

        if (currentFocus instanceof ListView) {
            ListView lv = (ListView) currentFocus;
            int pos = lv.getSelectedItemPosition();
            if (pos == -1) pos = lv.getFirstVisiblePosition();
            int count = lv.getCount();

            if (pos < count - 1) {
                lv.setSelection(pos + 1);
                notifySelectionChanged(lv, pos + 1);
                return true;
            } else if (count > 0) {
                // 到底部循环到顶部
                lv.setSelection(0);
                notifySelectionChanged(lv, 0);
                return true;
            }
        }
        return false;
    }

    // 频道面板 - 左键
    private boolean handleChannelLeft(View currentFocus) {
        // 右侧面板按左键：返回左侧面板
        if ("right".equals(currentPanel)) {
            switchToLeftPanel();
            return true;
        }

        // 左侧面板焦点切换
        if ("left".equals(currentPanel)) {
            if (currentFocus == lvChannelList) {
                // 频道列表 → 分组
                if (lvGroup != null) {
                    lvGroup.requestFocus();
                }
                currentFocusView = "group";
                updateFocusStyle();
                return true;
            } else if (currentFocus == btnShowEpg) {
                // 节目单按钮 → 频道列表
                if (lvChannelList != null) {
                    lvChannelList.requestFocus();
                }
                currentFocusView = "channel";
                updateFocusStyle();
                return true;
            }
        }
        return false;
    }

    // 频道面板 - 右键
    private boolean handleChannelRight(View currentFocus) {
        // 左侧面板焦点切换
        if ("left".equals(currentPanel)) {
            if (currentFocus == lvGroup) {
                // 分组 → 频道列表
                if (lvChannelList != null) {
                    lvChannelList.requestFocus();
                }
                currentFocusView = "channel";
                updateFocusStyle();
                return true;
            } else if (currentFocus == lvChannelList) {
                // 频道列表 → 节目单按钮
                if (btnShowEpg != null) {
                    btnShowEpg.requestFocus();
                }
                currentFocusView = "epgBtn";
                updateFocusStyle();
                return true;
            } else if (currentFocus == btnShowEpg) {
                // 节目单按钮 → 进入右侧面板
                switchToRightPanel();
                return true;
            }
        }

        // 右侧面板焦点切换
        if ("right".equals(currentPanel)) {
            if (currentFocus == lvGroup) {
                // 分组 → 频道组按钮
                if (btnBackGroup != null) {
                    btnBackGroup.requestFocus();
                }
                currentFocusView = "backBtn";
                updateFocusStyle();
                return true;
            } else if (currentFocus == lvDate) {
                // 日期 → 分组
                if (lvGroup != null) {
                    lvGroup.requestFocus();
                }
                currentFocusView = "group";
                updateFocusStyle();
                return true;
            }
        }
        return false;
    }

    // 频道面板 - OK键
    private boolean handleChannelOk(View currentFocus) {
        // 回看模式下的OK：预约或取消预约
        if (isCatchUpMode) {
            handleCatchUpOk(currentFocus);
            return true;
        }

        // 正常模式
        if (currentFocus == lvGroup) {
            // 分组OK：确定选中分组
            int pos = lvGroup.getSelectedItemPosition();
            if (pos >= 0) {
                controller.onGroupClicked(pos);
                return true;
            }
        } else if (currentFocus == lvChannelList) {
            // 频道列表OK：切换频道
            int pos = lvChannelList.getSelectedItemPosition();
            if (pos >= 0) {
                controller.onChannelClicked(pos);
                return true;
            }
        } else if (currentFocus == lvChannelListEpg) {
            // 右侧频道列表OK：切换频道
            int pos = lvChannelListEpg.getSelectedItemPosition();
            if (pos >= 0) {
                controller.onChannelClicked(pos);
                return true;
            }
        } else if (currentFocus == btnShowEpg) {
            // 节目单按钮OK：进入右侧面板
            switchToRightPanel();
            return true;
        } else if (currentFocus == btnBackGroup) {
            // 频道组按钮OK：返回左侧面板
            switchToLeftPanel();
            return true;
        } else if (currentFocus == lvDate) {
            // 日期OK：选择日期，刷新EPG
            int pos = lvDate.getSelectedItemPosition();
            if (pos >= 0) {
                controller.setCurrentDateIndex(pos);
                refreshEpgForDate(pos);
                return true;
            }
        } else if (currentFocus == lvEpg) {
            // 节目单内容OK：进入回看或预约
            handleEpgOk();
            return true;
        }
        return false;
    }

    // 频道面板 - 返回键
    private boolean handleChannelBack() {
        if ("right".equals(currentPanel)) {
            // 右侧面板返回左侧
            switchToLeftPanel();
            return true;
        } else if ("left".equals(currentPanel)) {
            // 左侧面板返回播放界面
            controller.hidePanel();
            return true;
        }
        return false;
    }

    // =========================================================================
    // 设置面板按键处理
    // =========================================================================

    private boolean dispatchSettingsPanelKey(int keyCode) {
        if (settingsActivity == null) return false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return handleSettingsUp();
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return handleSettingsDown();
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return handleSettingsLeft();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handleSettingsRight();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return handleSettingsOk();
            case KeyEvent.KEYCODE_BACK:
                return handleSettingsBack();
            default:
                return false;
        }
    }

    // 设置面板 - 上键
    private boolean handleSettingsUp() {
        if (settingsFocusPosition > 0) {
            settingsFocusPosition--;
            updateSettingsFocus();
            return true;
        } else if (settingsItemCount > 0) {
            // 到顶部循环到底部
            settingsFocusPosition = settingsItemCount - 1;
            updateSettingsFocus();
            return true;
        }
        return false;
    }

    // 设置面板 - 下键
    private boolean handleSettingsDown() {
        if (settingsFocusPosition < settingsItemCount - 1) {
            settingsFocusPosition++;
            updateSettingsFocus();
            return true;
        } else if (settingsItemCount > 0) {
            // 到底部循环到顶部
            settingsFocusPosition = 0;
            updateSettingsFocus();
            return true;
        }
        return false;
    }

    // 设置面板 - 左键
    private boolean handleSettingsLeft() {
        return false; // 设置面板暂不支持左键
    }

    // 设置面板 - 右键
    private boolean handleSettingsRight() {
        return false; // 设置面板暂不支持右键
    }

    // 设置面板 - OK键
    private boolean handleSettingsOk() {
        if (settingsActivity != null) {
            settingsActivity.handleSettingsItemClick(settingsFocusPosition);
            return true;
        }
        return false;
    }

    // 设置面板 - 返回键
    private boolean handleSettingsBack() {
        if (settingsActivity != null) {
            settingsActivity.finish();
            return true;
        }
        return false;
    }

    // =========================================================================
    // 长按OK处理
    // =========================================================================

    private boolean handleOkLongPress() {
        // 在播放界面长按OK键3秒进入设置
        if (mainActivity != null && !mainActivity.isPanelOpen()) {
            mainActivity.openSettings();
            return true;
        }
        return false;
    }

    // =========================================================================
    // 普通OK键处理（从dispatchKeyEvent调用）
    // =========================================================================

    private boolean handleOk(View currentFocus) {
        switch (currentMode) {
            case CHANNEL_PANEL:
                return handleChannelOk(currentFocus);
            case SETTINGS_PANEL:
                return handleSettingsOk();
            default:
                return false;
        }
    }

    // =========================================================================
    // 面板切换方法
    // =========================================================================

    /**
     * 切换到左侧面板
     */
    private void switchToLeftPanel() {
        if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
        if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
        currentPanel = "left";
        // 默认焦点给左侧频道列表
        if (lvChannelList != null) {
            lvChannelList.requestFocus();
        }
        currentFocusView = "channel";
        updateFocusStyle();
        // 通知控制器面板切换
        if (controller != null) {
            controller.onPanelSwitched("left");
        }
        Toast.makeText(context, "切换到左侧面板", Toast.LENGTH_SHORT).show();
    }

    /**
     * 切换到右侧面板
     */
    private void switchToRightPanel() {
        if (llLeftPanel != null) llLeftPanel.setVisibility(View.GONE);
        if (llRightPanel != null) llRightPanel.setVisibility(View.VISIBLE);
        currentPanel = "right";
        // 默认焦点给右侧频道列表
        if (lvChannelListEpg != null) {
            lvChannelListEpg.requestFocus();
        }
        currentFocusView = "channel";
        updateFocusStyle();
        // 通知控制器面板切换
        if (controller != null) {
            controller.onPanelSwitched("right");
        }
        // 刷新EPG数据
        if (controller != null) {
            refreshEpgForDate(controller.getCurrentSelectedDateIndex());
        }
        Toast.makeText(context, "切换到右侧面板", Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // 焦点与样式管理
    // =========================================================================

    /**
     * 获取当前获得焦点的视图
     */
    private View getCurrentFocusView() {
        if (lvGroup != null && lvGroup.hasFocus()) return lvGroup;
        if (lvChannelList != null && lvChannelList.hasFocus()) return lvChannelList;
        if (lvChannelListEpg != null && lvChannelListEpg.hasFocus()) return lvChannelListEpg;
        if (lvDate != null && lvDate.hasFocus()) return lvDate;
        if (lvEpg != null && lvEpg.hasFocus()) return lvEpg;
        if (btnShowEpg != null && btnShowEpg.hasFocus()) return btnShowEpg;
        if (btnBackGroup != null && btnBackGroup.hasFocus()) return btnBackGroup;
        return null;
    }

    /**
     * 设置默认焦点
     */
    private void setDefaultFocus() {
        if ("left".equals(currentPanel) && lvChannelList != null) {
            lvChannelList.requestFocus();
            currentFocusView = "channel";
        } else if (lvChannelListEpg != null) {
            lvChannelListEpg.requestFocus();
            currentFocusView = "channel";
        }
        updateFocusStyle();
    }

    /**
     * 更新频道面板焦点样式
     */
    private void updateFocusStyle() {
        if (controller != null) {
            controller.syncFocusStyle();
        }
    }

    /**
     * 更新设置面板焦点
     */
    private void updateSettingsFocus() {
        if (settingsActivity != null) {
            settingsActivity.updateSettingsFocus();
        }
    }

    // =========================================================================
    // 列表选中项变化回调
    // =========================================================================

    /**
     * 通知各管理器列表选中项发生变化
     */
    private void notifySelectionChanged(ListView lv, int newPos) {
        if (lv == lvGroup && groupListManager != null) {
            groupListManager.setSelectedPosition(newPos);
        } else if (lv == lvChannelList && channelListManager != null) {
            channelListManager.setSelectedPosition(newPos);
        } else if (lv == lvChannelListEpg && channelListManagerEpg != null) {
            channelListManagerEpg.setSelectedPosition(newPos);
        } else if (lv == lvDate && dateListManager != null) {
            dateListManager.setSelectedPosition(newPos);
        } else if (lv == lvEpg && epgManagerWrapper != null) {
            epgManagerWrapper.setSelectedPosition(newPos);
        }
    }

    // =========================================================================
    // 回看与预约相关
    // =========================================================================

    /**
     * 处理节目单OK事件
     */
    private void handleEpgOk() {
        if (lvEpg == null || epgManagerWrapper == null) return;

        int pos = lvEpg.getSelectedItemPosition();
        if (pos < 0) {
            Toast.makeText(context, "请先选择一个节目", Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取当前选中的节目
        Object program = epgManagerWrapper.getProgramAt(pos);
        if (program == null) {
            Toast.makeText(context, "无法获取节目信息", Toast.LENGTH_SHORT).show();
            return;
        }

        // 判断是否可回看
        if (epgManagerWrapper.isCatchUpAvailable(program)) {
            // 进入回看模式
            isCatchUpMode = true;
            if (controller != null) {
                controller.setCatchUpMode(true);
            }
            epgManagerWrapper.startCatchUp(program);
            Toast.makeText(context, "进入回看模式，按上下键退出回看", Toast.LENGTH_SHORT).show();
        } else if (epgManagerWrapper.isReservable(program)) {
            // 预约操作
            boolean reserved = epgManagerWrapper.toggleReservation(program);
            Toast.makeText(context, reserved ? "已预约" : "已取消预约", Toast.LENGTH_SHORT).show();
            // 刷新显示
            epgManagerWrapper.notifyDataSetChanged();
        } else {
            Toast.makeText(context, "该节目不支持回看或预约", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 处理回看模式下的OK键
     */
    private void handleCatchUpOk(View currentFocus) {
        if (currentFocus == lvEpg && epgManagerWrapper != null) {
            int pos = lvEpg.getSelectedItemPosition();
            if (pos >= 0) {
                Object program = epgManagerWrapper.getProgramAt(pos);
                if (program != null && epgManagerWrapper.isReservable(program)) {
                    boolean reserved = epgManagerWrapper.toggleReservation(program);
                    Toast.makeText(context, reserved ? "已预约" : "已取消预约", Toast.LENGTH_SHORT).show();
                    epgManagerWrapper.notifyDataSetChanged();
                }
            }
        }
    }

    /**
     * 退出回看模式并切台
     * @param isUp true=上键切台，false=下键切台
     */
    private void exitCatchUpAndSwitchChannel(boolean isUp) {
        // 退出回看模式
        isCatchUpMode = false;
        if (controller != null) {
            controller.setCatchUpMode(false);
        }
        if (epgManagerWrapper != null) {
            epgManagerWrapper.stopCatchUp();
        }

        // 执行切台操作
        if (controller != null) {
            if (isUp) {
                controller.switchUp();
            } else {
                controller.switchDown();
            }
        }

        Toast.makeText(context, "已退出回看模式", Toast.LENGTH_SHORT).show();
    }

    /**
     * 根据日期刷新EPG
     */
    private void refreshEpgForDate(int dateIndex) {
        if (controller == null || epgManagerWrapper == null) return;

        // 获取当前播放的频道
        List<Channel> currentGroupChannels = controller.getCurrentGroupChannels();
        int currentPlayIndex = controller.getCurrentPlayIndex();
        if (currentPlayIndex >= 0 && currentPlayIndex < currentGroupChannels.size()) {
            Channel currentChannel = currentGroupChannels.get(currentPlayIndex);
            if (currentChannel != null) {
                // 刷新EPG
                List<Channel> channelSourceList = controller.getChannelSourceList();
                epgManagerWrapper.refresh(currentChannel, channelSourceList, dateIndex);
            }
        }
    }

    // =========================================================================
    // 外部控制方法
    // =========================================================================

    /**
     * 设置当前模式
     */
    public void setMode(Mode mode) {
        this.currentMode = mode;
    }

    /**
     * 获取当前模式
     */
    public Mode getCurrentMode() {
        return currentMode;
    }

    /**
     * 频道面板打开时调用
     */
    public void onChannelPanelOpened() {
        currentPanel = "left";
        setDefaultFocus();
        // 重置回看状态
        isCatchUpMode = false;
        if (controller != null) {
            controller.setCatchUpMode(false);
        }
        Toast.makeText(context, "按左键或OK键进入频道面板", Toast.LENGTH_SHORT).show();
    }

    /**
     * 频道面板关闭时调用
     */
    public void onChannelPanelClosed() {
        // 清除所有焦点
        if (lvGroup != null) lvGroup.clearFocus();
        if (lvChannelList != null) lvChannelList.clearFocus();
        if (lvChannelListEpg != null) lvChannelListEpg.clearFocus();
        if (lvDate != null) lvDate.clearFocus();
        if (lvEpg != null) lvEpg.clearFocus();
        if (btnShowEpg != null) btnShowEpg.clearFocus();
        if (btnBackGroup != null) btnBackGroup.clearFocus();

        // 重置回看模式
        if (isCatchUpMode) {
            isCatchUpMode = false;
            if (controller != null) {
                controller.setCatchUpMode(false);
            }
            if (epgManagerWrapper != null) {
                epgManagerWrapper.stopCatchUp();
            }
        }

        Toast.makeText(context, "已关闭频道面板", Toast.LENGTH_SHORT).show();
    }

    /**
     * 设置面板打开时调用
     */
    public void onSettingsPanelOpened() {
        settingsFocusPosition = 0;
        updateSettingsFocus();
        Toast.makeText(context, "进入设置面板，按返回键退出", Toast.LENGTH_SHORT).show();
    }

    /**
     * 设置面板关闭时调用
     */
    public void onSettingsPanelClosed() {
        if (scrollView != null) {
            scrollView.clearFocus();
        }
        Toast.makeText(context, "已退出设置面板", Toast.LENGTH_SHORT).show();
    }

    /**
     * 获取频道面板当前区域
     */
    public String getChannelPanel() {
        return currentPanel;
    }

    /**
     * 获取频道面板当前焦点视图类型
     */
    public String getChannelFocusView() {
        return currentFocusView;
    }

    /**
     * 判断是否在回看模式
     */
    public boolean isCatchUpMode() {
        return isCatchUpMode;
    }

    /**
     * 设置设置面板的选项总数
     */
    public void setSettingsItemCount(int count) {
        this.settingsItemCount = count;
        if (settingsFocusPosition >= count) {
            settingsFocusPosition = count - 1;
        }
        if (settingsFocusPosition < 0) {
            settingsFocusPosition = 0;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        mainActivity = null;
        controller = null;
        groupListManager = null;
        channelListManager = null;
        channelListManagerEpg = null;
        dateListManager = null;
        epgManagerWrapper = null;
        settingsActivity = null;
        llLeftPanel = null;
        llRightPanel = null;
        btnShowEpg = null;
        btnBackGroup = null;
        lvGroup = null;
        lvChannelList = null;
        lvChannelListEpg = null;
        lvDate = null;
        lvEpg = null;
        scrollView = null;
        context = null;
    }
}
