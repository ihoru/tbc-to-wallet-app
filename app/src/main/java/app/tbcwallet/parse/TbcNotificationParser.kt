package app.tbcwallet.parse

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class TxKind { EXPENSE, INCOME }

/**
 * One transaction extracted from a TBC push notification.
 * [amount] is always positive; the sign is applied from [kind] when sending to Wallet.
 */
data class ParsedTransaction(
    val kind: TxKind,
    val amount: BigDecimal,
    val currency: String,
    /** Merchant for expenses ("Shopsy"), sender for income ("John Smith"); null if absent. */
    val counterParty: String?,
    /** Account line as TBC shows it, e.g. "TBC CARD VISA GOLD (***1234)" or "TBC CARD". */
    val accountLabel: String?,
    val cardLast4: String?,
    val date: LocalDate,
    /** Null when the notification carries only a date (deposits). */
    val time: LocalTime?,
    /** Balance after the operation, when TBC includes it. Used for de-duplication. */
    val balanceAfter: BigDecimal?,
)

/**
 * Parses the English TBC notification formats seen so far:
 *
 * Card purchase:
 *   42.10 GEL
 *   TBC CARD VISA GOLD (***1234)
 *   Shopsy 29/09/26 23:28
 *   Balance: 1500.00 GEL
 *   [You've received: 0.05 GEL]                      <- Ertguli cashback, ignored
 *   [In Ertguli Piggy bank you have: 25.30 GEL]      <- ignored
 *
 * Incoming money:
 *   Deposit Money: 20.00 GEL
 *   TBC CARD
 *   29/09/2026
 *   [John Smith]
 *
 * Anything else returns null and is kept in the app as "unparsed" for review.
 */
object TbcNotificationParser {

    private const val AMOUNT = """([0-9][0-9,]*(?:\.\d{1,2})?)\s*([A-Z]{3})"""

    private val PURCHASE_AMOUNT = Regex("""^$AMOUNT$""")
    private val DEPOSIT = Regex("""^Deposit Money:\s*$AMOUNT$""", RegexOption.IGNORE_CASE)
    private val CARD = Regex("""^(.*?)\s*\(\*+(\d{4})\)$""")
    private val MERCHANT_DATETIME = Regex("""^(.*?)\s*(\d{2}/\d{2}/\d{2})\s+(\d{2}:\d{2})$""")
    private val DATE_LONG = Regex("""^(\d{2}/\d{2}/\d{4})$""")
    private val BALANCE = Regex("""^Balance:\s*$AMOUNT$""", RegexOption.IGNORE_CASE)
    private val CASHBACK = Regex("""^(You.ve received:|In Ertguli)""", RegexOption.IGNORE_CASE)

    private val SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val LONG_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun parse(text: String): ParsedTransaction? {
        val lines = text
            .replace(' ', ' ')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return runCatching { parseDeposit(lines) ?: parsePurchase(lines) }.getOrNull()
    }

    private fun parsePurchase(lines: List<String>): ParsedTransaction? {
        val amount = PURCHASE_AMOUNT.matchEntire(lines[0]) ?: return null
        var card: MatchResult? = null
        var merchantDate: MatchResult? = null
        var balance: MatchResult? = null
        for (line in lines.drop(1)) {
            if (CASHBACK.containsMatchIn(line)) break // everything after is loyalty info
            when {
                card == null && CARD.matchEntire(line) != null -> card = CARD.matchEntire(line)
                merchantDate == null && MERCHANT_DATETIME.matchEntire(line) != null ->
                    merchantDate = MERCHANT_DATETIME.matchEntire(line)
                balance == null && BALANCE.matchEntire(line) != null -> balance = BALANCE.matchEntire(line)
            }
        }
        val md = merchantDate ?: return null
        return ParsedTransaction(
            kind = TxKind.EXPENSE,
            amount = amount.groupValues[1].toMoney(),
            currency = amount.groupValues[2],
            counterParty = md.groupValues[1].ifBlank { null },
            accountLabel = card?.value,
            cardLast4 = card?.groupValues?.get(2),
            date = LocalDate.parse(md.groupValues[2], SHORT_DATE),
            time = LocalTime.parse(md.groupValues[3], TIME),
            balanceAfter = balance?.groupValues?.get(1)?.toMoney(),
        )
    }

    private fun parseDeposit(lines: List<String>): ParsedTransaction? {
        val amount = DEPOSIT.matchEntire(lines[0]) ?: return null
        val dateIndex = lines.indexOfFirst { DATE_LONG.matchEntire(it) != null }
        if (dateIndex < 0) return null
        val accountLabel = lines.subList(1, dateIndex).joinToString(" ").ifBlank { null }
        val sender = lines.drop(dateIndex + 1).joinToString(" ").ifBlank { null }
        return ParsedTransaction(
            kind = TxKind.INCOME,
            amount = amount.groupValues[1].toMoney(),
            currency = amount.groupValues[2],
            counterParty = sender,
            accountLabel = accountLabel,
            cardLast4 = accountLabel?.let { CARD.matchEntire(it)?.groupValues?.get(2) },
            date = LocalDate.parse(lines[dateIndex], LONG_DATE),
            time = null,
            balanceAfter = null,
        )
    }

    private fun String.toMoney(): BigDecimal = BigDecimal(replace(",", ""))
}
