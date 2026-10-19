package me.rerere.rikkahub.ui.fairy

import android.content.Context
import me.rerere.rikkahub.Screen

/** Records the product destination restored after account sign-in. */
class FairyLastLocation(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Record where the user is standing. Called on every accepted destination. */
    fun record(screen: Screen) {
        val (route, conversation) = when (screen) {
            is Screen.Home -> "home" to null
            is Screen.Sessions -> "sessions" to null
            is Screen.Settings -> "settings" to null
            is Screen.Conversation -> "conversation" to screen.sessionId
            is Screen.Gateway -> "gateway" to null
            else -> return
        }
        prefs.edit().putString(KEY_ROUTE, route).putString(KEY_CONVERSATION, conversation).apply()
    }

    /**
     * The stack a cold start should open once the identity is restored.
     *
     * Sign-in is not remembered: the landing page is decided by the credential.
     * The conversation page authenticates a restored target and offers another
     * session when that target is no longer available.
     */
    fun restoredStack(): List<Screen> = when (prefs.getString(KEY_ROUTE, null)) {
        "sessions" -> listOf(Screen.Home, Screen.Sessions)
        "settings" -> listOf(Screen.Home, Screen.Settings)
        "gateway" -> listOf(Screen.Home, Screen.Settings, Screen.Gateway)
        "conversation" -> prefs.getString(KEY_CONVERSATION, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(Screen.Home, Screen.Conversation(it)) }
            ?: listOf(Screen.Home)
        else -> listOf(Screen.Home)
    }

    private companion object {
        const val FILE = "fairy_last_location"
        const val KEY_ROUTE = "route"
        const val KEY_CONVERSATION = "conversation"
    }
}
