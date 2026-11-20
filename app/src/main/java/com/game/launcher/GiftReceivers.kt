package com.game.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver nhận báo thức từ AlarmManager khi đến giờ có quà.
 *
 * Quy tắc hiển thị thông báo:
 * 1. Note phải còn tồn tại.
 * 2. Note chưa được tích nhận (isClaimed = false).
 * 3. availableAtMillis trong DataStore phải khớp với mốc của báo thức
 *    (nếu không khớp = báo thức cũ đã bị thay thế, bỏ qua).
 * 4. notifiedForMillis chưa bằng mốc đó (tránh thông báo trùng).
 * 5. Sau khi hiển thị, ghi notifiedForMillis = availableAtMillis vào DataStore.
 */
class GiftAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val giftId = intent.getStringExtra(EXTRA_GIFT_ID) ?: return
        val alarmAvailableAt = intent.getLongExtra(EXTRA_AVAILABLE_AT, 0L)
        if (alarmAvailableAt <= 0L) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val repository = GiftRepository(context.applicationContext)
                val scheduler = GiftScheduler(context.applicationContext)

                // Đọc danh sách hiện tại
                val gifts = repository.readGiftsOnce()
                val gift = gifts.find { it.id == giftId } ?: run {
                    pendingResult.finish()
                    return@launch
                }

                // Kiểm tra báo thức có còn hợp lệ không
                if (gift.isClaimed) {
                    pendingResult.finish()
                    return@launch
                }
                if (gift.availableAtMillis != alarmAvailableAt) {
                    // Báo thức cũ đã bị thay thế bởi báo thức mới → bỏ qua
                    pendingResult.finish()
                    return@launch
                }
                if (gift.notifiedForMillis == alarmAvailableAt) {
                    // Đã thông báo cho mốc này rồi → tránh trùng
                    pendingResult.finish()
                    return@launch
                }

                // Lấy tên game từ PackageManager
                val gameDisplayName = getGameDisplayName(context, gift.gamePackage)

                // Hiển thị thông báo
                scheduler.showNotificationNow(gift, gameDisplayName)

                // Ghi notifiedForMillis để tránh thông báo trùng
                val updated = gift.copy(notifiedForMillis = alarmAvailableAt)
                repository.upsertGift(updated)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * BroadcastReceiver sau khi thiết bị khởi động lại hoặc app được cập nhật.
 * Đặt lại báo thức cho các note ĐANG CHỜ.
 * Hiển thị thông báo ngay cho note đã quá giờ trong lúc máy tắt mà chưa được thông báo.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.MY_PACKAGE_REPLACED"
        ) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val repository = GiftRepository(context.applicationContext)
                val scheduler = GiftScheduler(context.applicationContext)
                val now = System.currentTimeMillis()

                val gifts = repository.readGiftsOnce()

                for (gift in gifts) {
                    val status = gift.statusAt(now)
                    when (status) {
                        GiftStatus.WAITING -> {
                            // Đặt lại báo thức cho note ĐANG CHỜ
                            scheduler.scheduleAlarm(gift)
                        }
                        GiftStatus.READY -> {
                            // Note đã quá giờ trong lúc máy tắt, chưa nhận, chưa được thông báo cho mốc này
                            if (gift.notifiedForMillis != gift.availableAtMillis) {
                                val gameDisplayName = getGameDisplayName(context, gift.gamePackage)
                                scheduler.showNotificationNow(gift, gameDisplayName)
                                val updated = gift.copy(notifiedForMillis = gift.availableAtMillis)
                                repository.upsertGift(updated)
                            }
                        }
                        GiftStatus.CLAIMED -> { /* Đã nhận, không làm gì */ }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Lấy tên hiển thị game từ PackageManager, fallback về packageName nếu không tìm thấy. */
fun getGameDisplayName(context: Context, gamePackage: String): String {
    return try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(gamePackage, 0)).toString()
    } catch (_: Exception) {
        gamePackage
    }
}
