package su.iho.tbc2wallet.ingest

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.parse.ParsedTransaction
import su.iho.tbc2wallet.parse.TbcNotificationParser
import su.iho.tbc2wallet.parse.TxKind
import su.iho.tbc2wallet.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Receives every notification on the phone (after you grant "Notification access")
 * and keeps only the ones posted by the TBC app.
 */
class TbcNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extractText(extras) ?: return

        val settings = Settings(this)
        val configured = settings.tbcPackage
        val isTbc = if (configured.isNotBlank()) {
            sbn.packageName == configured
        } else {
            // Not configured yet: learn the package from the first parsable "TBC" notification.
            (title.equals("TBC", true) || title.startsWith("TBC ", true)) &&
                TbcNotificationParser.parse(text) != null
        }
        if (!isTbc) return
        if (configured.isBlank()) settings.tbcPackage = sbn.packageName

        scope.launch {
            Ingest.handle(applicationContext, text, sbn.key, n.`when`, sbn.postTime)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun extractText(extras: Bundle): String? {
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeIf { it.isNotEmpty() }
            ?.let { return it.joinToString("\n") }
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
    }
}

object Ingest {
    /** TBC shows Georgian local time regardless of where the phone is. */
    val BANK_ZONE: ZoneId = ZoneId.of("Asia/Tbilisi")

    suspend fun handle(context: Context, text: String, notificationKey: String, whenMillis: Long, postTime: Long) {
        val dao = AppDatabase.get(context).dao()
        val parsed = TbcNotificationParser.parse(text)

        val entity = if (parsed == null) {
            TransactionEntity(
                dedupKey = sha("UNPARSED|$text|$notificationKey|$whenMillis"),
                kind = null, amount = null, currency = null, counterParty = null,
                accountLabel = null, cardLast4 = null, recordDateUtc = null,
                rawText = text, status = TxStatus.UNPARSED, receivedAt = System.currentTimeMillis(),
            )
        } else {
            val settings = Settings(context)
            settings.seenCurrencies = settings.seenCurrencies + parsed.currency
            TransactionEntity(
                dedupKey = dedupKey(parsed, notificationKey, whenMillis),
                kind = parsed.kind.name,
                amount = parsed.amount.toPlainString(),
                currency = parsed.currency,
                counterParty = parsed.counterParty,
                accountLabel = parsed.accountLabel,
                cardLast4 = parsed.cardLast4,
                recordDateUtc = recordInstant(parsed, postTime).toString(),
                rawText = text,
                status = TxStatus.PENDING,
                receivedAt = System.currentTimeMillis(),
            )
        }

        val inserted = dao.insertIfNew(entity) != -1L
        Log.d("TbcIngest", "status=${entity.status} new=$inserted")
        if (inserted && entity.status == TxStatus.PENDING) SyncWorker.enqueue(context)
        if (inserted && entity.status == TxStatus.UNPARSED) Notifier.unparsed(context)
    }

    /**
     * Purchases include time and balance-after, so identical content means the same
     * purchase even if Android re-posts the notification. Deposits only carry a date,
     * so the notification identity is added to keep two equal deposits apart.
     */
    private fun dedupKey(p: ParsedTransaction, notificationKey: String, whenMillis: Long): String {
        val base = listOf(p.kind, p.amount.toPlainString(), p.currency, p.counterParty, p.cardLast4, p.date, p.time, p.balanceAfter)
            .joinToString("|")
        return sha(if (p.kind == TxKind.EXPENSE) base else "$base|$notificationKey|$whenMillis")
    }

    private fun recordInstant(p: ParsedTransaction, postTime: Long): Instant {
        p.time?.let { return p.date.atTime(it).atZone(BANK_ZONE).toInstant() }
        // Date-only: use the moment the notification arrived if it's the same day, else noon.
        val posted = Instant.ofEpochMilli(postTime).atZone(BANK_ZONE)
        return if (posted.toLocalDate() == p.date) posted.withNano(0).toInstant()
        else p.date.atTime(LocalTime.NOON).atZone(BANK_ZONE).toInstant()
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
