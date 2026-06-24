package com.tv.live.manager;

import android.view.KeyEvent;

/**
 * 频道面板光标管理器
 * 
 * 【功能】
 * 1. 统一管理左右面板的焦点位置
 * 2. 处理上下左右键的光标移动
 * 3. 边界处理（到顶/到底/到左/到右）
 * 4. 支持循环模式和停止模式
 * 5. 光标移动回调（用于更新UI、播放音效等）
 * 
 * 【设计思路】
 * 不依赖 Android 原生的 focus 机制，自己管理选中位置。
 * 这样完全可控，不会出现焦点乱跑的问题。
 * 
 * 【左面板焦点区域】（从左到右）
 * 1. GROUP：分组列表
 * 2. CHANNEL：频道列表
 * 3. EPG_BTN：EPG按钮
 * 
 * 【右面板焦点区域】（从左到右）
 * 1. BACK_BTN：返回按钮
 * 2. CHANNEL：频道列表
 * 3. DATE：日期列表
 * 4. EPG：EPG节目单列表
 * 
 * 【使用方法】
 * 1. 创建 PanelCursorManager 实例
 * 2. 设置各个列表的数据数量
 * 3. 设置 OnCursorChangeListener 监听，更新UI
 * 4. 在 dispatchKeyEvent 中调用 handleKeyEvent()
 * 
 * 【2026-06-24 新增】
 * 全新的光标管理器，替代原生 focus 机制，更稳定可控。
 */
public class PanelCursorManager {

    // ====================================================================
    // 枚举：面板类型
    // ====================================================================
    public enum PanelType {
        LEFT,   // 左面板
        RIGHT   // 右面板
    }

    // ====================================================================
    // 枚举：左面板焦点区域
    // ====================================================================
    public enum LeftFocusView {
        GROUP,      // 分组列表
        CHANNEL,    // 频道列表
        EPG_BTN     // EPG按钮
    }

    // ====================================================================
    // 枚举：右面板焦点区域
    // ====================================================================
    public enum RightFocusView {
        BACK_BTN,   // 返回按钮
        CHANNEL,    // 频道列表
        DATE,       // 日期列表
        EPG         // EPG节目单列表
    }

    // ====================================================================
    // 成员变量
    // ====================================================================
    private PanelType currentPanel = PanelType.LEFT;              // 当前面板
    private LeftFocusView leftFocusView = LeftFocusView.CHANNEL;  // 左面板焦点
    private RightFocusView rightFocusView = RightFocusView.CHANNEL; // 右面板焦点

    private int groupSelectedPosition = 0;        // 分组列表选中位置
    private int leftChannelSelectedPosition = 0;  // 左面板频道列表选中位置
    private int rightChannelSelectedPosition = 0; // 右面板频道列表选中位置
    private int dateSelectedPosition = 0;         // 日期列表选中位置
    private int epgSelectedPosition = 0;          // EPG列表选中位置

    private int groupCount = 0;           // 分组数量
    private int leftChannelCount = 0;     // 左面板频道数量
    private int rightChannelCount = 0;    // 右面板频道数量
    private int dateCount = 0;            // 日期数量
    private int epgCount = 0;             // EPG数量

    private boolean enableCycle = true;   // 是否启用循环模式（到顶后到底）

    private OnCursorChangeListener listener;

    // ====================================================================
    // 接口：光标变化监听
    // ====================================================================
    public interface OnCursorChangeListener {
        /**
         * 面板切换（左面板 ↔ 右面板）
         * @param from 原来的面板
         * @param to 新的面板
         */
        void onPanelChanged(PanelType from, PanelType to);

        /**
         * 左面板焦点区域切换
         * @param from 原来的焦点区域
         * @param to 新的焦点区域
         */
        void onLeftFocusViewChanged(LeftFocusView from, LeftFocusView to);

        /**
         * 右面板焦点区域切换
         * @param from 原来的焦点区域
         * @param to 新的焦点区域
         */
        void onRightFocusViewChanged(RightFocusView from, RightFocusView to);

        /**
         * 分组列表选中位置变化
         * @param position 新的选中位置
         * @param isSmooth 是否平滑滚动
         */
        void onGroupSelectionChanged(int position, boolean isSmooth);

        /**
         * 左面板频道列表选中位置变化
         * @param position 新的选中位置
         * @param isSmooth 是否平滑滚动
         */
        void onLeftChannelSelectionChanged(int position, boolean isSmooth);

        /**
         * 右面板频道列表选中位置变化
         * @param position 新的选中位置
         * @param isSmooth 是否平滑滚动
         */
        void onRightChannelSelectionChanged(int position, boolean isSmooth);

        /**
         * 日期列表选中位置变化
         * @param position 新的选中位置
         * @param isSmooth 是否平滑滚动
         */
        void onDateSelectionChanged(int position, boolean isSmooth);

        /**
         * EPG列表选中位置变化
         * @param position 新的选中位置
         * @param isSmooth 是否平滑滚动
         */
        void onEpgSelectionChanged(int position, boolean isSmooth);

        /**
         * 确认键按下
         */
        void onConfirm();
    }

    // ====================================================================
    // 构造方法
    // ====================================================================
    public PanelCursorManager() {
    }

    // ====================================================================
    // 设置数据数量
    // ====================================================================

    /**
     * 设置分组数量
     */
    public void setGroupCount(int count) {
        this.groupCount = count;
        // 确保选中位置在有效范围内
        if (groupSelectedPosition >= count) {
            groupSelectedPosition = Math.max(0, count - 1);
        }
    }

    /**
     * 设置左面板频道数量
     */
    public void setLeftChannelCount(int count) {
        this.leftChannelCount = count;
        if (leftChannelSelectedPosition >= count) {
            leftChannelSelectedPosition = Math.max(0, count - 1);
        }
    }

    /**
     * 设置右面板频道数量
     */
    public void setRightChannelCount(int count) {
        this.rightChannelCount = count;
        if (rightChannelSelectedPosition >= count) {
            rightChannelSelectedPosition = Math.max(0, count - 1);
        }
    }

    /**
     * 设置日期数量
     */
    public void setDateCount(int count) {
        this.dateCount = count;
        if (dateSelectedPosition >= count) {
            dateSelectedPosition = Math.max(0, count - 1);
        }
    }

    /**
     * 设置EPG数量
     */
    public void setEpgCount(int count) {
        this.epgCount = count;
        if (epgSelectedPosition >= count) {
            epgSelectedPosition = Math.max(0, count - 1);
        }
    }

    // ====================================================================
    // 设置是否启用循环模式
    // ====================================================================

    /**
     * 设置是否启用循环模式
     * @param enable true=循环（到顶后跳到最后），false=停止（到顶就不动了）
     */
    public void setEnableCycle(boolean enable) {
        this.enableCycle = enable;
    }

    // ====================================================================
    // 设置监听
    // ====================================================================

    /**
     * 设置光标变化监听器
     */
    public void setOnCursorChangeListener(OnCursorChangeListener listener) {
        this.listener = listener;
    }

    // ====================================================================
    // 获取当前状态
    // ====================================================================

    public PanelType getCurrentPanel() {
        return currentPanel;
    }

    public LeftFocusView getLeftFocusView() {
        return leftFocusView;
    }

    public RightFocusView getRightFocusView() {
        return rightFocusView;
    }

    public int getGroupSelectedPosition() {
        return groupSelectedPosition;
    }

    public int getLeftChannelSelectedPosition() {
        return leftChannelSelectedPosition;
    }

    public int getRightChannelSelectedPosition() {
        return rightChannelSelectedPosition;
    }

    public int getDateSelectedPosition() {
        return dateSelectedPosition;
    }

    public int getEpgSelectedPosition() {
        return epgSelectedPosition;
    }

    // ====================================================================
    // 设置当前面板
    // ====================================================================

    /**
     * 设置当前面板（默认通知）
     */
    public void setCurrentPanel(PanelType panel) {
        setCurrentPanel(panel, true);
    }

    /**
     * 设置当前面板
     * @param panel 面板类型
     * @param notify 是否通知监听器
     */
    public void setCurrentPanel(PanelType panel, boolean notify) {
        if (currentPanel == panel) return;
        
        PanelType oldPanel = currentPanel;
        currentPanel = panel;
        
        if (notify && listener != null) {
            listener.onPanelChanged(oldPanel, panel);
        }
    }

    // ====================================================================
    // 设置左面板焦点区域
    // ====================================================================

    public void setLeftFocusView(LeftFocusView view) {
        setLeftFocusView(view, true);
    }

    public void setLeftFocusView(LeftFocusView view, boolean notify) {
        if (currentPanel != PanelType.LEFT) return;
        if (leftFocusView == view) return;
        
        LeftFocusView oldView = leftFocusView;
        leftFocusView = view;
        
        if (notify && listener != null) {
            listener.onLeftFocusViewChanged(oldView, view);
        }
    }

    // ====================================================================
    // 设置右面板焦点区域
    // ====================================================================

    public void setRightFocusView(RightFocusView view) {
        setRightFocusView(view, true);
    }

    public void setRightFocusView(RightFocusView view, boolean notify) {
        if (currentPanel != PanelType.RIGHT) return;
        if (rightFocusView == view) return;
        
        RightFocusView oldView = rightFocusView;
        rightFocusView = view;
        
        if (notify && listener != null) {
            listener.onRightFocusViewChanged(oldView, view);
        }
    }

    // ====================================================================
    // 设置选中位置
    // ====================================================================

    public void setGroupSelectedPosition(int position) {
        setGroupSelectedPosition(position, true);
    }

    public void setGroupSelectedPosition(int position, boolean notify) {
        if (position < 0 || position >= groupCount) return;
        if (groupSelectedPosition == position) return;
        
        groupSelectedPosition = position;
        
        if (notify && listener != null) {
            listener.onGroupSelectionChanged(position, true);
        }
    }

    public void setLeftChannelSelectedPosition(int position) {
        setLeftChannelSelectedPosition(position, true);
    }

    public void setLeftChannelSelectedPosition(int position, boolean notify) {
        if (position < 0 || position >= leftChannelCount) return;
        if (leftChannelSelectedPosition == position) return;
        
        leftChannelSelectedPosition = position;
        
        if (notify && listener != null) {
            listener.onLeftChannelSelectionChanged(position, true);
        }
    }

    public void setRightChannelSelectedPosition(int position) {
        setRightChannelSelectedPosition(position, true);
    }

    public void setRightChannelSelectedPosition(int position, boolean notify) {
        if (position < 0 || position >= rightChannelCount) return;
        if (rightChannelSelectedPosition == position) return;
        
        rightChannelSelectedPosition = position;
        
        if (notify && listener != null) {
            listener.onRightChannelSelectionChanged(position, true);
        }
    }

    public void setDateSelectedPosition(int position) {
        setDateSelectedPosition(position, true);
    }

    public void setDateSelectedPosition(int position, boolean notify) {
        if (position < 0 || position >= dateCount) return;
        if (dateSelectedPosition == position) return;
        
        dateSelectedPosition = position;
        
        if (notify && listener != null) {
            listener.onDateSelectionChanged(position, true);
        }
    }

    public void setEpgSelectedPosition(int position) {
        setEpgSelectedPosition(position, true);
    }

    public void setEpgSelectedPosition(int position, boolean notify) {
        if (position < 0 || position >= epgCount) return;
        if (epgSelectedPosition == position) return;
        
        epgSelectedPosition = position;
        
        if (notify && listener != null) {
            listener.onEpgSelectionChanged(position, true);
        }
    }

    // ====================================================================
    // 处理按键事件（统一入口）
    // ====================================================================

    /**
     * 处理遥控器按键事件
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    public boolean handleKeyEvent(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return moveUp();
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return moveDown();
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveLeft();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveRight();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return handleConfirm();
            default:
                return false;
        }
    }

    // ====================================================================
    // 向上移动
    // ====================================================================

    private boolean moveUp() {
        if (currentPanel == PanelType.LEFT) {
            return moveUpLeftPanel();
        } else {
            return moveUpRightPanel();
        }
    }

    // 左面板向上
    private boolean moveUpLeftPanel() {
        switch (leftFocusView) {
            case GROUP:
                return moveGroupUp();
            case CHANNEL:
                return moveLeftChannelUp();
            case EPG_BTN:
                // EPG按钮是一个按钮，上下键不处理
                return false;
            default:
                return false;
        }
    }

    // 右面板向上
    private boolean moveUpRightPanel() {
        switch (rightFocusView) {
            case BACK_BTN:
                // 返回按钮是一个按钮，上下键不处理
                return false;
            case CHANNEL:
                return moveRightChannelUp();
            case DATE:
                return moveDateUp();
            case EPG:
                return moveEpgUp();
            default:
                return false;
        }
    }

    // 分组列表向上
    private boolean moveGroupUp() {
        if (groupCount <= 0) return false;
        
        int newPosition = groupSelectedPosition - 1;
        if (newPosition < 0) {
            if (enableCycle) {
                // 循环模式：到顶后跳到最后
                newPosition = groupCount - 1;
            } else {
                // 停止模式：到顶就不动了
                return false;
            }
        }
        
        groupSelectedPosition = newPosition;
        if (listener != null) {
            listener.onGroupSelectionChanged(newPosition, true);
        }
        return true;
    }

    // 左面板频道列表向上
    private boolean moveLeftChannelUp() {
        if (leftChannelCount <= 0) return false;
        
        int newPosition = leftChannelSelectedPosition - 1;
        if (newPosition < 0) {
            if (enableCycle) {
                newPosition = leftChannelCount - 1;
            } else {
                return false;
            }
        }
        
        leftChannelSelectedPosition = newPosition;
        if (listener != null) {
            listener.onLeftChannelSelectionChanged(newPosition, true);
        }
        return true;
    }

    // 右面板频道列表向上
    private boolean moveRightChannelUp() {
        if (rightChannelCount <= 0) return false;
        
        int newPosition = rightChannelSelectedPosition - 1;
        if (newPosition < 0) {
            if (enableCycle) {
                newPosition = rightChannelCount - 1;
            } else {
                return false;
            }
        }
        
        rightChannelSelectedPosition = newPosition;
        if (listener != null) {
            listener.onRightChannelSelectionChanged(newPosition, true);
        }
        return true;
    }

    // 日期列表向上
    private boolean moveDateUp() {
        if (dateCount <= 0) return false;
        
        int newPosition = dateSelectedPosition - 1;
        if (newPosition < 0) {
            if (enableCycle) {
                newPosition = dateCount - 1;
            } else {
                return false;
            }
        }
        
        dateSelectedPosition = newPosition;
        if (listener != null) {
            listener.onDateSelectionChanged(newPosition, true);
        }
        return true;
    }

    // EPG列表向上
    private boolean moveEpgUp() {
        if (epgCount <= 0) return false;
        
        int newPosition = epgSelectedPosition - 1;
        if (newPosition < 0) {
            if (enableCycle) {
                newPosition = epgCount - 1;
            } else {
                return false;
            }
        }
        
        epgSelectedPosition = newPosition;
        if (listener != null) {
            listener.onEpgSelectionChanged(newPosition, true);
        }
        return true;
    }

    // ====================================================================
    // 向下移动
    // ====================================================================

    private boolean moveDown() {
        if (currentPanel == PanelType.LEFT) {
            return moveDownLeftPanel();
        } else {
            return moveDownRightPanel();
        }
    }

    // 左面板向下
    private boolean moveDownLeftPanel() {
        switch (leftFocusView) {
            case GROUP:
                return moveGroupDown();
            case CHANNEL:
                return moveLeftChannelDown();
            case EPG_BTN:
                return false;
            default:
                return false;
        }
    }

    // 右面板向下
    private boolean moveDownRightPanel() {
        switch (rightFocusView) {
            case BACK_BTN:
                return false;
            case CHANNEL:
                return moveRightChannelDown();
            case DATE:
                return moveDateDown();
            case EPG:
                return moveEpgDown();
            default:
                return false;
        }
    }

    // 分组列表向下
    private boolean moveGroupDown() {
        if (groupCount <= 0) return false;
        
        int newPosition = groupSelectedPosition + 1;
        if (newPosition >= groupCount) {
            if (enableCycle) {
                // 循环模式：到底后跳到最前
                newPosition = 0;
            } else {
                // 停止模式：到底就不动了
                return false;
            }
        }
        
        groupSelectedPosition = newPosition;
        if (listener != null) {
            listener.onGroupSelectionChanged(newPosition, true);
        }
        return true;
    }

    // 左面板频道列表向下
    private boolean moveLeftChannelDown() {
        if (leftChannelCount <= 0) return false;
        
        int newPosition = leftChannelSelectedPosition + 1;
        if (newPosition >= leftChannelCount) {
            if (enableCycle) {
                newPosition = 0;
            } else {
                return false;
            }
        }
        
        leftChannelSelectedPosition = newPosition;
        if (listener != null) {
            listener.onLeftChannelSelectionChanged(newPosition, true);
        }
        return true;
    }

    // 右面板频道列表向下
    private boolean moveRightChannelDown() {
        if (rightChannelCount <= 0) return false;
        
        int newPosition = rightChannelSelectedPosition + 1;
        if (newPosition >= rightChannelCount) {
            if (enableCycle) {
                newPosition = 0;
            } else {
                return false;
            }
        }
        
        rightChannelSelectedPosition = newPosition;
        if (listener != null) {
            listener.onRightChannelSelectionChanged(newPosition, true);
        }
        return true;
    }

    // 日期列表向下
    private boolean moveDateDown() {
        if (dateCount <= 0) return false;
        
        int newPosition = dateSelectedPosition + 1;
        if (newPosition >= dateCount) {
            if (enableCycle) {
                newPosition = 0;
            } else {
                return false;
            }
        }
        
        dateSelectedPosition = newPosition;
        if (listener != null) {
            listener.onDateSelectionChanged(newPosition, true);
        }
        return true;
    }

    // EPG列表向下
    private boolean moveEpgDown() {
        if (epgCount <= 0) return false;
        
        int newPosition = epgSelectedPosition + 1;
        if (newPosition >= epgCount) {
            if (enableCycle) {
                newPosition = 0;
            } else {
                return false;
            }
        }
        
        epgSelectedPosition = newPosition;
        if (listener != null) {
            listener.onEpgSelectionChanged(newPosition, true);
        }
        return true;
    }

    // ====================================================================
    // 向左移动（切换焦点区域）
    // ====================================================================

    private boolean moveLeft() {
        if (currentPanel == PanelType.LEFT) {
            return moveLeftInLeftPanel();
        } else {
            return moveLeftInRightPanel();
        }
    }

    // 左面板内向左（EPG_BTN → CHANNEL → GROUP）
    private boolean moveLeftInLeftPanel() {
        LeftFocusView newView;
        
        switch (leftFocusView) {
            case EPG_BTN:
                newView = LeftFocusView.CHANNEL;
                break;
            case CHANNEL:
                newView = LeftFocusView.GROUP;
                break;
            case GROUP:
                // 已经在最左边了
                return false;
            default:
                return false;
        }
        
        LeftFocusView oldView = leftFocusView;
        leftFocusView = newView;
        
        if (listener != null) {
            listener.onLeftFocusViewChanged(oldView, newView);
        }
        return true;
    }

    // 右面板内向左（EPG → DATE → CHANNEL → BACK_BTN）
    private boolean moveLeftInRightPanel() {
        RightFocusView newView;
        
        switch (rightFocusView) {
            case EPG:
                newView = RightFocusView.DATE;
                break;
            case DATE:
                newView = RightFocusView.CHANNEL;
                break;
            case CHANNEL:
                newView = RightFocusView.BACK_BTN;
                break;
            case BACK_BTN:
                // 已经在最左边了
                return false;
            default:
                return false;
        }
        
        RightFocusView oldView = rightFocusView;
        rightFocusView = newView;
        
        if (listener != null) {
            listener.onRightFocusViewChanged(oldView, newView);
        }
        return true;
    }

    // ====================================================================
    // 向右移动（切换焦点区域）
    // ====================================================================

    private boolean moveRight() {
        if (currentPanel == PanelType.LEFT) {
            return moveRightInLeftPanel();
        } else {
            return moveRightInRightPanel();
        }
    }

    // 左面板内向右（GROUP → CHANNEL → EPG_BTN）
    private boolean moveRightInLeftPanel() {
        LeftFocusView newView;
        
        switch (leftFocusView) {
            case GROUP:
                newView = LeftFocusView.CHANNEL;
                break;
            case CHANNEL:
                newView = LeftFocusView.EPG_BTN;
                break;
            case EPG_BTN:
                // 已经在最右边了
                return false;
            default:
                return false;
        }
        
        LeftFocusView oldView = leftFocusView;
        leftFocusView = newView;
        
        if (listener != null) {
            listener.onLeftFocusViewChanged(oldView, newView);
        }
        return true;
    }

    // 右面板内向右（BACK_BTN → CHANNEL → DATE → EPG）
    private boolean moveRightInRightPanel() {
        RightFocusView newView;
        
        switch (rightFocusView) {
            case BACK_BTN:
                newView = RightFocusView.CHANNEL;
                break;
            case CHANNEL:
                newView = RightFocusView.DATE;
                break;
            case DATE:
                newView = RightFocusView.EPG;
                break;
            case EPG:
                // 已经在最右边了
                return false;
            default:
                return false;
        }
        
        RightFocusView oldView = rightFocusView;
        rightFocusView = newView;
        
        if (listener != null) {
            listener.onRightFocusViewChanged(oldView, newView);
        }
        return true;
    }

    // ====================================================================
    // 确认键
    // ====================================================================

    private boolean handleConfirm() {
        if (listener != null) {
            listener.onConfirm();
            return true;
        }
        return false;
    }

    // ====================================================================
    // 重置状态
    // ====================================================================

    /**
     * 重置所有状态（打开面板时调用）
     */
    public void reset() {
        currentPanel = PanelType.LEFT;
        leftFocusView = LeftFocusView.CHANNEL;
        rightFocusView = RightFocusView.CHANNEL;
        groupSelectedPosition = 0;
        leftChannelSelectedPosition = 0;
        rightChannelSelectedPosition = 0;
        dateSelectedPosition = 0;
        epgSelectedPosition = 0;
    }
}
