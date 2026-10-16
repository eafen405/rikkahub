package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One account owns each session runtime. The page shares these instances with reception. */
class RemoteReceiveState(
    backend: SessionBackend,
    scope: CoroutineScope,
    private val chatFactory: () -> RemoteChatState,
) {
    private val owned = OwnedSessionState(backend, scope)
    private val mutableSessions = MutableStateFlow(SessionsSnapshot())
    val sessions: StateFlow<SessionsSnapshot> = mutableSessions.asStateFlow()
    private val mutableSelected = MutableStateFlow<RemoteChatState?>(null)
    val selectedChat: StateFlow<RemoteChatState?> = mutableSelected.asStateFlow()
    private val chats = mutableMapOf<String, RemoteChatState>()
    private var token: String? = null
    private var userShortId: Int? = null
    private var epoch = 0L
    private var observing = true

    init {
        scope.launch {
            owned.snapshot.collect { listed ->
                val allowed = listed.sessions.map { it.id }.toSet()
                val removed = chats.keys.filter { it !in allowed }
                removed.forEach { chats.remove(it)?.detach() }
                if (token != null && userShortId != null) {
                    listed.sessions.forEach { session ->
                        val chat = chats.getOrPut(session.id, chatFactory)
                        if (observing) chat.bind(token, session.id, userShortId)
                    }
                }
                mutableSessions.value = listed
                mutableSelected.value = chats[listed.selectedId]
            }
        }
    }

    fun bind(token: String?, userShortId: Int?) {
        if (token == this.token && userShortId == this.userShortId) return
        epoch++
        chats.values.forEach { it.detach() }
        chats.clear()
        mutableSelected.value = null
        mutableSessions.value = SessionsSnapshot()
        this.token = token
        this.userShortId = userShortId
        owned.bind(token)
        if (token != null && userShortId != null) owned.refresh()
    }

    fun isBoundTo(token: String?, userShortId: Int?): Boolean = token == this.token && userShortId == this.userShortId

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
}
