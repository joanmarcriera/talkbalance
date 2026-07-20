package uk.co.riera.talkbalance.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class SettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    val nudgeEnabled = settingsRepository.nudgeEnabled
    val nudgeSeconds = settingsRepository.nudgeSeconds
    val matchThreshold = settingsRepository.matchThreshold
    val keepScreenOn = settingsRepository.keepScreenOn

    fun setNudgeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNudgeEnabled(enabled)
        }
    }

    fun setNudgeSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setNudgeSeconds(seconds)
        }
    }

    fun setMatchThreshold(threshold: Float) {
        viewModelScope.launch {
            settingsRepository.setMatchThreshold(threshold)
        }
    }

    fun setKeepScreenOn(keepOn: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepScreenOn(keepOn)
        }
    }

    companion object {
        fun factory(settingsRepository: SettingsRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(settingsRepository) as T
                }
            }
        }
    }
}
