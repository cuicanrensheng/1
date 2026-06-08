}package com.tvlive

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        webView = findViewById(R.id.webView)

        initWebView()
        initPlayer()
    }

    private fun initWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        webView.webViewClient = WebViewClient()
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        // 换成你自己的直播源地址
        val mediaItem = MediaItem.fromUri("http://your-stream-url.com/stream.m3u8")
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    private fun openSetting() {
        // 空实现，不崩溃
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSetting()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
