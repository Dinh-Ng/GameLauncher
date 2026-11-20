package com.game.launcher

import java.util.UUID

/**
 * Trạng thái suy ra của một note quà — KHÔNG lưu thành trường riêng trong Gift.
 * Trạng thái được tính tại thời điểm [now] dựa vào [Gift.availableAtMillis] và [Gift.isClaimed].
 */
enum class GiftStatus {
    /** Chưa đến giờ có quà. */
    WAITING,

    /** Đã đến giờ có quà nhưng chưa tích nhận. */
    READY,

    /** Người dùng đã tích nhận. */
    CLAIMED
}

/**
 * Mô hình một ghi chú nhắc nhở nhận quà trong game.
 *
 * BẤT BIẾN cần giữ trong mọi thao tác:
 * - [isClaimed] = true chỉ khi [availableAtMillis] đã qua.
 * - Mọi thao tác đổi [availableAtMillis] PHẢI đặt [isClaimed] = false và [claimedAt] = null.
 *
 * @param id UUID ổn định, dùng làm key cho LazyColumn, PendingIntent request code và notification id.
 * @param gamePackage Package name của game.
 * @param note Ghi chú tùy chọn (tối đa 200 ký tự). Rỗng → hiển thị "Quà trong game".
 * @param availableAtMillis Epoch millis UTC — thời điểm quà có sẵn.
 * @param isClaimed true nếu người dùng đã tích nhận.
 * @param claimedAt Epoch millis UTC — thời điểm tích nhận (null nếu chưa nhận).
 * @param lastQuickOffsetMillis Offset chip nhanh đã dùng lần cuối (null nếu dùng "Tùy chọn").
 * @param notifiedForMillis Epoch millis — mốc [availableAtMillis] đã được thông báo (tránh thông báo trùng).
 * @param createdAt Epoch millis UTC — thời điểm tạo note.
 */
data class Gift(
    val id: String = UUID.randomUUID().toString(),
    val gamePackage: String,
    val note: String = "",
    val availableAtMillis: Long,
    val isClaimed: Boolean = false,
    val claimedAt: Long? = null,
    val lastQuickOffsetMillis: Long? = null,
    val notifiedForMillis: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Tính trạng thái suy ra của note tại thời điểm [now].
 * Hàm thuần (pure function) — kiểm thử được độc lập.
 */
fun Gift.statusAt(now: Long): GiftStatus = when {
    isClaimed -> GiftStatus.CLAIMED
    now >= availableAtMillis -> GiftStatus.READY
    else -> GiftStatus.WAITING
}

// ------------------------------------------------------------------------------------------
// Hàm định dạng thời gian — hàm thuần riêng để kiểm thử
// ------------------------------------------------------------------------------------------

/**
 * Định dạng "còn bao lâu" cho trạng thái WAITING.
 * Ví dụ: "Còn 45 phút", "Còn 3 giờ 10 phút", "Còn 2 ngày"
 */
fun formatTimeRemaining(availableAtMillis: Long, now: Long): String {
    val diff = (availableAtMillis - now).coerceAtLeast(0L)
    val totalSeconds = diff / 1000
    val minutes = totalSeconds / 60
    val hours = totalSeconds / 3600
    val days = totalSeconds / 86400
    return when {
        days >= 2 -> "Còn $days ngày"
        hours >= 1 -> {
            val remainMinutes = (totalSeconds % 3600) / 60
            if (remainMinutes > 0) "Còn $hours giờ $remainMinutes phút" else "Còn $hours giờ"
        }
        minutes >= 1 -> "Còn $minutes phút"
        else -> "Còn ít hơn 1 phút"
    }
}

/**
 * Định dạng "cách đây bao lâu" cho trạng thái READY/CLAIMED.
 * Ví dụ: "2 giờ trước", "vừa xong", "3 phút trước"
 */
fun formatTimeAgo(pastMillis: Long, now: Long): String {
    val diff = (now - pastMillis).coerceAtLeast(0L)
    val totalSeconds = diff / 1000
    val minutes = totalSeconds / 60
    val hours = totalSeconds / 3600
    val days = totalSeconds / 86400
    return when {
        days >= 2 -> "$days ngày trước"
        hours >= 1 -> "$hours giờ trước"
        minutes >= 1 -> "$minutes phút trước"
        else -> "vừa xong"
    }
}

/**
 * Tính thời điểm có quà từ chip nhanh tại thời điểm [now].
 * Hàm thuần — kiểm thử được.
 *
 * @param offsetMillis Offset millis của chip (ví dụ 15 * 60 * 1000 cho "+15 phút")
 * @param now Thời điểm bấm Lưu (epoch millis)
 * @return Epoch millis của thời điểm có quà
 */
fun computeAvailableAtFromOffset(offsetMillis: Long, now: Long): Long = now + offsetMillis

/** Danh sách offset của các chip nhanh, theo thứ tự hiển thị. */
val QUICK_CHIP_OFFSETS: List<Long> = listOf(
    15L * 60 * 1000,         // +15 phút
    30L * 60 * 1000,         // +30 phút
    1L * 60 * 60 * 1000,     // +1 giờ
    2L * 60 * 60 * 1000,     // +2 giờ
    6L * 60 * 60 * 1000,     // +6 giờ
    24L * 60 * 60 * 1000,    // +1 ngày
    7L * 24 * 60 * 60 * 1000 // +7 ngày
)

/** Nhãn hiển thị của từng chip nhanh, tương ứng với [QUICK_CHIP_OFFSETS]. */
val QUICK_CHIP_LABELS: List<String> = listOf(
    "+15 phút", "+30 phút", "+1 giờ", "+2 giờ", "+6 giờ", "+1 ngày", "+7 ngày"
)

/** Index mặc định khi tạo note mới ("+1 giờ"). */
const val DEFAULT_QUICK_CHIP_INDEX = 2
