package com.game.launcher

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameLauncherTheme {
                GameLauncherScreen()
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Theme (Dark, phong cách gaming)
// ------------------------------------------------------------------------------------------

private val GamingColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF005229),
    onPrimaryContainer = Color(0xFF9CFFC4),
    secondary = Color(0xFF7C4DFF),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFFA9B0C0),
    surfaceContainerLow = Color(0xFF141821),
    surfaceContainer = Color(0xFF181D28),
    surfaceContainerHigh = Color(0xFF1E2430)
)

@Composable
fun GameLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GamingColorScheme, content = content)
}

// ------------------------------------------------------------------------------------------
// Màn hình chính
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLauncherScreen(viewModel: GameLauncherViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showAddSheet by remember { mutableStateOf(false) }
    var showDisplaySheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var gameToRemove by remember { mutableStateOf<GameModel?>(null) }
    var randomSelectedGame by remember { mutableStateOf<GameModel?>(null) }

    // Làm mới dữ liệu khi app quay lại foreground (ON_RESUME)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun launchGame(game: GameModel) {
        val intent = viewModel.getLaunchIntent(game.packageName)
        if (intent == null) {
            Toast.makeText(context, "Không thể mở ${game.name}", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            context.startActivity(intent)
            // Ghi nhận thời điểm chơi nội bộ khi mở thành công
            viewModel.recordGameLaunched(game.packageName)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Không thể mở ${game.name}", Toast.LENGTH_SHORT).show()
        }
    }

    fun selectRandomApp() {
        val library = state.library
        if (library.isEmpty()) {
            Toast.makeText(context, "Thư viện đang trống!", Toast.LENGTH_SHORT).show()
            return
        }
        // Sắp xếp thư viện theo thứ tự: dùng gần đây nhất đứng đầu
        val sortedByRecency = library.sortedWith(compareByDescending<GameModel> { it.lastPlayed }.thenBy { it.name })

        // Tính trọng số: index 0 (gần nhất) = 1, index 1 = 2, index 2 = 4, ...
        val weights = sortedByRecency.mapIndexed { index, _ -> Math.pow(2.0, index.toDouble()) }
        val totalWeight = weights.sum()

        val selected = if (totalWeight <= 0.0) {
            library.random()
        } else {
            val randomValue = kotlin.random.Random.nextDouble() * totalWeight
            var cumulativeSum = 0.0
            var found = sortedByRecency.first()

            for (i in sortedByRecency.indices) {
                cumulativeSum += weights[i]
                if (randomValue <= cumulativeSum) {
                    found = sortedByRecency[i]
                    break
                }
            }
            found
        }

        randomSelectedGame = selected
        Toast.makeText(context, "Mở ngẫu nhiên: ${selected.name}", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Thư viện game", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                actions = {
                    // Nút Thêm game vào thư viện
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Thêm game")
                    }

                    // Menu sắp xếp
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sắp xếp danh sách")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Chơi gần nhất") },
                                trailingIcon = {
                                    if (state.settings.sortMode == SortMode.RECENT) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    viewModel.setSortMode(SortMode.RECENT)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Tên A–Z") },
                                trailingIcon = {
                                    if (state.settings.sortMode == SortMode.NAME) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    viewModel.setSortMode(SortMode.NAME)
                                    showSortMenu = false
                                }
                            )
                        }
                    }

                    // Tùy chỉnh hiển thị (số cột, quyền)
                    IconButton(onClick = { showDisplaySheet = true }) {
                        Icon(Icons.Filled.GridView, contentDescription = "Tùy chỉnh hiển thị")
                    }

                    // Quét lại danh sách
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Quét lại")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { selectRandomApp() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Mở app ngẫu nhiên")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            when {
                state.isLoading && state.library.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.library.isEmpty() -> {
                    EmptyState(Modifier.align(Alignment.Center))
                }

                else -> {
                    val gridCells = when (val cols = state.settings.columns) {
                        is ColumnsMode.Auto -> GridCells.Adaptive(96.dp)
                        is ColumnsMode.Fixed -> GridCells.Fixed(cols.count)
                    }

                    LazyVerticalGrid(
                        columns = gridCells,
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = innerPadding.calculateBottomPadding() + 88.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.library, key = { it.packageName }) { game ->
                            GameGridItem(
                                game = game,
                                sortMode = state.settings.sortMode,
                                columnsMode = state.settings.columns,
                                onClick = { launchGame(game) },
                                onLongClick = { gameToRemove = game },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    // Bottom sheet: Thêm game
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddSheet = false
                viewModel.onSearchQueryChange("")
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            AddGameSheetContent(
                state = state,
                onQueryChange = viewModel::onSearchQueryChange,
                onShowSystemChange = viewModel::onShowSystemAppsChange,
                onAdd = viewModel::addGame
            )
        }
    }

    // Bottom sheet: Tùy chỉnh hiển thị
    if (showDisplaySheet) {
        ModalBottomSheet(
            onDismissRequest = { showDisplaySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            DisplaySettingsSheetContent(
                columnsMode = state.settings.columns,
                usageStatus = state.usageAccessStatus,
                onColumnsChange = viewModel::setColumnsMode,
                onRequestUsagePermission = {
                    val intent = viewModel.getUsageAccessSettingsIntent()
                    runCatching { context.startActivity(intent) }.onFailure {
                        Toast.makeText(context, "Không thể mở trang cài đặt quyền", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    // Hộp thoại xác nhận xin quyền PACKAGE_USAGE_STATS
    if (state.showUsagePermissionDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissUsagePermissionDialog,
            title = { Text("Đồng bộ lần chơi thực tế") },
            text = {
                Text("Cấp quyền Truy cập dữ liệu sử dụng để tự động sắp xếp theo lần chơi gần nhất, kể cả khi bạn mở game ngoài launcher.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = viewModel.getUsageAccessSettingsIntent()
                        runCatching { context.startActivity(intent) }.onFailure {
                            Toast.makeText(context, "Không thể mở trang cài đặt quyền", Toast.LENGTH_SHORT).show()
                        }
                        viewModel.onDismissUsagePermissionDialog()
                    }
                ) {
                    Text("Cấp quyền")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissUsagePermissionDialog) {
                    Text("Để sau")
                }
            }
        )
    }

    // Hộp thoại xác nhận xóa game khỏi thư viện
    gameToRemove?.let { game ->
        AlertDialog(
            onDismissRequest = { gameToRemove = null },
            title = { Text("Xóa khỏi thư viện?") },
            text = { Text("${game.name} sẽ được bỏ khỏi danh sách (không gỡ cài đặt app).") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeGame(game.packageName)
                    gameToRemove = null
                }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { gameToRemove = null }) { Text("Hủy") }
            }
        )
    }

    // Hộp thoại thông báo khi chọn app ngẫu nhiên
    randomSelectedGame?.let { game ->
        AlertDialog(
            onDismissRequest = { randomSelectedGame = null },
            icon = {
                Image(
                    bitmap = game.icon,
                    contentDescription = game.name,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            },
            title = { Text("Mở ứng dụng ngẫu nhiên") },
            text = {
                Text(
                    text = "Đã chọn: ${game.name}\n(Tỉ lệ tính theo tần suất sử dụng)",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val gameToLaunch = game
                    randomSelectedGame = null
                    launchGame(gameToLaunch)
                }) {
                    Text("Mở ngay")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { selectRandomApp() }) {
                        Text("Đổi app")
                    }
                    TextButton(onClick = { randomSelectedGame = null }) {
                        Text("Hủy")
                    }
                }
            }
        )
    }
}

// ------------------------------------------------------------------------------------------
// Thành phần UI
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameGridItem(
    game: GameModel,
    sortMode: SortMode,
    columnsMode: ColumnsMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Co giãn icon và cỡ chữ linh hoạt theo số cột
    val (iconSize: Dp, titleSize: Float, subSize: Float) = when (columnsMode) {
        is ColumnsMode.Auto -> Triple(64.dp, 12f, 10f)
        is ColumnsMode.Fixed -> when (columnsMode.count) {
            2 -> Triple(96.dp, 15f, 12f)
            3 -> Triple(76.dp, 14f, 11f)
            4 -> Triple(58.dp, 12f, 10f)
            5 -> Triple(46.dp, 11f, 9f)
            else -> Triple(38.dp, 10f, 8f) // 6 cột
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = game.icon,
            contentDescription = game.name,
            modifier = Modifier
                .size(iconSize)
                .clip(RoundedCornerShape(iconSize * 0.22f))
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = game.name,
            fontSize = titleSize.sp,
            lineHeight = (titleSize * 1.25f).sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        // Dòng phụ nhỏ khi đang ở chế độ "Chơi gần nhất"
        if (sortMode == SortMode.RECENT) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatRelativeTime(game.lastPlayed),
                fontSize = subSize.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Chuyển timestamp thành văn bản thân thiện tiếng Việt. */
private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return "Chưa chơi"
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0L)
    val minutes = diff / (60 * 1000)
    val hours = diff / (60 * 60 * 1000)
    val days = diff / (24 * 60 * 60 * 1000)
    return when {
        minutes < 1 -> "Vừa xong"
        minutes < 60 -> "$minutes phút trước"
        hours < 24 -> "$hours giờ trước"
        days == 1L -> "Hôm qua"
        days < 30 -> "$days ngày trước"
        else -> "${days / 30} tháng trước"
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.SportsEsports,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Thư viện đang trống",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Bấm nút + để thêm game từ các ứng dụng đã cài.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ------------------------------------------------------------------------------------------
// Sheet: Cài đặt hiển thị & Quyền dữ liệu sử dụng
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySettingsSheetContent(
    columnsMode: ColumnsMode,
    usageStatus: UsageAccessStatus,
    onColumnsChange: (ColumnsMode) -> Unit,
    onRequestUsagePermission: () -> Unit
) {
    val columnOptions = listOf("Tự động", "2", "3", "4", "5", "6")
    val selectedIndex = when (columnsMode) {
        is ColumnsMode.Auto -> 0
        is ColumnsMode.Fixed -> columnsMode.count - 1 // 2 -> 1, 3 -> 2, ...
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = "Hiển thị",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Số cột lưới",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // Thanh chọn số cột (Segmented Buttons có thể cuộn ngang nếu màn hình nhỏ)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            SingleChoiceSegmentedButtonRow {
                columnOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = columnOptions.size),
                        onClick = {
                            val newMode = if (index == 0) ColumnsMode.Auto else ColumnsMode.Fixed(index + 1)
                            onColumnsChange(newMode)
                        },
                        selected = index == selectedIndex
                    ) {
                        Text(label)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Đồng bộ hệ thống",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        // Dòng trạng thái / cấp quyền UsageStats
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = usageStatus == UsageAccessStatus.DENIED, onClick = onRequestUsagePermission)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Đồng bộ lần chơi từ hệ thống",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                val desc = when (usageStatus) {
                    UsageAccessStatus.GRANTED -> "Đã cấp quyền (tự động lấy dữ liệu từ hệ thống)"
                    UsageAccessStatus.DENIED -> "Chưa cấp quyền (nhấn để mở Cài đặt cấp quyền)"
                    UsageAccessStatus.NOT_SUPPORTED -> "Thiết bị không hỗ trợ tính năng này"
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            when (usageStatus) {
                UsageAccessStatus.GRANTED -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Đã cấp",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                UsageAccessStatus.DENIED -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Mở cài đặt",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                UsageAccessStatus.NOT_SUPPORTED -> {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Không hỗ trợ",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Sheet: Thêm game
// ------------------------------------------------------------------------------------------

@Composable
private fun AddGameSheetContent(
    state: GameLauncherUiState,
    onQueryChange: (String) -> Unit,
    onShowSystemChange: (Boolean) -> Unit,
    onAdd: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Thêm game vào thư viện",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Tìm ứng dụng...") },
            singleLine = true
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hiện cả app hệ thống",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(checked = state.showSystemApps, onCheckedChange = onShowSystemChange)
        }

        if (state.candidates.isEmpty()) {
            Text(
                text = "Không có ứng dụng nào để thêm.",
                modifier = Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(state.candidates, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = app.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = app.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onAdd(app.packageName) }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Thêm ${app.name}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
