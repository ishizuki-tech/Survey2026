package com.negi.survey.net

import java.io.File

/**
 * Enumerates only atomically-published diagnostic trace archives.
 *
 * Trace contents are deliberately never inspected here. The filename is the upload identity and
 * the still-open NDJSON file is not an upload candidate.
 */
internal object DiagnosticTraceArtifacts {
    enum class Stream(val suffix: String, val directory: String) {
        SURVEY("_survey.ndjson.gz", "diagnostics/survey_traces"),
        WHISPER("_whisper.ndjson.gz", "diagnostics/whisper_trace")
    }

    data class Candidate(val file: File, val stream: Stream)

    fun discover(filesDir: File, surveyId: String? = null): List<Candidate> {
        val shortSurveyId = surveyId
            ?.trim()
            ?.take(8)
            ?.takeIf { it.isNotBlank() }

        return Stream.entries.flatMap { stream ->
            val directory = File(filesDir, stream.directory)
            directory.listFiles().orEmpty()
                .asSequence()
                .filter { it.isFile && it.length() > 0L }
                .filter { it.name.endsWith(stream.suffix) }
                .filter { candidate ->
                    shortSurveyId == null || candidate.name.contains("_${shortSurveyId}_")
                }
                .map { Candidate(it, stream) }
                .toList()
        }.sortedBy { it.file.absolutePath }
    }

    fun remoteRelativePath(candidate: Candidate): String =
        SurveyUploadWork.remoteRelativePath(candidate.file.name)
}
