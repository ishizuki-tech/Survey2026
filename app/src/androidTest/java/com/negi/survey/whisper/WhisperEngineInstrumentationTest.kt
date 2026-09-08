package com.negi.survey.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Direct real-device smoke test for the bundled Whisper model.
 *
 * This test intentionally does not start MainActivity, AppViewModel,
 * HeavyInitializer, LiteRT-LM, or any survey UI.
 */
@RunWith(AndroidJUnit4::class)
class WhisperEngineInstrumentationTest {

    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val testContext =
        InstrumentationRegistry.getInstrumentation().context

    private var fixtureFile: File? = null

    @Test
    fun bundledSmallModelTranscribesEnglishFixture() = runBlocking {
        val init = WhisperEngine.ensureInitializedFromAsset(
            context = context,
            assetPath = MODEL_ASSET_PATH,
        )
        assertTrue("Whisper model initialization failed: ${init.exceptionOrNull()}", init.isSuccess)
        assertTrue(
            "Whisper engine was not initialized for the bundled asset",
            WhisperEngine.isInitializedForAsset(MODEL_ASSET_PATH),
        )

        val wav = copyFixtureToCache()
        val transcription = WhisperEngine.transcribeWaveFile(
            file = wav,
            lang = "en",
        )

        assertTrue(
            "Whisper transcription failed: ${transcription.exceptionOrNull()}",
            transcription.isSuccess,
        )
        assertFalse("Whisper returned a blank transcription", transcription.getOrThrow().isBlank())
    }

    @After
    fun releaseWhisperContext() {
        runBlocking {
            runCatching { WhisperEngine.release() }
        }
        fixtureFile?.delete()
    }

    private fun copyFixtureToCache(): File {
        val output = File.createTempFile("whisper-smoke-", ".wav", context.cacheDir)
        testContext.assets.open(FIXTURE_ASSET_PATH).use { input ->
            FileOutputStream(output).use { fileOutput ->
                input.copyTo(fileOutput)
            }
        }
        fixtureFile = output
        return output
    }

    private companion object {
        const val MODEL_ASSET_PATH = "models/ggml-small-q5_1.bin"

        // Copied from whisper.cpp/samples/jfk.wav; the submodule is MIT licensed.
        const val FIXTURE_ASSET_PATH = "whisper/jfk.wav"
    }
}
