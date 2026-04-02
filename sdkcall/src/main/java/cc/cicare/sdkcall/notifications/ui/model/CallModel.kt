package cc.cicare.sdkcall.notifications.ui.model

data class CallInfo(
    val callerId: String,
    val callerName: String,
    val callerAvatar: String,
    val calleeId: String,
    val calleeName: String,
    val calleeAvatar: String,
    val checksum: String,
)