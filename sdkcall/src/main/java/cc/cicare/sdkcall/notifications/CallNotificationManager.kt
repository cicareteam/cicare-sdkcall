package cc.cicare.sdkcall.notifications

import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService

object CallNotificationManager {

    var ringtoneUrl: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    fun provideNotificationManagerCompat(
        context: Context,
        channelId: String,
        important: Int
    ): NotificationManagerCompat {
        val notificationManager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CALL_CHANNEL_NAME",
                important
            )
            notificationManager.createNotificationChannel(channel)
        }
        return notificationManager
    }

    fun provideNotificationManagerIncoming(
        context: Context,
        channelId: String,
        important: Int
    ): NotificationManagerCompat {
        val notificationManager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelId,
                important
            )
            channel.setSound(ringtoneUrl, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            channel.enableVibration(true)
            channel.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 500, 1000, 500, 1000)
            notificationManager.createNotificationChannel(channel)
        }
        return notificationManager
    }

    fun incomingCallNotificationBuilder(
        context: Context,
        intent: Intent,
        channelId: String,
        callerName: String,
        callerAvatar: String
    ): NotificationCompat.Builder {
        val callerProfile = Person.Builder()
            .setUri(callerAvatar)
            .setName(callerName)
            .setImportant(true)
            .build()

        val rejectService = Intent(context, IncomingCallService::class.java).apply{
            action = CiCareCallService.ACTION.REJECT
            putExtras(intent)
        }

        return NotificationCompat.Builder(context, channelId)
            .setFullScreenIntent(screenCallIntent(context, intent, "INCOMING"), true)
            .setSmallIcon(CiCareCallService.INCOMING_CALL_ICON)
            .setSound(ringtoneUrl, AudioManager.STREAM_NOTIFICATION)
            .setVibrate(longArrayOf(0, 500, 1000, 500, 1000))
            .addPerson(callerProfile)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(
                callerProfile,
                PendingIntent.getService(
                    context, 1,rejectService, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT),
                screenCallIntent(context, intent, CiCareCallService.ACTION.ACCEPT)
            ))
    }

    fun outgoingCallNotificationBuilder(
        context: Context,
        intent: Intent,
        channelId: String,
        text: String,
        calleeName: String,
        calleeAvatar: String
    ): NotificationCompat.Builder {
        val callerProfile = Person.Builder()
            .setUri(calleeAvatar)
            .setName(calleeName)
            .setImportant(true)
            .build()

        return NotificationCompat.Builder(context, channelId)
            .setFullScreenIntent(screenCallIntent(context, intent, "SCREEN"), true)
            .setSmallIcon(CiCareCallService.OUTGOING_CALL_ICON)
            //.setSound(CiCareCallService.ringtoneUrl, AudioManager.STREAM_VOICE_CALL)
            .addPerson(callerProfile)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(
                callerProfile,
                serviceHangupCallIntent(context, intent)
            ))
    }
    fun ongoingCallNotificationBuilder(
        context: Context,
        intent: Intent,
        channelId: String,
        calleeName: String,
        calleeAvatar: String
    ): NotificationCompat.Builder {
        val callerProfile = Person.Builder()
            .setUri(calleeAvatar)
            .setName(calleeName)
            .setImportant(true)
            .build()

        return NotificationCompat.Builder(context, channelId)
            .setFullScreenIntent(screenCallIntent(context, intent, "SCREEN"), true)
            .setSmallIcon(CiCareCallService.ONGOING_CALL_ICON)
            //.setSound(CiCareCallService.ringtoneUrl, AudioManager.STREAM_VOICE_CALL)
            .addPerson(callerProfile)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(
                callerProfile,
                serviceHangupCallIntent(context, intent)
            ))
    }

    fun terminalCallNotificationBuilder(
        context: Context,
        channelId: String,
        calleeName: String,
        calleeAvatar: String,
        description: String
    ): NotificationCompat.Builder {
        val callerProfile = Person.Builder()
            .setUri(calleeAvatar)
            .setName(calleeName)
            .setImportant(true)
            .build()

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(CiCareCallService.MISSED_CALL_ICON)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addPerson(callerProfile)
            .setContentTitle(calleeName)
            .setContentText(description)
            .setAutoCancel(true)
            .setOngoing(false)
    }

    fun missedCallNotificationBuilder(
        context: Context,
        channelId: String,
        calleeName: String,
        calleeAvatar: String,
        description: String
    ): NotificationCompat.Builder {
        val callerProfile = Person.Builder()
            .setUri(calleeAvatar)
            .setName(calleeName)
            .setImportant(true)
            .build()

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(CiCareCallService.MISSED_CALL_ICON)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addPerson(callerProfile)
            .setContentTitle(calleeName)
            .setContentText(description)
            .setAutoCancel(true)
    }

    private fun serviceHangupCallIntent(
        context: Context,
        intent: Intent,
    ): PendingIntent {
        val hangupIntent = Intent(context, CiCareCallService::class.java).apply {
            action = CiCareCallService.ACTION.HANGUP
            putExtras(intent)
        }

        return PendingIntent.getService(
            context, 1, hangupIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
    }

    private fun screenCallIntent(
        context: Context,
        intent: Intent,
        callAction: String
    ): PendingIntent {
        val screenIntent = Intent(context, ScreenCallActivity::class.java).apply {
            action = callAction
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtras(intent)
        }

        return PendingIntent.getActivity(
            context, 0, screenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}