package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CropRegion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionSelectorScreen(
    initialRegion: CropRegion,
    onRegionSelected: (CropRegion) -> Unit,
    onNavigateBack: () -> Unit
) {
    var left by remember { mutableStateOf(initialRegion.left.coerceIn(0f, 0.8f)) }
    var top by remember { mutableStateOf(initialRegion.top.coerceIn(0f, 0.8f)) }
    var right by remember { mutableStateOf(initialRegion.right.coerceIn(left + 0.1f, 1f)) }
    var bottom by remember { mutableStateOf(initialRegion.bottom.coerceIn(top + 0.1f, 1f)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Slide Region Selector",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Exclude browser chrome & speaker video",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Crop Size: ${((right - left) * 100).toInt()}% × ${((bottom - top) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Origin: (${(left * 100).toInt()}%, ${(top * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            onRegionSelected(CropRegion(left, top, right, bottom))
                            onNavigateBack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("confirm_crop_region_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply Crop Region", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Preset Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = left == 0f && top == 0f && right == 1f && bottom == 1f,
                    onClick = {
                        left = 0f; top = 0f; right = 1f; bottom = 1f
                    },
                    label = { Text("Full Screen") },
                    leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors()
                )

                FilterChip(
                    selected = left == 0f && top == 0.08f && right == 1f && bottom == 0.92f,
                    onClick = {
                        left = 0f; top = 0.08f; right = 1f; bottom = 0.92f
                    },
                    label = { Text("Trim Chrome") },
                    leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )

                FilterChip(
                    selected = left == 0.05f && top == 0.12f && right == 0.95f && bottom == 0.88f,
                    onClick = {
                        left = 0.05f; top = 0.12f; right = 0.95f; bottom = 0.88f
                    },
                    label = { Text("Center 16:9") },
                    leadingIcon = { Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Text(
                "Drag the handles or the selection frame to define the exact lecture slide boundary:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Interactive Mock Screen & Crop Overlay
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                val boxWidth = constraints.maxWidth.toFloat()
                val boxHeight = constraints.maxHeight.toFloat()

                // Presentation background preview mockup
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Top browser/app toolbar mockup
                    drawRect(
                        color = Color(0xFF1E293B),
                        topLeft = Offset(0f, 0f),
                        size = Size(boxWidth, boxHeight * 0.08f)
                    )
                    // URL/title pill
                    drawRoundRect(
                        color = Color(0xFF334155),
                        topLeft = Offset(boxWidth * 0.2f, boxHeight * 0.015f),
                        size = Size(boxWidth * 0.6f, boxHeight * 0.05f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f)
                    )

                    // Presentation slide mockup in the center
                    val slideLeft = boxWidth * 0.05f
                    val slideTop = boxHeight * 0.12f
                    val slideW = boxWidth * 0.90f
                    val slideH = boxHeight * 0.76f

                    drawRoundRect(
                        color = Color(0xFF1E1B4B), // Deep violet slide
                        topLeft = Offset(slideLeft, slideTop),
                        size = Size(slideW, slideH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )

                    // Slide title header line
                    drawRect(
                        color = Color(0xFF6366F1),
                        topLeft = Offset(slideLeft + 20f, slideTop + 24f),
                        size = Size(slideW * 0.5f, 16f)
                    )
                    // Slide bullet lines
                    drawRect(
                        color = Color(0x99A5B4FC),
                        topLeft = Offset(slideLeft + 20f, slideTop + 54f),
                        size = Size(slideW * 0.7f, 10f)
                    )
                    drawRect(
                        color = Color(0x99A5B4FC),
                        topLeft = Offset(slideLeft + 20f, slideTop + 74f),
                        size = Size(slideW * 0.6f, 10f)
                    )

                    // Speaker webcam mockup in bottom right corner
                    drawRoundRect(
                        color = Color(0xFF0F172A),
                        topLeft = Offset(boxWidth * 0.75f, boxHeight * 0.65f),
                        size = Size(boxWidth * 0.22f, boxHeight * 0.20f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )
                    drawCircle(
                        color = Color(0xFF38BDF8),
                        radius = boxWidth * 0.05f,
                        center = Offset(boxWidth * 0.86f, boxHeight * 0.75f)
                    )

                    // Dim out excluded area
                    val selLeftPx = left * boxWidth
                    val selTopPx = top * boxHeight
                    val selWidthPx = (right - left) * boxWidth
                    val selHeightPx = (bottom - top) * boxHeight

                    // Top dim
                    drawRect(Color(0x88000000), Offset(0f, 0f), Size(boxWidth, selTopPx))
                    // Bottom dim
                    drawRect(Color(0x88000000), Offset(0f, selTopPx + selHeightPx), Size(boxWidth, boxHeight - (selTopPx + selHeightPx)))
                    // Left dim
                    drawRect(Color(0x88000000), Offset(0f, selTopPx), Size(selLeftPx, selHeightPx))
                    // Right dim
                    drawRect(Color(0x88000000), Offset(selLeftPx + selWidthPx, selTopPx), Size(boxWidth - (selLeftPx + selWidthPx), selHeightPx))

                    // Crop border
                    drawRect(
                        color = Color(0xFF22D3EE),
                        topLeft = Offset(selLeftPx, selTopPx),
                        size = Size(selWidthPx, selHeightPx),
                        style = Stroke(
                            width = 4f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f)
                        )
                    )
                }

                // Interactive Draggable Area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(boxWidth, boxHeight) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / boxWidth
                                val dy = dragAmount.y / boxHeight

                                val widthFrac = right - left
                                val heightFrac = bottom - top

                                var newLeft = (left + dx).coerceIn(0f, 1f - widthFrac)
                                var newTop = (top + dy).coerceIn(0f, 1f - heightFrac)
                                left = newLeft
                                top = newTop
                                right = newLeft + widthFrac
                                bottom = newTop + heightFrac
                            }
                        }
                )

                // Top-Left corner handle
                Box(
                    modifier = Modifier
                        .padding(
                            start = (left * boxWidth - 16).coerceAtLeast(0f).dp,
                            top = (top * boxHeight - 16).coerceAtLeast(0f).dp
                        )
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22D3EE))
                        .pointerInput(boxWidth, boxHeight) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / boxWidth
                                val dy = dragAmount.y / boxHeight
                                left = (left + dx).coerceIn(0f, right - 0.1f)
                                top = (top + dy).coerceIn(0f, bottom - 0.1f)
                            }
                        }
                )

                // Bottom-Right corner handle
                Box(
                    modifier = Modifier
                        .padding(
                            start = (right * boxWidth - 16).coerceIn(0f, boxWidth - 32).dp,
                            top = (bottom * boxHeight - 16).coerceIn(0f, boxHeight - 32).dp
                        )
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22D3EE))
                        .pointerInput(boxWidth, boxHeight) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / boxWidth
                                val dy = dragAmount.y / boxHeight
                                right = (right + dx).coerceIn(left + 0.1f, 1f)
                                bottom = (bottom + dy).coerceIn(top + 0.1f, 1f)
                            }
                        }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
