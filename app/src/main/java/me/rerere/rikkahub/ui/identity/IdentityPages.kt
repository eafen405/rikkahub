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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.mutableIntStateOf
import me.rerere.rikkahub.ui.fairy.FairyBackground
import me.rerere.rikkahub.ui.fairy.FairyColdStart
import me.rerere.rikkahub.ui.fairy.FairyCrtPowerOn
import me.rerere.rikkahub.ui.fairy.FairyHddBoot
import me.rerere.rikkahub.ui.fairy.LocalFairyTokens
import me.rerere.rikkahub.ui.fairy.fairySlant
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.IdentityRefused
import me.rerere.rikkahub.data.remote.IdentityUnreachable
import org.koin.compose.koinInject

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
    val tokens = LocalFairyTokens.current
    var booted by remember { mutableStateOf(false) }
    var powerOn by remember { mutableIntStateOf(if (FairyColdStart.claim()) 1 else 0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.bgTop, tokens.bgBottom))),
    ) {
        FairyBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = "HOLLOW DEEP DIVE",
                fontFamily = tokens.titleFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                color = tokens.textPrimary,
            )
            Text(
                text = "PROXY TERMINAL",
                fontFamily = tokens.labelFontFamily,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = tokens.fairyLink.copy(alpha = 0.7f),
                modifier = Modifier.fairySlant(),
            )
            Spacer(Modifier.height(16.dp))
            FairyHddBoot(onBooted = { booted = true })
            androidx.compose.animation.AnimatedVisibility(
                visible = booted,
                enter = fadeIn(tween(300)),
            ) {
            Column {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.identity_sign_in_title),
                fontFamily = tokens.titleFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                color = tokens.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.identity_sign_in_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textMuted,
            )
            Spacer(Modifier.height(16.dp))

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
        FairyCrtPowerOn(powerOn)
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
    val tokens = LocalFairyTokens.current
    var powerOn by remember { mutableIntStateOf(if (FairyColdStart.claim()) 1 else 0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.bgTop, tokens.bgBottom))),
    ) {
        FairyBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = "INVITE REDEMPTION",
                fontFamily = tokens.labelFontFamily,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = tokens.fairyLink.copy(alpha = 0.7f),
                modifier = Modifier.fairySlant(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.identity_register_title),
                fontFamily = tokens.titleFontFamily,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                color = tokens.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.identity_register_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textMuted,
            )
            Spacer(Modifier.height(20.dp))

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
        FairyCrtPowerOn(powerOn)
    }
}

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
