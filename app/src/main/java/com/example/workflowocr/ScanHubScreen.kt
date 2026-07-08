package com.example.workflowocr

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp


@Composable
fun ScanHubScreen(onScanRequest: () -> Unit, onStubRequest: () -> Unit, onDebugScanRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Action: Make Picture / Scan
        Button(
            onClick = onScanRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("SCAN NEW SHEET", style = MaterialTheme.typography.titleMedium)
                Text("Run OpenCV + ML Kit", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onStubRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Use Stub")
        }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onDebugScanRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            Text("Put it into debug")
        }
    }
}

@Composable
fun ProcessingPreviewScreen(
    rawBitmap: Bitmap,
    diagnosticBitmap: Bitmap?,
    errorMessage: String?,
    onRedoClicked: () -> Unit
) {
    val isFailed = diagnosticBitmap != null

    // We wrap the main container column in a vertical scroll state.
    // This prevents layout overflows when two massive images + buttons are rendered simultaneously.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Dynamic Status Title block
        Text(
            text = if (isFailed) "Table Detection Failed" else "Analyzing Document...",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )

        if (isFailed) {
            // Primary Redo Action Button
            Button(
                onClick = onRedoClicked,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("REDO / RETAKE SHEET PICTURE", style = MaterialTheme.typography.titleMedium)
            }
        }

        // 1. PRIMARY CANVAS: Displays the main photo (or photo with processed cells)
        Text(
            text = "Captured Sheet / Cell Preview",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.Start)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp), // Fixed height so both fit on screen comfortably
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = rawBitmap.asImageBitmap(),
                    contentDescription = "Main Raw/Cell Preview Image Canvas",
                    modifier = Modifier.fillMaxSize()
                )

                // LOADING STATE: Show loading overlay placeholder while thread works
                if (!isFailed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Running OpenCV Grid Tiling...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 2. ADDITIONAL CONDITIONAL INFRASTRUCTURE (Only renders on failure)
        if (isFailed) {
            // Separator Title for clarity
            Text(
                text = "Computed Alignment Grid (Debug)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Start)
            )

            // ADDITIONAL DIAGNOSTIC IMAGE CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = diagnosticBitmap.asImageBitmap(),
                        contentDescription = "Diagnostic Line Grid Matrix Layer",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Explanatory Error Callout Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage ?: "Unknown structural table parsing layout anomaly.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun ScanPagePickerDialog(
    coordinator: OcrFlowCoordinator,
    enabledPages: Set<ScanPageType>,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { coordinator.onPagePickerDismissed() },
        title = { Text("Select Target Page Layout") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 4.dp)
            ) {
                if (enabledPages.isEmpty()) {
                    Text(
                        text = "No pages are enabled in settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val chunkedPages = remember(enabledPages) { enabledPages.chunked(2) }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    chunkedPages.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { pageType ->
                                ScanPageActionButton(
                                    pageType = pageType,
                                    onClick = { coordinator.onPageSelected(pageType) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Fill missing slots on rows that aren't perfectly filled out
                            if (rowItems.size < 2) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { coordinator.onPagePickerDismissed() }) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPageActionButton(
    pageType: ScanPageType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val borderStroke = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)

    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = borderStroke
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = pageType.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}