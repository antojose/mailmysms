package com.antolabs.mailmysms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.util.Log
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * A manifest-declared BroadcastReceiver for android.intent.action.PHONE_STATE does not
 * reliably fire in the background on modern Android (unlike SMS_RECEIVED, it is not on the
 * implicit-broadcast background-execution exemption list). A live listener held by a running
 * component is the only reliable way to detect call state changes, which means a foreground
 * service.
 */
class CallMonitorService : Service() {

    private var wasActive = false
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null

    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        startForegroundWithNotification()
        running = startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        running = false
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channelId = "call_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(channelId, "Call monitoring", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MailMySMS")
            .setContentText("Watching for call log entries")
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Returns whether the listener actually got registered - the single source of truth for
     *  whether this service is doing anything, not just alive. */
    private fun startListening(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "startListening: READ_PHONE_STATE granted=$granted, sdkInt=${Build.VERSION.SDK_INT}")
        if (!granted) {
            stopSelf()
            return false
        }
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        Log.d(TAG, "startListening: telephonyManager=${tm != null}")
        if (tm == null) return false
        telephonyManager = tm

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        Log.d(TAG, "onCallStateChanged (TelephonyCallback): state=$state")
                        handleState(state)
                    }
                }
                telephonyCallback = callback
                tm.registerTelephonyCallback(mainExecutor, callback)
                Log.d(TAG, "registerTelephonyCallback called successfully")
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        Log.d(TAG, "onCallStateChanged (PhoneStateListener): state=$state")
                        handleState(state)
                    }
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                Log.d(TAG, "PhoneStateListener.listen called successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register call state listener", e)
            return false
        }
        return true
    }

    private fun stopListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
    }

    private fun handleState(state: Int) {
        Log.d(TAG, "handleState: state=$state wasActive(before)=$wasActive")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                wasActive = true
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (wasActive) {
                    wasActive = false
                    Log.d(TAG, "handleState: enqueueing CallLogWorker")
                    val request = OneTimeWorkRequestBuilder<CallLogWorker>()
                        .setInitialDelay(3, TimeUnit.SECONDS)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                        .build()
                    WorkManager.getInstance(applicationContext).enqueue(request)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 42

        @Volatile private var running = false

        /** Whether the listener is actually registered right now - not just whether the service
         *  process is alive, which [startListening] can fail inside while the service survives. */
        fun isRunning() = running

        fun startIfNeeded(context: Context) {
            if (!SettingsStore.callsEnabled(context) || !SettingsStore.isConfigured(context)) return
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallMonitorService::class.java))
        }
    }
}
