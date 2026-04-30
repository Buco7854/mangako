package com.mangako.app.work.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mangako.app.MainActivity
import com.mangako.app.R

/**
 * Central place for notification construction. Channel ids live here so the
 * receiver and worker stay in sync.
 */
object Notifications {

    const val CHANNEL_DETECTED = "mangako_detected"
    const val CHANNEL_PROGRESS = "mangako_progress"
    const val CHANNEL_INBOX = "mangako_inbox"

    const val ACTION_APPROVE = "com.mangako.app.ACTION_APPROVE"
    const val ACTION_REJECT = "com.mangako.app.ACTION_REJECT"
    const val EXTRA_PENDING_ID = "pending_id"

    // Fixed id so re-posting updates-in-place rather than stacking.
    private const val INBOX_SUMMARY_ID = 0xBA5E

    /**
     * True on API <33 (permission implicit) OR when POST_NOTIFICATIONS has been
     * granted at runtime. Lint insists we check before every `notify()` call —
     * runCatching isn't enough for it even though SecurityException is handled.
     */
    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED


    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(
                CHANNEL_DETECTED,
                context.getString(R.string.notif_channel_detected),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notif_channel_detected_desc) },
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.notif_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ),
            NotificationChannel(
                CHANNEL_INBOX,
                context.getString(R.string.notif_channel_inbox),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_inbox_desc) },
        ).forEach(nm::createNotificationChannel)
    }

    // Lint can't see through canPost(); we keep the runtime check + try/catch
    // belt-and-suspenders, so suppressing the static warning is correct.
    @SuppressLint("MissingPermission")
    fun postDetected(context: Context, pendingId: String, filename: String, folder: String) {
        ensureChannels(context)
        val nm = NotificationManagerCompat.from(context)

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val approveIntent = pendingBroadcast(context, ACTION_APPROVE, pendingId, code = pendingId.hashCode())
        val rejectIntent = pendingBroadcast(context, ACTION_REJECT, pendingId, code = pendingId.hashCode() xor 0x1)

        val notif = NotificationCompat.Builder(context, CHANNEL_DETECTED)
            .setSmallIcon(R.drawable.ic_mangako_stat)
            .setContentTitle(context.getString(R.string.notif_detected_title, filename))
            .setContentText(folder)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notif_detected_body, filename, folder),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_mangako_stat, context.getString(R.string.notif_detected_action_process), approveIntent)
            .addAction(R.drawable.ic_mangako_stat, context.getString(R.string.notif_detected_action_ignore), rejectIntent)
            .build()

        if (canPost(context)) runCatching { nm.notify(pendingId.hashCode(), notif) }
    }

    fun cancelDetected(context: Context, pendingId: String) {
        NotificationManagerCompat.from(context).cancel(pendingId.hashCode())
    }

    /**
     * A single low-priority "N files awaiting review" notification the user
     * can tap to jump straight to the Inbox. Posted when per-file notifications
     * are disabled but there's still pending work — otherwise the user has no
     * cue besides the badge on the app icon.
     */
    @SuppressLint("MissingPermission")
    fun postInboxSummary(context: Context, pendingCount: Int) {
        if (pendingCount <= 0) {
            cancelInboxSummary(context)
            return
        }
        ensureChannels(context)
        val openInbox = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (pendingCount == 1) {
            context.getString(R.string.notif_inbox_summary_one)
        } else {
            context.getString(R.string.notif_inbox_summary_other, pendingCount)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_INBOX)
            .setSmallIcon(R.drawable.ic_mangako_stat)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_inbox_summary_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openInbox)
            .build()
        if (canPost(context)) runCatching { NotificationManagerCompat.from(context).notify(INBOX_SUMMARY_ID, notif) }
    }

    fun cancelInboxSummary(context: Context) {
        NotificationManagerCompat.from(context).cancel(INBOX_SUMMARY_ID)
    }

    private fun pendingBroadcast(context: Context, action: String, pendingId: String, code: Int): PendingIntent {
        val intent = Intent(context, PendingActionReceiver::class.java).apply {
            setAction(action)
            `package` = context.packageName
            putExtra(EXTRA_PENDING_ID, pendingId)
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
