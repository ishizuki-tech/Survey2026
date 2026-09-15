package com.negi.survey.utils

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes app-owned changes to one app-private model destination. */
object ModelDestinationLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(destination: File, action: suspend () -> T): T {
        val key = destination.canonicalFile.absolutePath
        val lock = locks.computeIfAbsent(key) { Mutex() }
        return lock.withLock { action() }
    }
}
