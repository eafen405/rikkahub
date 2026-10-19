package me.rerere.rikkahub.ui.identity

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import me.rerere.rikkahub.data.datastore.DisplayPreferences
import me.rerere.rikkahub.data.remote.RemoteChatMessage
import me.rerere.rikkahub.data.datastore.LocalDisplayPreferences
import me.rerere.rikkahub.ui.fairy.AgoraMotionPolicy
import me.rerere.rikkahub.ui.fairy.FairyAppTheme
import me.rerere.rikkahub.ui.fairy.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteTranscriptViewportTest {
    @get:Rule val compose = createComposeRule()
    private val history = (0..30).map { index ->
        RemoteChatMessage("message-$index", index.toLong(), role = 1, status = 2,
            text = "历史消息 $index\n这是持续阅读的内容。\n第二行内容。")
    }
    private var live by mutableStateOf("开始回复。")

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDisplayPreferences provides DisplayPreferences()) {
                FairyAppTheme {
                    CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                        RemoteTranscript(history, live, "g" to "a", false, { _, _ -> },
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun position() = compose.onNodeWithTag("conversation-messages")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun `tail follows additional streamed lines`() {
        show()
        val before = position()
        compose.runOnIdle { live += "\n新的回复内容。".repeat(40) }
        compose.mainClock.advanceTimeBy(500)
        val after = position()
        assertTrue("the viewport must follow the growing reply: $before -> $after", after > before)
    }

    @Test fun `reading history stays at the same position while the reply updates`() {
        show()
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(2_000)
        val before = position()
        compose.runOnIdle { live += "\n新的回复内容。".repeat(40) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("new text must not move a reader away from history", before, position(), 0.001f)
        compose.onNodeWithTag("conversation-messages").performScrollToIndex(history.size)
        compose.onNodeWithText(live).assertExists()
    }
}
