package com.game.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------------------------------
// Trạng thái UI
// ------------------------------------------------------------------------------------------

/** Một dòng hiển thị trong tab Quà, bao gồm trạng thái suy ra và game model tương ứng. */
data class GiftDisplayItem(
    val gift: Gift,
    val game: GameModel?,         // null nếu game không còn trong thư viện (dòng bị ẩn)
    val status: GiftStatus,
    val now: Long
)

/** Nhóm phân loại trong LazyColumn tab Quà. */
data class GiftGroups(
    val ready: List<GiftDisplayItem> = emptyList(),
    val waiting: List<GiftDisplayItem> = emptyList(),
    val claimed: List<GiftDisplayItem> = emptyList(),
    val readyCount: Int = 0
)

/** Trạng thái UI tổng của tab Quà. */
data class GiftUiState(
    val groups: GiftGroups = GiftGroups(),
    val notificationsEnabled: Boolean = true,
    val firstNoteSaved: Boolean = false
)

/** Sự kiện một lần từ ViewModel → UI (Snackbar, mở sheet...). */
sealed interface GiftEvent {
    data class ShowSnackbar(val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null) : GiftEvent
    data class OpenRescheduleSheet(val giftId: String) : GiftEvent
    data class ShowSuggestionSnackbar(val gameDisplayName: String, val giftId: String) : GiftEvent
}

// ------------------------------------------------------------------------------------------
// ViewModel
// ------------------------------------------------------------------------------------------

class GiftViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val repository = GiftRepository(appContext)
    private val scheduler = GiftScheduler(appContext)

    // "Bây giờ" — cập nhật mỗi 60 giây, chỉ khi có subscriber
    private val nowFlow = MutableStateFlow(System.currentTimeMillis())
    private var nowTickJob: Job? = null

    // Flow library games được inject từ bên ngoài qua updateLibrary()
    private val libraryFlow = MutableStateFlow<List<GameModel>>(emptyList())

    // Sự kiện một lần
    private val _events = MutableSharedFlow<GiftEvent>(extraBufferCapacity = 8)
    val events: Flow<GiftEvent> = _events.asSharedFlow()

    // Ghi nhớ gợi ý "Đã nhận quà chưa?"
    private val _pendingSuggestionGiftId = MutableStateFlow<String?>(null)

    // Yêu cầu xin quyền thông báo — được phát khi lưu note đầu tiên
    private val _requestNotificationPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestNotificationPermission: Flow<Unit> = _requestNotificationPermission.asSharedFlow()

    private var latestGifts: List<Gift> = emptyList()

    val uiState: StateFlow<GiftUiState> = combine(
        repository.giftsFlow,
        libraryFlow,
        nowFlow
    ) { gifts, library, now ->
        val gameMap = library.associateBy { it.packageName }

        // Chỉ hiện note của game còn trong thư viện
        val items = gifts.mapNotNull { gift ->
            val game = gameMap[gift.gamePackage] ?: return@mapNotNull null
            GiftDisplayItem(
                gift = gift,
                game = game,
                status = gift.statusAt(now),
                now = now
            )
        }

        // Phân nhóm và sắp xếp
        val ready = items.filter { it.status == GiftStatus.READY }
            .sortedBy { it.gift.availableAtMillis } // cũ nhất lên đầu

        val waiting = items.filter { it.status == GiftStatus.WAITING }
            .sortedBy { it.gift.availableAtMillis } // gần nhất lên đầu

        val claimed = items.filter { it.status == GiftStatus.CLAIMED }
            .sortedByDescending { it.gift.claimedAt ?: 0L } // mới nhất lên đầu

        GiftUiState(
            groups = GiftGroups(
                ready = ready,
                waiting = waiting,
                claimed = claimed,
                readyCount = ready.size
            ),
            notificationsEnabled = scheduler.areNotificationsEnabled()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GiftUiState())

    init {
        startNowTicker()
        scheduler.createNotificationChannel()
        viewModelScope.launch {
            repository.giftsFlow.collect {
                latestGifts = it
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Vòng lặp cập nhật "bây giờ" mỗi 60 giây
    // ------------------------------------------------------------------------------------------

    private fun startNowTicker() {
        nowTickJob?.cancel()
        nowTickJob = viewModelScope.launch {
            while (true) {
                delay(60_000L)
                nowFlow.value = System.currentTimeMillis()
            }
        }
    }

    /** Gọi khi app quay lại ON_RESUME để cập nhật "bây giờ" ngay lập tức. */
    fun onResume(currentTabIsGift: Boolean) {
        nowFlow.value = System.currentTimeMillis()

        // Hiện gợi ý nếu đang ở tab Quà
        if (currentTabIsGift) {
            checkAndShowSuggestion()
        }
    }

    /** Gọi khi người dùng chuyển sang tab Quà. */
    fun onTabGiftSelected() {
        checkAndShowSuggestion()
    }

    private fun checkAndShowSuggestion() {
        val pendingId = _pendingSuggestionGiftId.value ?: return
        val now = System.currentTimeMillis()

        // Kiểm tra note còn tồn tại và vẫn ở READY
        viewModelScope.launch {
            val gifts = repository.readGiftsOnce()
            val gift = gifts.find { it.id == pendingId }
            if (gift == null || gift.statusAt(now) != GiftStatus.READY) {
                _pendingSuggestionGiftId.value = null
                return@launch
            }

            // Kiểm tra timeout 6 giờ
            val library = libraryFlow.value
            val game = library.find { it.packageName == gift.gamePackage }
            if (game == null) {
                _pendingSuggestionGiftId.value = null
                return@launch
            }

            _pendingSuggestionGiftId.value = null
            _events.emit(GiftEvent.ShowSuggestionSnackbar(game.displayName, pendingId))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Inject library từ GameLauncherViewModel
    // ------------------------------------------------------------------------------------------

    fun updateLibrary(library: List<GameModel>) {
        libraryFlow.value = library
    }

    // ------------------------------------------------------------------------------------------
    // Thêm / Sửa note
    // ------------------------------------------------------------------------------------------

    /**
     * Thêm note mới.
     * Đặt báo thức, xin quyền thông báo nếu là note đầu tiên.
     */
    fun addGift(gift: Gift) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertGift(gift)
            if (gift.statusAt(System.currentTimeMillis()) == GiftStatus.WAITING) {
                scheduler.scheduleAlarm(gift)
            }

            // Xin quyền thông báo khi lưu note đầu tiên (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !scheduler.hasNotificationPermission()
            ) {
                val existingCount = repository.readGiftsOnce().size
                if (existingCount <= 1) { // vừa thêm xong nên có ít nhất 1
                    _requestNotificationPermission.emit(Unit)
                }
            }
        }
    }

    /**
     * Cập nhật note.
     * Nếu [timeChanged] = true: hủy báo thức cũ, đặt isClaimed=false, claimedAt=null,
     *   và note trở về ĐANG CHỜ; sau đó đặt báo thức mới.
     * Nếu [timeChanged] = false: chỉ cập nhật các trường khác, giữ nguyên trạng thái.
     */
    fun updateGift(updated: Gift, timeChanged: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (timeChanged) {
                // Hủy báo thức cũ
                scheduler.cancelAlarm(updated.id)
                scheduler.cancelNotification(updated.id)
                // Đặt báo thức mới nếu ở WAITING
                val finalGift = updated.copy(isClaimed = false, claimedAt = null)
                repository.upsertGift(finalGift)
                if (finalGift.statusAt(System.currentTimeMillis()) == GiftStatus.WAITING) {
                    scheduler.scheduleAlarm(finalGift)
                }
                // Xóa gợi ý nếu đang pending cho note này
                if (_pendingSuggestionGiftId.value == updated.id) {
                    _pendingSuggestionGiftId.value = null
                }
            } else {
                repository.upsertGift(updated)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tích / Bỏ tích nhận quà
    // ------------------------------------------------------------------------------------------

    /**
     * Tích hoặc bỏ tích nhận quà.
     * ViewModel từ chối (bỏ qua) khi note đang WAITING — không chỉ dựa vào enabled UI.
     * - Tích: READY → CLAIMED (isClaimed=true, claimedAt=now)
     * - Bỏ tích: CLAIMED → READY (isClaimed=false, claimedAt=null)
     *   Bỏ tích KHÔNG đặt báo thức mới và KHÔNG gửi thông báo.
     */
    fun toggleClaim(giftId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val gifts = repository.readGiftsOnce()
            val gift = gifts.find { it.id == giftId } ?: return@launch
            val now = System.currentTimeMillis()
            val status = gift.statusAt(now)

            when (status) {
                GiftStatus.WAITING -> return@launch // Từ chối
                GiftStatus.READY -> {
                    // Tích: → CLAIMED
                    val updated = gift.copy(isClaimed = true, claimedAt = now)
                    repository.upsertGift(updated)
                    scheduler.cancelNotification(giftId)
                    // Xóa gợi ý nếu đang pending
                    if (_pendingSuggestionGiftId.value == giftId) {
                        _pendingSuggestionGiftId.value = null
                    }
                }
                GiftStatus.CLAIMED -> {
                    // Bỏ tích: → READY, KHÔNG đặt báo thức mới
                    val updated = gift.copy(isClaimed = false, claimedAt = null)
                    repository.upsertGift(updated)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hẹn giờ mới
    // ------------------------------------------------------------------------------------------

    /**
     * Lưu giờ mới cho note [giftId].
     * Hủy báo thức cũ, đặt isClaimed=false, claimedAt=null, notifiedForMillis=null.
     * Đặt báo thức mới.
     * Hiện Snackbar có "Hoàn tác".
     */
    fun reschedule(
        giftId: String,
        newAvailableAt: Long,
        newQuickOffset: Long?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val gifts = repository.readGiftsOnce()
            val oldGift = gifts.find { it.id == giftId } ?: return@launch

            // Hủy báo thức và thông báo cũ
            scheduler.cancelAlarm(giftId)
            scheduler.cancelNotification(giftId)

            val newGift = oldGift.copy(
                availableAtMillis = newAvailableAt,
                isClaimed = false,
                claimedAt = null,
                notifiedForMillis = null,
                lastQuickOffsetMillis = newQuickOffset
            )
            repository.upsertGift(newGift)

            // Đặt báo thức mới
            if (newGift.statusAt(System.currentTimeMillis()) == GiftStatus.WAITING) {
                scheduler.scheduleAlarm(newGift)
            }

            // Xóa gợi ý nếu đang pending
            if (_pendingSuggestionGiftId.value == giftId) {
                _pendingSuggestionGiftId.value = null
            }

            // Hiện Snackbar có Hoàn tác
            val formattedTime = formatEpochMillis(newAvailableAt)
            _events.emit(GiftEvent.ShowSnackbar(
                message = "Đã hẹn lúc $formattedTime",
                actionLabel = "Hoàn tác",
                onAction = {
                    undoReschedule(giftId = giftId, oldGift = oldGift)
                }
            ))
        }
    }

    private fun undoReschedule(giftId: String, oldGift: Gift) {
        viewModelScope.launch(Dispatchers.IO) {
            // Hủy báo thức mới vừa đặt
            scheduler.cancelAlarm(giftId)
            scheduler.cancelNotification(giftId)

            // Khôi phục trạng thái cũ
            repository.upsertGift(oldGift)

            // Chỉ đặt lại báo thức cũ nếu giờ cũ vẫn ở tương lai
            // TUYỆT ĐỐI không bắn thông báo cho giờ đã qua khi hoàn tác
            val now = System.currentTimeMillis()
            if (oldGift.availableAtMillis > now && oldGift.statusAt(now) == GiftStatus.WAITING) {
                scheduler.scheduleAlarm(oldGift)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Xóa note
    // ------------------------------------------------------------------------------------------

    fun deleteGift(giftId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val gifts = repository.readGiftsOnce()
            val gift = gifts.find { it.id == giftId } ?: return@launch

            // Hủy báo thức và thông báo
            scheduler.cancelAlarm(giftId)
            scheduler.cancelNotification(giftId)

            repository.deleteGift(giftId)

            if (_pendingSuggestionGiftId.value == giftId) {
                _pendingSuggestionGiftId.value = null
            }

            // Snackbar Hoàn tác
            _events.emit(GiftEvent.ShowSnackbar(
                message = "Đã xóa ghi chú quà",
                actionLabel = "Hoàn tác",
                onAction = {
                    undoDelete(gift)
                }
            ))
        }
    }

    private fun undoDelete(gift: Gift) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertGift(gift)
            val now = System.currentTimeMillis()
            if (gift.statusAt(now) == GiftStatus.WAITING) {
                scheduler.scheduleAlarm(gift)
            }
        }
    }

    /** Xóa tất cả note đã nhận. */
    fun deleteAllClaimed() {
        viewModelScope.launch(Dispatchers.IO) {
            val gifts = repository.readGiftsOnce()
            val claimed = gifts.filter { it.statusAt(System.currentTimeMillis()) == GiftStatus.CLAIMED }
            for (g in claimed) {
                scheduler.cancelAlarm(g.id)
                scheduler.cancelNotification(g.id)
            }
            val remaining = gifts.filter { it.statusAt(System.currentTimeMillis()) != GiftStatus.CLAIMED }
            repository.saveGifts(remaining)
        }
    }

    /** Xóa tất cả note của một game (gọi từ GameLauncherViewModel khi game bị xóa). */
    fun deleteGiftsForPackage(gamePackage: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val gifts = repository.readGiftsOnce()
            val toDelete = gifts.filter { it.gamePackage == gamePackage }
            for (g in toDelete) {
                scheduler.cancelAlarm(g.id)
                scheduler.cancelNotification(g.id)
            }
            repository.deleteGiftsForPackage(gamePackage)
        }
    }

    /** Kiểm tra game có note nào không (dùng cho dialog xác nhận xóa game). */
    fun hasGiftsForPackage(gamePackage: String): Boolean {
        return latestGifts.any { it.gamePackage == gamePackage }
    }

    // ------------------------------------------------------------------------------------------
    // Ghi nhớ gợi ý "Đã nhận quà chưa?"
    // ------------------------------------------------------------------------------------------

    /**
     * Ghi nhớ id note khi người dùng bấm "Mở game" trên note ĐÃ CÓ SẴN.
     * Chỉ lưu trong bộ nhớ, không lưu DataStore.
     * Tự xóa sau 6 giờ.
     */
    fun markLaunchedFromReadyGift(giftId: String) {
        _pendingSuggestionGiftId.value = giftId

        // Tự xóa sau 6 giờ
        viewModelScope.launch {
            delay(6L * 60 * 60 * 1000)
            if (_pendingSuggestionGiftId.value == giftId) {
                _pendingSuggestionGiftId.value = null
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Quyền thông báo
    // ------------------------------------------------------------------------------------------

    fun getNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

// ------------------------------------------------------------------------------------------
// Tiện ích định dạng ngày giờ
// ------------------------------------------------------------------------------------------

/** Định dạng epoch millis thành chuỗi ngày giờ dễ đọc theo múi giờ máy. */
fun formatEpochMillis(epochMillis: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale("vi"))
    sdf.timeZone = java.util.TimeZone.getDefault()
    return sdf.format(java.util.Date(epochMillis))
}
