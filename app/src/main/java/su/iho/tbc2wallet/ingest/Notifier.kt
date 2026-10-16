package su.iho.tbc2wallet.ingest

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
import su.iho.tbc2wallet.MainActivity
import su.iho.tbc2wallet.R

/** Tells you when something needs attention; successful syncs stay silent. */
object Notifier {
    private const val CHANNEL = "attention"

    fun unparsed(context: Context) =
        show(context, 1, "New TBC notification format", "Couldn't read a TBC notification. Open the app to review it.")

    fun needsAccount(context: Context, currencies: Collection<String>) =
        show(context, 2, "Choose a Wallet account", "No Wallet account set for ${currencies.joinToString()}. Open Settings to map it.")

    fun failed(context: Context, count: Int, reason: String) =
        show(context, 3, "$count transaction(s) not saved to Wallet", reason)

    fun reversalUnmatched(context: Context) =
        show(context, 4, "TBC cancelled a charge", "No recent matching charge was found, so nothing was removed. Open the app to check.")

    @SuppressLint("MissingPermission") // checked just below
    private fun show(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Needs attention", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
