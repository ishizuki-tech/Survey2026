package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Reuses the startup reconciliation path after boot, unlock, and package replacement. */
class UploadRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isRelevantAction(intent.action.orEmpty())) return
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        if (!running.compareAndSet(false, true)) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                V2ArtifactDelivery.scheduleRecovery(app, immediate = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("UploadRescheduleRcvr", "V2 recovery deferred", error)
            } finally {
                running.set(false)
                pending.finish()
            }
        }
    }

    companion object {
        private val running = AtomicBoolean(false)

        internal fun isRelevantAction(action: String): Boolean = when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_USER_UNLOCKED -> true
            else -> false
        }
    }
}
