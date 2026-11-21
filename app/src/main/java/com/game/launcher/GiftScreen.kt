@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.game.launcher

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GiftScreen(
    viewModel: GiftViewModel,
    libraryGames: List<GameModel>,
    onLaunchGame: (GameModel, String?) -> Unit // (game, giftId nếu note ĐÃ CÓ SẴN)
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Sheets / Dialogs
    var showAddSheet by remember { mutableStateOf(false) }
    var editingGift by remember { mutableStateOf<Gift?>(null) }
    var rescheduleGiftId by remember { mutableStateOf<String?>(null) }
    var deleteAllClaimedConfirm by remember { mutableStateOf(false) }

    // Xin quyền thông báo (API 33+)
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Dù từ chối vẫn lưu bình thường, banner sẽ hiện */ }

    LaunchedEffect(viewModel) {
        viewModel.requestNotificationPermission.collect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Lắng nghe sự kiện một lần từ ViewModel
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GiftEvent.ShowSnackbar -> {
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = event.message,
                            actionLabel = event.actionLabel,
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            event.onAction?.invoke()
                        }
                    }
                }
                is GiftEvent.OpenRescheduleSheet -> {
                    rescheduleGiftId = event.giftId
                }
                is GiftEvent.ShowSuggestionSnackbar -> {
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Đã nhận quà ${event.gameDisplayName} chưa?",
                            actionLabel = "Hẹn giờ mới",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            rescheduleGiftId = event.giftId
                        }
                    }
                }
            }
        }
    }

    // Tìm gift để reschedule
    val rescheduleGift = rescheduleGiftId?.let { id ->
        uiState.groups.ready.find { it.gift.id == id }?.gift
            ?: uiState.groups.waiting.find { it.gift.id == id }?.gift
            ?: uiState.groups.claimed.find { it.gift.id == id }?.gift
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nhắc nhận quà", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Thêm ghi chú quà")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Banner thông báo bị tắt
            if (!uiState.notificationsEnabled) {
                NotificationBannerBar(
                    onOpenSettings = {
                        runCatching {
                            context.startActivity(viewModel.getNotificationSettingsIntent())
                        }
                    }
                )
            }

            val groups = uiState.groups
            val isEmpty = groups.ready.isEmpty() && groups.waiting.isEmpty() && groups.claimed.isEmpty()

            if (isEmpty) {
                GiftEmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    // Nhóm: Đã có sẵn
                    if (groups.ready.isNotEmpty()) {
                        item(key = "header_ready") {
                            GiftSectionHeader("Đã có sẵn")
                        }
                        items(groups.ready, key = { it.gift.id }) { item ->
                            GiftRow(
                                item = item,
                                onToggleClaim = { viewModel.toggleClaim(item.gift.id) },
                                onReschedule = { rescheduleGiftId = item.gift.id },
                                onEdit = { editingGift = item.gift },
                                onDelete = { viewModel.deleteGift(item.gift.id) },
                                onLaunchGame = {
                                    item.game?.let { game ->
                                        if (item.status == GiftStatus.READY) {
                                            viewModel.markLaunchedFromReadyGift(item.gift.id)
                                        }
                                        onLaunchGame(game, item.gift.id)
                                    }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    // Nhóm: Đang chờ
                    if (groups.waiting.isNotEmpty()) {
                        item(key = "header_waiting") {
                            GiftSectionHeader("Đang chờ")
                        }
                        items(groups.waiting, key = { it.gift.id }) { item ->
                            GiftRow(
                                item = item,
                                onToggleClaim = { /* không làm gì, checkbox disabled */ },
                                onReschedule = { rescheduleGiftId = item.gift.id },
                                onEdit = { editingGift = item.gift },
                                onDelete = { viewModel.deleteGift(item.gift.id) },
                                onLaunchGame = {
                                    item.game?.let { game -> onLaunchGame(game, null) }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    // Nhóm: Đã nhận
                    if (groups.claimed.isNotEmpty()) {
                        item(key = "header_claimed") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Đã nhận",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { deleteAllClaimedConfirm = true }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Xóa các note đã nhận",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                        items(groups.claimed, key = { it.gift.id }) { item ->
                            GiftRow(
                                item = item,
                                onToggleClaim = { viewModel.toggleClaim(item.gift.id) },
                                onReschedule = { rescheduleGiftId = item.gift.id },
                                onEdit = { editingGift = item.gift },
                                onDelete = { viewModel.deleteGift(item.gift.id) },
                                onLaunchGame = {
                                    item.game?.let { game -> onLaunchGame(game, null) }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog xác nhận xóa tất cả đã nhận
    if (deleteAllClaimedConfirm) {
        AlertDialog(
            onDismissRequest = { deleteAllClaimedConfirm = false },
            title = { Text("Xóa tất cả đã nhận?") },
            text = { Text("Tất cả các ghi chú quà đã nhận sẽ bị xóa vĩnh viễn.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllClaimed()
                        deleteAllClaimedConfirm = false
                    }
                ) { Text("Xóa", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllClaimedConfirm = false }) { Text("Hủy") }
            }
        )
    }

    // Sheet thêm note mới
    if (showAddSheet) {
        GiftEditSheet(
            gift = null,
            libraryGames = libraryGames,
            onSave = { gift ->
                viewModel.addGift(gift)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false }
        )
    }

    // Sheet sửa note
    editingGift?.let { gift ->
        GiftEditSheet(
            gift = gift,
            libraryGames = libraryGames,
            onSave = { updated ->
                val timeChanged = updated.availableAtMillis != gift.availableAtMillis
                viewModel.updateGift(updated, timeChanged)
                editingGift = null
            },
            onDismiss = { editingGift = null }
        )
    }

    // Sheet hẹn giờ mới
    rescheduleGift?.let { gift ->
        val gameModel = libraryGames.find { it.packageName == gift.gamePackage }
        if (gameModel != null) {
            RescheduleSheet(
                gift = gift,
                game = gameModel,
                onSave = { newAt, newOffset ->
                    viewModel.reschedule(gift.id, newAt, newOffset)
                    rescheduleGiftId = null
                },
                onDismiss = { rescheduleGiftId = null }
            )
        } else {
            rescheduleGiftId = null
        }
    }
}

// ------------------------------------------------------------------------------------------
// Banner thông báo tắt
// ------------------------------------------------------------------------------------------

@Composable
private fun NotificationBannerBar(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.NotificationsOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Thông báo đang tắt, bạn sẽ không được nhắc",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onOpenSettings) {
            Text("Bật", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
        }
    }
}

// ------------------------------------------------------------------------------------------
// Tiêu đề nhóm
// ------------------------------------------------------------------------------------------

@Composable
private fun GiftSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// ------------------------------------------------------------------------------------------
// Dòng ghi chú quà
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GiftRow(
    item: GiftDisplayItem,
    onToggleClaim: () -> Unit,
    onReschedule: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLaunchGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gift = item.gift
    val game = item.game ?: return
    val status = item.status
    val now = item.now
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                GiftStatus.READY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                GiftStatus.CLAIMED -> MaterialTheme.colorScheme.surfaceContainerLow
                GiftStatus.WAITING -> MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox (chỉ bấm được ở READY và CLAIMED)
            Checkbox(
                checked = status == GiftStatus.CLAIMED,
                onCheckedChange = { onToggleClaim() },
                enabled = status != GiftStatus.WAITING,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    disabledUncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            )

            Spacer(Modifier.width(4.dp))

            // Icon game
            Image(
                bitmap = game.icon,
                contentDescription = game.displayName,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Tên game
                Text(
                    text = game.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Ghi chú
                val displayNote = gift.note.ifBlank { "Quà trong game" }
                Text(
                    text = displayNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                // Chip trạng thái
                when (status) {
                    GiftStatus.WAITING -> {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    formatTimeRemaining(gift.availableAtMillis, now),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    GiftStatus.READY -> {
                        Column {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "Đã có sẵn",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                            Text(
                                text = "từ ${formatTimeAgo(gift.availableAtMillis, now)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    GiftStatus.CLAIMED -> {
                        Column {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "Đã nhận",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Text(
                                text = "lúc ${formatTimeAgo(gift.claimedAt ?: gift.availableAtMillis, now)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Nút hành động
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Nút Mở game (mọi trạng thái)
                    AssistChip(
                        onClick = onLaunchGame,
                        label = { Text("Mở game", style = MaterialTheme.typography.labelSmall) }
                    )
                    // Nút Hẹn giờ mới (chỉ ở CLAIMED)
                    if (status == GiftStatus.CLAIMED) {
                        AssistChip(
                            onClick = onReschedule,
                            label = {
                                Text("Hẹn giờ mới", style = MaterialTheme.typography.labelSmall)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Menu ba chấm
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sửa") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Hẹn giờ mới") },
                        onClick = {
                            menuExpanded = false
                            onReschedule()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Xóa", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Trạng thái rỗng
// ------------------------------------------------------------------------------------------

@Composable
private fun GiftEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CardGiftcard,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Chưa có quà nào.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Bấm + để ghi chú quà đầu tiên.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ------------------------------------------------------------------------------------------
// Sheet thêm/sửa note
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftEditSheet(
    gift: Gift?,              // null = thêm mới
    libraryGames: List<GameModel>,
    onSave: (Gift) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isEditing = gift != null

    // Chọn game
    var selectedGame by remember { mutableStateOf(
        if (gift != null) libraryGames.find { it.packageName == gift.gamePackage } else null
    ) }
    var showGamePicker by remember { mutableStateOf(false) }

    // Ghi chú
    var noteText by remember { mutableStateOf(gift?.note ?: "") }

    // Khi sửa: mặc định giữ nguyên thời điểm cũ trừ khi người dùng bấm đổi
    var isChangingTime by remember { mutableStateOf(!isEditing) }

    // Giờ và phút nhập tự do
    val initialHours = run {
        val offset = gift?.lastQuickOffsetMillis
        if (offset != null && offset > 0) (offset / 3_600_000L).toString() else "1"
    }
    val initialMinutes = run {
        val offset = gift?.lastQuickOffsetMillis
        if (offset != null && offset > 0) ((offset % 3_600_000L) / 60_000L).toString() else "0"
    }
    var hoursText by remember { mutableStateOf(initialHours) }
    var minutesText by remember { mutableStateOf(initialMinutes) }

    // Mốc lịch cụ thể tùy chọn (DatePicker / TimePicker)
    var customSpecificDateTime by remember { mutableStateOf<LocalDateTime?>(null) }

    // Lỗi validation
    var timeError by remember { mutableStateOf<String?>(null) }

    val hours = hoursText.toIntOrNull() ?: 0
    val minutes = minutesText.toIntOrNull() ?: 0
    val totalMinutes = hours * 60 + minutes

    fun canSave(): Boolean {
        if (selectedGame == null) return false
        if (!isEditing || isChangingTime) {
            if (customSpecificDateTime != null) {
                val target = customSpecificDateTime!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (target <= System.currentTimeMillis()) {
                    timeError = "Giờ có quà phải ở tương lai"
                    return false
                }
            } else {
                if (totalMinutes <= 0) {
                    timeError = "Vui lòng nhập thời gian lớn hơn 0 phút"
                    return false
                }
            }
        }
        timeError = null
        return true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = if (isEditing) "Sửa ghi chú quà" else "Thêm ghi chú quà",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))

            // Thư viện trống
            if (libraryGames.isEmpty()) {
                Text(
                    "Hãy thêm game vào thư viện trước",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss) { Text("Đóng") }
                return@Column
            }

            // Chọn game
            Text(
                "Chọn game *",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (selectedGame != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Image(
                        bitmap = selectedGame!!.icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        selectedGame!!.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isEditing) {
                        TextButton(onClick = { showGamePicker = true }) { Text("Đổi") }
                    }
                }
            } else {
                Button(
                    onClick = { showGamePicker = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Chọn game")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Ghi chú
            OutlinedTextField(
                value = noteText,
                onValueChange = { if (it.length <= 200) noteText = it },
                label = { Text("Ghi chú (tùy chọn)") },
                placeholder = { Text("Quà trong game") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                supportingText = { Text("${noteText.length}/200") }
            )

            Spacer(Modifier.height(16.dp))

            // Chọn thời điểm có quà
            Text(
                "Có quà sau",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (isEditing && !isChangingTime && gift != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Thời điểm hiện tại",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formatEpochMillis(gift.availableAtMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = { isChangingTime = true }) {
                            Text("Đổi giờ")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 4) {
                                hoursText = input
                                customSpecificDateTime = null
                                timeError = null
                            }
                        },
                        label = { Text("Số giờ") },
                        placeholder = { Text("0") },
                        suffix = { Text("giờ") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 3) {
                                minutesText = input
                                customSpecificDateTime = null
                                timeError = null
                            }
                        },
                        label = { Text("Số phút") },
                        placeholder = { Text("0") },
                        suffix = { Text("phút") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (customSpecificDateTime != null) {
                    val targetMillis = customSpecificDateTime!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Đã chọn lịch: ${formatEpochMillis(targetMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { customSpecificDateTime = null }) {
                            Text("Dùng giờ/phút")
                        }
                    }
                } else if (totalMinutes > 0) {
                    val previewMillis = System.currentTimeMillis() + totalMinutes * 60_000L
                    Text(
                        text = "Dự kiến có quà: ${formatEpochMillis(previewMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val cal = Calendar.getInstance()
                            cal.add(Calendar.HOUR_OF_DAY, 1)
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    TimePickerDialog(
                                        context,
                                        { _, hourOfDay, minute ->
                                            customSpecificDateTime = LocalDateTime.of(
                                                LocalDate.of(year, month + 1, dayOfMonth),
                                                LocalTime.of(hourOfDay, minute)
                                            )
                                            timeError = null
                                        },
                                        cal.get(Calendar.HOUR_OF_DAY),
                                        cal.get(Calendar.MINUTE),
                                        true
                                    ).show()
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                    ) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Chọn ngày & giờ cụ thể...")
                    }

                    if (isEditing) {
                        TextButton(onClick = {
                            isChangingTime = false
                            customSpecificDateTime = null
                        }) {
                            Text("Giữ nguyên giờ cũ")
                        }
                    }
                }
            }

            // Lỗi thời gian
            timeError?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))

            // Nút Lưu / Hủy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Hủy") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (!canSave()) return@Button
                        val game = selectedGame ?: return@Button
                        val now = System.currentTimeMillis()
                        val (newAt, newOffset) = when {
                            isEditing && !isChangingTime && gift != null -> {
                                gift.availableAtMillis to gift.lastQuickOffsetMillis
                            }
                            customSpecificDateTime != null -> {
                                val at = customSpecificDateTime!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                at to null
                            }
                            else -> {
                                val offset = totalMinutes * 60_000L
                                (now + offset) to offset
                            }
                        }

                        val savedGift = if (isEditing && gift != null) {
                            gift.copy(
                                note = noteText.trim(),
                                availableAtMillis = newAt,
                                lastQuickOffsetMillis = newOffset
                            )
                        } else {
                            Gift(
                                gamePackage = game.packageName,
                                note = noteText.trim(),
                                availableAtMillis = newAt,
                                lastQuickOffsetMillis = newOffset
                            )
                        }
                        onSave(savedGift)
                    },
                    enabled = selectedGame != null && (!isChangingTime || totalMinutes > 0 || customSpecificDateTime != null)
                ) { Text("Lưu") }
            }
        }
    }

    // Dialog chọn game
    if (showGamePicker) {
        AlertDialog(
            onDismissRequest = { showGamePicker = false },
            title = { Text("Chọn game") },
            text = {
                LazyColumn {
                    items(libraryGames, key = { it.packageName }) { game ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .padding(vertical = 6.dp)
                                .let { m ->
                                    m.then(Modifier)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = game.icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = game.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = {
                                selectedGame = game
                                showGamePicker = false
                            }) { Text("Chọn") }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGamePicker = false }) { Text("Đóng") }
            }
        )
    }
}

// ------------------------------------------------------------------------------------------
// Sheet hẹn giờ mới
// ------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescheduleSheet(
    gift: Gift,
    game: GameModel,
    onSave: (newAvailableAt: Long, newQuickOffset: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Khởi tạo số giờ và số phút từ offset lần trước
    val initialHours = run {
        val offset = gift.lastQuickOffsetMillis
        if (offset != null && offset > 0) (offset / 3_600_000L).toString() else "1"
    }
    val initialMinutes = run {
        val offset = gift.lastQuickOffsetMillis
        if (offset != null && offset > 0) ((offset % 3_600_000L) / 60_000L).toString() else "0"
    }
    var hoursText by remember { mutableStateOf(initialHours) }
    var minutesText by remember { mutableStateOf(initialMinutes) }
    var customSpecificDateTime by remember { mutableStateOf<LocalDateTime?>(null) }
    var timeError by remember { mutableStateOf<String?>(null) }

    val hours = hoursText.toIntOrNull() ?: 0
    val minutes = minutesText.toIntOrNull() ?: 0
    val totalMinutes = hours * 60 + minutes

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Tiêu đề
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = game.icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = game.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val displayNote = gift.note.ifBlank { "Quà trong game" }
                    Text(
                        text = displayNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Có quà sau",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // Nhập giờ và phút
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() } && input.length <= 4) {
                            hoursText = input
                            customSpecificDateTime = null
                            timeError = null
                        }
                    },
                    label = { Text("Số giờ") },
                    placeholder = { Text("0") },
                    suffix = { Text("giờ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() } && input.length <= 3) {
                            minutesText = input
                            customSpecificDateTime = null
                            timeError = null
                        }
                    },
                    label = { Text("Số phút") },
                    placeholder = { Text("0") },
                    suffix = { Text("phút") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Xem trước thời điểm
            if (customSpecificDateTime != null) {
                val targetMillis = customSpecificDateTime!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Đã chọn lịch: ${formatEpochMillis(targetMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { customSpecificDateTime = null }) {
                        Text("Dùng giờ/phút")
                    }
                }
            } else if (totalMinutes > 0) {
                val previewMillis = System.currentTimeMillis() + totalMinutes * 60_000L
                Text(
                    text = "Dự kiến có quà: ${formatEpochMillis(previewMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "Vui lòng nhập thời gian lớn hơn 0 phút",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Tùy chọn mở DatePicker / TimePicker
            TextButton(
                onClick = {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            TimePickerDialog(
                                context,
                                { _, hourOfDay, minute ->
                                    customSpecificDateTime = LocalDateTime.of(
                                        LocalDate.of(year, month + 1, dayOfMonth),
                                        LocalTime.of(hourOfDay, minute)
                                    )
                                    timeError = null
                                },
                                cal.get(Calendar.HOUR_OF_DAY),
                                cal.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Chọn ngày & giờ cụ thể...")
            }

            timeError?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Hủy") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val (newAt, newOffset) = if (customSpecificDateTime != null) {
                            val target = customSpecificDateTime!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            if (target <= now) {
                                timeError = "Thời điểm phải ở tương lai"
                                return@Button
                            }
                            target to null
                        } else {
                            if (totalMinutes <= 0) {
                                timeError = "Vui lòng nhập thời gian lớn hơn 0 phút"
                                return@Button
                            }
                            val offset = totalMinutes * 60_000L
                            (now + offset) to offset
                        }
                        onSave(newAt, newOffset)
                    },
                    enabled = totalMinutes > 0 || customSpecificDateTime != null
                ) { Text("Lưu") }
            }
        }
    }
}
