package com.locationjoystick.app.smoke

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.locationjoystick.core.common.util.LocaleContextWrapper
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.locationjoystick.feature.routes.impl.R as RoutesR

/** Changes language through the actual Settings picker and checks the recreated Activity. */
@HiltAndroidTest
class LanguageSelectionSmokeTest : BaseSmokeTest() {
    @Before
    override fun setup() {
        super.setup()
        setInitialEnglish()
        composeRule.waitForIdleScreen()
        composeRule.navigateViaDrawer("Settings")
        settingsLabel("App language").performScrollTo().assertIsDisplayed()
    }

    @After
    fun restoreEnglish() {
        setInitialEnglish()
    }

    @Test
    fun settings_language_picker_switches_korean_and_english() {
        selectLanguage("App language", "한국어")
        awaitLanguage("ko", "앱 언어")
        settingsLabel("설정").assertIsDisplayed()
        assertKoreanRouteResources()

        composeRule.onNodeWithContentDescription("탐색 메뉴 열기").performClick()
        composeRule
            .onAllNodesWithText("경로")
            .filterToOne(hasAnyAncestor(hasTestTag("nav_drawer")))
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText("즐겨찾기")
            .filterToOne(hasAnyAncestor(hasTestTag("nav_drawer")))
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText("설정")
            .filterToOne(hasAnyAncestor(hasTestTag("nav_drawer")))
            .performClick()

        selectLanguage("앱 언어", "English")
        awaitLanguage("en", "App language")
        settingsLabel("Settings").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals("Save", activity.getString(RoutesR.string.route_creator_save))
            assertEquals("Cancel", activity.getString(RoutesR.string.route_creator_cancel))
        }
    }

    @Test
    fun korean_selection_survives_activity_recreation() {
        selectLanguage("App language", "한국어")
        awaitLanguage("ko", "앱 언어")

        composeRule.activityRule.scenario.recreate()

        awaitLanguage("ko", "앱 언어")
        settingsLabel("설정").assertIsDisplayed()
        assertKoreanRouteResources()
    }

    private fun selectLanguage(
        card: String,
        language: String,
    ) {
        settingsLabel(card).performScrollTo().performClick()
        composeRule
            .onAllNodesWithText(language)
            .filterToOne(hasAnyAncestor(isDialog()))
            .performClick()
    }

    private fun awaitLanguage(
        expectedTag: String,
        settingsCard: String,
    ) {
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            val chosen = LocaleContextWrapper.getLanguage(InstrumentationRegistry.getInstrumentation().targetContext)
            chosen?.substringBefore('-') == expectedTag &&
                activityLanguage() == expectedTag &&
                composeRule.onAllNodesWithText(settingsCard).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        settingsLabel(settingsCard).assertIsDisplayed()
    }

    private fun activityLanguage(): String? =
        runCatching {
            var language: String? = null
            composeRule.activityRule.scenario.onActivity { activity ->
                language =
                    activity.resources.configuration.locales[0]
                        .language
            }
            language
        }.getOrNull()

    private fun assertKoreanRouteResources() {
        composeRule.activityRule.scenario.onActivity { activity ->
            assertEquals("지점 3", activity.getString(RoutesR.string.route_detail_waypoint_number, 3))
            assertEquals("저장", activity.getString(RoutesR.string.route_creator_save))
            assertEquals("취소", activity.getString(RoutesR.string.route_creator_cancel))
            val hint = activity.getString(RoutesR.string.route_waypoint_edit_hint)
            assertTrue(hint.contains("지도") && hint.contains("지점"))
        }
    }

    private fun settingsLabel(text: String) =
        composeRule
            .onAllNodesWithText(text)
            .filterToOne(!hasAnyAncestor(hasTestTag("nav_drawer")))

    private fun setInitialEnglish() {
        composeRule.activityRule.scenario.onActivity { activity ->
            LocaleContextWrapper.setLanguage(activity, "en")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) activity.recreate()
        }
        composeRule.waitUntil(timeoutMillis = 30_000L) { activityLanguage() == "en" }
        composeRule.waitForIdle()
    }
}
