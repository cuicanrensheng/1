package com.tv.live.listener;

import android.content.Context;
import android.widget.Toast;

import com.tv.live.TVPlayerManager;

/**
 * ================================================
 * 播放状态监听器实现类
 * 核心职责：
 * 1. 接收播放器各状态回调，统一做UI层提示
 * 2. 遵循「只提示、不自动重试」原则，播放异常由用户手动切台
 * 3. 与 TVPlayerManager 内部逻辑完全对齐，无行为冲突
 * ================================================
 *
 * 【2026-06-24 新增：频道失效提示】
 * 【修改说明】
 * 增加 onChannelInvalid() 方法的实现，
 * 当频道直播源失效（重试3次都失败）时，弹 Toast 提示用户。
 *
 * 【为什么只有频道失效才弹提示？】
 * 1. 普通的播放错误（网络波动、临时卡顿）会自动重试，
 *    大部分情况下重试一下就好了，不需要打扰用户。
 * 2. 只有重试 3 次都失败，判定为真正的"频道失效"时，
 *    才需要告诉用户，让用户手动切换到其他频道。
 * 3. 这样既保证了用户体验，又不会频繁弹窗干扰。
 */
public class PlayerStateListenerImpl implements TVPlayerManager.OnPlayStateListener {

    // 应用上下文，保留引用备用
    private final Context context;

    // 当前播放的频道名称，保留备用
    private String currentChannelName = "";

    /**
     * 构造函数
     * @param context 上下文，内部自动转成ApplicationContext避免内存泄漏
     */
    public PlayerStateListenerImpl(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 设置当前播放的频道名称
     * 切换频道时调用，确保提示信息与当前频道对应
     * @param name 频道名称
     */
    public void setCurrentChannelName(String name) {
        this.currentChannelName = name;
    }

    /**
     * 播放器空闲状态回调
     * 播放器已初始化但未加载媒体时触发，无需额外处理
     */
    @Override
    public void onIdle() {
        // 空闲状态无UI操作
    }

    /**
     * 缓冲中状态回调
     * PlayerView 已自带缓冲转圈动画，无需重复弹窗提示
     * 避免频繁弹窗干扰用户观看
     */
    @Override
    public void onBuffering() {
        // 缓冲状态由播放器视图自带加载动画反馈
    }

    /**
     * 播放就绪回调
     * 播放器准备完成、开始正常播放时触发，无需额外提示
     */
    @Override
    public void onPlayReady() {
        // 播放就绪无额外UI操作
    }

    /**
     * 播放结束回调
     * ✅ 已屏蔽：不弹Toast提示
     */
    @Override
    public void onPlayEnd() {
        // 已屏蔽播放结束提示
    }

    /**
     * 播放错误回调
     * ✅ 已屏蔽：不弹Toast提示
     * 网络异常、源失效、解码失败等情况触发
     * 
     * 【为什么屏蔽？】
     * 因为 TVPlayerManager 内部会自动重试（最多 3 次），
     * 每次失败都弹提示的话，用户会看到 3 次错误提示，体验不好。
     * 只有最后一次重试失败，判定为"频道失效"时，
     * 才通过 onChannelInvalid() 弹一次提示。
     */
    @Override
    public void onPlayError(String msg) {
        // 已屏蔽播放错误提示
        // 普通错误由播放器内部自动重试，不打扰用户
        // 只有真正失效时才通过 onChannelInvalid 提示
    }

    // ====================================================================
    // ✅ 2026-06-24 新增：频道失效提示
    // ====================================================================
    /**
     * 频道失效回调
     * 
     * 【触发时机】
     * 切换频道后，自动重试 3 次都失败，判定为"频道失效"时触发。
     * 
     * 【提示内容】
     * 直接使用 TVPlayerManager 传过来的提示文字，
     * 目前是："该频道直播源已失效，请切换其他频道"
     * 
     * 【为什么用 Toast.LENGTH_LONG？】
     * 频道失效是比较重要的信息，用户需要看到并做出反应（切台），
     * 所以用长时间显示，确保用户能看到。
     * 
     * 【为什么不弹 Dialog？】
     * Dialog 会打断用户操作，必须点击确认才能继续，体验不好。
     * Toast 只是提示，用户可以直接按遥控器切台，更流畅。
     */
    @Override
    public void onChannelInvalid(String msg) {
        try {
            // 弹 Toast 提示用户频道失效
            // 使用 currentChannelName 可以让提示更明确，比如：
            // "CCTV-1 直播源已失效，请切换其他频道"
            String tip;
            if (currentChannelName != null && !currentChannelName.isEmpty()) {
                tip = currentChannelName + "：" + msg;
            } else {
                tip = msg;
            }
            
            Toast.makeText(context, tip, Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            // 弹 Toast 失败也不影响主流程，静默处理
        }
    }
}
