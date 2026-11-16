package com.game.launcher

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    libraryGames: List<GameModel>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Cập nhật dữ liệu khi vào màn hình hoặc khi danh sách game thay đổi
    LaunchedEffect(libraryGames) {
        viewModel.updateLibraryAndLoad(libraryGames)
    }

    // Tự động tải lại dữ liệu khi người dùng quay lại từ Cài đặt (ON_RESUME)
    DisposableEffect(lifecycleOwner, libraryGames) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updateLibraryAndLoad(libraryGames)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Thống kê thời gian chơi",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                // Trạng thái 1: Chưa cấp quyền truy cập dữ liệu sử dụng
                !uiState.hasPermission -> {
                    PermissionExplanationView(
                        onGrantPermission = {
                            try {
                                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Không thể mở Cài đặt truy cập dữ liệu sử dụng",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    )
                }

                // Trạng thái 2: Đang tải dữ liệu
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Trạng thái 3: Đã cấp quyền và hoàn tất tải dữ liệu
                else -> {
                    StatsContent(
                        uiState = uiState,
                        onSelectPeriod = viewModel::selectPeriod,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Màn hình giải thích và nút cấp quyền khi chưa được cấp quyền Usage Stats.
 */
@Composable
private fun PermissionExplanationView(
    onGrantPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Cần quyền truy cập sử dụng",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Để hiển thị chính xác thời gian chơi và biểu đồ hoạt động của các game, ứng dụng cần được cấp quyền \"Truy cập dữ liệu sử dụng\" trong Cài đặt hệ thống.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onGrantPermission,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(text = "Cấp quyền", fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Nội dung chính màn hình thống kê: Chuyển đổi Hôm nay/7 ngày, Thống kê tổng, Biểu đồ và Xếp hạng.
 */
@Composable
private fun StatsContent(
    uiState: StatsUiState,
    onSelectPeriod: (StatsPeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hàng chọn thời gian: "Hôm nay | 7 ngày"
        item(key = "period_selector") {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = uiState.selectedPeriod == StatsPeriod.TODAY,
                    onClick = { onSelectPeriod(StatsPeriod.TODAY) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Hôm nay")
                }
                SegmentedButton(
                    selected = uiState.selectedPeriod == StatsPeriod.LAST_7_DAYS,
                    onClick = { onSelectPeriod(StatsPeriod.LAST_7_DAYS) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("7 ngày")
                }
            }
        }

        // Kiểm tra trạng thái không có dữ liệu
        if (uiState.totalTimeMs <= 0L || uiState.rankedGames.isEmpty()) {
            item(key = "empty_data") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chưa có dữ liệu chơi game trong khoảng này",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // (1) Tổng thời gian chơi của các game trong thư viện
            item(key = "total_time_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = if (uiState.selectedPeriod == StatsPeriod.TODAY) {
                                "Tổng thời gian chơi hôm nay"
                            } else {
                                "Tổng thời gian chơi (7 ngày qua)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = uiState.totalTimeFormatted,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // (2) Biểu đồ cột theo ngày (chỉ ở chế độ 7 ngày)
            if (uiState.selectedPeriod == StatsPeriod.LAST_7_DAYS && uiState.dailyUsages.isNotEmpty()) {
                item(key = "daily_chart_card") {
                    DailyBarChartCard(dailyUsages = uiState.dailyUsages)
                }
            }

            // Tiêu đề danh sách xếp hạng
            item(key = "ranking_header") {
                Text(
                    text = "Xếp hạng game",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // (3) Danh sách xếp hạng theo game (giảm dần theo thời gian, bỏ game 0 phút)
            items(
                items = uiState.rankedGames,
                key = { it.game.packageName }
            ) { item ->
                GameRankedItem(item = item)
            }
        }
    }
}

/**
 * Biểu đồ 7 cột theo ngày, vẽ thuần bằng Jetpack Compose.
 * Tô nổi bật cột hôm nay, gắn nhãn thứ trong tuần.
 */
@Composable
private fun DailyBarChartCard(
    dailyUsages: List<DailyUsage>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Thời gian theo ngày",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))

            val maxDailyMs = dailyUsages.maxOfOrNull { it.timeMs }?.coerceAtLeast(1L) ?: 1L
            val chartHeight = 120.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight + 42.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                dailyUsages.forEach { day ->
                    val fraction = if (day.timeMs > 0L) {
                        (day.timeMs.toFloat() / maxDailyMs.toFloat()).coerceIn(0.08f, 1f)
                    } else {
                        0f
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        // Nhãn thời gian nhỏ trên đầu cột
                        Text(
                            text = if (day.timeMs > 0L) UsageStatsRepository.formatShortUsageTime(day.timeMs) else "",
                            fontSize = 9.sp,
                            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                        Spacer(Modifier.height(4.dp))

                        // Thân cột
                        Box(
                            modifier = Modifier
                                .height(chartHeight)
                                .width(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (fraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((chartHeight.value * fraction).dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (day.isToday) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                            }
                                        )
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Nhãn thứ trong tuần
                        Text(
                            text = day.dayLabel,
                            fontSize = 11.sp,
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Một dòng game trong danh sách xếp hạng.
 */
@Composable
private fun GameRankedItem(
    item: GameStatDisplayItem,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = item.game.icon,
                contentDescription = item.game.displayName,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.game.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.timeFormatted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Thanh tiến độ ngang tỉ lệ với game chơi nhiều nhất
                LinearProgressIndicator(
                    progress = { item.ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }
    }
}
