package com.negi.survey.utils

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.vm.AppViewModel
import com.negi.survey.vm.DlState
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafModelImporterInstrumentationTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "saf-model-import-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        File(context.filesDir, TEST_MODEL_NAME).delete()
    }

    @Test
    fun valid_import_promotes_verified_temp_file() = runBlocking {
        val bytes = "valid selected model".encodeToByteArray()
        val destination = File(root, TEST_MODEL_NAME)

        val result = SafModelImporter.importStream(ByteArrayInputStream(bytes), destination, identity(bytes))

        assertTrue(result is SafModelImporter.Result.Imported)
        assertArrayEquals(bytes, destination.readBytes())
        assertNoTemporaryFiles(destination.parentFile!!)
    }

    @Test
    fun wrong_size_is_rejected_without_destination() = runBlocking {
        val expected = "expected bytes".encodeToByteArray()
        val destination = File(root, TEST_MODEL_NAME)

        val result = SafModelImporter.importStream(
            ByteArrayInputStream("short".encodeToByteArray()), destination, identity(expected)
        )

        assertTrue(result is SafModelImporter.Result.Rejected)
        assertFalse(destination.exists())
        assertNoTemporaryFiles(destination.parentFile!!)
    }

    @Test
    fun wrong_sha256_is_rejected_and_temp_is_removed() = runBlocking {
        val bytes = "same length".encodeToByteArray()
        val destination = File(root, TEST_MODEL_NAME)
        val wrongIdentity = identity(bytes).copy(sha256 = "00".repeat(32))

        val result = SafModelImporter.importStream(ByteArrayInputStream(bytes), destination, wrongIdentity)

        assertTrue(result is SafModelImporter.Result.Rejected)
        assertFalse(destination.exists())
        assertNoTemporaryFiles(destination.parentFile!!)
    }

    @Test
    fun interrupted_copy_is_rejected_and_temp_is_removed() = runBlocking {
        val bytes = "copy failure".encodeToByteArray()
        val destination = File(root, TEST_MODEL_NAME)

        val result = SafModelImporter.importStream(FailingInputStream(), destination, identity(bytes))

        assertTrue(result is SafModelImporter.Result.Rejected)
        assertFalse(destination.exists())
        assertNoTemporaryFiles(destination.parentFile!!)
    }

    @Test
    fun failed_import_leaves_existing_private_model_untouched() = runBlocking {
        val bytes = "existing private model".encodeToByteArray()
        val destination = File(root, TEST_MODEL_NAME).apply { writeBytes(bytes) }

        val result = SafModelImporter.importStream(FailingInputStream(), destination, identity(bytes))

        assertTrue(result is SafModelImporter.Result.ExistingPrivateModel)
        assertArrayEquals(bytes, destination.readBytes())
        assertNoTemporaryFiles(destination.parentFile!!)
    }

    @Test
    fun verified_import_never_overwrites_a_competing_private_model() = runBlocking {
        val bytes = "competing valid model".encodeToByteArray()
        val destination = File(root, TEST_MODEL_NAME)

        val result = SafModelImporter.importStream(
            input = ByteArrayInputStream(bytes),
            destination = destination,
            identity = identity(bytes),
            beforePromotion = {
                // Simulates a network final promotion using the same destination lock.
                ModelDestinationLock.withLock(destination) {
                    destination.writeBytes(bytes)
                }
            },
        )

        assertTrue(result is SafModelImporter.Result.Rejected)
        assertArrayEquals(bytes, destination.readBytes())
        assertNoTemporaryFiles(destination.parentFile!!)
    }

    @Test
    fun successful_saf_import_keeps_view_model_out_of_network_download() = runBlocking {
        val bytes = "view model import".encodeToByteArray()
        val source = File(root, TEST_MODEL_NAME).apply { writeBytes(bytes) }
        val vm = AppViewModel(
            modelUrl = "https://example.invalid/$TEST_MODEL_NAME",
            importIdentityOverride = identity(bytes),
        )

        vm.importExistingModel(context, Uri.fromFile(source))
        val imported = withTimeout(10_000) {
            vm.state.first { it is DlState.Done } as DlState.Done
        }
        vm.ensureModelDownloaded(context)

        assertTrue(imported.file.exists())
        assertTrue(vm.state.value is DlState.Done)
        assertTrue((vm.state.value as DlState.Done).file.absolutePath == imported.file.absolutePath)
    }

    private fun identity(bytes: ByteArray) = SafModelImporter.ModelIdentity(
        fileName = TEST_MODEL_NAME,
        expectedBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
    )

    private fun assertNoTemporaryFiles(parent: File) {
        assertTrue(parent.listFiles().orEmpty().none { it.name.endsWith(".import.tmp") })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class FailingInputStream : InputStream() {
        private var emitted = false

        override fun read(): Int = throw IOException("synthetic copy failure")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (emitted) throw IOException("synthetic copy failure")
            buffer[offset] = 1
            emitted = true
            return 1
        }
    }

    private companion object {
        const val TEST_MODEL_NAME = "saf-test-model.litertlm"
    }
}
