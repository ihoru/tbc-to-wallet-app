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
import su.iho.tbc2wallet.data.TxSource
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
            // The SMS app's notification for "TBC SMS" looks alike; SMS are read directly by TbcSmsReceiver.
            (title.equals("TBC", true) || title.startsWith("TBC ", true)) &&
                sbn.packageName != android.provider.Telephony.Sms.getDefaultSmsPackage(this) &&
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

    /** One notification at a time, so a reversal never looks for its charge before that charge is saved. */
    private val lock = Mutex()

    /**
     * [fake] marks text typed in from the Activity tab. It goes through the same parser, rules
     * and upload, but every submission is kept (never deduplicated) and tagged "fake" in Wallet.
     */
    suspend fun handle(
        context: Context, text: String, notificationKey: String, whenMillis: Long, postTime: Long,
        fake: Boolean = false, source: String = TxSource.PUSH,
    ): Unit = lock.withLock {
        if (!fake && TbcNotificationParser.isNoise(text)) return@withLock   // codes and security alerts
        val dao = AppDatabase.get(context).dao()
        val parsed = TbcNotificationParser.parse(text)

        val entity = if (parsed == null) {
            TransactionEntity(
                dedupKey = sha("UNPARSED|$text|$notificationKey|$whenMillis"),
                kind = null, amount = null, currency = null, counterParty = null,
                accountLabel = null, cardLast4 = null, recordDateUtc = null,
                rawText = text, status = TxStatus.UNPARSED, receivedAt = System.currentTimeMillis(),
                isFake = fake, source = source,
            )
        } else {
            val settings = Settings(context)
            settings.seenCurrencies = settings.seenCurrencies + listOfNotNull(parsed.currency, parsed.counterCurrency)
            TransactionEntity(
                dedupKey = if (fake) sha("FAKE|$text|$notificationKey|$whenMillis") else dedupKey(parsed, notificationKey, whenMillis),
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
                isFake = fake,
                counterAmount = parsed.counterAmount?.toPlainString(),
                counterCurrency = parsed.counterCurrency,
                source = source,
            )
        }

        // The same transaction already came in by the other source (push vs SMS): keep that one only.
        val existing = dao.tx(entity.dedupKey)
        if (existing != null && existing.source != source && existing.alsoVia == null && !fake) {
            // Identical text both ways (e.g. a purchase): same key, so just mark the pair.
            dao.setAlsoVia(existing.dedupKey, source)
        }
        if (entity.kind != null && existing == null) {
            val window = Twins.WINDOW.toMillis()
            val other = if (source == TxSource.SMS) TxSource.PUSH else TxSource.SMS
            val twin = Twins.find(entity, dao.twinCandidates(other, entity.receivedAt - window, entity.receivedAt + window))
            if (twin != null) {
                dao.setAlsoVia(twin.dedupKey, source)
                // Keep its key, hidden, so a re-post of this copy is ignored too; alsoVia keeps it out of pairing.
                dao.insertIfNew(entity.copy(status = TxStatus.DELETED, alsoVia = twin.source))
                Log.d("TbcIngest", "duplicate of ${twin.dedupKey} via $source, dropped")
                return@withLock
            }
        }

        if (entity.kind == TxKind.REVERSAL.name) return@withLock reverse(context, entity)

        val inserted = dao.insertIfNew(entity) != -1L
        Log.d("TbcIngest", "status=${entity.status} new=$inserted")
        if (inserted && entity.status == TxStatus.PENDING) SyncWorker.enqueue(context)
        if (inserted && entity.status == TxStatus.UNPARSED && !fake) Notifier.unparsed(context)
    }

    /**
     * Links a reversal to the charge it cancels; the worker then removes that charge from Wallet and the list.
     * A re-posted reversal is ignored like a re-posted purchase.
     */
    private suspend fun reverse(context: Context, reversal: TransactionEntity) {
        val dao = AppDatabase.get(context).dao()
        if (dao.tx(reversal.dedupKey) != null) return
        val charge = Reversals.findCharge(reversal, dao.reversalCandidates(reversal.currency!!, reversal.isFake))
        val entity = if (charge == null) reversal.copy(status = TxStatus.NO_MATCH)
            else reversal.copy(status = TxStatus.REVERSING, reversesKey = charge.dedupKey)
        val inserted = dao.insertIfNew(entity) != -1L
        Log.d("TbcIngest", "reversal status=${entity.status} new=$inserted")
        if (inserted && charge != null) SyncWorker.enqueue(context)
        if (inserted && charge == null && !reversal.isFake) Notifier.reversalUnmatched(context)
    }

    /**
     * Purchases and reversals include time and balance-after, so identical content means the same
     * one even if Android re-posts the notification. Deposits, money transfers and conversions only carry a date,
     * so the notification identity is added to keep two equal ones apart.
     */
    private fun dedupKey(p: ParsedTransaction, notificationKey: String, whenMillis: Long): String {
        val base = listOf(p.kind, p.amount.toPlainString(), p.currency, p.counterParty, p.cardLast4, p.date, p.time, p.balanceAfter)
            .joinToString("|") + (p.counterAmount?.let { "|${it.toPlainString()}|${p.counterCurrency}" } ?: "")
        return sha(if (p.time != null) base else "$base|$notificationKey|$whenMillis")
    }

    /**
     * Never later than [now]: Wallet rejects records more than a day ahead, and a bank's own date
     * can run ahead (e.g. a weekend transfer dated to the next business day).
     */
    internal fun recordInstant(p: ParsedTransaction, postTime: Long, now: Instant = Instant.now()): Instant {
        val parsed = p.time?.let { p.date.atTime(it).atZone(BANK_ZONE).toInstant() } ?: run {
            // Date-only: use the moment the notification arrived if it's the same day, else noon.
            val posted = Instant.ofEpochMilli(postTime).atZone(BANK_ZONE)
            if (posted.toLocalDate() == p.date) posted.withNano(0).toInstant()
            else p.date.atTime(LocalTime.NOON).atZone(BANK_ZONE).toInstant()
        }
        return notAfter(parsed, now)
    }

    /** [instant], or [now] (to the second) when it is in the future. */
    fun notAfter(instant: Instant, now: Instant = Instant.now()): Instant =
        if (instant.isAfter(now)) now.truncatedTo(ChronoUnit.SECONDS) else instant

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
