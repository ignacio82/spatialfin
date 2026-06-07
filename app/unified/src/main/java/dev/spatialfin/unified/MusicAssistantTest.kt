package dev.spatialfin.unified

import android.content.Context
import android.util.Log

object MusicAssistantTest {
    fun runTest(context: Context) {
        val prefs = context.getSharedPreferences("sendspin_prefs", Context.MODE_PRIVATE)
        Log.i("MATest", "Preferences keys: ${prefs.all.keys}")
    }
}
