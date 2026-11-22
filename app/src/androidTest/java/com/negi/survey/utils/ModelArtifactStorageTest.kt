package com.negi.survey.utils

import android.content.ContentUris
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.negi.survey.BuildConfig
import com.negi.survey.utils.ModelArtifactIdentity
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tiny artifacts validate the Android filesystem and real MediaStore adapter; no model inference. */
@RunWith(AndroidJUnit4::class)
class ModelArtifactStorageTest {
    private fun identity(content: String) = ModelArtifactIdentity("test", "test", "test.bin", content.toByteArray().size.toLong(),
        java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) })

    @Test fun sameSizedReplacementPersistsAndRestoresNewestBytes() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "issue123-${UUID.randomUUID()}.bin"
        val file = File(context.filesDir, name)
        try {
            file.writeText("AA")
            assertTrue(PersistentModelStore.persistFromPrivate(context, name, file, replaceExisting = true, identity = identity(file.readText())))
            file.writeText("BB")
            assertTrue(PersistentModelStore.persistFromPrivate(context, name, file, replaceExisting = true, identity = identity(file.readText())))
            assertTrue(file.delete())
            assertTrue(PersistentModelStore.reuseOrReplace(context, name, file, {}, {}, identity = identity("BB")))
            assertEquals("BB", file.readText())
        } finally {
            PersistentModelStore.forget(context, name)
            file.delete()
        }
    }

    @Test fun interruptedReplacementCleanupRestoresNewerSmallerCopyToOneCharacterDestination() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "issue123-${UUID.randomUUID()}.bin"
        val dir = File(context.cacheDir, "issue123-${UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(dir, "a")
        val resolver = context.contentResolver
        fun insertCopy(contents: String, pending: Boolean = false): android.net.Uri {
            val subfolder = if (BuildConfig.ISSUE157_DA) "Survey2026Models-da" else "Survey2026Models"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subfolder")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(contents.toByteArray()) }
            if (!pending) {
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null) == 1)
            }
            return uri
        }
        try {
            val old = insertCopy("older-larger-valid-copy")
            val replacement = insertCopy("BB")
            assertTrue(ContentUris.parseId(replacement) > ContentUris.parseId(old))
            // Both completed rows remain, exactly as after interrupted/failed old-row cleanup.
            assertTrue(PersistentModelStore.reuseOrReplace(context, name, destination, {}, {}, identity = identity("BB")))
            assertEquals("BB", destination.readText())
            destination.delete()
            insertCopy("incomplete-newer-copy", pending = true)
            assertTrue(PersistentModelStore.reuseOrReplace(context, name, destination, {}, {}, identity = identity("BB")))
            assertEquals("BB", destination.readText())
        } finally {
            PersistentModelStore.forget(context, name)
            destination.delete()
            dir.delete()
        }
    }

    @Test fun canceledBlockedCopyCannotReplaceNewImportOnAndroidFilesystem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "issue123-${UUID.randomUUID()}.bin")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val old = ModelArtifactOperations.begin(file)
        val job = launch(Dispatchers.IO) {
            old.import(validate = identity("AX")::validate, open = { object : ByteArrayInputStream("AX".toByteArray()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.read(b, off, len)
                }
            } }) { fail("Canceled copy installed") }
        }
        old.attach(job)
        try {
            withTimeout(5000) { entered.await() }
            job.cancel()
            ModelArtifactOperations.begin(file).import(validate = identity("BB")::validate, open = { ByteArrayInputStream("BB".toByteArray()) }) {}
            release.countDown()
            withTimeout(5000) { job.join() }
            assertEquals("BB", file.readText())
            assertFalse(context.filesDir.listFiles()!!.any { it.name.startsWith(file.name + ".") })
        } finally {
            release.countDown()
            job.join()
            file.delete()
        }
    }
}
