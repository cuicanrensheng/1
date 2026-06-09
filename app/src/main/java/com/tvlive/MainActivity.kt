package com.tvlive

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var tvEpg: TextView
    private var player: ExoPlayer? = null

    // EPG 地址（可用公共EPG）
    private val EPG_URL = "https://epg.112114.xyz/epg.xml"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        tvEpg = findViewById(R.id.tvEpg)

        // 打开 APP 直接播放 + 加载EPG
        playFirstChannel()
    }

    // 加载并播放第一个频道（完全调用你自己的 M3UHelper）
    private fun playFirstChannel() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channelList = M3UHelper.getChannelList()

                if (channelList.isNotEmpty()) {
                    val firstChannel = channelList[0]
                    runOnUiThread {
                        // 🔥 这里用你正确的字段：streamUrl
                        initPlayer(firstChannel.streamUrl)
                        tvEpg.text = "正在播放：${firstChannel.name}"
                    }
                    loadEpg()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvEpg.text = "直播源加载失败"
                }
            }
        }
    }

    // 加载EPG（完全调用你自己的 EpgHelper）
    private fun loadEpg() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(EPG_URL).build()
                val response = client.newCall(request).execute()
                val xmlContent = response.body?.string() ?: ""

                val programs = EpgHelper.parseXml(xmlContent)
                val now = System.currentTimeMillis()
                val currentProgram = programs.firstOrNull {
                    now in it.startTime..it.endTime
                }

                runOnUiThread {
                    tvEpg.text = currentProgram?.title ?: "暂无节目信息"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 初始化播放器
    private fun initPlayer(url: String) {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        val mediaItem = MediaItem.fromUri(url)
        player!!.setMediaItem(mediaItem)
        player!!.prepare()
        player!!.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
