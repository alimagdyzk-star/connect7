package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.data.repository.ActiveTransferProgress
import com.example.ui.theme.AppleGreen
import com.example.ui.theme.AppleOrange
import com.example.ui.theme.AppleRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun deviceIconForType(type: String): ImageVector = when (type.lowercase()) {
    "laptop" -> Icons.Filled.LaptopMac
    "phone", "smartphone" -> Icons.Filled.Smartphone
    "tv" -> Icons.Filled.Tv
    else -> Icons.Filled.Computer
}

fun formatByteSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}

fun formatShortTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

fun formatDateAndTime(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))

// ============================================================================
// APPLE HIG REUSABLE DESIGN SYSTEM PRIMITIVES
// ============================================================================

/**
 * Apple iOS Inset Grouped Section Title (subtle secondary label above white rounded card).
 */
@Composable
fun AppleSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingActionLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        if (trailingActionLabel != null && onTrailingClick != null) {
            Text(
                text = trailingActionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onTrailingClick)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Apple iOS Inset Grouped Card Container (`secondarySystemGroupedBackground`, 16dp squircle).
 */
@Composable
fun AppleGroupedSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.5.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Hairline inset divider starting after the 32dp squircle icon column (58dp start inset),
 * matching iOS Settings & Files list separators.
 */
@Composable
fun AppleInsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    )
}

/**
 * Signature Apple rounded-square tinted icon badge (like iOS Settings / Control Center).
 */
@Composable
fun AppleSquircleIcon(
    icon: ImageVector,
    tintColor: Color,
    modifier: Modifier = Modifier,
    sizeDp: Int = 32
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp * 0.26f).dp))
            .background(tintColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((sizeDp * 0.58f).dp)
        )
    }
}

/**
 * Clean iOS-style grouped row with leading squircle icon, title/subtitle, optional value/badge, and chevron.
 */
@Composable
fun AppleGroupedRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailingText: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 54.dp)
        .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
        .let { m -> if (testTag != null) m.testTag(testTag) else m }
        .padding(horizontal = 16.dp, vertical = 11.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (leadingIcon != null) {
                AppleSquircleIcon(icon = leadingIcon, tintColor = iconTint)
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!trailingText.isNullOrBlank()) {
                    Text(
                        text = trailingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (showChevron && onClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Apple iOS Sliding Segmented Control (`UISegmentedControl` aesthetic).
 */
@Composable
fun AppleSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    testTags: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            options.forEachIndexed { idx, label ->
                val isSelected = idx == selectedIndex
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    animationSpec = spring(),
                    label = "segment_bg"
                )
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = bgColor,
                    shadowElevation = if (isSelected) 1.5.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelectIndex(idx) }
                        .let { m ->
                            if (idx < testTags.size) m.testTag(testTags[idx]) else m
                        }
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact Dynamic-Island-inspired active device capsule in the top navigation bar.
 * Replaces the old multi-row cluttered device bar with a sleek one-line Apple pill + dropdown menu.
 */
@Composable
fun ActiveDeviceSelectorStrip(
    devices: List<PairedDeviceEntity>,
    selectedDeviceId: String,
    localIpSummary: String,
    isScanning: Boolean,
    onSelectDevice: (String) -> Unit,
    onReconnectDevice: (String) -> Unit,
    onScanNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (devices.isEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.8.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onScanNetwork)
                    .testTag("active_device_capsule")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isScanning) MaterialTheme.colorScheme.primary else AppleGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScanning) "Scanning Wi-Fi…" else "Ready on $localIpSummary",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onScanNetwork,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("toggle_reachability_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = "Scan Wi-Fi network",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        return
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.deviceId == selectedDeviceId } ?: devices.first()

    val statusDotColor = when {
        selected.pairState != PairState.PAIRED -> AppleOrange
        selected.isReachable -> AppleGreen
        else -> AppleRed
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Sleek Apple Device Picker Pill
        Box {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { menuExpanded = true }
                    .testTag("active_device_capsule")
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = deviceIconForType(selected.deviceType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selected.deviceName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Switch device",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                devices.forEach { peer ->
                    val isCurrent = peer.deviceId == selected.deviceId
                    val batterySuffix = if (peer.batteryLevel in 0..100) " • ${peer.batteryLevel}%" else ""
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = peer.deviceName,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = when {
                                        peer.pairState != PairState.PAIRED -> "Unpaired • ${peer.ipAddress}"
                                        peer.isReachable -> "Connected$batterySuffix"
                                        else -> "Offline • ${peer.ipAddress}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = deviceIconForType(peer.deviceType),
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onSelectDevice(peer.deviceId)
                        },
                        modifier = Modifier.testTag("device_chip_${peer.deviceId}")
                    )
                }
            }
        }

        // Right-hand battery (only if reported by remote peer) + reconnect button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selected.batteryLevel in 0..100) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 0.5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selected.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                            contentDescription = "Battery",
                            tint = AppleGreen,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "${selected.batteryLevel}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            IconButton(
                onClick = { onReconnectDevice(selected.deviceId) },
                modifier = Modifier
                    .size(36.dp)
                    .testTag("toggle_reachability_button")
            ) {
                Icon(
                    imageVector = if (selected.isReachable) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    contentDescription = "Refresh peer connection",
                    tint = if (selected.isReachable) MaterialTheme.colorScheme.primary else AppleRed,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

/**
 * Clean AirDrop-inspired transfer progress card.
 */
@Composable
fun ActiveTransferBanner(
    progress: ActiveTransferProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (progress == null) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .testTag("active_transfer_banner")
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (progress.direction == "UPLOAD") "Sending ${progress.fileName}" else "Receiving ${progress.fileName}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatByteSize(progress.transferredBytes)} of ${formatByteSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("cancel_transfer_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cancel,
                        contentDescription = stringResource(R.string.files_cancel_transfer),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
