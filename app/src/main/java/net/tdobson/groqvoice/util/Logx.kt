package net.tdobson.groqvoice.util

import android.util.Log
import net.tdobson.groqvoice.BuildConfig

/**
 * The only door to logcat. Debug builds only: in release every call is a no-op (and R8 strips Log.d anyway).
 * Never pass transcript text, typed text or the API key, in any build.
 */
object Logx {
    const val TAG = "GroqVoice"

    inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    inline fun w(t: Throwable? = null, message: () -> String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message(), t)
    }
}
