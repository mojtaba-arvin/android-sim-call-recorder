package com.mojtaba.simcallrecorder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mojtaba.simcallrecorder.domain.model.CallRecord
import com.mojtaba.simcallrecorder.domain.model.CallRecordFilter
import com.mojtaba.simcallrecorder.ui.RecordingsListScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecorderScreensInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val records = listOf(
        record(1L, "alpha-call.wav", 0, 1_000L),
        record(2L, "beta-call.wav", 1, 2_000L),
    )

    @Test
    fun listDisplaysRecordsAndSimBadges() {
        composeRule.setContent {
            MaterialTheme {
                RecordingsListScreen(records, onFilterChanged = {}, onOpenRecord = {})
            }
        }
        composeRule.onNodeWithText("Recordings").assertIsDisplayed()
        // Each SIM label appears once in the filter chip and once in its recording card.
        composeRule.onAllNodesWithText("SIM 1").assertCountEquals(2)
        composeRule.onAllNodesWithText("SIM 2").assertCountEquals(2)
        composeRule.onNodeWithTag("sim-filter-0").assertIsDisplayed()
        composeRule.onNodeWithTag("sim-filter-1").assertIsDisplayed()
        composeRule.onNodeWithTag("record-list-item-1").assertIsDisplayed()
    }

    @Test
    fun searchInputUpdatesTheDatabaseFilter() {
        var filter = CallRecordFilter()
        composeRule.setContent {
            MaterialTheme {
                RecordingsListScreen(records, onFilterChanged = { filter = it }, onOpenRecord = {})
            }
        }
        composeRule.onNodeWithTag("recordings-search").performTextInput("beta")
        composeRule.runOnIdle { assertEquals("beta", filter.query) }
    }

    @Test
    fun simChipUpdatesTheSlotFilter() {
        var filter = CallRecordFilter()
        composeRule.setContent {
            MaterialTheme {
                RecordingsListScreen(records, onFilterChanged = { filter = it }, onOpenRecord = {})
            }
        }
        composeRule.onNodeWithTag("sim-filter-1").performClick()
        composeRule.runOnIdle { assertEquals(1, filter.simSlotIndex) }
    }

    private fun record(id: Long, name: String, slot: Int, start: Long) = CallRecord(
        id = id,
        startedAtEpochMillis = start,
        durationMillis = 12_000L,
        phoneNumber = null,
        simSlotIndex = slot,
        filePath = "/private/recordings/$name",
        recordingMode = "SHIZUKU_VOICE_CALL",
        audioSource = "VOICE_CALL",
        sampleRate = 48_000,
        channelCount = 2,
        dataBytes = 1000L,
        peakSample = 1200,
        rmsSample = 200,
        isSilent = false,
        status = "COMPLETED",
        failureReason = null,
    )
)
