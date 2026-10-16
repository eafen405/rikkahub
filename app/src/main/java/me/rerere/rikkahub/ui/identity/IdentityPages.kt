package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.IdentityBackend
import me.rerere.rikkahub.data.remote.IdentityRefused
import me.rerere.rikkahub.data.remote.IdentityUnreachable
import org.koin.compose.koinInject

/**
 * The identity screens this release actually delivers.
 *
 * They are deliberately plain: the first version's job is to establish who the
 * caller is and to keep the path from an invite to a working identity short.
 * Every area that is *not* delivered does not get a screen at all — it composes
 * [IdentityNotReadyPage] instead, so there is no partially working page to
 * reach and no legacy path left triggerable.
 */

/** Sign in as an existing identity. */
@Composable
fun IdentitySignInPage(
    onSignedIn: () -> Unit,
    onRegister: () -> Unit,
    session: AccountSession = koinInject(),
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unreachableText = stringResource(R.string.identity_unreachable)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.identity_sign_in_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.identity_sign_in_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.identity_name_label)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.identity_password_label)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            failure?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (busy || name.isBlank()) return@Button
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            session.signIn(name.trim(), password)
                            onSignedIn()
                        } catch (refused: IdentityRefused) {
                            failure = refused.message ?: refused.code
                        } catch (unreachable: IdentityUnreachable) {
                            failure = unreachableText
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                } else {
                    Text(stringResource(R.string.identity_sign_in_action))
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRegister, enabled = !busy) {
                Text(stringResource(R.string.identity_have_invite))
            }
        }
    }
}

/** Redeem an invite and become a new identity. */
@Composable
fun IdentityRegisterPage(
    onRegistered: () -> Unit,
    onBackToSignIn: () -> Unit,
    session: AccountSession = koinInject(),
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unreachableText = stringResource(R.string.identity_unreachable)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.identity_register_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.identity_register_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.identity_invite_code_label)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.identity_name_label)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.identity_password_label)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            failure?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (busy || code.isBlank() || name.isBlank()) return@Button
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            session.register(code.trim(), name.trim(), password)
                            onRegistered()
                        } catch (refused: IdentityRefused) {
                            failure = refused.message ?: refused.code
                        } catch (unreachable: IdentityUnreachable) {
                            failure = unreachableText
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && code.isNotBlank() && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                } else {
                    Text(stringResource(R.string.identity_register_action))
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBackToSignIn, enabled = !busy) {
                Text(stringResource(R.string.identity_back_to_sign_in))
            }
        }
    }
}

/**
 * The identity area: who this app is acting as, and the operations that
 * identity is allowed to perform.
 *
 * The invite operation is the visible difference between the identities: an
 * ordinary agent does not see it, and the backend refuses it as well, so hiding
 * it is a courtesy rather than the protection.
 */
@Composable
fun IdentityAccountPage(
    onSessions: () -> Unit,
    onSignedOut: () -> Unit,
    session: AccountSession = koinInject(),
    backend: IdentityBackend = koinInject(),
) {
    val user = session.user
    val token = session.token
    val scope = rememberCoroutineScope()
    val unreachableText = stringResource(R.string.identity_unreachable)

    var invite by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(R.string.identity_account_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(20.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = user?.name.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.identity_short_id_label)}: ${user?.shortId ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            if (user?.isAdmin == true) R.string.identity_role_admin
                            else R.string.identity_role_agent,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            RemoteReceiveSetup()

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSessions,
                enabled = token != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sessions_title))
            }

            if (user?.isAdmin == true) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.identity_invites_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.identity_invites_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (busy || token == null) return@Button
                        busy = true
                        failure = null
                        scope.launch {
                            try {
                                invite = backend.createInvite(token)
                            } catch (refused: IdentityRefused) {
                                failure = refused.message ?: refused.code
                            } catch (unreachable: IdentityUnreachable) {
                                failure = unreachableText
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && token != null,
                ) {
                    Text(stringResource(R.string.identity_invites_create_action))
                }
                invite?.let { created ->
                    Spacer(Modifier.height(12.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.identity_invites_created),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = created,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            failure?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        session.signOut()
                        busy = false
                        onSignedOut()
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.identity_sign_out_action))
            }
        }
    }
}

/**
 * What an area that this release does not deliver composes.
 *
 * Naming the reason rather than silently rendering nothing is the point: a
 * blank screen invites the belief that a feature is merely unfinished, while an
 * explicit refusal states that the area is out of scope. Nothing here is
 * reachable by another route either — the gate refuses the destination for
 * every identity, the administrator included.
 */
@Composable
fun IdentityNotReadyPage(
    identity: IdentityScope,
    onSignIn: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.identity_not_ready_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.identity_not_ready_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!identity.signedIn) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.identity_not_ready_signed_out),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSignIn) {
                    Text(stringResource(R.string.identity_sign_in_action))
                }
            }
        }
    }
}
