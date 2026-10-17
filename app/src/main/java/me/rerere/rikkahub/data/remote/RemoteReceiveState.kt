package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One account owns each session runtime. The page shares these instances with reception.
 *
 * The list is not copied here: [sessions] is the very flow [OwnedSessionState] publishes, so
 * there is no second copy that a collector could lag behind. Whose list it is is decided by
 * [bind], which the identity publisher calls in the turn it publishes the account (see
 * [bindIdentity]) — the previous account's list and chats are retired before a collector can
 * observe the new identity.
 *
 * Reception also owns the reminder: a session records the episode it owes in its own durable
 * record, and this module sees every session, so a backlog is published as one reminder rather
 * than one per session.
 */
class RemoteReceiveState(
    backend: SessionBackend,
    private val scope: CoroutineScope,
    private val chatFactory: (settleReminders: Boolean) -> RemoteChatState,
    private val notifications: RemoteNotificationPlatform? = null,
    private val notificationsEnabled: () -> Boolean = { true },
) {
    private val owned = OwnedSessionState(backend, scope)

    /** The account's own snapshot; the page renders this flow and does not decide whose it is. */
    val sessions: StateFlow<SessionsSnapshot> = owned.snapshot

    private val mutableSelected = MutableStateFlow<RemoteChatState?>(null)

    /** The observable pointer to the selected runtime; [bind] retires it with the list. */
    val selectedChat: StateFlow<RemoteChatState?> = mutableSelected.asStateFlow()
    private val chats = mutableMapOf<String, RemoteChatState>()
    private var token: String? = null
    private var userShortId: Int? = null
    private var epoch = 0L
    private var observing = true
    private var publishing: Job? = null

    init {
        scope.launch {
            owned.snapshot.collect { listed ->
                val allowed = listed.sessions.map { it.id }.toSet()
                val removed = chats.keys.filter { it !in allowed }
                removed.forEach { chats.remove(it)?.detach() }
                if (token != null && userShortId != null) {
                    listed.sessions.forEach { session ->
                        val chat = chats.getOrPut(session.id) { chatFactory(notifications != null) }
                        if (observing) chat.bind(token, session.id, userShortId)
                        chat.onSettled(::publishBacklog)
                    }
                }
                mutableSelected.value = chats[listed.selectedId]
                if (removed.isNotEmpty()) publishBacklog()
            }
        }
    }

    /**
     * Take up the identity that was just published, or drop the previous one.
     *
     * The publisher calls this synchronously with the identity it carries (null on sign-out), so
     * [OwnedSessionState.bind] has already retired every answer asked for under the previous
     * credential before the new identity can be collected.
     */
    fun bindIdentity(identity: SignedIn?) = bind(identity?.token, identity?.user?.shortId)

    fun bind(token: String?, userShortId: Int?) {
        if (token == this.token && userShortId == this.userShortId) return
        epoch++
        chats.values.forEach { it.detach() }
        chats.clear()
        mutableSelected.value = null
        this.token = token
        this.userShortId = userShortId
        owned.bind(token)
        if (token != null && userShortId != null) owned.refresh()
    }

    fun stateForSession(sessionId: String): RemoteChatState? = chats[sessionId]
    fun refresh() = owned.refresh()
    fun create() = owned.create()
    fun select(sessionId: String) = owned.select(sessionId)
    fun read(sessionId: String) = owned.read(sessionId)
    fun returnToPrimary() = owned.returnToPrimary()

    /** Intent data is a target, not authority: use the existing authenticated read. */
    suspend fun openNotification(sessionId: String): Boolean {
        if (token == null || userShortId == null) return false
        val started = epoch
        owned.snapshot.first { !it.loading }
        if (epoch != started) return false
        owned.read(sessionId)
        val settled = owned.snapshot.first { !it.loading }
        return epoch == started && settled.failure == null && settled.selectedId == sessionId
    }

    fun stopObservation() {
        observing = false
        chats.values.forEach { it.detach() }
    }

    fun resumeObservation() {
        observing = true
        chats.forEach { (sessionId, chat) -> chat.bind(token, sessionId, userShortId) }
    }

    /**
     * Publish only once no observed session is still catching up: a session mid-recovery has not
     * said whether it owes anything, so publishing earlier would spend the reminder on whichever
     * sessions answered first. The backlog is read back from the records, so a session that never
     * connects cannot hold another session's reminder hostage.
     */
    private fun publishBacklog() {
        val platform = notifications ?: return
        if (publishing?.isActive == true) return
        if (chats.values.any { it.snapshot.value.syncing }) return
        val started = epoch
        publishing = scope.launch {
            try {
                val owed = chats.values.mapNotNull { chat ->
                    chat.owedReminder()?.let { Owed(chat, it.first, it.second) }
                }
                // Ask again after the awaited disk work: the account and the app may have moved.
                if (owed.isEmpty() || started != epoch) return@launch
                // Consumed before the attempt: a crash here may miss the sound, but cannot replay it.
                owed.forEach { it.chat.consumeReminder() }
                if (started != epoch || !notificationsEnabled() || platform.isForeground()) return@launch
                // One reminder for the account's whole backlog. It opens the primary session when
                // that one owes something, so a merge still lands where the user reads.
                val primary = owned.snapshot.value.primarySessionId
                val target = owed.minWithOrNull(compareBy({ it.key.sessionId != primary }, { it.key.sessionId }))
                    ?: return@launch
                platform.publish(target.key, target.preview)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Nothing was consumed, so the record still owes it and this can be tried again.
                scope.launch { delay(RETRY_MILLIS); publishBacklog() }
            } finally {
                publishing = null
            }
        }
    }

    private class Owed(val chat: RemoteChatState, val key: ChatSyncKey, val preview: String)

    private companion object {
        const val RETRY_MILLIS = 1_000L
    }
}