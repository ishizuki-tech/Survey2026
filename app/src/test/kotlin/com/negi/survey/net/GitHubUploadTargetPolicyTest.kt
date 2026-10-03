package com.negi.survey.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUploadTargetPolicyTest {
    @Test
    fun productionDefaultUsesSurveyExportsMain() {
        val config = resolve(local = false, owner = "ishizuki-tech", repo = "SurveyExports")

        requireNotNull(config)
        assertEquals("ishizuki-tech", config.owner)
        assertEquals("SurveyExports", config.repo)
        assertEquals("main", config.branch)
    }

    @Test
    fun localDefaultUsesSharedDevelopmentDestination() {
        val config = resolve(local = true, owner = "ishizuki-tech", repo = "SurveyExports-Dev")

        requireNotNull(config)
        assertEquals("ishizuki-tech", config.owner)
        assertEquals("SurveyExports-Dev", config.repo)
        assertEquals("main", config.branch)
    }

    @Test
    fun localMissingRepositoryDoesNotResolveToProductionFallback() {
        assertNull(resolve(local = true, owner = "ishizuki-tech", repo = ""))
    }

    @Test
    fun localBuildCannotUseTheProductionDestination() {
        assertNull(resolve(local = true, owner = "ishizuki-tech", repo = "SurveyExports"))
    }

    @Test
    fun legacyCapturedProductionRouteIsRejectedOnlyInLocalMode() {
        assertFalse(
            GitHubUploadTargetPolicy.allowsCapturedRouting(
                localBuild = true,
                owner = "ishizuki-tech",
                repo = "SurveyExports",
            ),
        )
        assertTrue(
            GitHubUploadTargetPolicy.allowsCapturedRouting(
                localBuild = false,
                owner = "ishizuki-tech",
                repo = "SurveyExports",
            ),
        )
        assertTrue(
            GitHubUploadTargetPolicy.allowsCapturedRouting(
                localBuild = true,
                owner = "dev-owner",
                repo = "dev-exports",
            ),
        )
    }

    @Test
    fun diagnosticsCredentialCannotOverrideBuildRouting() {
        val config = resolve(
            local = false,
            owner = "ishizuki-tech",
            repo = "SurveyExports",
            diagnosticsCredential = "diagnostic-token",
        )

        requireNotNull(config)
        assertEquals("ishizuki-tech", config.owner)
        assertEquals("SurveyExports", config.repo)
        assertEquals("diagnostic-token", config.token)
    }

    @Test
    fun ownerRepoFormNormalizesConsistently() {
        val config = resolve(local = true, owner = "", repo = "dev-owner/dev-exports", prefix = "/field/")

        requireNotNull(config)
        assertEquals("dev-owner", config.owner)
        assertEquals("dev-exports", config.repo)
        assertEquals("field", config.pathPrefix)
    }

    @Test
    fun startupDiagnosticExplainsDisabledLocalUploadWithoutExposingToken() {
        val target = GitHubUploadTargetPolicy.BuildTarget(
            localBuild = true,
            owner = "",
            repo = "",
            branch = "main",
            pathPrefix = "",
            token = "secret-token",
        )
        val message = GitHubUploadTargetPolicy.startupDiagnostic(target, effectiveConfig = null)

        assertEquals(
            "GitHub upload mode: local\n" +
                "GitHub upload target: disabled\n" +
                "Reason: local build has no explicit development destination",
            message,
        )
        assertFalse(message.contains("secret-token"))
    }

    @Test
    fun startupDiagnosticTreatsProductionTargetAsMissingLocalDevelopmentDestination() {
        val target = GitHubUploadTargetPolicy.BuildTarget(
            localBuild = true,
            owner = "ishizuki-tech",
            repo = "SurveyExports",
            branch = "main",
            pathPrefix = "",
            token = "secret-token",
        )

        val message = GitHubUploadTargetPolicy.startupDiagnostic(target, effectiveConfig = null)

        assertEquals(
            "GitHub upload mode: local\n" +
                "GitHub upload target: disabled\n" +
                "Reason: local build has no explicit development destination",
            message,
        )
    }

    @Test
    fun startupDiagnosticShowsProductionRouteWhenCredentialsAreUnavailable() {
        val message = GitHubUploadTargetPolicy.startupDiagnostic(
            target(local = false, owner = "ishizuki-tech", repo = "SurveyExports", token = ""),
            effectiveConfig = null,
        )

        assertTrue(message.contains("GitHub upload target: ishizuki-tech/SurveyExports"))
        assertTrue(message.contains("GitHub upload branch: main"))
        assertTrue(message.contains("GitHub credentials: unavailable"))
    }

    @Test
    fun startupDiagnosticShowsLocalDevelopmentRouteWhenCredentialsAreUnavailable() {
        val message = GitHubUploadTargetPolicy.startupDiagnostic(
            target(local = true, owner = "dev-owner", repo = "dev-exports", token = ""),
            effectiveConfig = null,
        )

        assertTrue(message.contains("GitHub upload target: dev-owner/dev-exports"))
        assertTrue(message.contains("GitHub credentials: unavailable"))
    }

    @Test
    fun startupDiagnosticNeverIncludesAvailableCredential() {
        val secret = "secret-token"
        val message = GitHubUploadTargetPolicy.startupDiagnostic(
            target(local = false, owner = "ishizuki-tech", repo = "SurveyExports", token = secret),
            effectiveConfig = resolve(local = false, owner = "ishizuki-tech", repo = "SurveyExports"),
        )

        assertTrue(message.contains("GitHub credentials: available"))
        assertFalse(message.contains(secret))
    }

    private fun resolve(
        local: Boolean,
        owner: String,
        repo: String,
        branch: String = "main",
        prefix: String = "",
        diagnosticsCredential: String = "",
    ): GitHubUploader.GitHubConfig? =
        GitHubUploadTargetPolicy.resolve(
            GitHubUploadTargetPolicy.BuildTarget(
                localBuild = local,
                owner = owner,
                repo = repo,
                branch = branch,
                pathPrefix = prefix,
                token = "build-token",
            ),
            diagnosticsCredential = diagnosticsCredential,
        )

    private fun target(
        local: Boolean,
        owner: String,
        repo: String,
        token: String,
    ): GitHubUploadTargetPolicy.BuildTarget =
        GitHubUploadTargetPolicy.BuildTarget(
            localBuild = local,
            owner = owner,
            repo = repo,
            branch = "main",
            pathPrefix = "",
            token = token,
        )
}
