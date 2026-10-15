package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.Screen

/**
 * The one screen a start-up lands on for a given identity.
 *
 * This is a decision about a *settled* session, which is why it lives here as a
 * plain function of [IdentityScope]: the navigation stack is created from it, and
 * a start-up that seeds the stack before `AccountSession.restore()` has answered
 * seeds it from an identity that does not exist yet — pointed at sign-in, which
 * [IdentityAccessPolicy] refuses the moment the credential turns out to be good.
 *
 * Keeping it out of the composable also keeps it answerable without a device.
 */
fun identityStartScreen(identity: IdentityScope): Screen =
    if (identity.signedIn) Screen.Account else Screen.SignIn
