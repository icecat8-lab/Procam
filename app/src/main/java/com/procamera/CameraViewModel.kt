package com.procamera

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_ACTIVE_MODE = "active_mode"
        private const val KEY_AF_PROGRESS = "af_progress"
        private const val KEY_ISO_PROGRESS = "iso_progress"
        private const val KEY_WB_PROGRESS = "wb_progress"
        private const val KEY_S_PROGRESS = "s_progress"
        private const val KEY_EV_PROGRESS = "ev_progress"
        private const val KEY_LATEST_URI = "latest_uri"
    }

    // Reactive state flows (UI collects these)
    private val _activeMode = MutableStateFlow(savedStateHandle.get<String>(KEY_ACTIVE_MODE) ?: "NONE")
    val activeModeFlow: StateFlow<String> = _activeMode.asStateFlow()

    private val _afProgress = MutableStateFlow(savedStateHandle.get<Int>(KEY_AF_PROGRESS) ?: 0)
    val afProgressFlow: StateFlow<Int> = _afProgress.asStateFlow()

    private val _isoProgress = MutableStateFlow(savedStateHandle.get<Int>(KEY_ISO_PROGRESS) ?: 0)
    val isoProgressFlow: StateFlow<Int> = _isoProgress.asStateFlow()

    private val _wbProgress = MutableStateFlow(savedStateHandle.get<Int>(KEY_WB_PROGRESS) ?: 0)
    val wbProgressFlow: StateFlow<Int> = _wbProgress.asStateFlow()

    private val _sProgress = MutableStateFlow(savedStateHandle.get<Int>(KEY_S_PROGRESS) ?: 0)
    val sProgressFlow: StateFlow<Int> = _sProgress.asStateFlow()

    private val _evProgress = MutableStateFlow(savedStateHandle.get<Int>(KEY_EV_PROGRESS) ?: 50)
    val evProgressFlow: StateFlow<Int> = _evProgress.asStateFlow()

    // Backwards-compatible plain property accessors (used by MainActivity)
    var activeMode: String
        get() = _activeMode.value
        set(value) {
            _activeMode.value = value
            savedStateHandle[KEY_ACTIVE_MODE] = value
        }

    var afProgress: Int
        get() = _afProgress.value
        set(value) {
            _afProgress.value = value
            savedStateHandle[KEY_AF_PROGRESS] = value
        }

    var isoProgress: Int
        get() = _isoProgress.value
        set(value) {
            _isoProgress.value = value
            savedStateHandle[KEY_ISO_PROGRESS] = value
        }

    var wbProgress: Int
        get() = _wbProgress.value
        set(value) {
            _wbProgress.value = value
            savedStateHandle[KEY_WB_PROGRESS] = value
        }

    var sProgress: Int
        get() = _sProgress.value
        set(value) {
            _sProgress.value = value
            savedStateHandle[KEY_S_PROGRESS] = value
        }

    var evProgress: Int
        get() = _evProgress.value
        set(value) {
            _evProgress.value = value
            savedStateHandle[KEY_EV_PROGRESS] = value
        }

    var latestImageUri: Uri?
        get() = savedStateHandle.get<Uri>(KEY_LATEST_URI)
        set(value) {
            savedStateHandle[KEY_LATEST_URI] = value
        }
}
