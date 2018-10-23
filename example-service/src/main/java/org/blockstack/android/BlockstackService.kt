package org.blockstack.android

import android.app.IntentService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.util.Log
import org.blockstack.android.sdk.BlockstackSession
import org.blockstack.android.sdk.PutFileOptions


class BlockstackService : IntentService("BlockstackExample") {
    private val TAG: String = "BlockstackService"
    private val CHANNEL_ID = "progress"
    private lateinit var _blockstackSession: BlockstackSession
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        handler = Handler()
    }

    override fun onHandleIntent(intent: Intent?) {
        runOnUIThread {
            _blockstackSession = BlockstackSession(this, defaultConfig)
            putFileFromService()
        }
    }

    fun runOnUIThread(runnable: () -> Unit) {
        handler.post(runnable)
    }

    fun initNotifChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val notificationManager = this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID,
                "Progress",
                NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Progress messages for file operations"
        notificationManager.createNotificationChannel(channel)
    }

    private fun putFileFromService() {
        initNotifChannel()
        val signedIn = _blockstackSession.isUserSignedIn()
        if (signedIn) {
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(org.blockstack.android.sdk.R.drawable.org_blockstack_logo)
                    .setContentTitle("Blockstack Service")
                    .setContentText("Uploading file")
                    .setProgress(100, 50, true)
                    .build()
            NotificationManagerCompat.from(this).notify(0, notif)
            // make it take looong
            Thread.sleep(10000)
            _blockstackSession.putFile("fromService.txt", "Hello Android from Service", PutFileOptions()) { result ->
                Log.d(TAG, "File stored at: ${result.value}")
                val notif2 = NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(org.blockstack.android.sdk.R.drawable.org_blockstack_logo)
                        .setContentTitle("Blockstack Service")
                        .setContentText("File stored at: ${result.value}")
                        .build()

                NotificationManagerCompat.from(this).notify(0, notif2)
            }
        } else {
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Not logged In")
                    .build()
            NotificationManagerCompat.from(this).notify(0, notif)
        }

    }
}