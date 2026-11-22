package com.negi.survey.vm

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.negi.survey.utils.ModelArtifactIdentity
import com.negi.survey.utils.HeavyInitializer
import com.negi.survey.utils.ModelArtifactOperations
import com.negi.survey.utils.PersistentModelStore
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelArtifactOwnershipTest {
    @get:Rule val directory = TemporaryFolder()
    private val app = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val models = mutableListOf<AppViewModel>()
    private val jobs = mutableListOf<Job>()
    private val destination get() = File(directory.root, "model.bin").canonicalFile

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { app.applicationContext } returns app
        every { app.filesDir } returns directory.root
        every { app.cacheDir } returns File(directory.root, "cache").apply { mkdirs() }
        every { app.getDir("models", any()) } returns File(directory.root, "models").apply { mkdirs() }
        every { app.contentResolver } returns resolver
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        mockkObject(PersistentModelStore)
        coEvery { PersistentModelStore.persistFromPrivate(any(), any(), any(), any(), any()) } returns true
        coEvery { PersistentModelStore.reuseOrReplace(any(), any(), any(), any(), any(), any()) } returns false
    }

    @After fun cleanup() = runBlocking {
        models.forEach { it.cancelDownload() }
        jobs.forEach { withTimeout(5000) { it.join() } }
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun vm(content: String = "BB") = AppViewModel(modelUrl = "https://example.invalid/model.bin", fileName = "model.bin",
        trustedIdentity = syntheticIdentity(content))
        .also { models += it }
    private fun job(vm: AppViewModel): Job = (AppViewModel::class.java.getDeclaredField("downloadJob")
        .apply { isAccessible = true }.get(vm) as Job).also { jobs += it }
    private fun uri(stream: InputStream): Uri = mockk<Uri>().also {
        every { resolver.openInputStream(it) } returns stream
    }
    private fun syntheticIdentity(content: String) = ModelArtifactIdentity("test", "test", "model.bin", content.toByteArray().size.toLong(),
        java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) })

    private fun input(text: String) = ByteArrayInputStream(text.toByteArray())

    private class BlockedRead(private val failure: Boolean = false) : InputStream() {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        private var reads = 0
        override fun read(): Int = error("Buffered reads only")
        override fun read(b: ByteArray, off: Int, len: Int): Int = when (reads++) {
            0 -> { b[off] = 'A'.code.toByte(); 1 }
            1 -> {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
                if (failure) throw IOException("late old failure")
                b[off] = 'X'.code.toByte(); 1
            }
            else -> -1
        }
    }

    @Test fun twoImportsUseActualViewModelPathAndOnlyNewestPersistsOrCompletes() = runBlocking {
        val vm = vm()
        val blocked = BlockedRead()
        vm.useManuallySelectedModel(app, uri(blocked))
        val old = job(vm)
        try {
            withTimeout(5000) { blocked.entered.await() }
            vm.useManuallySelectedModel(app, uri(input("BB")))
            withTimeout(5000) { job(vm).join() }
            assertEquals("BB", destination.readText())
            assertEquals(DlState.Done(destination, true), vm.state.value)
        } finally {
            blocked.release.countDown()
            withTimeout(5000) { old.join() }
        }
        assertEquals("BB", destination.readText())
        assertEquals(DlState.Done(destination, true), vm.state.value)
        coVerify(exactly = 1) { PersistentModelStore.persistFromPrivate(app, "model.bin", any(), true, any()) }
    }

    @Test fun cancelWhileCopyBlockedKeepsExistingArtifactAndDoesNotPublishDone() = runBlocking {
        destination.writeText("valid")
        val vm = vm()
        val blocked = BlockedRead()
        vm.useManuallySelectedModel(app, uri(blocked))
        val old = job(vm)
        try {
            withTimeout(5000) { blocked.entered.await() }
            vm.cancelDownload()
        } finally {
            blocked.release.countDown()
            withTimeout(5000) { old.join() }
        }
        assertEquals("valid", destination.readText())
        assertEquals(DlState.Error("Canceled by user"), vm.state.value)
        assertFalse(directory.root.listFiles()!!.any { it.name.endsWith(".tmp") })
        coVerify(exactly = 0) { PersistentModelStore.persistFromPrivate(any(), any(), any(), any(), any()) }
    }

    @Test fun clearedViewModelCannotCorruptReplacementViewModel() = runBlocking {
        val oldVm = vm()
        val store = ViewModelStore().apply { put("old", oldVm) }
        val blocked = BlockedRead()
        oldVm.useManuallySelectedModel(app, uri(blocked))
        val old = job(oldVm)
        val replacement = vm()
        try {
            withTimeout(5000) { blocked.entered.await() }
            store.clear()
            replacement.useManuallySelectedModel(app, uri(input("BB")))
            withTimeout(5000) { job(replacement).join() }
        } finally {
            blocked.release.countDown()
            withTimeout(5000) { old.join() }
        }
        assertEquals("BB", destination.readText())
        assertEquals(DlState.Done(destination, true), replacement.state.value)
        assertFalse(oldVm.state.value is DlState.Done)
    }

    @Test fun liveOldViewModelLosesDestinationOwnershipWithoutScopeCancellation() = runBlocking {
        val oldVm = vm()
        val blocked = BlockedRead()
        oldVm.useManuallySelectedModel(app, uri(blocked))
        val old = job(oldVm)
        val replacement = vm()
        try {
            withTimeout(5000) { blocked.entered.await() }
            replacement.useManuallySelectedModel(app, uri(input("BB")))
            withTimeout(5000) { job(replacement).join() }
            assertTrue(old.isActive) // The other ViewModel cannot cancel this scope.
        } finally {
            blocked.release.countDown()
            withTimeout(5000) { old.join() }
        }
        assertEquals("BB", destination.readText())
        assertFalse(oldVm.state.value is DlState.Done)
        assertEquals(DlState.Done(destination, true), replacement.state.value)
        coVerify(exactly = 1) { PersistentModelStore.persistFromPrivate(any(), any(), any(), true, any()) }
    }

    @Test fun oldFailureCannotOverwriteNewError() = runBlocking {
        val vm = vm()
        val blocked = BlockedRead(failure = true)
        vm.useManuallySelectedModel(app, uri(blocked))
        val old = job(vm)
        try {
            withTimeout(5000) { blocked.entered.await() }
            vm.useManuallySelectedModel(app, uri(input("")))
            withTimeout(5000) { job(vm).join() }
            assertTrue(vm.state.value is DlState.Error)
            val currentError = vm.state.value
            blocked.release.countDown()
            withTimeout(5000) { old.join() }
            assertEquals(currentError, vm.state.value)
        } finally {
            blocked.release.countDown()
        }
    }

    @Test fun importSupersedesDownloadAndRejectsItsLateProgressCompletionAndPersistence() = runBlocking {
        mockkObject(HeavyInitializer)
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var progress: ((Long, Long?) -> Unit)? = null
        coEvery { HeavyInitializer.ensureInitialized(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            progress = arg(7)
            started.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            // Deliberately emulate a blocking source returning success despite cancellation.
            progress!!(100, 100)
            Result.success(destination)
        }
        val vm = vm()
        vm.proceedWithDownload(app)
        val old = job(vm)
        try {
            withTimeout(5000) { started.await() }
            vm.useManuallySelectedModel(app, uri(input("BB")))
            val replacement = job(vm)
            withTimeout(5000) { vm.state.first { it == DlState.ImportingSelectedFile } }
            release.countDown()
            withTimeout(5000) { old.join(); replacement.join() }
            val current = vm.state.value
            progress!!(999, 999)
            assertEquals(current, vm.state.value)
        } finally {
            release.countDown()
        }
        assertEquals("BB", destination.readText())
        assertEquals(DlState.Done(destination, true), vm.state.value)
        coVerify(exactly = 1) { PersistentModelStore.persistFromPrivate(any(), any(), any(), true, any()) }
    }

    @Test fun cancellationAtPersistenceBoundaryCannotPublishDone() = runBlocking {
        val vm = vm("valid")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        coEvery { PersistentModelStore.persistFromPrivate(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            true
        }
        vm.useManuallySelectedModel(app, uri(input("valid")))
        val old = job(vm)
        try {
            withTimeout(5000) { entered.await() }
            vm.cancelDownload()
        } finally {
            release.countDown()
            withTimeout(5000) { old.join() }
        }
        assertEquals("valid", destination.readText())
        assertEquals(DlState.Error("Canceled by user"), vm.state.value)
    }

    @Test fun verifiedManualInstallationSurvivesRuntimeRecovery() = runBlocking {
        val vm = vm()
        vm.useManuallySelectedModel(app, uri(input("BB")))
        withTimeout(5000) { job(vm).join() }
        coEvery { PersistentModelStore.forget(app, "model.bin") } returns true
        vm.prepareNativeRuntimeRestart(vm.state.value as DlState.Done)
        assertEquals("BB", destination.readText())
        coVerify(exactly = 0) { PersistentModelStore.forget(app, "model.bin") }
    }

    @Test fun lateRecoveryInSameViewModelCannotDeleteNewInstallationAtSamePath() = runBlocking {
        val vm = vm("valid")
        vm.useManuallySelectedModel(app, uri(input("valid")))
        withTimeout(5000) { job(vm).join() }
        val oldInstallation = vm.state.value as DlState.Done
        vm.useManuallySelectedModel(app, uri(input("valid")))
        withTimeout(5000) { job(vm).join() }
        vm.prepareNativeRuntimeRestart(oldInstallation)
        assertEquals("valid", destination.readText())
        coVerify(exactly = 0) { PersistentModelStore.forget(any(), any()) }
    }

    @Test fun badModelRecoveryDoesNotDeleteReplacementInstallation() = runBlocking {
        val oldVm = vm()
        oldVm.useManuallySelectedModel(app, uri(input("BB")))
        withTimeout(5000) { job(oldVm).join() }
        val replacement = vm("valid")
        replacement.useManuallySelectedModel(app, uri(input("valid")))
        withTimeout(5000) { job(replacement).join() }
        assertTrue(runCatching { oldVm.prepareNativeRuntimeRestart(oldVm.state.value as DlState.Done) }
            .exceptionOrNull() is CancellationException)
        assertEquals("valid", destination.readText())
    }
    @Test fun checksumFailurePreservesPrivateAndNeverPersistsOrForgets() = runBlocking {
        destination.writeText("valid")
        val vm = vm("valid")
        vm.useManuallySelectedModel(app, uri(input("wrong")))
        withTimeout(5000) { job(vm).join() }
        assertTrue(vm.state.value is DlState.Error)
        assertEquals("valid", destination.readText())
        coVerify(exactly = 0) { PersistentModelStore.persistFromPrivate(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { PersistentModelStore.forget(any(), any()) }
    }

    @Test fun invalidEarlierPrivateCandidateDoesNotHideLaterValidCacheFile() = runBlocking {
        destination.writeText("wrong")
        val cached = File(app.cacheDir, "model.bin").apply { writeText("valid") }
        val vm = vm("valid")
        vm.ensureModelDownloaded(app)
        withTimeout(5000) { job(vm).join() }
        assertEquals(DlState.Done(cached), vm.state.value)
        assertEquals("wrong", destination.readText())
    }

    @Test fun doneAndRestartBothRevalidateModifiedPrivateBytes() = runBlocking {
        destination.writeText("valid")
        val vm = vm("valid")
        vm.ensureModelDownloaded(app)
        withTimeout(5000) { job(vm).join() }
        assertTrue(vm.state.value is DlState.Done)
        destination.writeText("wrong")
        vm.ensureModelDownloaded(app)
        withTimeout(5000) { job(vm).join() }
        assertTrue(vm.state.value is DlState.NeedsModel)
        val restarted = vm("valid")
        restarted.ensureModelDownloaded(app)
        withTimeout(5000) { job(restarted).join() }
        assertTrue(restarted.state.value is DlState.NeedsModel)
    }

    @Test fun bestEffortPersistenceFailureKeepsVerifiedPrivateAndDone() = runBlocking {
        coEvery { PersistentModelStore.persistFromPrivate(any(), any(), any(), any(), any()) } returns false
        val vm = vm("valid")
        vm.useManuallySelectedModel(app, uri(input("valid")))
        withTimeout(5000) { job(vm).join() }
        assertTrue(vm.state.value is DlState.Done)
        assertEquals("valid", destination.readText())
    }

    @Test fun unsupportedConfigurationFailsBeforeCopyingOrPublishingDone() = runBlocking {
        val vm = AppViewModel(modelUrl = "https://example.invalid/unknown.bin").also { models += it }
        vm.useManuallySelectedModel(app, uri(input("valid")))
        withTimeout(5000) { job(vm).join() }
        assertTrue(vm.state.value is DlState.Error)
        assertTrue((vm.state.value as DlState.Error).message.contains("Unsupported model configuration"))
        verify(exactly = 0) { resolver.openInputStream(any()) }
    }

    @Test fun verifiedPrivateModelSurvivesRuntimeRecoveryWithoutPersistence() = runBlocking {
        // false covers unsupported API 26-28 and best-effort provider failure alike.
        coEvery { PersistentModelStore.persistFromPrivate(any(), any(), any(), any(), any()) } returns false
        val vm = vm("valid")
        vm.useManuallySelectedModel(app, uri(input("valid")))
        withTimeout(5000) { job(vm).join() }
        vm.prepareNativeRuntimeRestart(vm.state.value as DlState.Done)
        assertTrue(destination.isFile)
        assertEquals("valid", destination.readText())
        coVerify(exactly = 0) { PersistentModelStore.forget(any(), any()) }
    }

    @Test fun poisonedRuntimeRetriesSelectProcessRestartInsteadOfSameProcessRetry() {
        assertTrue(AppViewModel.requiresNativeRuntimeRestart(
            IllegalStateException(com.negi.survey.slm.NATIVE_RUNTIME_POISON_MESSAGE)))
        assertFalse(AppViewModel.requiresNativeRuntimeRestart(IOException("temporary initialization failure")))
    }

    @Test fun unsupportedMediaStoreDoesNotDiscardVerifiedPrivateModelOnRuntimeFailure() = runBlocking {
        every { PersistentModelStore.isSupported() } returns false
        coEvery { PersistentModelStore.persistFromPrivate(any(), any(), any(), any(), any()) } coAnswers { callOriginal() }
        val vm = vm("valid")
        vm.useManuallySelectedModel(app, uri(input("valid")))
        withTimeout(5000) { job(vm).join() }
        vm.prepareNativeRuntimeRestart(vm.state.value as DlState.Done)
        assertEquals("valid", destination.readText())
        coVerify(exactly = 0) { PersistentModelStore.forget(any(), any()) }
    }

}
