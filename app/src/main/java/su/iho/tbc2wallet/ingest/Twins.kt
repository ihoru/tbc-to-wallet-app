package su.iho.tbc2wallet.ingest

import su.iho.tbc2wallet.data.TransactionEntity
import java.time.Duration

/**
 * TBC can tell you about one transaction twice: as a push from the TBC app and as an SMS from "TBC SMS".
 * The two texts differ (an incoming transfer is "Deposit Money:" in the push and "Received Amount:" in the SMS),
 * so they are matched by what both carry: kind, amount and currency (both sides for a conversion), the card
 * when both name one, and arriving close together. The first to arrive is kept; the second only marks it.
 * Each kept transaction pairs with one twin at most, so two equal coffees still make two records.
 */
object Twins {
    /** SMS can lag behind the push, or the other way round. */
    val WINDOW: Duration = Duration.ofMinutes(30)

    fun find(tx: TransactionEntity, candidates: List<TransactionEntity>): TransactionEntity? {
        if (tx.isFake || tx.kind == null) return null
        val amount = tx.amount?.toBigDecimalOrNull() ?: return null
        return candidates
            .filter { c ->
                !c.isFake && c.source != tx.source && c.alsoVia == null &&
                    c.kind == tx.kind && c.currency == tx.currency &&
                    c.amount?.toBigDecimalOrNull()?.compareTo(amount) == 0 &&
                    c.counterCurrency == tx.counterCurrency &&
                    sameAmount(c.counterAmount, tx.counterAmount) &&
                    (c.cardLast4 == null || tx.cardLast4 == null || c.cardLast4 == tx.cardLast4) &&
                    Math.abs(c.receivedAt - tx.receivedAt) <= WINDOW.toMillis()
            }
            .minByOrNull { Math.abs(it.receivedAt - tx.receivedAt) }
    }

    private fun sameAmount(a: String?, b: String?): Boolean =
        if (a == null || b == null) a == b else a.toBigDecimalOrNull()?.compareTo(b.toBigDecimalOrNull()) == 0
}
