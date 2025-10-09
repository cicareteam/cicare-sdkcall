package cc.cicare.sdkcall.event

object MessageListenerHolder {
    var listener: MessageActionListener? = null
    var callEventListener: CallEventListener? = null
}