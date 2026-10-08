package com.example.mapstyleeditor.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.mapstyleeditor.R
import com.example.mapstyleeditor.account.MapboxAccount
import com.example.mapstyleeditor.account.TokenCheck
import com.example.mapstyleeditor.account.checkToken
import kotlinx.coroutines.launch

/**
 * Shown until a Mapbox account is connected: open Mapbox to sign in (or sign up, free), copy the
 * default public token, paste it here. Mapbox has no "sign in with Mapbox" for other apps, so the
 * token is how an account is connected.
 */
@Composable
fun SignInScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    // Black screen, so light status-bar icons.
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = false }
    }

    fun submit() {
        val entered = token.trim()
        if (entered.isEmpty() || checking) return
        checking = true
        error = null
        scope.launch {
            when (val result = checkToken(entered)) {
                is TokenCheck.Valid -> MapboxAccount.signIn(context, entered, result.user)
                is TokenCheck.Invalid -> error = result.reason
            }
            checking = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(112.dp))
            Text("MinMap", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in with your Mapbox account to load the map. A free account is all you need.",
                color = Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            Step("1", "Sign in to Mapbox and copy your Default public token.")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKENS_PAGE)))
                    } catch (_: ActivityNotFoundException) {
                        error = "No browser found. Open $TOKENS_PAGE on another device."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("Open Mapbox", fontSize = 16.sp) }

            Spacer(Modifier.height(28.dp))
            Step("2", "Paste it here.")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    error = null
                },
                placeholder = { Text("pk.…", color = Muted) },
                singleLine = true,
                isError = error != null,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                trailingIcon = {
                    TextButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            token = text
                            error = null
                        }
                    }) { Text("Paste", color = Color.White) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color(0xFF3A3A40),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { submit() },
                enabled = token.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF2A2A2E),
                    disabledContentColor = Muted,
                ),
            ) { Text(if (checking) "Checking…" else "Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(20.dp))
            Text(
                "Your token stays on this phone. Map loads and searches count toward your own Mapbox free tier.",
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).background(Color.White, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(number, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Text(text, color = Color.White, fontSize = 15.sp)
    }
}

private val Muted = Color(0xFF9A9AA2)
private const val TOKENS_PAGE = "https://account.mapbox.com/access-tokens/"
