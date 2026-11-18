package com.ahmedmunna.callrecorder.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.ahmedmunna.callrecorder.security.Biometrics
import com.ahmedmunna.callrecorder.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen gate shown when a PIN and/or biometric lock is enabled. */
@Composable
fun LockScreen(vm: MainViewModel, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val canBio = settings.biometricLock && Biometrics.available(ctx)

    fun bio() {
        (ctx as? FragmentActivity)?.let {
            Biometrics.prompt(it, "Unlock Advanced Auto Call Recorder", onUnlocked) { msg -> error = msg }
        }
    }
    LaunchedEffect(canBio) { if (canBio) bio() }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.padding(bottom = 16.dp), tint = MaterialTheme.colorScheme.primary)
        Text("App locked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        if (settings.pinHash != null) {
            OutlinedTextField(
                pin, { if (it.length <= 12 && it.all(Char::isDigit)) pin = it; error = null },
                Modifier.fillMaxWidth(), label = { Text("PIN") }, singleLine = true, isError = error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = { error?.let { Text(it) } },
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        if (vm.verifyPin(pin)) onUnlocked()
                        else {
                            failures++
                            error = "Wrong PIN"
                            pin = ""
                            delay((failures.coerceAtMost(5) * 1000L)) // slows down guessing
                        }
                        busy = false
                    }
                },
                enabled = pin.length >= 4 && !busy, modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock") }
        }
        if (canBio) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ bio() }, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, null); Text("  Use biometrics")
            }
            if (settings.pinHash == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun PinEntryDialog(title: String, vm: MainViewModel, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                pin, { if (it.length <= 12 && it.all(Char::isDigit)) { pin = it; error = false } },
                label = { Text("PIN") }, singleLine = true, isError = error,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = { if (error) Text("Wrong PIN") },
            )
        },
        confirmButton = {
            TextButton({ scope.launch { if (vm.verifyPin(pin)) onSuccess() else { error = true; pin = "" } } }, enabled = pin.length >= 4) { Text("Unlock") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Dialog to create a new PIN (entered twice). */
@Composable
fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= 4 && a == b
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                Text("4–12 digits. There is no way to recover a forgotten PIN except clearing the app's data, which deletes your recordings' metadata.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(a, { if (it.length <= 12 && it.all(Char::isDigit)) a = it }, label = { Text("New PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(b, { if (it.length <= 12 && it.all(Char::isDigit)) b = it }, label = { Text("Repeat PIN") }, singleLine = true,
                    isError = b.isNotEmpty() && a != b,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        },
        confirmButton = { TextButton({ onSet(a) }, enabled = ok) { Text("Save PIN") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
