package com.negi.survey.vm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.rememberNavBackStack
import com.negi.survey.MultiChoiceNodeScreen
import com.negi.survey.SingleChoiceNodeScreen
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.ui.theme.DarkSecondaryContainer
import com.negi.survey.ui.theme.LightSecondaryContainer
import com.negi.survey.ui.theme.SurveyNavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OutdoorChoiceScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun multipleChoiceKeepsSelectionsAndProgressWhenThemeChanges() {
        val config = SurveyConfig(
            graph = SurveyConfig.Graph(
                startId = "Choice",
                nodes = listOf(
                    NodeDTO(id = "Choice", type = "MULTI_CHOICE", options = listOf("A", "B"), nextId = "Done"),
                    NodeDTO(id = "Done", type = "DONE"),
                )
            )
        )
        lateinit var vm: SurveyViewModel
        var dark by mutableStateOf(false)
        var submitted: Set<String>? = null
        rule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            SurveyNavTheme(darkTheme = dark) {
                vm = remember { SurveyViewModel(backStack, config).apply { goto("Choice") } }
                val selected by vm.multi.collectAsState()
                MultiChoiceNodeScreen(
                    title = "Choose answers",
                    question = "Which apply?",
                    options = listOf("A", "B"),
                    selected = selected,
                    onToggle = vm::toggleMultiChoice,
                    onNext = { submitted = selected },
                    onBack = {}
                )
            }
        }

        val first = rule.onNodeWithText("A")
        val second = rule.onNodeWithText("B")
        val next = rule.onNodeWithText("Next")
        val back = rule.onNodeWithText("Back")
        next.assertIsEnabled() // Existing behavior permits an empty selection.
        first.assertIsNotSelected().assertHeightIsAtLeast(56.dp)
        first.assertFill(Color.White)
        next.assertFill(Color(0xFF42A5F5))
        back.assertFill(LightSecondaryContainer)
        val backBounds = back.getUnclippedBoundsInRoot()
        val nextBounds = next.getUnclippedBoundsInRoot()
        assertEquals((backBounds.right - backBounds.left).value, (nextBounds.right - nextBounds.left).value, 0.01f)
        assertEquals((backBounds.bottom - backBounds.top).value, (nextBounds.bottom - nextBounds.top).value, 0.01f)
        back.assertHeightIsAtLeast(48.dp)
        next.assertHeightIsAtLeast(48.dp)
        // Invoke the button action without a touch ripple over the sampled color.
        first.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        first.assertIsSelected()
        second.assertIsNotSelected()
        second.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        second.assertIsSelected()
        first.assertFill(Color(0xFFDCEEFF))

        rule.runOnIdle { dark = true }
        first.assertIsSelected().assertFill(Color(0xFF18354D))
        second.assertIsSelected().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        second.assertIsNotSelected()
        second.assertFill(Color(0xFF202428))
        next.assertFill(Color(0xFF90CAF9))
        back.assertFill(DarkSecondaryContainer)
        next.performClick()
        rule.runOnIdle {
            assertEquals("Choice", vm.currentNodeId)
            assertEquals(setOf("A"), submitted)
        }
    }

    @Test
    fun longEnglishAndSwahiliChoicesWrapOnNarrowScreenWithLargeText() {
        val english = "I understand the purpose of this survey and agree to take part in the interview."
        val swahili = "Ninaelewa madhumuni ya utafiti huu na ninakubali kushiriki katika mahojiano."
        val question = "Please read each answer before you continue. Tafadhali soma kila jibu kabla ya kuendelea."
        var selected by mutableStateOf<String?>(null)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SurveyNavTheme {
                    Box(Modifier.width(280.dp)) {
                        SingleChoiceNodeScreen(
                            title = "Consent",
                            question = question,
                            options = listOf(english, swahili),
                            selected = selected,
                            onSelect = { selected = it },
                            onSubmit = { _, _ -> },
                            onBack = {}
                        )
                    }
                }
            }
        }
        rule.onNodeWithText(question).assertWrapsWithoutClipping()
        for (option in listOf(english, swahili)) {
            rule.onNodeWithText(option).performScrollTo().performClick().assertIsSelected()
            rule.onNodeWithText(option, useUnmergedTree = true).assertWrapsWithoutClipping()
        }
        for (label in listOf("Back", "Next")) {
            rule.onNodeWithText(label).performScrollTo()
                .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // The semantics action rebuilds the paragraph at max width. Check the glyph bounds.
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("$label clips horizontally", layout.getLineRight(line) <= layout.size.width + 1)
                assertTrue("$label clips vertically", layout.getLineBottom(line) <= layout.size.height + 1)
            }
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
        rule.onNodeWithText(swahili).performScrollTo().assertIsSelected()
    }

    private fun SemanticsNodeInteraction.assertFill(expected: Color) {
        assertEquals(expected.toArgb(), sampleFill().toArgb())
    }

    private fun SemanticsNodeInteraction.sampleFill(): Color {
        val pixels = captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 6]
    }

    private fun SemanticsNodeInteraction.assertWrapsWithoutClipping() {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow)
    }
}
