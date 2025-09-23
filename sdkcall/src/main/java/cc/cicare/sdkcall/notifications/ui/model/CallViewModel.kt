package cc.cicare.sdkcall.notifications.ui.model

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CallViewModel : ViewModel() {
    private val _callStatusRaw = MutableStateFlow("calling")
    val callStatusRaw: StateFlow<String> = _callStatusRaw

    fun updateState(state: String) {
        _callStatusRaw.value = state
    }

}
