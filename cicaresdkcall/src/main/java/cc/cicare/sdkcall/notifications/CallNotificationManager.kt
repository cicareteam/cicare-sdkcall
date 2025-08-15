package cc.cicare.sdkcall.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jakarta.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CallNotificationManager {

    @Provides
    @Singleton
    fun provideNotificationmanagerCompat(
        @ApplicationContext context: Context,
        channelId: String,
        important: Int
    ): NotificationManagerCompat {
        val notificationManager = NotificationManagerCompat.from(context)
        val channel = NotificationChannel(
            channelId,
            "CALL_CHANNEL_NAME",
            important
        )
        notificationManager.createNotificationChannel(channel)
        return notificationManager
    }

    @Provides
    @Singleton
    fun incomingCallNotificationBuilder(
        @ApplicationContext context: Context,
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
        }

        return NotificationCompat.Builder(context, channelId)
            .setFullScreenIntent(screenCallIntent(context, intent, "INCOMING"), true)
            .setSmallIcon(CiCareCallService.INCOMING_CALL_ICON)
            .setSound(CiCareCallService.ringtoneUrl, AudioManager.STREAM_RING)
            .addPerson(callerProfile)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(
                callerProfile,
                PendingIntent.getService(
                    context, 1,rejectService, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT),
                screenCallIntent(context, intent, CiCareCallService.ACTION.ACCEPT)
            ))
    }

    @Provides
    @Singleton
    fun outgoingCallNotificationBuilder(
        @ApplicationContext context: Context,
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
                serviceCallIntent(context, intent, 1, CiCareCallService.ACTION.HANGUP)
            ))
    }
    @Provides
    @Singleton
    fun ongoingCallNotificationBuilder(
        @ApplicationContext context: Context,
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
                serviceCallIntent(context, intent, 1, CiCareCallService.ACTION.HANGUP)
            ))
    }

    @Provides
    @Singleton
    fun missedCallNotificationBuilder(
        @ApplicationContext context: Context,
        intent: Intent,
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addPerson(callerProfile)
            .setContentTitle(calleeName)
            .setContentText(description)
            .setAutoCancel(true)
    }

    private fun serviceCallIntent(
        @ApplicationContext context: Context,
        intent: Intent,
        code: Int,
        callAction: String
    ): PendingIntent {
        val hangupIntent = Intent(context, CiCareCallService::class.java).apply {
            action = callAction
            putExtras(intent)
        }

        return PendingIntent.getService(
            context, code, hangupIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
    }

    private fun screenCallIntent(
        @ApplicationContext context: Context,
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