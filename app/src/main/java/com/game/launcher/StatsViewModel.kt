package com.game.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mục hiển thị xếp hạng game theo thời gian chơi.
 */
data class GameStatDisplayItem(
    val game: GameModel,
    val timeMs: Long,
    val timeFormatted: String,
    val ratio: Float // Tỉ lệ so với game chơi nhiều nhất trong danh sách (0f .. 1f)
)

/**
 * Trạng thái giao diện màn hình Thống kê.
 */
data class StatsUiState(
    val isLoading: Boolean = true,
    val hasPermission: Boolean = false,
    val selectedPeriod: StatsPeriod = StatsPeriod.TODAY,
    val totalTimeMs: Long = 0L,
    val totalTimeFormatted: String = "0 phút",
    val dailyUsages: List<DailyUsage> = emptyList(),
    val rankedGames: List<GameStatDisplayItem> = emptyList()
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = UsageStatsRepository(application)

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    private var currentLibrary: List<GameModel> = emptyList()

    /**
     * Cập nhật danh sách game thư viện hiện tại và tải lại dữ liệu thống kê.
     */
    fun updateLibraryAndLoad(library: List<GameModel>) {
        currentLibrary = library
        loadStats()
    }

    /**
     * Chuyển đổi khoảng thời gian (Hôm nay / 7 ngày).
     */
    fun selectPeriod(period: StatsPeriod) {
        if (_uiState.value.selectedPeriod == period) return
        _uiState.update { it.copy(selectedPeriod = period) }
        loadStats()
    }

    /**
     * Tải lại dữ liệu (ví dụ khi app quay lại ON_RESUME).
     */
    fun refresh() {
        loadStats()
    }

    private fun loadStats() {
        val hasPermission = repository.hasUsagePermission()
        if (!hasPermission) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasPermission = false,
                    totalTimeMs = 0L,
                    totalTimeFormatted = "0 phút",
                    dailyUsages = emptyList(),
                    rankedGames = emptyList()
                )
            }
            return
        }

        val period = _uiState.value.selectedPeriod
        val library = currentLibrary

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, hasPermission = true) }

            val statsData = withContext(Dispatchers.IO) {
                val packages = library.map { it.packageName }.toSet()
                repository.queryUsageStats(period, packages)
            }

            // Map kết quả sang danh sách hiển thị
            val gameMap = library.associateBy { it.packageName }
            // Lọc chỉ game trong thư viện, loại bỏ game 0 phút, sắp xếp giảm dần theo thời gian
            val validGameUsages = statsData.gameUsages
                .filter { it.timeMs > 0L && gameMap.containsKey(it.packageName) }
                .sortedByDescending { it.timeMs }

            val maxTimeMs = validGameUsages.maxOfOrNull { it.timeMs } ?: 1L

            val rankedItems = validGameUsages.mapNotNull { gu ->
                val game = gameMap[gu.packageName] ?: return@mapNotNull null
                GameStatDisplayItem(
                    game = game,
                    timeMs = gu.timeMs,
                    timeFormatted = UsageStatsRepository.formatUsageTime(gu.timeMs),
                    ratio = (gu.timeMs.toFloat() / maxTimeMs.toFloat()).coerceIn(0f, 1f)
                )
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasPermission = true,
                    totalTimeMs = statsData.totalTimeMs,
                    totalTimeFormatted = UsageStatsRepository.formatUsageTime(statsData.totalTimeMs),
                    dailyUsages = statsData.dailyUsages,
                    rankedGames = rankedItems
                )
            }
        }
    }
}
