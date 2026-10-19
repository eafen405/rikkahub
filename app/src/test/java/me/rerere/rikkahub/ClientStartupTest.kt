package me.rerere.rikkahub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RikkaHubApp::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClientStartupTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()

    @Test
    fun `fresh application starts with sign in and opens invitation registration`() {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.identity_name_label)).assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.identity_have_invite)).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.identity_invite_code_label)).assertIsDisplayed()
    }
}
