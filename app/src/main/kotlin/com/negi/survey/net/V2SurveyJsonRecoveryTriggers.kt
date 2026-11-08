package com.negi.survey.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.UserManager
import android.util.Log
import androidx.work.WorkManager
import com.negi.survey.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Shared Android adapter for the existing startup/broadcast entry points and network events. */
internal object V2SurveyJsonRecoveryTriggers {
    private const val TAG = "V2SurveyJsonRecovery"
    private val registered = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun recover(context: Context, trigger: String) {
        val app = context.applicationContext ?: context
        try {
            if (!isAllowed(app)) return
            val localOrDebug = BuildConfig.DEBUG || BuildConfig.GH_UPLOAD_MODE == "local"
            val config by lazy { GitHubDiagnosticsConfigStore.buildGitHubConfigOrNull(app) }
            runRecovery(trigger, "JSON") {
                // Resolve WorkManager and credentials only when a durable candidate needs them.
                val scheduler by lazy {
                    V2SurveyJsonScheduler(
                        app.filesDir,
                        SurveyUploadWork.AndroidWorkManagerOperations(WorkManager.getInstance(app)),
                        localOrDebug
                    )
                }
                val results = V2SurveyJsonRecovery(
                    V2SurveyJsonRouteStore(app.filesDir),
                    V2SurveyJsonLedger(app.filesDir, UploadedSurveyStore(app)::isUploaded),
                    SurveyUploadExecutionAuthority(app),
                    localOrDebug,
                    { config },
                    { route, file -> scheduler.scheduleReady(route, file, recoverTerminalSuccess = true) }
                ).reconcile()
                if (results.isNotEmpty()) {
                    val counts = results.groupingBy { it.status }.eachCount()
                    val reasons = results.mapNotNull { it.reason }.groupingBy { it }.eachCount()
                    Log.i(TAG, "V2 recovery trigger=$trigger results=$counts reasons=$reasons")
                }
            }
            runRecovery(trigger, "WAV") {
                val scheduler by lazy {
                    V2VoiceScheduler(app.filesDir, SurveyUploadWork.AndroidWorkManagerOperations(WorkManager.getInstance(app)))
                }
                val results = V2VoiceRecovery(
                    V2VoiceRouteStore(app.filesDir), V2VoiceLedger(app.filesDir), localOrDebug,
                    { config }, { scheduler.schedule(it) }
                ).reconcile()
                if (results.isNotEmpty()) {
                    val counts = results.groupingBy { it.status }.eachCount()
                    val reasons = results.mapNotNull { it.reason }.groupingBy { it }.eachCount()
                    Log.i(TAG, "V2 WAV recovery trigger=$trigger results=$counts reasons=$reasons")
                }
            }
            // A network constraint resumes active work. Reconnect discovery also revisits terminal work.
            if (trigger != NETWORK) watchReconnect(app)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (exception: Exception) {
            Log.w(TAG, "V2 recovery deferred for trigger=$trigger", exception)
        }
    }

    private fun runRecovery(trigger: String, kind: String, action: () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (exception: Exception) {
            Log.w(TAG, "V2 $kind recovery deferred for trigger=$trigger", exception)
        }
    }

    private fun isAllowed(context: Context): Boolean =
        !BuildConfig.ISSUE86_PROCESS_DEATH_HARNESS && !BuildConfig.ISSUE157_DA &&
            !DiagnosticUploadInstrumentationGate.isDisabled() &&
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
            GitHubDiagnosticsConfigStore.isUploadEnabled(context)

    private fun watchReconnect(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val events = Channel<Unit>(Channel.CONFLATED)
        try {
            val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    events.trySend(Unit)
                }
            })
        } catch (exception: Exception) {
            registered.set(false)
            events.close()
            Log.w(TAG, "V2 reconnect observation unavailable", exception)
            return
        }
        // App-process lifetime registration. Receives events only; no connectivity polling.
        scope.launch {
            for (event in events) recover(context, NETWORK)
        }
    }

    private const val NETWORK = "network"
}
