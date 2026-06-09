package com.tvlive

import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity(), GestureDetector.OnGestureListener {

    private lateinit var playerView: PlayerView
    private lateinit var tvEpg: TextView
    private var player: ExoPlayer? = null
    private var channelList = mutableListOf<Channel>()
    private var currentPosition = 0

    // ✅ 这里换成你自己的 EPG XML 链接即可
    private val EPG_URL = "https://epg.catvod.com/epg.xml"

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏（状态栏+导航栏永久隐藏）
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.playerView)
        tvEpg = findViewById(R.id.tvEpg)
        gestureDetector = GestureDetector(this, this)

        loadChannelList()
    }

    // 触摸事件全局生效（手势核心）
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // 手势实现
    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        showChannelListDialog()
        return true
    }
    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (distanceY < -15) previousChannel()
        if (distanceY > 15) nextChannel()
        return true
    }
    override fun onLongPress(e: MotionEvent) {
        showScreenRatioDialog()
    }
    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        return true
    }

    // 加载频道列表
    private fun loadChannelList() {
        CoroutineScope(Dispatchers.IO).launch {
            channelList = M3UHelper.getChannelList()
            if (channelList.isNotEmpty()) {
                launch(Dispatchers.Main) {
                    playChannel(0)
                }
            }
        }
    }

    // 播放频道
    private fun playChannel(pos: Int) {
        if (pos < 0 || pos >= channelList.size) return
        currentPosition = pos
        val channel = channelList[pos]

        player?.release()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player!!.setMediaItem(mediaItem)
        player!!.prepare()
        player!!.playWhenReady = true

        tvEpg.text = "正在播放：${channel.name}"
        loadEpgFromNetwork() // ✅ 切台自动刷新 EPG
    }

    // ✅ 从网络链接自动获取 EPG XML
    private fun loadEpgFromNetwork() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(EPG_URL).build()
                val response = client.newCall(request).execute()
                val xmlContent = response.body?.string() ?: ""

                val programs = EpgHelper.parseXml(xmlContent)
                val currentProgram = programs.firstOrNull()

                launch(Dispatchers.Main) {
                    tvEpg.text = currentProgram?.title ?: "正在播放：${channelList[currentPosition].name}"
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    tvEpg.text = "正在播放：${channelList[currentPosition].name}"
                }
            }
        }
    }

    // 切台
    private fun previousChannel() = playChannel(if (currentPosition > 0) currentPosition - 1 else channelList.size - 1)
    private fun nextChannel() = playChannel(if (currentPosition < channelList.size - 1) currentPosition + 1 else 0)

    // 屏幕比例
    private fun showScreenRatioDialog() {
        val items = arrayOf("正常", "拉伸", "填充")
        AlertDialog.Builder(this)
            .setTitle("屏幕比例")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            }
            .show()
    }

    // 遥控器
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> previousChannel()
            KeyEvent.KEYCODE_DPAD_DOWN -> nextChannel()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showChannelListDialog()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HELP -> showScreenRatioDialog()
        }
        return super.onKeyDown(keyCode, event)
    }

    // 频道列表
    private fun showChannelListDialog() {
        val names = channelList.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("频道列表")
            .setItems(names) { _, pos -> playChannel(pos) }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
