package su.iho.tbc2wallet.ingest

import su.iho.tbc2wallet.data.TransactionEntity
import java.time.Duration
import java.time.Instant

/**
 * TBC sends no id linking a reversal to the charge it cancels, so the link is by content: the same
 * amount, currency and card, the same merchant when both name one, and a charge from shortly before.
 * The latest such charge wins. With no match nothing is removed; the reversal waits for you instead.
 */
object Reversals {
    /** How far back a charge may be; card checks are released within the hour, give or take. */
    val WINDOW: Duration = Duration.ofHours(2)
    /** A charge timed a little after the reversal still counts (minutes are rounded, clocks drift). */
    private val SLACK: Duration = Duration.ofMinutes(5)

    fun findCharge(reversal: TransactionEntity, candidates: List<TransactionEntity>): TransactionEntity? {
        val at = reversal.recordDateUtc?.let(Instant::parse) ?: return null
        val amount = reversal.amount?.toBigDecimalOrNull() ?: return null
        // Removing a charge can't be undone, so a reversal that doesn't name its card matches nothing.
        if (reversal.cardLast4 == null) return null
        return candidates
            .filter { c ->
                c.kind == "EXPENSE" && c.currency == reversal.currency && c.isFake == reversal.isFake &&
                    c.amount?.toBigDecimalOrNull()?.compareTo(amount) == 0 &&
                    c.cardLast4 == reversal.cardLast4 &&
                    sameMerchant(reversal.counterParty, c.counterParty) &&
                    c.recordDateUtc?.let(Instant::parse)?.let { it >= at - WINDOW && it <= at + SLACK } == true
            }
            .maxWithOrNull(compareBy<TransactionEntity> { Instant.parse(it.recordDateUtc) }.thenBy { it.receivedAt })
    }

    private fun sameMerchant(a: String?, b: String?): Boolean =
        a == null || b == null || key(a) == key(b)

    private fun key(s: String) = s.uppercase().filter { it.isLetterOrDigit() }
}
