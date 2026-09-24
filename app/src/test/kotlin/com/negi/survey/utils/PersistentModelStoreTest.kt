package com.negi.survey.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentModelStoreTest {

    @Test
    fun collisionVariant_matchesOnlyExactAndroidStyleNames() {
        val target = "gemma-3n-E4B-it-int4.litertlm"

        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant(target, target))
        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (1).litertlm", target))
        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (8).litertlm", target))

        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4-old.litertlm", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (abc).litertlm", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (1١).litertlm", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (1).bin", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4-v2.litertlm", target))
    }

    @Test
    fun collisionVariant_preservesExactExtensionAndSupportsExtensionlessNames() {
        assertTrue(
            PersistentModelStore.isMediaStoreCollisionVariant(
                "gemma.model.v1 (2).litertlm",
                "gemma.model.v1.litertlm"
            )
        )
        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant("model (3)", "model"))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("model (0)", "model"))
    }
}
