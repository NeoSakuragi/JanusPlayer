package com.janusplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DebugReceiver : BroadcastReceiver() {

    companion object {
        var activity: JanusPlusActivity? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.i("DebugCmd", cmd)
        val act = activity ?: return

        when {
            cmd == "play" -> act.debugPlay()
            cmd == "back" -> act.debugBack()
            cmd == "controls" -> PlayerScreen.toggleControls()
            cmd == "pause" -> act.debugPause()
            cmd.startsWith("seek:") -> {
                val sec = cmd.removePrefix("seek:").toIntOrNull() ?: return
                act.debugSeek(sec * 1000L)
            }
            cmd.startsWith("nav:") -> act.debugNav(cmd.removePrefix("nav:"))
        }
    }
}
