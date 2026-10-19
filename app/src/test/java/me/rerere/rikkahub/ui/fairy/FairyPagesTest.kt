package me.rerere.rikkahub.ui.fairy

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplayPreferences
import me.rerere.rikkahub.data.remote.*
import me.rerere.rikkahub.data.datastore.LocalDisplayPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FairyPagesTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = object : SessionBackend {
        override suspend fun list(token: String) = OwnedSessions(
            listOf(OwnedSession("main", primary = true), OwnedSession("other")), "main",
        )
        override suspend fun read(token: String, sessionId: String): OwnedSession {
            if (sessionId == "missing") throw IdentityRefused(404, "session/not-found", "会话已不存在")
            return OwnedSession(sessionId, primary = sessionId == "main")
        }
        override suspend fun select(token: String, sessionId: String) = read(token, sessionId)
        override suspend fun primary(token: String) = OwnedSession("main", primary = true)
        override suspend fun create(token: String) = OwnedSession("created")
    }
    private val updates = mutableMapOf<String, MutableStateFlow<RemoteChatProjection>>()
    private val backend = object : RemoteChatBackend {
        override fun observe(token: String, sessionId: String) = updates.getOrPut(sessionId) {
            MutableStateFlow(RemoteChatProjection(sessionId, activity = RemoteActivity(status = 1)))
        }
        override suspend fun checkpoint(token: String, sessionId: String) = updates.getValue(sessionId).value.cursor
        override suspend fun page(token: String, sessionId: String, throughSeq: Long, beforeSeq: Long) = error("not used")
        override suspend fun read(token: String, sessionId: String) = error("not used")
        override suspend fun send(token: String, sessionId: String, requestId: String, text: String, uploadIds: List<String>) = error("not used")
        override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null
        override suspend fun stop(token: String, sessionId: String) = Unit
    }
    private val store = object : ChatSyncStore {
        override suspend fun load(key: ChatSyncKey) = ChatSyncRecord(-1, true)
        override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) = Unit
    }
    private val receive = RemoteReceiveState(sessions, scope, {
        RemoteChatState(backend, scope, store, "https://fairy.test")
    })
    private val account = AccountSession(object : IdentityBackend {
        override suspend fun session(token: String) = IdentityUser(2, "代理人")
        override suspend fun signIn(name: String, password: String) = SignedIn("test", session("test"))
        override suspend fun register(inviteCode: String, name: String, password: String) = error("not used")
        override suspend fun createInvite(token: String) = error("not used")
        override suspend fun signOut(token: String) = Unit
    }, InMemoryCredentialStore("test"), receive::bindIdentity)

    @After fun close() { scope.cancel() }

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        runBlocking { account.restore() }
        compose.setContent {
            CompositionLocalProvider(LocalDisplayPreferences provides DisplayPreferences()) {
                FairyAppTheme {
                    ProvideAgoraMotionPolicy(appReduceMotion = true, content = content)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val directory = System.getenv("FAIRY_UI_SCREENSHOTS")?.let(::File) ?: return
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun `home opens settings with visible account content`() {
        var destination by mutableStateOf<Screen>(Screen.Home)
        show {
            when (destination) {
                Screen.Home -> FairyConsolePage({ destination = it }, account, receive)
                else -> FairySettingsPage({}, {}, account, onBack = { destination = Screen.Home })
            }
        }
        compose.onNodeWithText("设置").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("账号").assertIsDisplayed()
        compose.onNodeWithText("退出登录").assertIsDisplayed()
        screenshot("settings")
    }

    private fun conversation(initial: String = "main") {
        var target by mutableStateOf(initial)
        show {
            FairyConversationPage(target, {}, receive,
                onOpenConversation = { target = it }, onSettings = {})
        }
    }

    @Test fun `home opens the current conversation with a visible composer`() {
        var destination by mutableStateOf<Screen>(Screen.Home)
        show {
            when (val target = destination) {
                is Screen.Conversation -> FairyConversationPage(
                    target.sessionId, {}, receive,
                    onOpenConversation = { destination = Screen.Conversation(it) }, onSettings = {},
                )
                else -> FairyConsolePage({ destination = it }, account, receive)
            }
        }
        compose.onNodeWithText("进入会话").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("切换会话").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed()
    }

    @Test fun `selected session can still be opened from the session list`() {
        conversation()
        compose.onNodeWithText("发送消息…").performTextInput("保留草稿")
        compose.onNodeWithContentDescription("切换会话").performClick()
        compose.mainClock.advanceTimeBy(500)
        screenshot("sessions")
        compose.onNodeWithText("主会话").assertIsDisplayed().assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed()
        compose.onNodeWithText("主会话").assertIsNotDisplayed()
        compose.onNodeWithText("保留草稿").assertIsDisplayed()
        compose.runOnIdle { assertEquals("main", receive.owned.snapshot.value.selectedId) }
    }

    @Test fun `drawer switches and creates conversations`() {
        conversation()
        compose.onNodeWithContentDescription("切换会话").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("other").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals("other", receive.owned.snapshot.value.selectedId) }
        compose.onNodeWithContentDescription("切换会话").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("新建会话").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals("created", receive.owned.snapshot.value.selectedId) }
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed()
    }

    @Test fun `unavailable restored conversation offers another session`() {
        conversation("missing")
        compose.onNodeWithText("会话不可用").assertIsDisplayed()
        compose.onNodeWithText("选择会话").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("主会话").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed()
    }

    @Test fun `conversation observes thinking changes without moving the composer`() {
        updates["main"] = MutableStateFlow(RemoteChatProjection(
            sessionId = "main", cursor = 0, durableThroughSeq = 0,
            events = eventCoverage(0, 0),
            messages = listOf(RemoteChatMessage("history", 0, role = 1, status = 2, text = "你好，Fairy")),
            activity = RemoteActivity(1),
        ))
        conversation()
        val before = compose.onNodeWithContentDescription("发送消息").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("conversation-messages").fetchSemanticsNode().boundsInRoot
        screenshot("conversation")
        compose.runOnIdle {
            val stream = updates.getValue("main")
            stream.value = stream.value.copy(version = 1, replyStatus = 1, activity = RemoteActivity(2, listOf(1)))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("正在思考…").assertIsDisplayed()
        val during = compose.onNodeWithContentDescription("停止回复").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, during.top)
        assertEquals(viewport, compose.onNodeWithTag("conversation-messages").fetchSemanticsNode().boundsInRoot)
        screenshot("thinking")
        compose.runOnIdle {
            val stream = updates.getValue("main")
            stream.value = stream.value.copy(version = 2, replyStatus = 0, activity = RemoteActivity(1))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("正在思考…").assertDoesNotExist()
    }
}
