/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: IntroScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Monotone (black/gray/white) intro screen for the survey flow.
 *
 *  Update (2026-01):
 *   • Added a "Selected configuration details" panel.
 *   • Supports long details with expand/collapse.
 *
 *  Debug/UX fix (2026-01):
 *   • Fix: "Show more" appeared to do nothing when expanded content was clipped off-screen.
 *     - Card content is now height-clamped + vertically scrollable.
 *   • Fix: Show more button now appears only if it would actually expand something.
 *     - Uses onTextLayout overflow detection.
 *   • UX: When toggling "Show more", auto-scroll the details panel into view.
 *     - Uses BringIntoViewRequester.
 *   • Added test tags for UI testing.
 *
 *  Strengthen (2026-01):
 *   • Treat CancellationException correctly (do not show as error).
 *   • BringIntoView runs after layout settles (delay one tick).
 *   • animateContentSize only while collapsed to reduce jank on huge text.
 *   • Stable meta rendering order (sorted).
 *   • Retry button for failed detail resolution.
 *
 *  Fix (2026-02-22):
 *   • Debug crash button is debug-only again (BuildConfig.DEBUG).
 *   • Place debug crash button at bottom-end to avoid UI interference.
 * =====================================================================
 */

package com.negi.survey.screens

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.negi.survey.BuildConfig
import com.negi.survey.ui.theme.ActionBlue
import com.negi.survey.ui.theme.monotoneColors
import com.negi.survey.vm.UploadStatus
import com.negi.survey.vm.UploadStatusDetails
import com.negi.survey.vm.UploadStatusEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "IntroScreen"
private const val BRING_INTO_VIEW_DELAY_MS = 24L

/**
 * UI-facing descriptor of a survey configuration option.
 *
 * NOTE:
 * - Details are resolved via onResolveConfigDetails(configId).
 */
data class ConfigOptionUi(
    val id: String,
    val label: String,
    val description: String,
)

/**
 * Resolved details for a selected config.
 *
 * @param title Display title (e.g., file name or config name)
 * @param summary Short description (1-2 lines) shown near the top
 * @param longText Long content (e.g., YAML excerpt / normalized config text)
 * @param meta Key/value important knobs (backend, model, maxTokens, etc.)
 */
data class ConfigDetails(
    val title: String,
    val summary: String,
    val longText: String,
    val meta: Map<String, String> = emptyMap()
)

/**
 * Intro screen rendered in a strict grayscale palette.
 *
 * @param onResolveConfigDetails Called when selected config id changes.
 *        Implement this in your ViewModel/Repository to load YAML or config and return display-ready ConfigDetails.
 */
@Composable
fun IntroScreen(
    options: List<ConfigOptionUi>,
    defaultOptionId: String? = null,
    uploadStatus: UploadStatus,
    onUploadStatusActive: () -> Unit,
    onStart: (ConfigOptionUi) -> Unit,
    restartEpoch: Long = 0L,
    showRestart: Boolean = false,
    isRestarting: Boolean = false,
    onRestart: (() -> Unit)? = null,
    onResolveConfigDetails: suspend (configId: String) -> ConfigDetails,
    onResolveUploadStatusDetails: suspend () -> UploadStatusDetails,
    onClearCompletedSurveys: suspend () -> Unit,
) {
    require(options.isNotEmpty()) { "IntroScreen requires at least one ConfigOptionUi." }

    val bgBrush = animatedMonotoneBackground()
    LaunchedEffect(Unit) { onUploadStatusActive() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .background(bgBrush)
            .semantics { contentDescription = "Survey intro screen" }
            .testTag("IntroScreenRoot")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            IntroCardMono(
                title = "Survey Test App",
                options = options,
                defaultOptionId = defaultOptionId,
                uploadStatus = uploadStatus,
                onStart = onStart,
                restartEpoch = restartEpoch,
                showRestart = showRestart,
                isRestarting = isRestarting,
                onRestart = onRestart,
                onResolveConfigDetails = onResolveConfigDetails,
                onResolveUploadStatusDetails = onResolveUploadStatusDetails,
                onClearCompletedSurveys = onClearCompletedSurveys,
            )
        }
    }
}

/* ──────────────────────────── Card & Typography ─────────────────────────── */

@Composable
private fun IntroCardMono(
    title: String,
    options: List<ConfigOptionUi>,
    defaultOptionId: String?,
    uploadStatus: UploadStatus,
    onStart: (ConfigOptionUi) -> Unit,
    restartEpoch: Long,
    showRestart: Boolean,
    isRestarting: Boolean,
    onRestart: (() -> Unit)?,
    onResolveConfigDetails: suspend (configId: String) -> ConfigDetails,
    onResolveUploadStatusDetails: suspend () -> UploadStatusDetails,
    onClearCompletedSurveys: suspend () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val corner = 20.dp

    /** Readability palette (shared light/dark monotone tokens). */
    val mono = monotoneColors()
    val textPrimary = mono.textPrimary
    val textSecondary = mono.textSecondary
    val textMuted = mono.textMuted
    val textHint = mono.textHint

    /** A slightly stronger card scrim to lift text from the animated background. */
    val cardBg = mono.cardBg.copy(alpha = 0.86f)

    val optionIds = remember(options) { options.map { it.id } }
    val optionIdSet = remember(optionIds) { optionIds.toHashSet() }
    val defaultIdNorm = remember(defaultOptionId) { defaultOptionId?.trim().orEmpty() }

    var selectedId by remember(optionIds, defaultIdNorm, restartEpoch) {
        mutableStateOf(
            defaultIdNorm
                .takeIf { it.isNotBlank() && optionIdSet.contains(it) }
                ?: options.first().id
        )
    }

    LaunchedEffect(optionIds, restartEpoch) {
        if (!optionIdSet.contains(selectedId)) {
            selectedId = options.first().id
        }
    }

    val selectedOption = options.firstOrNull { it.id == selectedId } ?: options.first()

    // ───────────────────── Resolve current config details ─────────────────────

    val resolveDetails by rememberUpdatedState(newValue = onResolveConfigDetails)

    var detailsState by remember(restartEpoch, selectedId) {
        mutableStateOf<ResolvedDetailsState>(ResolvedDetailsState.Loading)
    }

    /** Manual reload epoch for Retry. */
    var detailsReloadEpoch by remember(selectedId, restartEpoch) { mutableLongStateOf(0L) }

    LaunchedEffect(restartEpoch, selectedId, detailsReloadEpoch) {
        val startMs = System.currentTimeMillis()
        detailsState = ResolvedDetailsState.Loading

        detailsState = try {
            val d = resolveDetails(selectedId)
            val dt = System.currentTimeMillis() - startMs
            Log.d(TAG, "Resolved config details: id=$selectedId in ${dt}ms")
            ResolvedDetailsState.Ready(d)
        } catch (ce: CancellationException) {
            /** Cancellation should not be surfaced as an error state. */
            Log.d(TAG, "Resolve cancelled: id=$selectedId")
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to resolve config details: id=$selectedId", t)
            ResolvedDetailsState.Error(t)
        }
    }

    val screen = LocalConfiguration.current
    val screenH = screen.screenHeightDp.dp
    val screenW = screen.screenWidthDp.dp

    /** Responsive clamp: keep it readable on phones + not absurd on tablets. */
    val cardMaxWidth = min(760.dp, max(520.dp, screenW * 0.92f))

    ElevatedCard(
        shape = RoundedCornerShape(corner),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardBg,
            contentColor = textPrimary
        ),
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .widthIn(max = cardMaxWidth)
            .wrapContentHeight()
            .wrapContentWidth()
            .drawBehind {
                val dark = mono.cardBorderDark.copy(alpha = 0.65f)
                val mid = mono.cardBorderMid.copy(alpha = 0.35f)
                val light = mono.cardBorderLight.copy(alpha = 0.18f)
                val sweep = Brush.sweepGradient(
                    0f to dark,
                    0.25f to mid,
                    0.5f to light,
                    0.75f to mid,
                    1f to dark
                )
                drawRoundRect(
                    brush = sweep,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(corner.toPx(), corner.toPx())
                )
            }
            .testTag("IntroCard")
    ) {
        val scroll = rememberScrollState()

        Column(
            modifier = Modifier
                /** Clamp the whole card height so expanded details remain reachable. */
                .heightIn(max = screenH * 0.82f)
                .verticalScroll(scroll)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GradientHeadlineMono(
                text = title,
                colorTop = mono.headlineTop,
                colorBottom = mono.headlineBottom
            )

            Spacer(Modifier.padding(top = 14.dp))

            Text(
                text = "Device ID: ${uploadStatus.deviceTag}",
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondary,
                modifier = Modifier.align(Alignment.Start)
            )
            Text(
                text = "Build: ${BuildConfig.GIT_COMMIT_SHA} ${BuildConfig.BUILD_TIMESTAMP}",
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondary,
                modifier = Modifier
                    .align(Alignment.Start)
                    .testTag("BuildTimestamp")
            )

            Spacer(Modifier.padding(top = 14.dp))

            UploadStatusSummary(
                uploadStatus = uploadStatus,
                onResolveUploadStatusDetails = onResolveUploadStatusDetails,
                onClearCompletedSurveys = onClearCompletedSurveys,
                titleColor = textMuted,
                countColor = textSecondary,
                actionColor = mono.actionText
            )

            Spacer(Modifier.padding(top = 16.dp))

            Text(
                text = "Select survey configuration",
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.2.sp),
                color = textMuted,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ConfigSelectorColumn"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                options.forEach { option ->
                    key(option.id) {
                        MonoConfigOptionChip(
                            option = option,
                            selected = option.id == selectedId,
                            onClick = {
                                if (selectedId != option.id) {
                                    Log.d(TAG, "Config selected: ${selectedId} -> ${option.id}")
                                    selectedId = option.id
                                }
                            },
                            textPrimary = textPrimary,
                            textSecondary = textHint
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                    }
                }
            }

            // ───────────────────── Selected details panel ─────────────────────
            Spacer(Modifier.padding(top = 10.dp))

            SelectedConfigDetailsMono(
                configId = selectedId,
                optionLabel = selectedOption.label,
                optionDescription = selectedOption.description,
                state = detailsState,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                textMuted = textMuted,
                onRetry = {
                    Log.d(TAG, "Retry resolve details: id=$selectedId")
                    detailsReloadEpoch = System.currentTimeMillis()
                }
            )

            Spacer(Modifier.padding(top = 12.dp))

            HorizontalDivider(
                thickness = DividerDefaults.Thickness,
                color = cs.outlineVariant.copy(alpha = 0.22f)
            )

            Spacer(Modifier.padding(top = 16.dp))

            CtaRowMono(
                onStart = { onStart(selectedOption) },
                showRestart = showRestart,
                isRestarting = isRestarting,
                onRestart = onRestart
            )

            // About: version, build, and credits/licences for voices, models and libraries.
            var showAbout by remember { mutableStateOf(false) }
            Spacer(Modifier.padding(top = 4.dp))
            TextButton(
                onClick = { showAbout = true },
                modifier = Modifier.testTag("AboutButton")
            ) {
                Text(
                    text = "About · Credits & licences",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
                    color = textMuted
                )
            }
            if (showAbout) {
                AboutScreen(onClose = { showAbout = false })
            }
        }
    }

}

/**
 * Tappable "Survey Upload Status" summary (title, pending/completed counts,
 * "Show more") that opens [UploadStatusDetailsDialog] with the full
 * pending/completed list. Shared by the config-selection screen and the
 * post-survey Done screen so both show identical upload-status information.
 */
@Composable
internal fun UploadStatusSummary(
    uploadStatus: UploadStatus,
    onResolveUploadStatusDetails: suspend () -> UploadStatusDetails,
    onClearCompletedSurveys: suspend () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = monotoneColors().textMuted,
    countColor: Color = monotoneColors().textSecondary,
    actionColor: Color = monotoneColors().actionText,
) {
    var showUploadStatusDetails by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { showUploadStatusDetails = true }
            .padding(vertical = 4.dp)
            .testTag("UploadStatusArea"),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Survey Upload Status",
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.2.sp),
            color = titleColor,
            modifier = Modifier.align(Alignment.Start)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = "Pending uploads: ${uploadStatus.pendingCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = countColor,
                    modifier = Modifier.align(Alignment.Start)
                )
                Text(
                    text = "Completed uploads: ${uploadStatus.uploadedCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = countColor,
                    modifier = Modifier.align(Alignment.Start)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Show",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
                    color = actionColor
                )
                Text(
                    text = "more",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
                    color = actionColor
                )
            }
        }
    }

    if (showUploadStatusDetails) {
        UploadStatusDetailsDialog(
            onDismiss = { showUploadStatusDetails = false },
            onResolve = onResolveUploadStatusDetails,
            onClearCompleted = onClearCompletedSurveys
        )
    }
}

/**
 * Modal listing pending and completed survey uploads, opened by tapping the
 * Survey Upload Status area.
 *
 * Shows only each entry's run-start timestamp (decoded from its time-ordered
 * survey ID) — never the survey ID, respondent names, or any other content.
 */
private sealed class UploadStatusDetailsState {
    data object Loading : UploadStatusDetailsState()
    data class Ready(val details: UploadStatusDetails) : UploadStatusDetailsState()
    data class Error(val error: Throwable) : UploadStatusDetailsState()
}

@Composable
private fun UploadStatusDetailsDialog(
    onDismiss: () -> Unit,
    onResolve: suspend () -> UploadStatusDetails,
    onClearCompleted: suspend () -> Unit,
) {
    val resolve by rememberUpdatedState(newValue = onResolve)
    val clearCompleted by rememberUpdatedState(newValue = onClearCompleted)
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf<UploadStatusDetailsState>(UploadStatusDetailsState.Loading)
    }

    suspend fun reload() {
        state = UploadStatusDetailsState.Loading
        state = try {
            UploadStatusDetailsState.Ready(resolve())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to resolve upload status details.", t)
            UploadStatusDetailsState.Error(t)
        }
    }

    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Survey Upload Status") },
        text = {
            when (val s = state) {
                is UploadStatusDetailsState.Loading -> {
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                is UploadStatusDetailsState.Error -> {
                    Text(
                        text = "Failed to load upload status: " +
                                (s.error.message ?: s.error::class.java.simpleName),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                is UploadStatusDetailsState.Ready -> {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(scroll)
                            .testTag("UploadStatusDetailsReady")
                    ) {
                        UploadStatusDetailsSection(
                            heading = "Pending (${s.details.pending.size})",
                            entries = s.details.pending
                        )

                        Spacer(Modifier.padding(top = 14.dp))

                        UploadStatusDetailsSection(
                            heading = "Completed (${s.details.completed.size})",
                            entries = s.details.completed,
                            showColumnHeaders = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            /**
             * Both buttons live in this single slot (not dismissButton) so they can share
             * one full-width row, spread to opposite ends — Material3's AlertDialog always
             * clusters confirmButton/dismissButton together otherwise.
             */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clearCompleted()
                            reload()
                        }
                    },
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.testTag("ClearCompletedUploadsButton")
                ) {
                    Text("Clear List")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun UploadStatusDetailsSection(
    heading: String,
    entries: List<UploadStatusEntry>,
    showColumnHeaders: Boolean = false,
) {
    Text(
        text = heading,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp)
    )

    Spacer(Modifier.padding(top = 6.dp))

    if (entries.isEmpty()) {
        Text(
            text = "None",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else if (showColumnHeaders) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Survey ID",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.4f)
            )
            Text(
                text = "Survey End Time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.6f)
            )
        }

        Spacer(Modifier.padding(top = 4.dp))

        entries.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = entry.idPrefix,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(0.4f)
                )
                Text(
                    text = entry.displayTime,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(0.6f)
                )
            }
        }
    } else {
        entries.forEach { entry ->
            Text(
                text = "${entry.idPrefix} \u00b7 ${entry.displayTime}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private sealed class ResolvedDetailsState {
    data object Loading : ResolvedDetailsState()
    data class Ready(val details: ConfigDetails) : ResolvedDetailsState()
    data class Error(val error: Throwable) : ResolvedDetailsState()
}

/**
 * Details panel for the currently selected configuration.
 *
 * Collapsed, it shows only the config id chip and a "Show more" button.
 * Tapping "Show more" reveals the resolved config details (loading/ready/error).
 *
 * Debug fixes applied:
 * - When expanding, auto-scroll this panel into view (after layout settles).
 * - animateContentSize only while collapsed to reduce jank on huge expansions.
 * - Retry action for errors.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SelectedConfigDetailsMono(
    configId: String,
    optionLabel: String,
    optionDescription: String,
    state: ResolvedDetailsState,
    textPrimary: Color,
    textSecondary: Color,
    textMuted: Color,
    onRetry: () -> Unit,
) {
    val mono = monotoneColors()
    val bg = mono.detailsPanelBg.copy(alpha = 0.85f)
    val border = mono.detailsPanelBorder.copy(alpha = 0.35f)

    var expanded by remember(configId) { mutableStateOf(false) }

    val bringIntoViewRequester = remember(configId) { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    /**
     * Bring the panel into view after expanding, once layout has a chance to settle.
     */
    LaunchedEffect(expanded, configId) {
        if (expanded) {
            delay(BRING_INTO_VIEW_DELAY_MS)
            bringIntoViewRequester.bringIntoView()
        }
    }

    val panelModifier = Modifier
        .fillMaxWidth()
        .bringIntoViewRequester(bringIntoViewRequester)
        .clip(RoundedCornerShape(14.dp))
        .background(bg)
        .border(BorderStroke(1.dp, border), RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .testTag("SelectedConfigDetails")

    Column(
        modifier = if (!expanded) panelModifier.animateContentSize() else panelModifier,
        horizontalAlignment = Alignment.Start
    ) {
        if (expanded) {
            when (state) {
                is ResolvedDetailsState.Loading -> {
                    Text(
                        text = "Loading config: $configId …",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = mono.chipLabel,
                        modifier = Modifier.testTag("ConfigDetailsLoading")
                    )
                }

                is ResolvedDetailsState.Error -> {
                    Text(
                        text = "Failed to load config: $configId",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = mono.errorText,
                        modifier = Modifier.testTag("ConfigDetailsErrorTitle")
                    )

                    Spacer(Modifier.padding(top = 6.dp))

                    Text(
                        text = (state.error.message ?: state.error::class.java.simpleName).take(240),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = mono.errorTextMuted,
                        modifier = Modifier.testTag("ConfigDetailsErrorMessage")
                    )

                    Spacer(Modifier.padding(top = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.testTag("ConfigDetailsRetryButton")
                        ) {
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
                                color = mono.actionText
                            )
                        }
                    }
                }

                is ResolvedDetailsState.Ready -> {
                    val d = state.details

                    Text(
                        text = d.summary,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = mono.textSecondary,
                        modifier = Modifier.testTag("ConfigDetailsSummary")
                    )

                    Spacer(Modifier.padding(top = 8.dp))

                    Text(
                        text = d.longText,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = mono.chipLabel,
                        modifier = Modifier.testTag("ConfigDetailsLongText")
                    )
                }
            }

            Spacer(Modifier.padding(top = 10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetaChipMono(label = "id", value = configId)

            TextButton(
                onClick = {
                    val next = !expanded
                    expanded = next
                    Log.d(TAG, "ShowMore toggle: optionId=$configId expanded=$expanded")
                    if (next) {
                        /** Defensive: also request bring into view in case effect is delayed. */
                        scope.launch {
                            delay(BRING_INTO_VIEW_DELAY_MS)
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                },
                modifier = Modifier.testTag("ShowMoreButton"),
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text(
                    text = if (expanded) "Show less" else "Show more",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
                    color = mono.actionText
                )
            }
        }
    }
}

/**
 * Small monotone key/value chip.
 *
 * Notes:
 * - Keep testTag stable: avoid embedding values that can change.
 * - Validate values in tests via Text assertions instead.
 */
@Composable
private fun MetaChipMono(label: String, value: String) {
    val mono = monotoneColors()
    val safeLabel = remember(label) { label.safeTestTagToken(32) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(mono.chipBg)
            .border(
                BorderStroke(1.dp, mono.chipBorder.copy(alpha = 0.35f)),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("MetaChip_$safeLabel")
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
            color = mono.chipLabel
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = mono.chipValue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * CTA row that hosts the primary Start button and an optional Restart button.
 */
@Composable
private fun CtaRowMono(
    onStart: () -> Unit,
    showRestart: Boolean,
    isRestarting: Boolean,
    onRestart: (() -> Unit)?,
) {
    var confirmRestart by remember { mutableStateOf(false) }
    val mono = monotoneColors()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Matches RowButtons' Next/Back pattern (MainActivity.kt) in shape,
        // padding and plain text style, filled with the shared ActionBlue so
        // Start reads as the primary action like Send/Stop/accepted Next.
        FilledTonalButton(
            onClick = onStart,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = ActionBlue,
                contentColor = Color.White,
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.testTag("StartButton")
        ) {
            Text("Start")
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Start"
            )
        }

        if (showRestart && onRestart != null) {
            Spacer(Modifier.width(12.dp))

            OutlinedButton(
                enabled = !isRestarting,
                onClick = { confirmRestart = true },
                border = BorderStroke(1.dp, mono.outlineBorder.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = mono.outlineContent
                ),
                modifier = Modifier.testTag("RestartButton")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRestarting) "Restarting..." else "Restart",
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.2.sp)
                )
            }
        }
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart engine?") },
            text = { Text("This cancels current work and recreates the engine/session.") },
            confirmButton = {
                Button(
                    enabled = !isRestarting,
                    onClick = {
                        confirmRestart = false
                        onRestart?.invoke()
                    }
                ) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Monotone headline with a subtle vertical gradient (high-contrast).
 */
@Composable
private fun GradientHeadlineMono(
    text: String,
    colorTop: Color,
    colorBottom: Color
) {
    val brush = Brush.verticalGradient(
        0f to colorTop,
        1f to colorBottom
    )
    val label = buildAnnotatedString {
        withStyle(SpanStyle(brush = brush)) { append(text) }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            lineHeight = 26.sp,
            letterSpacing = 0.2.sp
        ),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Single monotone configuration chip (readability-tuned).
 */
@Composable
private fun MonoConfigOptionChip(
    option: ConfigOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
    textPrimary: Color,
    textSecondary: Color
) {
    val mono = monotoneColors()
    val background = if (selected) mono.optionSelectedBg else mono.optionUnselectedBg
    val borderColor = if (selected) {
        mono.optionSelectedBorder.copy(alpha = 0.90f)
    } else {
        mono.optionUnselectedBorder.copy(alpha = 0.55f)
    }
    val labelColor = if (selected) textPrimary else mono.optionUnselectedLabel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .testTag("ConfigOption_${option.id.safeTestTagToken(32)}")
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeight = 22.sp
                ),
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        val strokeWidth = 1.6.dp.toPx()
                        drawCircle(
                            color = mono.optionSelectedBorder.copy(alpha = 0.88f),
                            style = Stroke(width = strokeWidth)
                        )
                        drawCircle(
                            color = mono.optionSelectedBorder.copy(alpha = 0.88f),
                            radius = size.minDimension / 2.5f
                        )
                    }
            )
        }
    }
}

/* ────────────────────────── Background (monotone) ───────────────────────── */

/**
 * Animated grayscale background brush for the intro screen.
 */
@Composable
private fun animatedMonotoneBackground(): Brush {
    val t = rememberInfiniteTransition(label = "mono-bg")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mono-bg-p"
    )

    val mono = monotoneColors()

    val endX = 900f + 280f * p
    val endY = 720f - 220f * p

    return Brush.linearGradient(
        colors = mono.introBgStops,
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

/* ────────────────────────── TestTag Sanitizer ────────────────────────── */

/**
 * Sanitize strings for use in test tags.
 *
 * Notes:
 * - Allow only [A-Za-z0-9_.-]
 * - Replace other characters with underscore.
 * - Truncate to [maxLen].
 */
private fun String.safeTestTagToken(maxLen: Int): String {
    val cleaned = buildString(length) {
        for (ch in this@safeTestTagToken) {
            val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.'
            append(if (ok) ch else '_')
        }
    }
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen)
}
