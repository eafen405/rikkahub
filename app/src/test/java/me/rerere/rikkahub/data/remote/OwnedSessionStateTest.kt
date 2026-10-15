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
        await(token)
        return OwnedSessions(
            sessions = listOf(OwnedSession(id = "s-$token", owner = "user:$token", primary = true)),
            primarySessionId = "s-$token",
        )
    }

    override suspend fun create(token: String): OwnedSession {
        await(token)
        // A first session is the account's main, which is exactly the case that
        // must show up on the page without waiting for a list.
        return OwnedSession(id = "new-$token", owner = "user:$token", primary = true)
    }

    override suspend fun select(token: String, sessionId: String): OwnedSession {
        await(token)
        return OwnedSession(id = sessionId, owner = "user:$token")
    }

    override suspend fun primary(token: String): OwnedSession {
        await(token)
        return OwnedSession(id = "s-$token", owner = "user:$token", primary = true)
    }

    override suspend fun read(token: String, sessionId: String): OwnedSession {
        await(token)
        return OwnedSession(id = sessionId, owner = "user:$token")
    }

    private suspend fun await(token: String) {
        seenTokens += token
        withContext(NonCancellable) { gate.await() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedSessionStateTest {

    @Test
    fun `the list shows what the backend said`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.refresh()
        advanceUntilIdle()

        assertEquals(listOf("s-alice"), state.snapshot.value.sessions.map { it.id })
        assertEquals("s-alice", state.snapshot.value.primarySessionId)
    }

    @Test
    fun `choosing a session shows that one as the current session`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.select("s-2")
        advanceUntilIdle()

        assertEquals("s-2", state.snapshot.value.selectedId)
    }

    @Test
    fun `returning to the main session does not change which one the account owns`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.refresh()
        advanceUntilIdle()
        state.select("s-2")
        advanceUntilIdle()

        state.returnToPrimary()
        advanceUntilIdle()

        assertEquals("s-alice", state.snapshot.value.primarySessionId)
        assertEquals("s-alice", state.snapshot.value.selectedId)
    }

    @Test
    fun `a list that arrives after the account changed is not shown to the new account`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)

        state.bind("alice")
        state.refresh()
        advanceUntilIdle()

        // Alice is signed out and Bob signs in while Alice's list is still in
        // flight. The page is empty at once…
        state.bind("bob")
        assertEquals(emptyList<OwnedSession>(), state.snapshot.value.sessions)
        assertNull(state.snapshot.value.selectedId)

        // …and Alice's answer, which the backend sends anyway, changes nothing.
        backend.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<OwnedSession>(), state.snapshot.value.sessions)
        assertNull(state.snapshot.value.primarySessionId)
        assertNull(state.snapshot.value.selectedId)
    }

    @Test
    fun `a selection made as one account is gone once another signs in`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.select("s-secret")
        advanceUntilIdle()
        assertEquals("s-secret", state.snapshot.value.selectedId)

        state.bind("bob")
        state.refresh()
        advanceUntilIdle()

        // Alice's choice is gone: Bob stands in his own main, and nothing of
        // hers is left anywhere on the page.
        assertEquals("s-bob", state.snapshot.value.selectedId)
        assertEquals(listOf("s-bob"), state.snapshot.value.sessions.map { it.id })
        assertTrue("s-secret" !in state.snapshot.value.sessions.map { it.id })
    }

    @Test
    fun `signing out empties the page and refuses to ask for anything`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.refresh()
        advanceUntilIdle()

        state.bind(null)
        state.refresh()
        state.create()
        advanceUntilIdle()

        assertEquals(emptyList<OwnedSession>(), state.snapshot.value.sessions)
        assertNull(state.snapshot.value.selectedId)
        // One credential, used as itself: nothing was ever asked for as a user.
        assertEquals(listOf("alice"), backend.seenTokens)
    }

    @Test
    fun `a selection the account's own list does not contain is not kept`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.select("s-elsewhere")
        advanceUntilIdle()

        state.refresh()
        advanceUntilIdle()

        assertTrue("s-elsewhere" !in state.snapshot.value.sessions.map { it.id })
        assertEquals("s-alice", state.snapshot.value.selectedId)
    }

    @Test
    fun `a first create shows the new session and records the account's first main`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.create()
        advanceUntilIdle()

        assertEquals(listOf("new-alice"), state.snapshot.value.sessions.map { it.id })
        assertEquals("new-alice", state.snapshot.value.primarySessionId)
        assertEquals("new-alice", state.snapshot.value.selectedId)
    }

    @Test
    fun `returning to a main the page never listed shows it and stands in it`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.returnToPrimary()
        advanceUntilIdle()

        assertEquals(listOf("s-alice"), state.snapshot.value.sessions.map { it.id })
        assertEquals("s-alice", state.snapshot.value.primarySessionId)
        assertEquals("s-alice", state.snapshot.value.selectedId)
    }

    @Test
    fun `an ordinary choice leaves the account's main where it was`() = runTest {
        val backend = DeferredBackend()
        val state = OwnedSessionState(backend, this)
        backend.gate.complete(Unit)

        state.bind("alice")
        state.returnToPrimary()
        advanceUntilIdle()
        state.create()
        advanceUntilIdle()

        assertEquals("s-alice", state.snapshot.value.primarySessionId)
        assertEquals("new-alice", state.snapshot.value.selectedId)
        assertEquals(listOf("s-alice", "new-alice"), state.snapshot.value.sessions.map { it.id })
    }
}
