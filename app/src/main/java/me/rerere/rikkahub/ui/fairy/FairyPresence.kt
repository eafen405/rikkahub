package me.rerere.rikkahub.ui.fairy

/** What Fairy is doing right now, as shown by her window. */
enum class FairyPresence {
    IDLE,
    THINKING,
    SPEAKING,
    OFFLINE,
    CONNECTING;

    /** Thinking or speaking: the working glow, fast breath and active accent bar. */
    val isWorking: Boolean get() = this == THINKING || this == SPEAKING
}
