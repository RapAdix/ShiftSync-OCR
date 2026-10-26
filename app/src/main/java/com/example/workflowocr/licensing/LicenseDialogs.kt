package com.example.workflowocr.licensing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.workflowocr.BuildConfig
import com.example.workflowocr.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun OrganizationSignInDialog(
    licenseManager: LicenseManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var orgCode by remember { mutableStateOf(licenseManager.state.organizationCode) }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.license_sign_in_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.license_sign_in_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = orgCode,
                    onValueChange = { orgCode = it; errorMessage = null },
                    label = { Text(stringResource(R.string.license_org_code_label)) },
                    placeholder = { Text("ORG-101") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text(stringResource(R.string.license_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.license_device_nickname_label)) },
                    placeholder = { Text(stringResource(R.string.license_device_nickname_placeholder)) },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (orgCode.isBlank() || password.isBlank()) {
                        errorMessage = "Please enter both Organization Code and License Key."
                        return@Button
                    }
                    isSubmitting = true
                    errorMessage = null
                    scope.launch {
                        val outcome = licenseManager.signIn(orgCode, password, nickname)
                        isSubmitting = false
                        when (outcome) {
                            is LoginOutcome.Success -> onDismiss()
                            is LoginOutcome.SlotsFull -> {
                                // Close sign-in dialog; MainActivity will immediately show the Slot Picker dialog
                                onDismiss()
                            }
                            is LoginOutcome.Error -> {
                                errorMessage = outcome.message
                            }
                        }
                    }
                },
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.license_sign_in_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * Shown when an organization's `1..maxDevices` slots are all occupied, allowing the user
 * to pick which Device Number (`#1..#N`) to log out and take over.
 */
@Composable
fun DeviceSlotReplacementDialog(
    slotsFull: LoginOutcome.SlotsFull,
    licenseManager: LicenseManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val defaultSelection = remember(slotsFull) {
        slotsFull.activeDevices.minByOrNull { it.lastSeenAtMillis }?.slotNumber
            ?: slotsFull.activeDevices.firstOrNull()?.slotNumber
            ?: 1
    }
    var selectedSlot by remember(slotsFull) { mutableIntStateOf(defaultSelection) }
    var isReplacing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isReplacing) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.license_slots_full_title, slotsFull.maxDevices),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.license_slots_full_description, slotsFull.orgName, slotsFull.maxDevices),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                slotsFull.activeDevices.forEach { slotInfo ->
                    val isSelected = selectedSlot == slotInfo.slotNumber
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isReplacing) { selectedSlot = slotInfo.slotNumber },
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { if (!isReplacing) selectedSlot = slotInfo.slotNumber }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        R.string.license_device_slot_badge,
                                        slotInfo.slotNumber,
                                        slotsFull.maxDevices
                                    ) + " — ${slotInfo.deviceLabel}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.license_slot_last_active,
                                        formatRelativeTime(slotInfo.lastSeenAtMillis)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isReplacing = true
                    errorMessage = null
                    scope.launch {
                        val outcome = licenseManager.takeoverSlot(slotsFull, selectedSlot)
                        isReplacing = false
                        when (outcome) {
                            is LoginOutcome.Success -> onDismiss()
                            is LoginOutcome.Error -> errorMessage = outcome.message
                            is LoginOutcome.SlotsFull -> Unit
                        }
                    }
                },
                enabled = !isReplacing
            ) {
                if (isReplacing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.license_replace_slot_button, selectedSlot))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isReplacing
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * Confirmation popup before rolling back to Demo Mode.
 * Warns that all saved schedules, employee snippets, projections, and scanned VLH tables will be deleted,
 * while preserving Settings.
 */
@Composable
fun RollbackToDemoConfirmDialog(
    licenseManager: LicenseManager,
    onConfirmWipeAndRollback: suspend () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.license_rollback_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = stringResource(R.string.license_rollback_message),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    isWorking = true
                    scope.launch {
                        licenseManager.rollbackToDemoMode(onConfirmWipeAndRollback)
                        isWorking = false
                        onDismiss()
                    }
                },
                enabled = !isWorking,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.license_rollback_confirm_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isWorking
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * Full-screen lockout rendered whenever `LicenseManager.hasDemoAccess()` is `false`
 * (i.e. user is signed in, and their subscription expired > 3 days ago OR hasn't connected online in > 3 days
 * OR their device slot was taken over by another phone).
 *
 * Blocks all app usage (including "Use previous photo" and saved schedules) until they:
 * 1. Verify online (if renewed / back online)
 * 2. Sign in to another account
 * 3. Rollback to Demo Mode (which deletes all non-settings data after confirmation)
 */
@Composable
fun LicenseLockoutScreen(
    licenseManager: LicenseManager
) {
    val scope = rememberCoroutineScope()
    val state = licenseManager.state
    val reason = licenseManager.getLockReason()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (reason) {
                    LicenseLockReason.SUBSCRIPTION_EXPIRED_OVER_3_DAYS ->
                        stringResource(R.string.license_lock_expired_title)
                    LicenseLockReason.OFFLINE_OVER_3_DAYS ->
                        stringResource(R.string.license_lock_offline_title)
                    LicenseLockReason.CLOCK_ROLLBACK_DETECTED ->
                        stringResource(R.string.license_lock_clock_title)
                    LicenseLockReason.SESSION_REVOKED ->
                        stringResource(R.string.license_lock_revoked_title)
                    LicenseLockReason.NONE ->
                        stringResource(R.string.license_lock_expired_title)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${state.organizationName.ifBlank { state.organizationCode }} • " +
                            stringResource(R.string.license_device_slot_badge, state.slotNumber, state.maxDevices),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.license_paid_until_label, formatDate(state.paidUntilMillis)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(
                            R.string.license_last_verified_label,
                            formatRelativeTime(state.lastVerifiedWallClockMillis)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (reason) {
                    LicenseLockReason.SUBSCRIPTION_EXPIRED_OVER_3_DAYS ->
                        stringResource(R.string.license_lock_expired_body)
                    LicenseLockReason.OFFLINE_OVER_3_DAYS ->
                        stringResource(R.string.license_lock_offline_body)
                    LicenseLockReason.CLOCK_ROLLBACK_DETECTED ->
                        stringResource(R.string.license_lock_clock_body)
                    LicenseLockReason.SESSION_REVOKED ->
                        stringResource(R.string.license_lock_revoked_body)
                    LicenseLockReason.NONE ->
                        stringResource(R.string.license_lock_expired_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (licenseManager.lastStatusMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = licenseManager.lastStatusMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { scope.launch { licenseManager.verifyOnlineNow() } },
                enabled = !licenseManager.isVerifying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (licenseManager.isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.license_verify_now_button))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { licenseManager.showSignInDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Key, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.license_switch_account_button))
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = { licenseManager.showRollbackConfirmDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.license_rollback_to_demo_button))
            }
        }
    }
}

/**
 * Settings Screen card displaying:
 * - Current Organization Name & Code
 * - Prominent Device Number badge (`Device #k of N`)
 * - Subscription expiration date & last online verification time
 * - Buttons to Verify Now, Sign In / Switch Organization, or Rollback to Demo Mode
 * - (Internal Flavor Only): Toggle for Stubbed Authorization so developers/tests can test both modes
 */
@Composable
fun LicenseSettingsSectionCard(
    licenseManager: LicenseManager
) {
    val scope = rememberCoroutineScope()
    val state = licenseManager.state

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.license_settings_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                if (state.isSignedIn && state.slotNumber > 0) {
                    val devicePrefix = stringResource(R.string.license_device_prefix)
                    val slotRatio = stringResource(R.string.license_slot_ratio, state.slotNumber, state.maxDevices)
                    val badgeText = buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)) {
                            append(devicePrefix)
                            if (!devicePrefix.endsWith(" ")) append(" ")
                        }
                        withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)) {
                            append(slotRatio)
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = badgeText,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 7.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                }
            }

            if (BuildConfig.IS_INTERNAL_FLAVOR) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Internal Flavor Stub Authorization",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Bypasses server licensing checks for automated tests and development",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.internalStubActive,
                        onCheckedChange = { licenseManager.setInternalStubEnabled(it) }
                    )
                }
                HorizontalDivider()
            }

            if (state.isSignedIn) {
                Text(
                    text = "${state.organizationName.ifBlank { state.organizationCode }} (${state.organizationCode})",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.license_device_name_label, state.deviceLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.license_paid_until_label, formatDate(state.paidUntilMillis)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.license_last_verified_label,
                        formatRelativeTime(state.lastVerifiedWallClockMillis)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { scope.launch { licenseManager.verifyOnlineNow() } },
                        enabled = !licenseManager.isVerifying,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.license_verify_now_button))
                    }
                    OutlinedButton(
                        onClick = { licenseManager.showRollbackConfirmDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.license_rollback_short_button))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.license_demo_mode_status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { licenseManager.showSignInDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.license_sign_in_button))
                }
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

private fun formatRelativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "never"
    val diff = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 48 -> "${hours}h ago"
        else -> "${days}d ago"
    }
}
