package com.tvlive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        if(intent?.action == Intent.ACTION_BOOT_COMPLETED && AppConfig.bootAutoStart){
            val launch = Intent(ctx, MainActivity::class.java)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx?.startActivity(launch)
        }
    }
}
