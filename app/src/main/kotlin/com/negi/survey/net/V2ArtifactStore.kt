package com.negi.survey.net

import android.content.Context
import com.negi.survey.BuildConfig
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.DeviceUploadTagProvider
import com.negi.survey.utils.V2ArtifactPaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One atomically published payload and its immutable delivery evidence. */
internal class V2ArtifactStore(root: File) {
    private val root = root.canonicalFile
    constructor(context: Context) : this(File(context.noBackupFilesDir, "v2_artifacts"))

    enum class Kind(val directory: String, val type: String, val extension: String) {
        SURVEY("survey", "survey", ".json"), VOICE("voice", "voice", ".wav"),
        SURVEY_TRACE("diagnostics/survey", "survey", ".ndjson.gz"),
        WHISPER_TRACE("diagnostics/whisper", "whisper", ".ndjson.gz"),
        SLM("diagnostics/slm", "slm", ".ndjson.gz"),
        LOGCAT("diagnostics/logcat", "logcat", ".log.gz"),
        RUNTIME("diagnostics/runtime", "runtime", ".log"),
        CRASH("diagnostics/crash", "crash", ".log")
    }

    enum class Profile(val repo: String) {
        DEVELOPMENT("SurveyExports-Dev"), PRODUCTION("SurveyExports");

        fun matches(config: GitHubUploader.GitHubConfig): Boolean =
            config.owner.equals(OWNER, ignoreCase = true) &&
                config.repo.equals(repo, ignoreCase = true) &&
                config.branch == BRANCH && config.pathPrefix.isEmpty()
    }

    @Serializable
    data class Route(
        val id: String,
        val kind: Kind,
        val surveyId: String?,
        val profile: Profile,
        val owner: String,
        val repo: String,
        val branch: String,
        val pathPrefix: String,
        val path: String,
        val size: Long,
        val fingerprint: String,
        val diagnosticsRedacted: Boolean = false,
    ) {
        val basename: String get() = path.substringAfterLast('/')

        fun matches(config: GitHubUploader.GitHubConfig): Boolean =
            profile.matches(config) && owner.equals(config.owner, true) && repo.equals(config.repo, true) &&
                branch == config.branch && pathPrefix == config.pathPrefix
    }

    data class Entry(val route: Route, val routeFingerprint: String, val directory: File) {
        val payload: File get() = File(directory, route.basename)
        fun matchesPayload(): Boolean =
            payload.isFile && payload.canonicalFile == payload.absoluteFile &&
                payload.length() == route.size && hash(payload) == route.fingerprint

        fun hasReceipt(): Boolean {
            val receipt = File(directory, RECEIPT)
            if (!receipt.exists()) return false
            check(receipt.isFile && receipt.readText() == routeFingerprint) { "Invalid delivery receipt." }
            return true
        }

        fun recordReceipt() = writeAtomically(File(directory, RECEIPT), routeFingerprint)

        fun hasRemoteConflict(): Boolean {
            val conflict = File(directory, "conflict")
            if (!conflict.exists()) return false
            check(conflict.isFile && conflict.readText() == routeFingerprint) { "Invalid conflict evidence." }
            return true
        }

        fun recordRemoteConflict() = writeAtomically(File(directory, "conflict"), routeFingerprint)

        /** Keep a route-bound index of exact basenames after the survey payload is retired. */
        fun voiceReferences(): Set<String> = synchronized(lock) {
            check(route.kind == Kind.SURVEY)
            val index = File(directory, VOICE_REFERENCES)
            if (!Files.notExists(index.toPath(), NOFOLLOW_LINKS)) {
                check(index.isFile && index.canonicalFile == index.absoluteFile)
                val stored = index.readText()
                val references = stored.substringAfter('\n')
                check(stored.substringBefore('\n') == hash("$routeFingerprint\n$references")) {
                    "Invalid finalized voice references."
                }
                return@synchronized references.lineSequence().filter(String::isNotEmpty).toSet()
            }
            if (!payload.exists()) return@synchronized emptySet()
            check(matchesPayload()) { "Cannot recover references from an invalid survey payload." }
            val survey = Json.parseToJsonElement(payload.readText()) as JsonObject
            check((survey["survey_id"] as? JsonPrimitive)?.content.equals(route.surveyId, ignoreCase = true))
            val references = (survey["voice_files"] as? JsonArray).orEmpty().mapNotNull { item ->
                ((item as? JsonObject)?.get("file") as? JsonPrimitive)?.content
                    ?.takeIf { basenamePattern.matches(it) && it.endsWith(".wav") }
            }.toSet()
            val encoded = references.sorted().joinToString("\n")
            syncDirectory(directory.parentFile!!)
            syncDirectory(directory.parentFile!!.parentFile!!)
            writeAtomically(index, "${hash("$routeFingerprint\n$encoded")}\n$encoded")
            references
        }

        /** Keep the route and delivery receipt as a tombstone across restarts. */
        fun cleanup(confirmed: () -> Boolean = ::hasReceipt): Boolean = synchronized(lock) {
            if (!confirmed() || readDirectory(directory, route.id)?.routeFingerprint != routeFingerprint) {
                return@synchronized false
            }
            // A receipt rename may be visible even if its directory sync failed. Establish the
            // durable package and receipt before deleting the only local copy.
            syncDirectory(directory)
            syncDirectory(directory.parentFile!!)
            syncDirectory(directory.parentFile!!.parentFile!!)
            if (payload.exists() && (!matchesPayload() || !payload.delete())) return@synchronized false
            if (route.kind != Kind.VOICE) return@synchronized true
            val metadata = listOf(VOICE_METADATA, SurveyRecordings.METADATA_CHECKSUM, SurveyRecordings.OUTCOME)
            val interruptedOutcomes = directory.listFiles().orEmpty().filter {
                it.name.startsWith(SurveyRecordings.OUTCOME_TEMP_PREFIX) && it.name.endsWith(".part")
            }
            (metadata.map { File(directory, it) } + interruptedOutcomes).all { !it.exists() || it.delete() }
        }
    }

    /** Captures one device tag per UUID even if Android ID availability changes mid-survey. */
    fun paths(context: Context, surveyId: String, proposed: DeviceUploadTag? = null): V2ArtifactPaths =
        V2ArtifactPaths(surveyId, frozenDeviceTag(context, surveyId, proposed))

    fun frozenDeviceTag(
        context: Context,
        surveyId: String,
        proposed: DeviceUploadTag? = null,
        persist: Boolean = true,
    ): DeviceUploadTag {
        val normalized = V2ArtifactPaths(surveyId, DeviceUploadTag("validation")).surveyUuid
        val tags = File(root, DEVICE_METADATA_DIRECTORY)
        val frozen = File(tags, normalized)
        val tag = synchronized(deviceLock) {
            cachedDeviceTags.getOrPut(frozen) {
                if (frozen.exists()) DeviceUploadTag(frozen.readText())
                else proposed ?: DeviceUploadTagProvider.from(context)
            }
        }
        if (persist && !frozen.exists()) synchronized(deviceWriteLock) {
            if (!frozen.exists()) {
                V2ArtifactPaths(surveyId, tag)
                Files.createDirectories(tags.toPath())
                writeAtomically(frozen, tag.value)
            }
        }
        return tag
    }

    fun stage(
        id: String,
        kind: Kind,
        surveyId: String?,
        profile: Profile,
        path: V2ArtifactPaths.ArtifactPath,
        voiceMetadata: String? = null,
        diagnosticsRedacted: Boolean = false,
        rebuildUnsealed: Boolean = false,
        write: (FileOutputStream) -> Unit,
    ): Entry = synchronized(lock) {
        require(basenamePattern.matches(path.basename) && ".." !in path.basename &&
            path.basename == path.relativePath.substringAfterLast('/')) { "Invalid staged artifact basename." }
        val target = directory(id)
        recoverInterruptedPublication(id)
        if (!Files.notExists(target.toPath(), NOFOLLOW_LINKS)) {
            val existing = checkNotNull(read(id)) { "Existing artifact has invalid route evidence." }
            check(existing.route.kind == kind && existing.route.surveyId == surveyId &&
                existing.route.profile == profile && existing.route.path == path.relativePath &&
                existing.route.diagnosticsRedacted == diagnosticsRedacted
            ) { "Existing artifact has a different immutable route." }
            syncDirectory(root)
            syncDirectory(root.parentFile!!)
            return@synchronized existing
        }
        if (kind == Kind.VOICE) {
            check(!hasReservedRoute(surveyKey(checkNotNull(surveyId)))) {
                "The survey already has an immutable export."
            }
        }
        Files.createDirectories(root.toPath())
        syncDirectory(root.parentFile!!)
        val temporary = File(root, ".stage-$id")
        if ((kind == Kind.SURVEY || rebuildUnsealed) && isUnsealed(temporary)) {
            // This package never acquired a route and was never eligible for delivery.
            // Preserve its bytes outside publication and recovery scans.
            val abandoned = File(root, ".incomplete-$id-${newId()}")
            Files.move(temporary.toPath(), abandoned.toPath(), ATOMIC_MOVE)
            syncDirectory(root)
        }
        check(temporary.mkdir()) { "Interrupted artifact staging has incomplete or invalid evidence." }
        try {
            syncDirectory(root)
            val payload = File(temporary, path.basename)
            FileOutputStream(payload).use { output ->
                write(output)
                output.fd.sync()
            }
            val route = Route(
                id, kind, surveyId, profile, OWNER, profile.repo, BRANCH, "",
                path.relativePath, payload.length(), hash(payload), diagnosticsRedacted,
            )
            check(valid(route)) { "Invalid immutable artifact route." }
            val encoded = Json.encodeToString(Route.serializer(), route)
            val checksum = hash(encoded)
            if (kind == Kind.VOICE) {
                writeAtomically(File(temporary, VOICE_METADATA), requireNotNull(voiceMetadata))
                writeAtomically(File(temporary, SurveyRecordings.METADATA_CHECKSUM), hash(voiceMetadata))
            }
            writeAtomically(File(temporary, MANIFEST), "$checksum\n$encoded")
            publishDirectory(temporary, target)
            Entry(route, checksum, target)
        } finally {
            // A fully sealed transaction survives a failed publish for the next recovery pass.
            if (!File(temporary, MANIFEST).exists()) temporary.deleteRecursively()
        }
    }

    fun recoverInterruptedPublications() = synchronized(lock) {
        interruptedIds().forEach { id ->
            runCatching { recoverInterruptedPublication(id) }
                .onFailure { android.util.Log.w("V2ArtifactStore", "Interrupted staging retained: $id", it) }
        }
    }

    fun interruptedIds(): List<String> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith(".stage-") }
        .map { it.name.removePrefix(".stage-") }
        .filter(idPattern::matches)

    private fun recoverInterruptedPublication(id: String) {
        val target = directory(id)
        if (!Files.notExists(target.toPath(), NOFOLLOW_LINKS)) return
        val temporary = File(root, ".stage-$id")
        val sealed = readDirectory(temporary, id) ?: return
        if (!sealed.matchesPayload() ||
            (sealed.route.kind == Kind.VOICE && !File(temporary, VOICE_METADATA).isFile)
        ) return
        syncDirectory(temporary)
        publishDirectory(temporary, target)
    }

    private fun publishDirectory(temporary: File, target: File) {
        Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
        syncDirectory(root)
        syncDirectory(root.parentFile!!)
    }

    fun read(id: String): Entry? = readDirectory(directory(id), id)

    fun artifactIds(): List<String> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name != DEVICE_METADATA_DIRECTORY && idPattern.matches(it.name) }
        .map { it.name }

    fun hasReservedRoute(id: String): Boolean = synchronized(lock) {
        val target = directory(id)
        val pending = File(root, ".stage-$id")
        !Files.notExists(target.toPath(), NOFOLLOW_LINKS) ||
            (!Files.notExists(pending.toPath(), NOFOLLOW_LINKS) && !isUnsealed(pending))
    }

    fun voiceCandidates(): List<Entry> = synchronized(lock) {
        recoverInterruptedPublications()
        artifactIds().mapNotNull(::read).filter { it.route.kind == Kind.VOICE }
    }

    /** Inspect sealed packages too, so corrupt evidence cannot silently disappear from an export. */
    fun voiceEntries(surveyId: String): List<Entry> = synchronized(lock) {
        (artifactIds() + interruptedIds()).distinct().sorted().mapNotNull { id ->
            val entry = read(id)
            if (entry != null) {
                return@mapNotNull entry.takeIf { it.route.kind == Kind.VOICE && it.route.surveyId == surveyId }
            }

            val staging = File(root, ".stage-$id")
            val sealed = readDirectory(staging, id)
            if (sealed?.route?.kind == Kind.VOICE && sealed.route.surveyId == surveyId) {
                recoverInterruptedPublication(id)
                return@mapNotNull checkNotNull(read(id)) { "A sealed WAV could not be recovered." }
            }

            val possiblePackages = listOf(directory(id), staging).filter {
                it.isDirectory && (it != staging || !isUnsealed(it))
            }
            check(possiblePackages.none { candidate ->
                candidate.listFiles().orEmpty().any {
                    it.name.endsWith("_$id.wav") && "_${surveyId}_" in it.name
                }
            }) { "A staged recording has missing or invalid V2 route evidence." }
            null
        }
    }

    fun isVoiceReferenced(voice: Route): Boolean {
        val survey = read(surveyKey(checkNotNull(voice.surveyId))) ?: return false
        return survey.route.kind == Kind.SURVEY && survey.route.profile == voice.profile &&
            voice.basename in survey.voiceReferences()
    }

    private fun isUnsealed(directory: File): Boolean =
        Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) && directory.canonicalFile == directory.absoluteFile &&
            Files.notExists(File(directory, MANIFEST).toPath(), NOFOLLOW_LINKS) &&
            Files.notExists(File(directory, RECEIPT).toPath(), NOFOLLOW_LINKS) &&
            Files.notExists(File(directory, "conflict").toPath(), NOFOLLOW_LINKS)


    fun recoverAndRead(id: String): Entry? = synchronized(lock) {
        recoverInterruptedPublication(id)
        read(id)
    }

    private fun directory(id: String): File {
        require(idPattern.matches(id)) { "Invalid V2 artifact ID." }
        return File(root, id)
    }

    companion object {
        const val OWNER = "ishizuki-tech"
        const val BRANCH = "main"
        const val VOICE_METADATA = "voice.meta.json"
        private const val DEVICE_METADATA_DIRECTORY = "device"
        private const val MANIFEST = "route"
        private const val RECEIPT = "delivered"
        private const val VOICE_REFERENCES = "voice.references"
        private val lock = Any()
        private val deviceLock = Any()
        private val deviceWriteLock = Any()
        private val cachedDeviceTags = mutableMapOf<File, DeviceUploadTag>()
        private val idPattern = Regex("[a-z0-9-]{1,80}")
        private val basenamePattern = Regex("[A-Za-z0-9._-]{1,255}")
        private val stampPattern = Regex("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}Z")
        val profile: Profile
            get() = if (BuildConfig.GH_UPLOAD_MODE == "local") Profile.DEVELOPMENT else Profile.PRODUCTION

        fun surveyKey(surveyId: String): String = "survey-${surveyId.lowercase(java.util.Locale.ROOT)}"

        fun newId(): String = UUID.randomUUID().toString()

        fun stableId(value: String): String = hash(value)

        private fun valid(route: Route): Boolean {
            val name = route.basename
            val stamp = name.take(20)
            if (route.owner != OWNER || route.repo != route.profile.repo ||
                route.branch != BRANCH || route.pathPrefix.isNotEmpty() ||
                !idPattern.matches(route.id) || !basenamePattern.matches(name) || ".." in name ||
                !stampPattern.matches(stamp) || route.size <= 0 ||
                !name.startsWith("${stamp}_${route.kind.type}_") || !name.endsWith(route.kind.extension) ||
                !SurveyUploadIdempotency.isPayloadFingerprint(route.fingerprint) ||
                (route.diagnosticsRedacted && route.kind !in setOf(Kind.SURVEY_TRACE, Kind.WHISPER_TRACE, Kind.SLM)) ||
                route.path != "${stamp.take(10)}/${route.kind.directory}/$name"
            ) return false
            val surveyId = route.surveyId
            if (surveyId == null) {
                return route.kind in setOf(Kind.LOGCAT, Kind.RUNTIME, Kind.CRASH) && name.endsWith("_no-survey_${route.id}${route.kind.extension}")
            }
            val paths = runCatching { V2ArtifactPaths(surveyId, DeviceUploadTag("validation")) }.getOrNull()
                ?: return false
            if (paths.surveyUuid != surveyId || paths.startStampUtc != stamp) return false
            return if (route.kind == Kind.SURVEY) {
                route.id == surveyKey(surveyId) && name.endsWith("_$surveyId.json")
            } else {
                "_${surveyId}_" in name && name.endsWith("_${route.id}${route.kind.extension}")
            }
        }

        // Atomic rename provides visibility, while fsync of the containing directory commits
        // its name. Android's filesystem and storage hardware still govern power-loss recovery.
        internal fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), READ).use { it.force(true) }
        }

        private fun readDirectory(directory: File, id: String): Entry? {
            if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) return null
            val manifest = File(directory, MANIFEST)
            if (!manifest.isFile || manifest.canonicalFile != manifest.absoluteFile || manifest.length() > 8192L) return null
            return runCatching {
                val stored = manifest.readText()
                val encoded = stored.substringAfter('\n', "")
                val checksum = hash(encoded)
                check(stored.substringBefore('\n') == checksum)
                val route = Json.decodeFromString(Route.serializer(), encoded)
                check(route.id == id && valid(route))
                Entry(route, checksum, directory)
            }.getOrNull()
        }

        internal fun writeAtomically(target: File, value: String, tempPrefix: String = ".write-") {
            val temporary = File.createTempFile(tempPrefix, ".part", target.parentFile)
            try {
                FileOutputStream(temporary).use {
                    it.write(value.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
                syncDirectory(target.parentFile!!)
            } finally {
                temporary.delete()
            }
        }

        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().hex()
        }

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
