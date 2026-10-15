package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backend that answers only when the test says so, and that deliberately
 * ignores cancellation.
 *
 * Ignoring cancellation is the honest half of the double. Cancelling a request
 * whose answer has already left the backend is something the phone cannot
 * actually do — the bytes are on their way regardless — so a fake that stops
 * cooperating on cancellation would let a cancelled-but-already-arrived answer
 * slip through untested. Here the answer always arrives, and only the generation
 * check can stop it from being applied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class DeferredBackend : SessionBackend {

    val gate = CompletableDeferred<Unit>()
    val seenTokens = mutableListOf<String>()

    override suspend fun list(token: String): OwnedSessions {
        await()
        return OwnedSessions(
            sessions = listOf(OwnedSession(id = "s-$token", owner = "user:$token", primary = true)),
            primarySessionId = "s-$token",
        )
    }

    override suspend fun create(token: String): OwnedSession {
        await()
        return OwnedSession(id = "new-$token", owner = "user:$token")
    }

    override suspend fun select(token: String, sessionId: String): OwnedSession {
        await()
        return OwnedSession(id = sessionId, owner = "user:$token")
    }

    override suspend fun primary(token: String): OwnedSession {
        await()
        return OwnedSession(id = "s-$token", owner = "user:$token", primary = true)
    }

    override suspend fun read(token: String, sessionId: String): OwnedSession {
        await()
        return OwnedSession(id = sessionId, owner = "user:$token")
    }

    private suspend fun await() {
        withContext(NonCancellable) { gate.await() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedSessionStateTest {

    @Test
    fun `the list shows what the backend said`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.refresh(backgroundScope)
        advanceUntilIdle()

        assertEquals(listOf("s-alice"), state.snapshot.sessions.map { it.id })
        assertEquals("s-alice", state.snapshot.primarySessionId)
    }

    @Test
    fun `choosing a session shows that one as the current session`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.select(backgroundScope, "s-2")
        advanceUntilIdle()

        assertEquals("s-2", state.snapshot.selectedId)
    }

    @Test
    fun `returning to the main session does not change which one the account owns`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.select(backgroundScope, "s-2")
        advanceUntilIdle()
        val primaryBefore = state.snapshot.primarySessionId

        state.returnToPrimary(backgroundScope)
        advanceUntilIdle()

        assertEquals(primaryBefore, state.snapshot.primarySessionId)
        assertEquals("s-alice", state.snapshot.selectedId)
    }

    @Test
    fun `a list that arrives after the account changed is not shown to the new account`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend)

        state.bind("alice")
        state.refresh(backgroundScope)
        advanceUntilIdle()

        // Alice is signed out and Bob signs in while Alice's list is still in
        // flight. The page is empty at once…
        state.bind("bob")
        assertEquals(emptyList<OwnedSession>(), state.snapshot.sessions)
        assertNull(state.snapshot.selectedId)

        // …and Alice's answer, which the backend sends anyway, changes nothing.
        backend.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<OwnedSession>(), state.snapshot.sessions)
        assertNull(state.snapshot.primarySessionId)
        assertNull(state.snapshot.selectedId)
    }

    @Test
    fun `a selection made as one account is gone once another signs in`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.select(backgroundScope, "s-secret")
        advanceUntilIdle()
        assertEquals("s-secret", state.snapshot.selectedId)

        state.bind("bob")
        state.refresh(backgroundScope)
        advanceUntilIdle()

        assertNull(state.snapshot.selectedId)
        assertEquals(listOf("s-bob"), state.snapshot.sessions.map { it.id })
    }

    @Test
    fun `signing out empties the page and refuses to ask for anything`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.refresh(backgroundScope)
        advanceUntilIdle()

        state.bind(null)
        state.refresh(backgroundScope)
        state.create(backgroundScope)
        advanceUntilIdle()

        assertEquals(emptyList<OwnedSession>(), state.snapshot.sessions)
        assertNull(state.snapshot.selectedId)
        // One credential, used as itself: nothing was ever asked for as a user.
        assertEquals(listOf("alice"), backend.seenTokens)
    }

    @Test
    fun `a selection the account's own list does not contain is not kept`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.select(backgroundScope, "s-elsewhere")
        advanceUntilIdle()

        state.refresh(backgroundScope)
        advanceUntilIdle()

        assertTrue("s-elsewhere" !in state.snapshot.sessions.map { it.id })
        assertEquals("s-alice", state.snapshot.selectedId)
    }
}
