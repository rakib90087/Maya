package com.example.data

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class NotificationType {
    MESSAGE,
    CALL,
    OTHER
}

data class MayaNotificationEvent(
    val type: NotificationType,
    val appName: String,
    val sender: String,
    val content: String,
    val sbn: StatusBarNotification
)

object NotificationEvents {
    private val _events = MutableSharedFlow<MayaNotificationEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    // Keep track of the last replyable notification to handle voice-command replies
    var lastReplyableNotification: StatusBarNotification? = null
    // Keep track of the last active call notification to handle voice-command answering
    var lastCallNotification: StatusBarNotification? = null

    fun postEvent(event: MayaNotificationEvent) {
        if (event.type == NotificationType.MESSAGE && hasReplyAction(event.sbn)) {
            lastReplyableNotification = event.sbn
        }
        if (event.type == NotificationType.CALL) {
            lastCallNotification = event.sbn
        }
        _events.tryEmit(event)
    }

    private fun hasReplyAction(sbn: StatusBarNotification): Boolean {
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                return true
            }
        }
        return false
    }

    fun isNotificationAccessGranted(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    fun openNotificationAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("NotificationEvents", "Failed to open notification settings", e)
        }
    }

    fun replyToLastNotification(context: Context, replyText: String): Boolean {
        val sbn = lastReplyableNotification ?: return false
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, replyText)
                val intent = Intent()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                try {
                    action.actionIntent.send(context, 0, intent)
                    Log.d("NotificationEvents", "Successfully sent reply via notification action.")
                    // Clear after replying to avoid double reply
                    lastReplyableNotification = null
                    return true
                } catch (e: Exception) {
                    Log.e("NotificationEvents", "Error sending reply intent", e)
                }
            }
        }
        return false
    }

    fun answerLastCall(context: Context): Boolean {
        // Method 1: Try to trigger "Answer" action on the notification
        val sbn = lastCallNotification
        if (sbn != null) {
            val actions = sbn.notification.actions
            if (actions != null) {
                for (action in actions) {
                    val title = action.title?.toString()?.lowercase() ?: ""
                    if (title.contains("answer") || title.contains("accept") || title.contains("কল") || title.contains("রিসিভ") || title.contains("গ্রহণ")) {
                        try {
                            action.actionIntent.send()
                            Log.d("NotificationEvents", "Successfully answered call via notification action.")
                            lastCallNotification = null
                            return true
                        } catch (e: Exception) {
                            Log.e("NotificationEvents", "Error sending answer intent from notification", e)
                        }
                    }
                }
            }
        }

        // Method 2: TelecomManager fallback
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (context.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    telecomManager?.acceptRingingCall()
                    Log.d("NotificationEvents", "Successfully answered call via TelecomManager.")
                    lastCallNotification = null
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("NotificationEvents", "Error answering via TelecomManager", e)
        }
        return false
    }
}

class MayaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MayaNotificationService", "Notification listener connected successfully.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        
        val packageName = sbn.packageName ?: return
        // Skip our own notifications
        if (packageName == packageName) {
            val myPackage = applicationContext.packageName
            if (packageName == myPackage) return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Skip empty notifications
        if (title.isBlank() && text.isBlank()) return

        // Resolve friendly app name
        val appName = when {
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("facebook.orca") || packageName.contains("messenger") -> "Messenger"
            packageName.contains("android.talk") || packageName.contains("hangouts") -> "Google Chat"
            packageName.contains("sms") || packageName.contains("messaging") || packageName.contains("telephony") -> "SMS"
            packageName.contains("viber") -> "Viber"
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("dialer") || packageName.contains("phone") -> "Phone Call"
            else -> {
                try {
                    val pm = packageManager
                    val ai = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    "App"
                }
            }
        }

        // Determine category / type
        val category = notification.category ?: ""
        val type = when {
            category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MISSED_CALL || appName == "Phone Call" -> NotificationType.CALL
            category == Notification.CATEGORY_MESSAGE || appName in listOf("WhatsApp", "Messenger", "SMS", "Telegram", "Google Chat") -> NotificationType.MESSAGE
            else -> NotificationType.OTHER
        }

        // We only announce message and call notifications to keep voice flow natural and pleasant
        if (type == NotificationType.MESSAGE || type == NotificationType.CALL) {
            val event = MayaNotificationEvent(
                type = type,
                appName = appName,
                sender = title,
                content = text,
                sbn = sbn
            )
            NotificationEvents.postEvent(event)
        }
    }
}
