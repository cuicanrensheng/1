package com.tvlive

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private val channelList = mutableListOf<Channel>()
    private var curIdx = 0

    // 手势参数
    private var downTime = 0L
    private var lastClickTs = 0L
    private var downX = 0f
    private var downY = 0f
    private val DOUBLE_CLICK_GAP = 300L
    private val LONG_TAP_DUR = 500L
    private val SLIDE_THRESHOLD = 80f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
        initPlayer()
        loadSource()
        bindTouchHandler()
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.useController = false
    }

    private fun loadSource() {
        CoroutineScope(Dispatchers.IO).launch {
            val channels = M3UHelper.getChannelList()
            channelList.addAll(channels)
            if(channelList.isNotEmpty()){
                withContext(Dispatchers.Main){ play(curIdx) }
            }
        }
    }

    private fun play(index: Int) {
        if(index <0 || index >= channelList.size) return
        curIdx = index
        val item = MediaItem.fromUri(channelList[index].streamUrl)
        player?.setMediaItem(item)
        player?.prepare()
        player?.play()
        Toast.makeText(this, channelList[index].name, Toast.LENGTH_SHORT).show()
    }

    // ========== 电视遥控器按键 ==========
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when(keyCode){
            KeyEvent.KEYCODE_DPAD_UP -> { play(curIdx -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { play(curIdx +1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showChannelList()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HELP -> {
                openSetting()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if(keyCode == KeyEvent.KEYCODE_DPAD_CENTER){
            openSetting()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // ========== 手机触屏手势 ==========
    private fun bindTouchHandler() {
        playerView.setOnTouchListener { _, ev ->
            when(ev.action){
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downX = ev.x
                    downY = ev.y
                }
                MotionEvent.ACTION_UP -> {
                    val dur = System.currentTimeMillis() - downTime
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    // 长按打开设置
                    if(dur > LONG_TAP_DUR){
                        openSetting()
                        return@setOnTouchListener true
                    }
                    // 上下滑动切换频道
                    if(Math.abs(dy) > SLIDE_THRESHOLD && Math.abs(dx) < SLIDE_THRESHOLD){
                        if(dy <0) play(curIdx -1) else play(curIdx +1)
                        return@setOnTouchListener true
                    }
                    // 单击/双击
                    if(Math.abs(dx) < SLIDE_THRESHOLD && Math.abs(dy) < SLIDE_THRESHOLD){
                        val now = System.currentTimeMillis()
                        if(now - lastClickTs < DOUBLE_CLICK_GAP){
                            openSetting()
                        }else{
                            showChannelList()
                        }
                        lastClickTs = now
                    }
                }
            }
            true
        }
    }

    private fun showChannelList() {
        Toast.makeText(this, "弹出频道列表弹窗", Toast.LENGTH_SHORT).show()
    }

    priva
