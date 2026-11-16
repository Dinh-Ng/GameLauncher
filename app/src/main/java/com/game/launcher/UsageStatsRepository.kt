package com.game.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/**
 * Khoảng thời gian thống kê.
 */
enum class StatsPeriod {
    TODAY,
    LAST_7_DAYS
}

/**
 * Dữ liệu sự kiện thô phục vụ việc ghép cặp và kiểm thử đơn vị.
 */
data class RawUsageEvent(
    val packageName: String,
    val className: String?,
    val eventType: Int,
    val timestamp: Long
)

/**
 * Khoảng thời gian foreground hợp nhất (đã ghép cặp và khử trùng lặp).
 */
data class TimeInterval(
    val start: Long,
    val end: Long
)

/**
 * Thống kê thời gian chơi theo từng ngày.
 */
data class DailyUsage(
    val date: LocalDate,
    val dayLabel: String,
    val isToday: Boolean,
    val timeMs: Long
)

/**
 * Thống kê thời gian chơi của một game.
 */
data class GameUsage(
    val packageName: String,
    val timeMs: Long
)

/**
 * Kết quả thống kê thời gian chơi dạng data class thuần.
 */
data class UsageStatsData(
    val totalTimeMs: Long,
    val dailyUsages: List<DailyUsage>,
    val gameUsages: List<GameUsage>
)

/**
 * Lớp chịu trách nhiệm truy vấn UsageStatsManager và tính toán thời gian chơi.
 *
 * LƯU Ý HỆ THỐNG:
 * Hệ thống Android chỉ lưu trữ lịch sử UsageEvents (sự kiện chi tiết) trong thời gian ngắn hạn
 * (thường từ vài ngày đến tối đa 7 ngày tùy theo thiết bị và nhà sản xuất). Do đó, giới hạn tối đa
 * được thiết kế là 7 ngày để đảm bảo độ chính xác cao nhất khi ghép cặp sự kiện.
 */
class UsageStatsRepository(private val context: Context) {

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * Kiểm tra quyền PACKAGE_USAGE_STATS:
     * - API 29+ (Q): Dùng unsafeCheckOpNoThrow
     * - API 26–28: Dùng checkOpNoThrow (deprecated từ API 29)
     */
    fun hasUsagePermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Truy vấn UsageEvents và tính toán thống kê cho danh sách game trong thư viện.
     */
    fun queryUsageStats(
        period: StatsPeriod,
        libraryPackages: Set<String>
    ): UsageStatsData {
        if (!hasUsagePermission() || usageStatsManager == null || libraryPackages.isEmpty()) {
            return UsageStatsData(totalTimeMs = 0L, dailyUsages = emptyList(), gameUsages = emptyList())
        }

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val now = System.currentTimeMillis()

        // Ranh giới thời gian tính theo 0 giờ múi giờ của máy, không dùng "24 giờ lùi lại"
        val (beginTime, dailyBuckets) = when (period) {
            StatsPeriod.TODAY -> {
                val startOfToday = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val buckets = listOf(
                    createDayBucket(today, startOfToday, now, today)
                )
                Pair(startOfToday, buckets)
            }
            StatsPeriod.LAST_7_DAYS -> {
                // 7 ngày: từ 6 ngày trước đến hôm nay
                val startDate = today.minusDays(6)
                val startOfPeriod = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val buckets = (0..6).map { i ->
                    val date = startDate.plusDays(i.toLong())
                    val dayStart = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val dayEnd = if (date == today) {
                        now
                    } else {
                        date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    }
                    createDayBucket(date, dayStart, dayEnd, today)
                }
                Pair(startOfPeriod, buckets)
            }
        }

        // Truy vấn sự kiện từ hệ thống
        val rawEvents = mutableListOf<RawUsageEvent>()
        val events = usageStatsManager.queryEvents(beginTime, now)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            if (pkg != null && pkg in libraryPackages) {
                rawEvents.add(
                    RawUsageEvent(
                        packageName = pkg,
                        className = event.className,
                        eventType = event.eventType,
                        timestamp = event.timeStamp
                    )
                )
            }
        }

        // Ghép cặp và tính toán khoảng thời gian (thuật toán thuần, độc lập và kiểm thử được)
        val packageIntervals = computePackageIntervals(
            events = rawEvents,
            beginTime = beginTime,
            endTime = now
        )

        // Tính thời gian theo từng game
        val gameUsages = libraryPackages.mapNotNull { pkg ->
            val intervals = packageIntervals[pkg] ?: return@mapNotNull null
            val totalTime = intervals.sumOf { it.end - it.start }
            if (totalTime > 0L) {
                GameUsage(packageName = pkg, timeMs = totalTime)
            } else null
        }.sortedByDescending { it.timeMs }

        val totalTimeMs = gameUsages.sumOf { it.timeMs }

        // Tính thời gian theo từng ngày (phân bổ chính xác kể cả phiên vắt qua nửa đêm)
        val dailyUsages = dailyBuckets.map { bucket ->
            var dayTotalMs = 0L
            for (pkg in libraryPackages) {
                val intervals = packageIntervals[pkg] ?: continue
                for (interval in intervals) {
                    val overlapStart = maxOf(interval.start, bucket.startMs)
                    val overlapEnd = minOf(interval.end, bucket.endMs)
                    if (overlapEnd > overlapStart) {
                        dayTotalMs += (overlapEnd - overlapStart)
                    }
                }
            }
            DailyUsage(
                date = bucket.date,
                dayLabel = bucket.dayLabel,
                isToday = bucket.isToday,
                timeMs = dayTotalMs
            )
        }

        return UsageStatsData(
            totalTimeMs = totalTimeMs,
            dailyUsages = dailyUsages,
            gameUsages = gameUsages
        )
    }

    private data class DayBucket(
        val date: LocalDate,
        val startMs: Long,
        val endMs: Long,
        val dayLabel: String,
        val isToday: Boolean
    )

    private fun createDayBucket(date: LocalDate, startMs: Long, endMs: Long, today: LocalDate): DayBucket {
        val label = when (date.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> "T2"
            java.time.DayOfWeek.TUESDAY -> "T3"
            java.time.DayOfWeek.WEDNESDAY -> "T4"
            java.time.DayOfWeek.THURSDAY -> "T5"
            java.time.DayOfWeek.FRIDAY -> "T6"
            java.time.DayOfWeek.SATURDAY -> "T7"
            java.time.DayOfWeek.SUNDAY -> "CN"
        }
        return DayBucket(
            date = date,
            startMs = startMs,
            endMs = endMs,
            dayLabel = label,
            isToday = (date == today)
        )
    }

    companion object {
        /**
         * Nhận diện sự kiện Foreground rẽ nhánh theo API level:
         * - API 29+ (Android 10+): ACTIVITY_RESUMED
         * - API 26–28: MOVE_TO_FOREGROUND
         */
        fun isForegroundEvent(eventType: Int): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
        }

        /**
         * Nhận diện sự kiện Background rẽ nhánh theo API level:
         * - API 29+ (Android 10+): ACTIVITY_PAUSED hoặc ACTIVITY_STOPPED
         * - API 26–28: MOVE_TO_BACKGROUND
         */
        fun isBackgroundEvent(eventType: Int): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                eventType == UsageEvents.Event.ACTIVITY_PAUSED || eventType == UsageEvents.Event.ACTIVITY_STOPPED
            } else {
                @Suppress("DEPRECATION")
                eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            }
        }

        /**
         * Logic thuần ghép cặp sự kiện foreground/background và khử trùng lặp giữa các Activity.
         *
         * Xử lý:
         * 1. Sự kiện lặp: Nếu có nhiều resume liên tiếp trên cùng activity, giữ mốc bắt đầu đầu tiên.
         * 2. Thiếu sự kiện kết thúc: Nếu đến cuối chuỗi sự kiện mà activity vẫn chưa background (phiên đang diễn ra),
         *    tính kéo dài đến mốc [endTime].
         * 3. Phiên bắt đầu trước [beginTime]: Nếu bắt gặp background trước khi có foreground, cắt từ [beginTime].
         * 4. Nhiều Activity của cùng một app chạy chồng nhau: Ghép cặp theo từng activity rồi dùng thuật toán
         *    Merge Intervals để gộp tất cả khoảng giao nhau, KHÔNG cộng dồn thời gian trùng.
         */
        fun computePackageIntervals(
            events: List<RawUsageEvent>,
            beginTime: Long,
            endTime: Long
        ): Map<String, List<TimeInterval>> {
            val eventsByPackage = events.groupBy { it.packageName }
            val resultMap = mutableMapOf<String, List<TimeInterval>>()

            for ((pkg, pkgEvents) in eventsByPackage) {
                // Sắp xếp các sự kiện theo thời gian tăng dần
                val sortedEvents = pkgEvents.sortedBy { it.timestamp }

                // Theo dõi mốc mở của từng activity class (để xử lý nhiều Activity lồng nhau)
                val activeActivityStarts = mutableMapOf<String, Long>()
                val rawIntervals = mutableListOf<TimeInterval>()

                for (ev in sortedEvents) {
                    val key = ev.className ?: "default_activity"
                    when {
                        isForegroundEvent(ev.eventType) -> {
                            // Nếu chưa mở thì ghi nhận thời điểm mở; nếu đã mở (sự kiện lặp) thì giữ nguyên start cũ
                            if (!activeActivityStarts.containsKey(key)) {
                                activeActivityStarts[key] = ev.timestamp
                            }
                        }
                        isBackgroundEvent(ev.eventType) -> {
                            val startTime = activeActivityStarts.remove(key)
                            if (startTime != null) {
                                // Ghép cặp bình thường
                                val s = maxOf(startTime, beginTime)
                                val e = minOf(ev.timestamp, endTime)
                                if (e > s) {
                                    rawIntervals.add(TimeInterval(s, e))
                                }
                            } else {
                                // Phiên bắt đầu trước mốc beginTime (thiếu sự kiện resume trong khung truy vấn)
                                val s = beginTime
                                val e = minOf(ev.timestamp, endTime)
                                if (e > s) {
                                    rawIntervals.add(TimeInterval(s, e))
                                }
                            }
                        }
                    }
                }

                // Xử lý phiên đang diễn ra tại thời điểm truy vấn (chưa có sự kiện background kết thúc)
                for ((_, startTime) in activeActivityStarts) {
                    val s = maxOf(startTime, beginTime)
                    val e = endTime
                    if (e > s) {
                        rawIntervals.add(TimeInterval(s, e))
                    }
                }

                // Gộp tất cả các khoảng thời gian bị chồng nhau để không cộng trùng thời gian
                val merged = mergeIntervals(rawIntervals)
                if (merged.isNotEmpty()) {
                    resultMap[pkg] = merged
                }
            }

            return resultMap
        }

        /**
         * Thuật toán Merge Overlapping Intervals (hợp các khoảng thời gian giao nhau).
         */
        fun mergeIntervals(intervals: List<TimeInterval>): List<TimeInterval> {
            if (intervals.isEmpty()) return emptyList()
            val sorted = intervals.sortedBy { it.start }
            val result = mutableListOf<TimeInterval>()
            var current = sorted[0]

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.start <= current.end) {
                    // Chồng lấn hoặc tiếp giáp -> gộp khoảng
                    current = TimeInterval(current.start, maxOf(current.end, next.end))
                } else {
                    result.add(current)
                    current = next
                }
            }
            result.add(current)
            return result
        }

        /**
         * Định dạng mili giây sang văn bản: "1 giờ 25 phút", "45 phút", "< 1 phút", "0 phút".
         */
        fun formatUsageTime(totalMs: Long): String {
            if (totalMs <= 0L) return "0 phút"
            val totalSeconds = totalMs / 1000
            val totalMinutes = totalSeconds / 60
            if (totalMinutes < 1) {
                return "< 1 phút"
            }
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> "$hours giờ $minutes phút"
                hours > 0 -> "$hours giờ"
                else -> "$minutes phút"
            }
        }

        /**
         * Định dạng ngắn gọn cho biểu đồ: "1h25p", "45p", "<1p", "0p".
         */
        fun formatShortUsageTime(totalMs: Long): String {
            if (totalMs <= 0L) return "0p"
            val totalMinutes = totalMs / (1000 * 60)
            if (totalMinutes < 1) return "<1p"
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}h${minutes}p"
                hours > 0 -> "${hours}h"
                else -> "${minutes}p"
            }
        }
    }
}
