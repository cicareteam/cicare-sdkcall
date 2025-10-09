package cc.cicare.sdkcall.event

interface CallEventListener {
    fun onCallStateChange(callState: CallState)

    fun onError(code: Int, message: String)
}