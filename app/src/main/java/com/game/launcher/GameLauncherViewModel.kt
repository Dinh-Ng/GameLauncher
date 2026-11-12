package com.game.launcher

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "game_library")

private val KEY_PACKAGES = stringSetPreferencesKey("library_packages")
private val KEY_INITIALIZED = booleanPreferencesKey("initialized")
private val KEY_INTERNAL_TIMESTAMPS = stringPreferencesKey("internal_timestamps")
private val KEY_SORT_MODE = stringPreferencesKey("sort_mode")
private val KEY_COLUMNS_COUNT = intPreferencesKey("columns_count")
private val KEY_USAGE_PERMISSION_DISMISSED = booleanPreferencesKey("usage_permission_dismissed")
/** Lưu tên tùy chỉnh theo format "package|tên\npackage|tên" */
private val KEY_CUSTOM_NAMES = stringPreferencesKey("custom_names")

private const val ICON_SIZE_PX = 128

/** Trạng thái duy nhất mà UI quan sát. */
data class GameLauncherUiState(
    val isLoading: Boolean = true,
    /** Các game đã nằm trong thư viện của người dùng (đã sắp xếp, displayName đã ghép). */
    val library: List<GameModel> = emptyList(),
    /** Các app đã cài nhưng chưa có trong thư viện (đã lọc theo tìm kiếm / app hệ thống). */
    val candidates: List<GameModel> = emptyList(),
    val searchQuery: String = "",
    val showSystemApps: Boolean = false,
    val settings: LauncherSettings = LauncherSettings(),
    val usageAccessStatus: UsageAccessStatus = UsageAccessStatus.NOT_SUPPORTED,
    val showUsagePermissionDialog: Boolean = false
)

class GameLauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val ownPackage: String = appContext.packageName
    private val store = appContext.dataStore

    private val allApps = MutableStateFlow<List<GameModel>>(emptyList())
    private val isLoading = MutableStateFlow(true)
    private val searchQuery = MutableStateFlow("")
    private val showSystemApps = MutableStateFlow(false)

    private val systemUsageStats = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val usageAccessStatusFlow = MutableStateFlow(UsageAccessStatus.NOT_SUPPORTED)
    private val showUsageDialogFlow = MutableStateFlow(false)

    private val savedPackages = store.data.map { it[KEY_PACKAGES] ?: emptySet() }
    private val internalTimestamps = store.data.map { parseTimestamps(it[KEY_INTERNAL_TIMESTAMPS]) }
    private val customNames = store.data.map { parseCustomNames(it[KEY_CUSTOM_NAMES]) }
    private val settingsFlow = store.data.map { prefs ->
        val sortModeName = prefs[KEY_SORT_MODE] ?: SortMode.RECENT.name
        val sortMode = runCatching { SortMode.valueOf(sortModeName) }.getOrDefault(SortMode.RECENT)

        val columnsCount = prefs[KEY_COLUMNS_COUNT] ?: 0
        val columns = if (columnsCount in 2..6) {
            ColumnsMode.Fixed(columnsCount)
        } else {
            ColumnsMode.Auto
        }

        LauncherSettings(sortMode = sortMode, columns = columns)
    }

    val uiState: StateFlow<GameLauncherUiState> = combine(
        combine(allApps, savedPackages, isLoading, searchQuery, showSystemApps) { all, saved, loading, query, showSystem ->
            CombinedAppData(all, saved, loading, query, showSystem)
        },
        combine(internalTimestamps, systemUsageStats, settingsFlow, usageAccessStatusFlow, showUsageDialogFlow) { internalTimes, systemTimes, settings, usageStatus, showDialog ->
            CombinedSettingsData(internalTimes, systemTimes, settings, usageStatus, showDialog)
        },
        customNames
    ) { appData, settingsData, nameMap ->
        val q = appData.query.trim()
        val settings = settingsData.settings

        // Gắn timestamp hiệu lực và displayName cho từng game trong thư viện
        val libraryWithData = appData.all
            .filter { it.packageName in appData.saved }
            .map { game ->
                val internalTime = settingsData.internalTimes[game.packageName] ?: 0L
                val systemTime = settingsData.systemTimes[game.packageName] ?: 0L
                val effectiveTime = maxOf(internalTime, systemTime)
                val displayName = nameMap[game.packageName] ?: game.name
                game.copy(lastPlayed = effectiveTime, displayName = displayName)
            }

        // Sắp xếp theo cấu hình đã chọn (dùng displayName cho chế độ NAME)
        val sortedLibrary = libraryWithData.sortedWith(createComparator(settings.sortMode))

        val candidates = appData.all.filter {
            it.packageName !in appData.saved &&
                (appData.showSystem || !it.isSystemApp) &&
                (q.isEmpty() || it.name.contains(q, ignoreCase = true))
        }

        GameLauncherUiState(
            isLoading = appData.loading,
            library = sortedLibrary,
            candidates = candidates,
            searchQuery = appData.query,
            showSystemApps = appData.showSystem,
            settings = settings,
            usageAccessStatus = settingsData.usageStatus,
            showUsagePermissionDialog = settingsData.showDialog
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameLauncherUiState())

    init {
        refresh()
        checkAndInitUsagePermissionState()
    }

    /** Gọi khi Activity resume để cập nhật dữ liệu lần chơi, kiểm tra lại quyền và quét app (phát hiện app bị gỡ). */
    fun onResume() {
        viewModelScope.launch {
            updateUsageStatsIfGranted()
            // Quét lại để game bị gỡ cài đặt biến mất khỏi thư viện
            val apps = withContext(Dispatchers.IO) { queryLaunchableApps() }
            allApps.value = apps
            val installed = apps.map { it.packageName }.toSet()
            store.edit { prefs ->
                val currentLibrary = prefs[KEY_PACKAGES] ?: emptySet()
                val removedPackages = currentLibrary - installed
                if (removedPackages.isNotEmpty()) {
                    prefs[KEY_PACKAGES] = currentLibrary.intersect(installed)
                    // Xóa tên tùy chỉnh của game đã bị gỡ
                    val currentNames = parseCustomNames(prefs[KEY_CUSTOM_NAMES]).toMutableMap()
                    removedPackages.forEach { currentNames.remove(it) }
                    prefs[KEY_CUSTOM_NAMES] = formatCustomNames(currentNames)
                }
            }
        }
    }

    /** Quét lại danh sách app đã cài, tự động dọn các app đã bị gỡ khỏi thư viện. */
    fun refresh() {
        viewModelScope.launch {
            isLoading.value = true
            val apps = withContext(Dispatchers.IO) { queryLaunchableApps() }
            allApps.value = apps

            val installed = apps.map { it.packageName }.toSet()
            store.edit { prefs ->
                if (prefs[KEY_INITIALIZED] != true) {
                    // Lần đầu: tự thêm các app được hệ thống nhận là game
                    prefs[KEY_PACKAGES] = apps.filter { it.isGame }.map { it.packageName }.toSet()
                    prefs[KEY_INITIALIZED] = true
                } else {
                    // Các lần sau: bỏ app đã bị gỡ cài đặt
                    val currentLibrary = prefs[KEY_PACKAGES] ?: emptySet()
                    val removedPackages = currentLibrary - installed
                    prefs[KEY_PACKAGES] = currentLibrary.intersect(installed)
                    // Xóa tên tùy chỉnh của game đã bị gỡ
                    if (removedPackages.isNotEmpty()) {
                        val currentNames = parseCustomNames(prefs[KEY_CUSTOM_NAMES]).toMutableMap()
                        removedPackages.forEach { currentNames.remove(it) }
                        prefs[KEY_CUSTOM_NAMES] = formatCustomNames(currentNames)
                    }
                }
            }

            updateUsageStatsIfGranted()
            isLoading.value = false
        }
    }

    fun addGame(packageName: String) {
        viewModelScope.launch {
            store.edit { it[KEY_PACKAGES] = (it[KEY_PACKAGES] ?: emptySet()) + packageName }
        }
    }

    /** Xóa game khỏi thư viện, đồng thời xóa tên tùy chỉnh nếu có. */
    fun removeGame(packageName: String) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[KEY_PACKAGES] = (prefs[KEY_PACKAGES] ?: emptySet()) - packageName
                val currentNames = parseCustomNames(prefs[KEY_CUSTOM_NAMES]).toMutableMap()
                if (currentNames.remove(packageName) != null) {
                    prefs[KEY_CUSTOM_NAMES] = formatCustomNames(currentNames)
                }
            }
        }
    }

    /**
     * Lưu tên tùy chỉnh cho game. Nếu [customName] rỗng thì xóa tên tùy chỉnh (khôi phục tên gốc).
     */
    fun onRename(packageName: String, customName: String) {
        viewModelScope.launch {
            store.edit { prefs ->
                val currentNames = parseCustomNames(prefs[KEY_CUSTOM_NAMES]).toMutableMap()
                val trimmed = customName.trim()
                if (trimmed.isEmpty()) {
                    currentNames.remove(packageName)
                } else {
                    currentNames[packageName] = trimmed
                }
                prefs[KEY_CUSTOM_NAMES] = formatCustomNames(currentNames)
            }
        }
    }

    /** Xóa tên tùy chỉnh, khôi phục về tên gốc. */
    fun onResetName(packageName: String) {
        viewModelScope.launch {
            store.edit { prefs ->
                val currentNames = parseCustomNames(prefs[KEY_CUSTOM_NAMES]).toMutableMap()
                currentNames.remove(packageName)
                prefs[KEY_CUSTOM_NAMES] = formatCustomNames(currentNames)
            }
        }
    }

    fun onSearchQueryChange(query: String) = searchQuery.update { query }

    fun onShowSystemAppsChange(show: Boolean) = showSystemApps.update { show }

    /** Lưu chế độ sắp xếp bền vững vào DataStore. */
    fun setSortMode(mode: SortMode) {
        viewModelScope.launch {
            store.edit { it[KEY_SORT_MODE] = mode.name }
        }
    }

    /** Lưu chế độ số cột bền vững vào DataStore. */
    fun setColumnsMode(columns: ColumnsMode) {
        viewModelScope.launch {
            val count = when (columns) {
                is ColumnsMode.Auto -> 0
                is ColumnsMode.Fixed -> columns.count
            }
            store.edit { it[KEY_COLUMNS_COUNT] = count }
        }
    }

    /** Ghi nhận thời điểm mở game thành công từ launcher (nguồn a). */
    fun recordGameLaunched(packageName: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            store.edit { prefs ->
                val current = parseTimestamps(prefs[KEY_INTERNAL_TIMESTAMPS]).toMutableMap()
                current[packageName] = now
                prefs[KEY_INTERNAL_TIMESTAMPS] = formatTimestamps(current)
            }
        }
    }

    /** Trả về Intent để mở app, hoặc null nếu app không còn / không thể mở. */
    fun getLaunchIntent(packageName: String): Intent? =
        pm.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Tạo Intent mở màn hình Thông tin ứng dụng. */
    fun getAppDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Tạo Intent kích hoạt flow gỡ cài đặt ứng dụng. */
    fun getUninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Tạo Intent mở màn hình Cài đặt cấp quyền truy cập dữ liệu sử dụng. */
    fun getUsageAccessSettingsIntent(): Intent {
        val packageUriIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:$ownPackage")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (isIntentHandled(packageUriIntent)) {
            packageUriIntent
        } else {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /** Người dùng chọn "Để sau" trên dialog xin quyền. */
    fun onDismissUsagePermissionDialog() {
        showUsageDialogFlow.value = false
        viewModelScope.launch {
            store.edit { it[KEY_USAGE_PERMISSION_DISMISSED] = true }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Logic quyền và UsageStatsManager
    // ---------------------------------------------------------------------------------------

    private fun checkAndInitUsagePermissionState() {
        viewModelScope.launch {
            val supported = isUsageAccessSettingAvailable()
            if (!supported) {
                usageAccessStatusFlow.value = UsageAccessStatus.NOT_SUPPORTED
                return@launch
            }

            val granted = checkUsageAccessPermission()
            usageAccessStatusFlow.value = if (granted) UsageAccessStatus.GRANTED else UsageAccessStatus.DENIED

            if (granted) {
                updateUsageStatsIfGranted()
            } else {
                // Kiểm tra xem đã từng bấm "Để sau" chưa
                store.data.map { it[KEY_USAGE_PERMISSION_DISMISSED] == true }
                    .collect { dismissed ->
                        if (!dismissed && usageAccessStatusFlow.value == UsageAccessStatus.DENIED) {
                            showUsageDialogFlow.value = true
                        }
                    }
            }
        }
    }

    private suspend fun updateUsageStatsIfGranted() {
        if (!isUsageAccessSettingAvailable()) {
            usageAccessStatusFlow.value = UsageAccessStatus.NOT_SUPPORTED
            systemUsageStats.value = emptyMap()
            return
        }

        val granted = checkUsageAccessPermission()
        usageAccessStatusFlow.value = if (granted) UsageAccessStatus.GRANTED else UsageAccessStatus.DENIED

        if (granted) {
            showUsageDialogFlow.value = false
            val stats = withContext(Dispatchers.IO) { querySystemUsageStats() }
            systemUsageStats.value = stats
        } else {
            // Khi quyền bị thu hồi, tự động quay về chỉ dùng timestamp nội bộ
            systemUsageStats.value = emptyMap()
        }
    }

    /**
     * Rẽ nhánh theo API level:
     * - API 29+ (Q): Dùng unsafeCheckOpNoThrow
     * - API 26-28: Dùng checkOpNoThrow (đã deprecated từ API 29)
     */
    private fun checkUsageAccessPermission(): Boolean {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ownPackage
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ownPackage
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Kiểm tra thiết bị có Activity xử lý Settings.ACTION_USAGE_ACCESS_SETTINGS không. */
    private fun isUsageAccessSettingAvailable(): Boolean {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        return isIntentHandled(intent)
    }

    private fun isIntentHandled(intent: Intent): Boolean {
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolveInfos.isNotEmpty()
    }

    private fun querySystemUsageStats(): Map<String, Long> {
        return try {
            val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyMap()
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 1000L * 60 * 60 * 24 * 365
            val realTimeStartTime = endTime - 1000L * 60 * 60 * 24 * 30

            val result = mutableMapOf<String, Long>()

            // 1. Dữ liệu tổng hợp từ queryAndAggregateUsageStats
            val aggregated = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
            for ((pkg, stats) in aggregated) {
                if (stats.lastTimeUsed > 0L) {
                    result[pkg] = stats.lastTimeUsed
                }
            }

            // 2. Sự kiện real-time mới nhất từ queryEvents (không bị trễ do cache hệ thống)
            val events = usageStatsManager.queryEvents(realTimeStartTime, endTime)
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val eventType = event.eventType
                if (eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                    eventType == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED ||
                    eventType == android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
                ) {
                    val pkg = event.packageName
                    if (!pkg.isNullOrEmpty() && pkg != ownPackage) {
                        val current = result[pkg] ?: 0L
                        if (event.timeStamp > current) {
                            result[pkg] = event.timeStamp
                        }
                    }
                }
            }

            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Sắp xếp dùng displayName (tên tùy chỉnh) ở chế độ NAME.
     * Chế độ RECENT vẫn dùng displayName khi thời gian bằng nhau (tiebreaker).
     */
    private fun createComparator(sortMode: SortMode): Comparator<GameModel> {
        return Comparator { a, b ->
            when (sortMode) {
                SortMode.NAME -> a.displayName.compareTo(b.displayName, ignoreCase = true)
                SortMode.RECENT -> {
                    when {
                        // Cả hai đã chơi: mới nhất lên đầu, nếu bằng nhau thì theo displayName A-Z
                        a.lastPlayed > 0L && b.lastPlayed > 0L -> {
                            val timeCompare = b.lastPlayed.compareTo(a.lastPlayed)
                            if (timeCompare != 0) timeCompare else a.displayName.compareTo(b.displayName, ignoreCase = true)
                        }
                        // a đã chơi, b chưa chơi: a xếp trước
                        a.lastPlayed > 0L -> -1
                        // b đã chơi, a chưa chơi: b xếp trước
                        b.lastPlayed > 0L -> 1
                        // Cả hai chưa chơi: xếp theo displayName A-Z
                        else -> a.displayName.compareTo(b.displayName, ignoreCase = true)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchableApps(): List<GameModel> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

        return resolveInfos
            .asSequence()
            .filter { it.activityInfo.packageName != ownPackage }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { ri ->
                runCatching {
                    val info = ri.activityInfo.applicationInfo
                    val flags = info.flags
                    val isGame = info.category == ApplicationInfo.CATEGORY_GAME ||
                        (flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    GameModel(
                        name = ri.loadLabel(pm).toString(),
                        packageName = ri.activityInfo.packageName,
                        icon = ri.loadIcon(pm).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX).asImageBitmap(),
                        isGame = isGame,
                        isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }.getOrNull()
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    companion object {
        private fun parseTimestamps(raw: String?): Map<String, Long> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.lineSequence()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0 && idx < line.length - 1) {
                        val pkg = line.substring(0, idx).trim()
                        val time = line.substring(idx + 1).trim().toLongOrNull()
                        if (pkg.isNotEmpty() && time != null) pkg to time else null
                    } else null
                }
                .toMap()
        }

        private fun formatTimestamps(map: Map<String, Long>): String {
            return map.entries.joinToString("\n") { "${it.key}=${it.value}" }
        }

        /**
         * Format: "package\u0000tên_tùy_chỉnh\npackage\u0000tên_tùy_chỉnh"
         * Dùng NULL char (\u0000) làm separator giữa package và tên để tránh xung đột với ký tự '=' trong tên.
         */
        private fun parseCustomNames(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.lineSequence()
                .mapNotNull { line ->
                    val idx = line.indexOf('\u0000')
                    if (idx > 0 && idx < line.length - 1) {
                        val pkg = line.substring(0, idx)
                        val customName = line.substring(idx + 1)
                        if (pkg.isNotEmpty() && customName.isNotEmpty()) pkg to customName else null
                    } else null
                }
                .toMap()
        }

        private fun formatCustomNames(map: Map<String, String>): String {
            return map.entries.joinToString("\n") { "${it.key}\u0000${it.value}" }
        }
    }
}

private data class CombinedAppData(
    val all: List<GameModel>,
    val saved: Set<String>,
    val loading: Boolean,
    val query: String,
    val showSystem: Boolean
)

private data class CombinedSettingsData(
    val internalTimes: Map<String, Long>,
    val systemTimes: Map<String, Long>,
    val settings: LauncherSettings,
    val usageStatus: UsageAccessStatus,
    val showDialog: Boolean
)
