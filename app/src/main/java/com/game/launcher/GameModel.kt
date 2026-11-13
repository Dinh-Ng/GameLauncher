package com.game.launcher

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Chế độ sắp xếp danh sách game.
 */
enum class SortMode {
    RECENT,
    NAME
}

/**
 * Chế độ số cột hiển thị trên lưới game.
 */
sealed interface ColumnsMode {
    data object Auto : ColumnsMode
    data class Fixed(val count: Int) : ColumnsMode
}

/**
 * Nhóm cấu hình hiển thị và sắp xếp được lưu bền vững.
 */
data class LauncherSettings(
    val sortMode: SortMode = SortMode.RECENT,
    val columns: ColumnsMode = ColumnsMode.Auto
)

/**
 * Trạng thái quyền truy cập dữ liệu sử dụng từ hệ thống.
 */
enum class UsageAccessStatus {
    NOT_SUPPORTED,
    GRANTED,
    DENIED
}

/**
 * Thông tin một ứng dụng/game đã cài trên thiết bị.
 * - [name]: Tên gốc từ PackageManager, không thay đổi.
 * - [displayName]: Tên hiển thị (tên tùy chỉnh nếu có, nếu không thì bằng [name]).
 *   Được ghép sẵn trong ViewModel, UI chỉ dùng [displayName].
 * - [isPinned]: Trạng thái ghim yêu thích lên đầu danh sách.
 * Icon được chuyển sẵn thành ImageBitmap (trên luồng IO) để Compose vẽ trực tiếp, không giữ Drawable.
 */
@Immutable
data class GameModel(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap,
    val isGame: Boolean = false,
    val isSystemApp: Boolean = false,
    val lastPlayed: Long = 0L,
    val displayName: String = name,
    val isPinned: Boolean = false
)
