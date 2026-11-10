package com.meenai.mina.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home 页的状态:顶部通知(可左滑删除、可撤销)、常驻天气卡(自动定位)、天气是不是第一次在取。
 * 通知库没有流,隔几秒看一眼;天气启动先把缓存画出来再原地更新,打开期间每半小时一刷。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    data class HomeUiState(
        val notifications: List<NotifUi> = emptyList(),
        val weather: WeatherData? = null,
        val isWeatherLoading: Boolean = false,
        /** 没给定位权限时亮出「开启定位」的入口。 */
        val locationDenied: Boolean = false,
    )

    private val weatherRepo = WeatherRepository(app)
    private val notifRepo = NotificationRepository(app)
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        // 启动:缓存先上屏,新鲜度判断决定要不要重取
        viewModelScope.launch {
            weatherRepo.cached()?.let { c -> _ui.update { it.copy(weather = c) } }
            refreshWeather(force = false)
        }
        // 打开期间每 30 分钟一刷
        viewModelScope.launch {
            while (true) {
                delay(30 * 60_000L)
                refreshWeather(force = false)
            }
        }
        // 通知:隔几秒看一眼(删掉的不回来,Undo 才回来)
        viewModelScope.launch {
            while (true) {
                _ui.update { it.copy(notifications = notifRepo.active()) }
                delay(4_000L)
            }
        }
    }

    /** 回到前台:位置可能变了,看一眼(缓存新鲜就原地不动)。 */
    fun onResume() = refreshWeather(force = false)

    /** 定位授权刚给:立刻强制刷新一次。 */
    fun onLocationGranted() = refreshWeather(force = true)

    private fun refreshWeather(force: Boolean) = viewModelScope.launch {
        _ui.update { it.copy(isWeatherLoading = true) }
        val w = runCatching { weatherRepo.refresh(force) }.getOrNull()
        _ui.update {
            it.copy(
                weather = w ?: it.weather,
                isWeatherLoading = false,
                locationDenied = !weatherRepo.hasLocationPermission(),
            )
        }
    }

    /** 删一条通知:界面上立刻消失,持久化里也删掉(不会回来)。 */
    fun dismiss(key: String) {
        _ui.update { it.copy(notifications = it.notifications.filterNot { n -> n.key == key }) }
        viewModelScope.launch { notifRepo.dismiss(key) }
    }

    /** Undo:放回来。 */
    fun undoDismiss(key: String) = viewModelScope.launch {
        notifRepo.undismiss(key)
        _ui.update { it.copy(notifications = notifRepo.active()) }
    }
}
