package com.negi.survey.vm

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleChoiceRestorationTest {
    @Test
    fun back_and_forward_restore_saved_choices_without_changing_answers() {
        val config = SurveyConfig(graph = SurveyConfig.Graph(startId = "Start", nodes = listOf(
            NodeDTO("Start", "START", nextId = "Choice"),
            NodeDTO("Choice", "SINGLE_CHOICE", options = listOf("Known", "Other"),
                otherTextOption = "Other", nextId = "Done"),
            NodeDTO("Done", "DONE")
        )))
        val vm = SurveyViewModel(NavBackStack<NavKey>(FlowHome), config)
        vm.advanceToNext()
        assertNull(vm.single.value)
        vm.setAnswer("Known", "Choice")
        vm.advanceToNext()
        repeat(2) {
            vm.backToPrevious()
            assertEquals("Known", vm.single.value)
            assertEquals("Known", vm.getAnswer("Choice"))
            vm.advanceToNext()
        }
        vm.backToPrevious()
        vm.setAnswer("Other: detail", "Choice")
        vm.advanceToNext()
        vm.backToPrevious()
        assertEquals("Other", vm.single.value)
        assertEquals("detail", vm.currentNode.value.decomposeSingleChoiceAnswer(vm.getAnswer("Choice")).otherText)
        assertEquals("Other: detail", vm.getAnswer("Choice"))
    }
}
