package com.negi.survey.slm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelIdentityTest {
    @Test
    fun aliases_for_the_same_file_share_filesystem_identity() {
        val sharedIdentity = ModelFilesystemIdentity(
            device = 65088L,
            inode = 23726L,
            size = 4_919_541_760L,
            modifiedAtMillis = 1_700_000_000_000L,
        )

        val resolver: (File) -> ModelFilesystemIdentity? = { sharedIdentity }

        assertEquals(
            modelIdentityForPath("/data/data/com.negi.survey/files/model.litertlm", resolver),
            modelIdentityForPath("/data/user/0/com.negi.survey/files/model.litertlm", resolver),
        )
    }

    @Test
    fun different_files_have_different_filesystem_identities() {
        val resolver: (File) -> ModelFilesystemIdentity? = { file ->
            if (file.path.endsWith("first.litertlm")) {
                ModelFilesystemIdentity(65088L, 23726L, 4_919_541_760L, 1_700_000_000_000L)
            } else {
                ModelFilesystemIdentity(65088L, 23727L, 4_919_541_760L, 1_700_000_000_000L)
            }
        }

        assertNotEquals(
            modelIdentityForPath("/data/user/0/com.negi.survey/files/first.litertlm", resolver),
            modelIdentityForPath("/data/user/0/com.negi.survey/files/second.litertlm", resolver),
        )
    }

    @Test
    fun unavailable_stat_uses_a_deterministic_path_fallback() {
        val unavailable: (File) -> ModelFilesystemIdentity? = { null }

        val first = modelIdentityForPath("relative/model.litertlm", unavailable)
        assertEquals(first, modelIdentityForPath("relative/model.litertlm", unavailable))
        assertNotEquals(first, modelIdentityForPath("relative/other.litertlm", unavailable))
    }
}
