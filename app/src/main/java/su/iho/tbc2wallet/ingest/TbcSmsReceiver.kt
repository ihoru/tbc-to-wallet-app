package su.iho.tbc2wallet.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TxSource

/**
 * Reads SMS from "TBC SMS" (or the senders set in Settings) as they arrive (needs the "SMS" permission), for when TBC is set to
 * send SMS instead of, or as well as, push notifications. SMS are read here rather than from the
 * SMS app's notifications: the broadcast carries the exact sender and the whole text, and it comes
 * whichever SMS app you use, even with its notifications muted or bundled into one conversation.
 */
class TbcSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        // A long SMS arrives in parts; join them per sender.
        val senders = Settings(context).smsSenders
        val messages = parts.filter { isTbcSender(it.displayOriginatingAddress, senders) }
            .groupBy { it.displayOriginatingAddress }
            .map { (sender, ps) -> Triple(sender, ps.joinToString("") { it.displayMessageBody.orEmpty() }, ps.first().timestampMillis) }
        if (messages.isEmpty()) return

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((sender, text, sentAt) in messages) {
                    // Only a time-less transaction uses the key: two equal SMS a second apart stay apart.
                    Ingest.handle(app, text, "sms:$sender", sentAt, sentAt, source = TxSource.SMS)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** One of [senders] ("TBC SMS" unless changed in Settings), ignoring case, spaces and punctuation. */
        fun isTbcSender(address: String?, senders: List<String> = listOf(Settings.DEFAULT_SMS_SENDER)): Boolean {
            val key = address?.let(::key)?.ifEmpty { null } ?: return false
            return senders.any { key(it) == key }
        }

        private fun key(s: String) = s.uppercase().filter { it.isLetterOrDigit() }
    }
}
