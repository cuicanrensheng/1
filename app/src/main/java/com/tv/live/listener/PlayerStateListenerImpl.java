package com.tv.live.listener;
import android.content.Context;
import com.tv.live.MainActivity;
import com.tv.live.TVPlayerManager;
/**
 * ================================================
 * 播放状态监听器实现类
 * 核心职责：
 * 1. 接收播放器各状态回调，统一做UI层提示
 * 2. 遵循「只提示、不自动重试」原则，播放异常由用户手动切台
 * 3. 与 TVPlayerManager 内部逻辑完全对齐，无行为冲突
 * 
 * 【2026-06-24 修改：配合失效频道图片 + 自动跳过功能】
 * 修改说明：
 * - 构造函数从接收 Context 改为接收 MainActivity，
 *   以便在播放状态变化时调用 MainActivity 的方法
 *   （显示/隐藏失效图片、自动跳过失效频道等）
 * 
 * 为什么不用 ApplicationContext？
 * - 之前用 getApplicationContext() 是为了避免内存泄漏
 * - 但现在需要调用 MainActivity 的 UI 方法，必须持有 Activity 引用
 * - PlayerStateListenerImpl 的生命周期和 MainActivity 是绑定的，
 *   MainActivity 销毁时会释放播放器，不会造成内存泄漏
 * ================================================
 */
public class PlayerStateListenerImpl implements TVPlayerManager.OnPlayStateListener {
    // ====================================================================
    // ✅ 2026-06-24 修改：从 Context 改成 MainActivity
    // ====================================================================
    // 【修改原因】
    // 需要在播放状态变化时调用 MainActivity 的方法：
    // - 播放失败时：mainActivity.onPlayError() - 显示失效图片 + 自动跳过
    // - 播放成功时：mainActivity.onPlaySuccess() - 隐藏失效图片 + 重置状态
    // 
    // 【为什么可以直接持有 MainActivity？】
    // 1. PlayerStateListenerImpl 是在 MainActivity.onCreate() 中创建的
    // 2. MainActivity.onDestroy() 中会释放播放器，监听器也会一起释放
    // 3. 不会出现 Activity 已经销毁但监听器还活着的情况
    // 
    // 【保留 context 变量名】
    // 为了最小化修改，变量名还是叫 context，
    // 但实际类型改成了 MainActivity，这样其他地方的代码不用改。
    private final MainActivity mainActivity;
    // 当前播放的频道名称，保留备用
    private String currentChannelName = "";
    /**
     * 构造函数
     * 
     * 【2026-06-24 修改】
     * 参数从 Context 改成 MainActivity，
     * 以便在播放状态变化时调用 MainActivity 的方法。
     * 
     * @param activity MainActivity 实例，用于回调播放状态
     */
    public PlayerStateListenerImpl(MainActivity activity) {
        // ✅ 直接保存 MainActivity 引用
        // 不再用 getApplicationContext()，因为需要调用 Activity 的方法
        this.mainActivity = activity;
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
     * 播放器准备完成、开始正常播放时触发
     * 
     * 【2026-06-24 修改】
     * 新增：调用 mainActivity.onPlaySuccess()
     * 作用：
     * 1. 隐藏失效频道提示图片
     * 2. 通知 ChannelPanelController 重置切台状态和自动跳过计数
     * 
     * 为什么在这里调用？
     * - onPlayReady 表示播放器已经准备好，可以正常播放了
     * - 说明当前频道是有效的，需要把之前的错误状态清除掉
     */
    @Override
    public void onPlayReady() {
        // ✅ 2026-06-24 新增：播放成功回调
        // 通知 MainActivity：播放成功了
        // MainActivity 会做两件事：
        // 1. 隐藏失效频道图片（ivChannelError.setVisibility(GONE)）
        // 2. 通知 ChannelPanelController 重置切台状态和跳过计数
        if (mainActivity != null) {
            mainActivity.onPlaySuccess();
        }
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
     * 网络异常、源失效、解码失败等情况触发
     * 
     * 【2026-06-24 修改】
     * 新增：调用 mainActivity.onPlayError(msg)
     * 作用：
     * 1. 显示失效频道提示图片
     * 2. 判断是否可以自动跳过
     * 3. 如果可以跳过，延迟 500ms 后自动切下一个频道
     * 
     * 自动跳过的条件：
     * - 必须是切台状态（用户刚按了上下键）
     * - 有明确的切台方向
     * - 未达到最大自动跳过次数（10次）
     * 
     * 为什么不在这个类里直接处理自动跳过？
     * - 自动跳过需要和 ChannelPanelController 配合
     * - ChannelPanelController 在 MainActivity 里
     * - 所以统一交给 MainActivity 处理，逻辑更清晰
     */
    @Override
    public void onPlayError(String msg) {
        // ✅ 2026-06-24 新增：播放失败回调
        // 通知 MainActivity：播放失败了
        // MainActivity 会做三件事：
        // 1. 显示失效频道图片（ivChannelError.setVisibility(VISIBLE)）
        // 2. 判断是否可以自动跳过（调用 channelPanelController.canAutoSkip()）
        // 3. 如果可以跳过，延迟 500ms 后自动切下一个
        if (mainActivity != null) {
            mainActivity.onPlayError(msg);
        }
    }
}
