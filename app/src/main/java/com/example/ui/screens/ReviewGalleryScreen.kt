package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.SlideEntity
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.viewmodel.SlideViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReviewGalleryScreen(
    sessionId: Long,
    viewModel: SlideViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToPdfExport: (Long) -> Unit
) {
    val context = LocalContext.current
    val slides by viewModel.currentSessionSlides.collectAsState()
    val session by viewModel.currentSession.collectAsState()

    // Group comparison bottom sheet state
    var selectedGroupForInspection by remember { mutableStateOf<Long?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Full screen image zoom viewer state
    var fullScreenSlide by remember { mutableStateOf<SlideEntity?>(null) }

    // Photo picker for importing missing slides
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            viewModel.importSlideFromUri(uri, context)
        }
    }

    val selectedCount = slides.count { it.isSelected }
    val totalCount = slides.size

    // Group counts map
    val groupCounts = remember(slides) {
        slides.groupBy { it.groupId }.mapValues { it.value.size }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session?.title ?: "Review Session",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "$selectedCount of $totalCount slides selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Import from gallery button
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("import_gallery_button")
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import from Gallery")
                    }

                    // Auto-deduplicate / Regroup button
                    IconButton(
                        onClick = { viewModel.regroupSlides(sessionId) },
                        modifier = Modifier.testTag("regroup_button")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Re-cluster Duplicates")
                    }

                    // Toggle select all
                    IconButton(
                        onClick = {
                            val shouldSelect = selectedCount < totalCount
                            viewModel.setAllSlidesSelected(sessionId, shouldSelect)
                        }
                    ) {
                        Icon(
                            if (selectedCount == totalCount && totalCount > 0) Icons.Default.Close else Icons.Default.SelectAll,
                            contentDescription = "Select All"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (slides.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { onNavigateToPdfExport(sessionId) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("convert_to_pdf_fab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export PDF ($selectedCount)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        if (slides.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No slides captured yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Start a capture session or import slide photos from your device gallery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Photos")
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(slides, key = { _, slide -> slide.id }) { index, slide ->
                    val groupSize = groupCounts[slide.groupId] ?: 1

                    SlideGridCard(
                        slide = slide,
                        index = index,
                        groupSize = groupSize,
                        canMoveUp = index > 0,
                        canMoveDown = index < slides.size - 1,
                        onToggleSelect = { isSelected ->
                            viewModel.toggleSlideSelection(slide.id, isSelected)
                        },
                        onOpenGroup = {
                            selectedGroupForInspection = slide.groupId
                        },
                        onPreview = {
                            fullScreenSlide = slide
                        },
                        onMoveUp = {
                            viewModel.moveSlide(index, index - 1)
                        },
                        onMoveDown = {
                            viewModel.moveSlide(index, index + 1)
                        },
                        onDelete = {
                            viewModel.deleteSlide(slide)
                        }
                    )
                }
            }
        }
    }

    // Modal Bottom Sheet: Inspect Duplicate Group
    if (selectedGroupForInspection != null) {
        val groupId = selectedGroupForInspection!!
        val groupSlides = slides.filter { it.groupId == groupId }

        ModalBottomSheet(
            onDismissRequest = { selectedGroupForInspection = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Similar Slide Cluster (${groupSlides.size} captures)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Perceptually matched. The sharpest slide is auto-selected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { selectedGroupForInspection = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                groupSlides.forEach { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .border(
                                width = if (item.isRepresentative) 2.dp else 1.dp,
                                color = if (item.isRepresentative) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                viewModel.setGroupRepresentative(groupId, item.id)
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(File(item.filePath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Slide candidate",
                                modifier = Modifier
                                    .size(90.dp, 60.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(item.timestamp)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (item.isRepresentative) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = EmeraldSuccess.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "BEST (SHARPEST)",
                                                color = EmeraldSuccess,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    "Sharpness Score: ${String.format(Locale.US, "%.1f", item.sharpnessScore)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (item.edgeDetected) {
                                    Text(
                                        "Edge Detected & Cropped",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyanAccent,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Checkbox(
                                checked = item.isSelected,
                                onCheckedChange = { isChecked ->
                                    viewModel.toggleSlideSelection(item.id, isChecked)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Full Screen Zoom/Preview Dialog
    if (fullScreenSlide != null) {
        val slide = fullScreenSlide!!
        Dialog(onDismissRequest = { fullScreenSlide = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Slide Preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { fullScreenSlide = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(slide.filePath))
                            .crossfade(true)
                            .build(),
                        contentDescription = "Full slide",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Sharpness: ${String.format(Locale.US, "%.1f", slide.sharpnessScore)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(slide.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SlideGridCard(
    slide: SlideEntity,
    index: Int,
    groupSize: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onOpenGroup: () -> Unit,
    onPreview: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (slide.isSelected) 2.dp else 1.dp,
                color = if (slide.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // Thumbnail container with overlays
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(Color.Black)
                    .clickable { onPreview() }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(slide.filePath))
                        .crossfade(true)
                        .build(),
                    contentDescription = "Slide ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                // Page number badge
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        "#${index + 1}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Group badge (if multiple duplicates)
                if (groupSize > 1) {
                    Surface(
                        color = Color(0xFF1E1B4B).copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clickable { onOpenGroup() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "$groupSize similar",
                                color = CyanAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Selection Checkbox
                Checkbox(
                    checked = slide.isSelected,
                    onCheckedChange = onToggleSelect,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = Color.White
                    )
                )
            }

            // Card Bottom Details & Reordering actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(slide.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                    Text(
                        "Focus: ${String.format(Locale.US, "%.0f", slide.sharpnessScore)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (slide.sharpnessScore > 30) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canMoveUp) {
                        IconButton(
                            onClick = onMoveUp,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Left/Up", modifier = Modifier.size(16.dp))
                        }
                    }

                    if (canMoveDown) {
                        IconButton(
                            onClick = onMoveDown,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Right/Down", modifier = Modifier.size(16.dp))
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Slide",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
