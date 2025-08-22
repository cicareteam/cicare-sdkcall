package cc.cicare.sdkcall.notifications.ui.model

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CallViewModel : ViewModel() {
    private val _callTimer = MutableStateFlow("00:00")
    val callTimer: StateFlow<String> = _callTimer

    private val _callStatusRaw = MutableStateFlow("calling")
    val callStatusRaw: StateFlow<String> = _callStatusRaw

    fun updateTimer(seconds: Int) {
        _callTimer.value = formatTime(seconds)
    }

    fun updateState(state: String) {
        _callStatusRaw.value = state
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }
}
