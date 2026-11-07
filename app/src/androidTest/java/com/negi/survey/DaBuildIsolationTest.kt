package com.negi.survey

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.net.GitHubUploader
import com.negi.survey.utils.PersistentModelStore
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaBuildIsolationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireDaBuild() {
        assumeTrue(BuildConfig.ISSUE157_DA)
    }

    @Test
    fun launcherAndModelsUseTheExperimentalPackage() {
        assertEquals("com.negi.survey.da", context.packageName)
        val launcher = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0
        )
        assertEquals("Survey D+A", launcher?.loadLabel(context.packageManager)?.toString())
        assertNotNull(context.packageManager.resolveContentProvider("com.negi.survey.da.androidx-startup", 0))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(context.filesDir.parentFile?.name == context.packageName)
        context.getExternalFilesDir(null)?.let { assertTrue(it.parentFile?.name == context.packageName) }

        for (name in listOf("ggml-small-q5_1.bin", "ggml-small-sw-cv14-q5_0.bin")) {
            DataInputStream(context.assets.open("models/$name")).use { model ->
                val magic = ByteArray(4)
                model.readFully(magic)
                assertEquals("lmgg", magic.decodeToString())
            }
        }
    }

    @Test
    fun modelCopyAndRestoreUseTheExperimentalDownloadsFolder() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val name = "da-isolation-${UUID.randomUUID()}.bin"
        val source = File(context.cacheDir, name)
        val restored = File(context.filesDir, name)
        val contents = "D+A model storage probe"
        try {
            source.writeText(contents)
            assertTrue(PersistentModelStore.persistFromPrivate(context, name, source))
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.RELATIVE_PATH),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            ).use { rows ->
                checkNotNull(rows)
                assertTrue(rows.moveToFirst())
                assertEquals("Download/Survey2026Models-da/", rows.getString(0))
                assertEquals(1, rows.count)
            }
            assertTrue(PersistentModelStore.reuseOrReplace(context, name, restored, {}, {}))
            assertEquals(contents, restored.readText())
        } finally {
            PersistentModelStore.forget(context, name)
            source.delete()
            restored.delete()
        }
    }

    @Test
    fun evenExplicitGithubRoutingCannotSendAnUpload() = runBlocking {
        assertTrue(BuildConfig.GH_OWNER.isEmpty())
        assertTrue(BuildConfig.GH_REPO.isEmpty())
        assertTrue(BuildConfig.GH_TOKEN.isEmpty())
        assertTrue(BuildConfig.SUPABASE_URL.isEmpty())
        assertTrue(BuildConfig.SUPABASE_ANON_KEY.isEmpty())

        val config = GitHubUploader.GitHubConfig(owner = "unused", repo = "unused", token = "test-only")
        val failure = runCatching { GitHubUploader.uploadJsonAtPath(config, "isolation-test.json", "{}") }
            .exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("GitHub uploads are disabled in Survey D+A.", failure?.message)
    }
}
