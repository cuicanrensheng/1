package com.tvlive

import android.os.Build
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
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.util.AspectRatioFrameLayout
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

    private val EPG_URL = "https://epg.112114.xyz/epg.xml"
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 最强全屏：彻底干掉状态栏 / 通知栏 / 导航栏
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 只要回到界面，立刻强制全屏（永远不显示状态栏）
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun loadChannelList() {
        CoroutineScope(Dispatchers.IO).launch {
            channelList = M3UHelper.getChannelList()
            if (channelList.isNotEmpty()) {
                runOnUiThread {
                    playChannel(0)
                }
            }
        }
    }

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
        loadEpg()
    }

    private fun previousChannel() = playChannel(if (currentPosition > 0) currentPosition - 1 else channelList.size - 1)
    private fun nextChannel() = playChannel(if (currentPosition < channelList.size - 1) currentPosition + 1 else 0)

    private fun loadEpg() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(EPG_URL).build()
                val xml = client.newCall(request).execute().body?.string() ?: ""
                val list = EpgHelper.parseXml(xml)
                val now = System.currentTimeMillis()
                val program = list.firstOrNull { now in it.startTime..it.endTime }
                runOnUiThread {
                    tvEpg.text = program?.title ?: "正在播放：${channelList[currentPosition].name}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvEpg.text = "正在播放：${channelList[currentPosition].name}"
                }
            }
        }
    }

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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> previousChannel()
            KeyEvent.KEYCODE_DPAD_DOWN -> nextChannel()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showChannelListDialog()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HELP -> showScreenRatioDialog()
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showChannelListDialog() {
        val names = channelList.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("频道列表")
            .setItems(names) { _, pos -> playChannel(pos) }
            .show()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        showChannelListDialog()
        return true
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
        if (dY > 10) nextChannel()
        if (dY < -10) previousChannel()
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        showScreenRatioDialog()
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
        if (e2.y < e1.y) previousChannel() else nextChannel()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
