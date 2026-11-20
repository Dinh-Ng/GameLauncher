package com.game.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

const val GIFT_CHANNEL_ID = "gift_reminder"
const val GIFT_CHANNEL_NAME = "Nhắc nhận quà"

const val EXTRA_GIFT_ID = "gift_id"
const val EXTRA_AVAILABLE_AT = "available_at"

/**
 * Lớp đặt/hủy báo thức AlarmManager và tạo thông báo cho tab Quà.
 * Mỗi note có đúng MỘT PendingIntent, request code suy ra ổn định từ id note.
 */
class GiftScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ------------------------------------------------------------------------------------------
    // Kênh thông báo
    // ------------------------------------------------------------------------------------------

    fun createNotificationChannel() {
        val channel = NotificationChannel(
            GIFT_CHANNEL_ID,
            GIFT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Thông báo khi quà trong game đã sẵn sàng để nhận"
            enableLights(true)
            enableVibration(true)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------------------------------
    // Đặt / Hủy báo thức
    // ------------------------------------------------------------------------------------------

    /**
     * Đặt báo thức chính xác cho note [gift].
     * Báo thức mới cho cùng id PHẢI ghi đè báo thức cũ.
     * Chỉ đặt khi note ở trạng thái WAITING và [availableAtMillis] ở tương lai.
     */
    fun scheduleAlarm(gift: Gift) {
        if (gift.availableAtMillis <= System.currentTimeMillis()) return
        val intent = buildAlarmIntent(gift.id, gift.availableAtMillis)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                // Rơi về setAndAllowWhileIdle nếu không có quyền báo thức chính xác
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    gift.availableAtMillis,
                    intent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    gift.availableAtMillis,
                    intent
                )
            }
        } catch (_: SecurityException) {
            // Fallback khi không có quyền
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                gift.availableAtMillis,
                intent
            )
        }
    }

    /** Hủy báo thức đang chờ của note theo [giftId]. */
    fun cancelAlarm(giftId: String) {
        val intent = buildAlarmIntent(giftId, 0L)
        alarmManager.cancel(intent)
    }

    /** Hủy thông báo đang hiển thị của note theo [giftId]. */
    fun cancelNotification(giftId: String) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(notificationId(giftId))
    }

    /**
     * Hiển thị thông báo ngay (dùng sau boot hoặc khi note đã quá giờ trong lúc máy tắt).
     * Trước khi gọi, phải đảm bảo đã kiểm tra quyền POST_NOTIFICATIONS.
     */
    fun showNotificationNow(gift: Gift, gameDisplayName: String) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val launchIntent = context.packageManager.getLaunchIntentForPackage(gift.gamePackage)
            ?: Intent(context, MainActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context,
            notificationId(gift.id),
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = buildNotification(gift, gameDisplayName, pi)
        nm.notify(notificationId(gift.id), notification)
    }

    private fun buildNotification(gift: Gift, gameDisplayName: String, contentIntent: PendingIntent): Notification {
        val noteText = gift.note.ifBlank { "Quà trong game" }
        return android.app.Notification.Builder(context, GIFT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$gameDisplayName: quà đã sẵn sàng")
            .setContentText(noteText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
    }

    private fun buildAlarmIntent(giftId: String, availableAtMillis: Long): PendingIntent {
        val intent = Intent(context, GiftAlarmReceiver::class.java).apply {
            putExtra(EXTRA_GIFT_ID, giftId)
            putExtra(EXTRA_AVAILABLE_AT, availableAtMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(giftId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ------------------------------------------------------------------------------------------
    // Quyền POST_NOTIFICATIONS (API 33+)
    // ------------------------------------------------------------------------------------------

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // ------------------------------------------------------------------------------------------
    // Tiện ích ổn định
    // ------------------------------------------------------------------------------------------

    companion object {
        /** Request code PendingIntent suy ra ổn định từ id note (tránh va chạm). */
        fun requestCode(giftId: String): Int = giftId.hashCode() and 0x7FFFFFFF

        /** ID thông báo suy ra ổn định từ id note. */
        fun notificationId(giftId: String): Int = (giftId.hashCode() and 0x7FFFFFFF) xor 0x1000
    }
}
