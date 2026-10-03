package com.negi.survey.net

/**
 * Resolves the immutable GitHub upload route embedded in an APK.
 *
 * Diagnostics preferences may provide a credential, but never replace this route. Existing
 * WorkManager requests remain independent because their input routing stays authoritative.
 */
internal object GitHubUploadTargetPolicy {
    data class BuildTarget(
        val localBuild: Boolean,
        val owner: String,
        val repo: String,
        val branch: String,
        val pathPrefix: String,
        val token: String,
    )

    fun resolve(
        buildTarget: BuildTarget,
        diagnosticsCredential: String = "",
    ): GitHubUploader.GitHubConfig? {
        val route = resolveRoute(buildTarget) ?: return null
        val token = diagnosticsCredential.trim().ifBlank { buildTarget.token.trim() }
        if (token.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = route.owner,
            repo = route.repo,
            token = token,
            branch = route.branch,
            pathPrefix = route.pathPrefix,
        )
    }

    fun modeLabel(localBuild: Boolean): String = if (localBuild) "local" else "production"

    /** Existing WorkManager routing is immutable, but local builds may never execute production work. */
    fun allowsCapturedRouting(localBuild: Boolean, owner: String, repo: String): Boolean =
        !localBuild || !isProductionDestination(owner.trim(), repo.trim())

    /**
     * Reports the configured route separately from the credential needed to execute uploads.
     * [effectiveConfig] is used only as credential-availability evidence.
     */
    fun startupDiagnostic(
        buildTarget: BuildTarget,
        effectiveConfig: GitHubUploader.GitHubConfig?,
    ): String {
        val mode = modeLabel(buildTarget.localBuild)
        val route = resolveRoute(buildTarget)
            ?: return "GitHub upload mode: $mode\n" +
                "GitHub upload target: disabled\n" +
                "Reason: ${disabledRouteReason(buildTarget)}"

        return "GitHub upload mode: $mode\n" +
            "GitHub upload target: ${route.owner}/${route.repo}\n" +
            "GitHub upload branch: ${route.branch}\n" +
            "GitHub upload path prefix: ${route.pathPrefix.ifBlank { "(none)" }}\n" +
            "GitHub credentials: ${if (effectiveConfig == null) "unavailable" else "available"}"
    }

    private data class Route(
        val owner: String,
        val repo: String,
        val branch: String,
        val pathPrefix: String,
    )

    private fun resolveRoute(buildTarget: BuildTarget): Route? {
        var owner = buildTarget.owner.trim()
        var repo = buildTarget.repo.trim()

        if (repo.contains('/')) {
            val inferredOwner = repo.substringBefore('/').trim()
            val inferredRepo = repo.substringAfterLast('/').trim()
            if (owner.isBlank()) owner = inferredOwner
            repo = inferredRepo
        }

        if (
            owner.isBlank() ||
            repo.isBlank() ||
            owner.any(Char::isWhitespace) ||
            repo.any(Char::isWhitespace)
        ) {
            return null
        }

        if (
            buildTarget.localBuild &&
            isProductionDestination(owner, repo)
        ) {
            return null
        }

        return Route(
            owner = owner,
            repo = repo,
            branch = buildTarget.branch.trim().ifBlank { "main" },
            pathPrefix = buildTarget.pathPrefix.trim().trim('/'),
        )
    }

    private fun disabledRouteReason(buildTarget: BuildTarget): String =
        if (
            buildTarget.localBuild &&
            (
                buildTarget.repo.isBlank() ||
                    isProductionDestination(buildTarget.owner.trim(), buildTarget.repo.trim())
            )
        ) {
            "local build has no explicit development destination"
        } else {
            "no usable GitHub upload routing"
        }

    private const val PRODUCTION_OWNER = "ishizuki-tech"
    private const val PRODUCTION_REPOSITORY = "SurveyExports"

    private fun isProductionDestination(owner: String, repo: String): Boolean =
        owner.equals(PRODUCTION_OWNER, ignoreCase = true) &&
            repo.equals(PRODUCTION_REPOSITORY, ignoreCase = true)
}
