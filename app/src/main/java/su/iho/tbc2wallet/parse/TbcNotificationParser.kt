package su.iho.tbc2wallet.parse

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * REVERSAL: TBC released a charge it had made, often a card check; the app removes that charge.
 * CONVERSION: money exchanged between your own TBC accounts in two currencies; sent as a transfer.
 */
enum class TxKind { EXPENSE, INCOME, REVERSAL, CONVERSION }

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
    /** Account line as TBC shows it, e.g. "TBC CARD VISA GOLD (***1234)" or "TBC CARD"; for conversions, the rate line. */
    val accountLabel: String?,
    val cardLast4: String?,
    val date: LocalDate,
    /** Null when the notification carries only a date (deposits). */
    val time: LocalTime?,
    /** Balance after the operation, when TBC includes it. Used for de-duplication. */
    val balanceAfter: BigDecimal?,
    /** Conversions only: what arrived on the other side, while [amount] and [currency] are what left. */
    val counterAmount: BigDecimal? = null,
    val counterCurrency: String? = null,
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
 * Outgoing money transfer (amount may also sit on the header line):
 *   Money Transfer:
 *   800.00 USD
 *   TBC CARD
 *   21/10/2026
 *   [Recipient]
 *
 * Reversal (a charge cancelled, usually a card check shortly after it):
 *   Reversal:
 *   4.00 GEL
 *   TBC CARD VISA GOLD (***1234)
 *   FLT*Jetshr
 *   16/10/26 00:56
 *   Balance: 4588.05 GEL
 *
 * Currency conversion between your own accounts (no account line, date only).
 * The first amount is what left, the second what arrived (here 104.36 GEL bought 40.00 USD):
 *   Conversion:
 *   104.36 GEL
 *   40.00 USD
 *   Rate: 2.609
 *   30/10/2026
 *
 * The same transactions can also arrive as SMS from "TBC SMS". Purchases and conversions read like the
 * push; incoming money and cash deposits have SMS-only headers:
 *   Received Amount: 60.00 GEL
 *   GE61TB****0007
 *   2026/11/12 11:24:26
 *   Description: personal transfer
 *
 *   Cash Deposit: 500.00 USD
 *   TBC CARD
 *   12/09/2026
 *
 * Mobile top-up (the amount may have no space before the currency):
 *   Mobile Balance Recharge
 *   35.00GEL
 *   Account refill (magti)
 *   ID:591006815
 *   12/11/2026
 *
 * Login codes, payment confirmation codes and new-device alerts are [isNoise] and never stored.
 * Anything else returns null and is kept in the app as "unparsed" for review.
 */
object TbcNotificationParser {

    private const val AMOUNT = """([0-9][0-9,]*(?:\.\d{1,2})?)\s*([A-Z]{3})"""

    private val PURCHASE_AMOUNT = Regex("""^$AMOUNT$""")
    private val DEPOSIT = Regex("""^Deposit Money:\s*(?:$AMOUNT)?$""", RegexOption.IGNORE_CASE)
    private val TRANSFER = Regex("""^Money Transfer:\s*(?:$AMOUNT)?$""", RegexOption.IGNORE_CASE)
    private val CARD = Regex("""^(.*?)\s*\(\*+(\d{4})\)$""")
    private val MERCHANT_DATETIME = Regex("""^(.*?)\s*(\d{2}/\d{2}/\d{2})\s+(\d{2}:\d{2})$""")
    private val DATE_LONG = Regex("""^(\d{2}/\d{2}/\d{4})$""")
    private val BALANCE = Regex("""^Balance:\s*$AMOUNT$""", RegexOption.IGNORE_CASE)
    private val REVERSAL = Regex("""^Reversal:\s*(?:$AMOUNT)?$""", RegexOption.IGNORE_CASE)
    private val CONVERSION = Regex("""^Conversion:\s*(?:$AMOUNT)?$""", RegexOption.IGNORE_CASE)
    private val RATE = Regex("""^Rate:\s*[0-9.,]+$""", RegexOption.IGNORE_CASE)
    private val DATETIME = Regex("""^(\d{2}/\d{2}/\d{2})\s+(\d{2}:\d{2})$""")
    private val CASH_DEPOSIT = Regex("""^Cash Deposit:\s*(?:$AMOUNT)?$""", RegexOption.IGNORE_CASE)
    private val RECEIVED = Regex("""^Received Amount:\s*(?:$AMOUNT)?$""", RegexOption.IGNORE_CASE)
    private val ISO_DATETIME = Regex("""^(\d{4}/\d{2}/\d{2})\s+(\d{2}:\d{2}(?::\d{2})?)$""")
    private val DESCRIPTION = Regex("""^Description:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val RECHARGE = Regex("""^Mobile Balance Recharge:?\s*(?:$AMOUNT)?$""", RegexOption.IGNORE_CASE)
    private val ID_LINE = Regex("""^ID:\s*\S+$""", RegexOption.IGNORE_CASE)
    private val NOISE = listOf(
        Regex("""^Sms Code:""", RegexOption.IGNORE_CASE),           // login code
        Regex("""^Code:\s*\d+$""", RegexOption.IGNORE_CASE),        // "Code: 288677 / 401.00 GEL payment ***5190 at ..."
        Regex("""^A new device or browser""", RegexOption.IGNORE_CASE),
    )
    private val CASHBACK = Regex("""^(You.ve received:|In Ertguli)""", RegexOption.IGNORE_CASE)

    private val SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val LONG_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val ISO_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    /** TBC messages that are not transactions: one-time codes (a payment code comes before the payment itself) and security alerts. */
    fun isNoise(text: String): Boolean {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        return NOISE.any { it.containsMatchIn(first) }
    }

    fun parse(text: String): ParsedTransaction? {
        val lines = text
            .replace(' ', ' ')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return runCatching { parseReversal(lines) ?: parseConversion(lines) ?: parseDateOnly(lines, DEPOSIT, TxKind.INCOME) ?: parseDateOnly(lines, TRANSFER, TxKind.EXPENSE)
            ?: parseDateOnly(lines, CASH_DEPOSIT, TxKind.INCOME, defaultCounterParty = "Cash Deposit")
            ?: parseReceived(lines) ?: parseRecharge(lines) ?: parsePurchase(lines) }.getOrNull()
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

    /** The merchant may sit on its own line or share one with the date, as in purchases. */
    private fun parseReversal(lines: List<String>): ParsedTransaction? {
        val head = REVERSAL.matchEntire(lines[0]) ?: return null
        var rest = lines.drop(1)
        val amount = if (head.groupValues[1].isNotEmpty()) head
            else PURCHASE_AMOUNT.matchEntire(rest.firstOrNull() ?: return null)?.also { rest = rest.drop(1) } ?: return null
        var card: MatchResult? = null
        var merchant: String? = null
        var date: String? = null
        var time: String? = null
        var balance: MatchResult? = null
        for (line in rest) {
            if (CASHBACK.containsMatchIn(line)) break
            val dt = DATETIME.matchEntire(line)
            val mdt = MERCHANT_DATETIME.matchEntire(line)
            when {
                card == null && CARD.matchEntire(line) != null -> card = CARD.matchEntire(line)
                balance == null && BALANCE.matchEntire(line) != null -> balance = BALANCE.matchEntire(line)
                date == null && dt != null -> { date = dt.groupValues[1]; time = dt.groupValues[2] }
                date == null && mdt != null -> {
                    if (merchant == null) merchant = mdt.groupValues[1].ifBlank { null }
                    date = mdt.groupValues[2]; time = mdt.groupValues[3]
                }
                merchant == null && date == null -> merchant = line
            }
        }
        return ParsedTransaction(
            kind = TxKind.REVERSAL,
            amount = amount.groupValues[1].toMoney(),
            currency = amount.groupValues[2],
            counterParty = merchant,
            accountLabel = card?.value,
            cardLast4 = card?.groupValues?.get(2),
            date = LocalDate.parse(date ?: return null, SHORT_DATE),
            time = LocalTime.parse(time, TIME),
            balanceAfter = balance?.groupValues?.get(1)?.toMoney(),
        )
    }

    private fun parseConversion(lines: List<String>): ParsedTransaction? {
        val head = CONVERSION.matchEntire(lines[0]) ?: return null
        val amounts = (listOfNotNull(head.takeIf { it.groupValues[1].isNotEmpty() }) +
            lines.drop(1).mapNotNull { PURCHASE_AMOUNT.matchEntire(it) }).take(2)
        if (amounts.size < 2) return null
        val (paid, received) = amounts
        if (received.groupValues[2] == paid.groupValues[2]) return null
        val date = lines.firstOrNull { DATE_LONG.matchEntire(it) != null } ?: return null
        val rate = lines.firstOrNull { RATE.matchEntire(it) != null }
        return ParsedTransaction(
            kind = TxKind.CONVERSION,
            amount = paid.groupValues[1].toMoney(),
            currency = paid.groupValues[2],
            counterParty = null,
            accountLabel = rate,
            cardLast4 = null,
            date = LocalDate.parse(date, LONG_DATE),
            time = null,
            balanceAfter = null,
            counterAmount = received.groupValues[1].toMoney(),
            counterCurrency = received.groupValues[2],
        )
    }

    /**
     * Deposits and money transfers: a header with the amount on it or on the next line,
     * the account, a dd/MM/yyyy date, then an optional sender or recipient.
     */
    private fun parseDateOnly(lines: List<String>, header: Regex, kind: TxKind, defaultCounterParty: String? = null): ParsedTransaction? {
        val head = header.matchEntire(lines[0]) ?: return null
        var bodyStart = 1
        val amount = if (head.groupValues[1].isNotEmpty()) head
            else PURCHASE_AMOUNT.matchEntire(lines.getOrNull(1) ?: return null)?.also { bodyStart = 2 } ?: return null
        val dateIndex = lines.indexOfFirst { DATE_LONG.matchEntire(it) != null }
        if (dateIndex < bodyStart) return null
        val accountLabel = lines.subList(bodyStart, dateIndex).joinToString(" ").ifBlank { null }
        val counterParty = lines.drop(dateIndex + 1).joinToString(" ").ifBlank { defaultCounterParty }
        return ParsedTransaction(
            kind = kind,
            amount = amount.groupValues[1].toMoney(),
            currency = amount.groupValues[2],
            counterParty = counterParty,
            accountLabel = accountLabel,
            cardLast4 = accountLabel?.let { CARD.matchEntire(it)?.groupValues?.get(2) },
            date = LocalDate.parse(lines[dateIndex], LONG_DATE),
            time = null,
            balanceAfter = null,
        )
    }

    /** SMS "Received Amount:": account (masked IBAN), yyyy/MM/dd HH:mm:ss, then "Description:" naming the sender or purpose. */
    private fun parseReceived(lines: List<String>): ParsedTransaction? {
        val head = RECEIVED.matchEntire(lines[0]) ?: return null
        var bodyStart = 1
        val amount = if (head.groupValues[1].isNotEmpty()) head
            else PURCHASE_AMOUNT.matchEntire(lines.getOrNull(1) ?: return null)?.also { bodyStart = 2 } ?: return null
        val dateIndex = lines.indexOfFirst { ISO_DATETIME.matchEntire(it) != null }
        if (dateIndex < bodyStart) return null
        val dt = ISO_DATETIME.matchEntire(lines[dateIndex])!!
        val accountLabel = lines.subList(bodyStart, dateIndex).joinToString(" ").ifBlank { null }
        val counterParty = lines.drop(dateIndex + 1)
            .joinToString(" ") { DESCRIPTION.matchEntire(it)?.groupValues?.get(1) ?: it }.trim().ifBlank { null }
        val time = dt.groupValues[2]
        return ParsedTransaction(
            kind = TxKind.INCOME,
            amount = amount.groupValues[1].toMoney(),
            currency = amount.groupValues[2],
            counterParty = counterParty,
            accountLabel = accountLabel,
            cardLast4 = null,
            date = LocalDate.parse(dt.groupValues[1], ISO_DATE),
            time = LocalTime.parse(if (time.length == 5) "$time:00" else time),
            balanceAfter = null,
        )
    }

    /** "Mobile Balance Recharge": the operator line ("Account refill (magti)") is the payee, the phone "ID:" the account. */
    private fun parseRecharge(lines: List<String>): ParsedTransaction? {
        val head = RECHARGE.matchEntire(lines[0]) ?: return null
        var bodyStart = 1
        val amount = if (head.groupValues[1].isNotEmpty()) head
            else PURCHASE_AMOUNT.matchEntire(lines.getOrNull(1) ?: return null)?.also { bodyStart = 2 } ?: return null
        val dateIndex = lines.indexOfFirst { DATE_LONG.matchEntire(it) != null }
        if (dateIndex < bodyStart) return null
        val (ids, details) = lines.subList(bodyStart, dateIndex).partition { ID_LINE.matchEntire(it) != null }
        return ParsedTransaction(
            kind = TxKind.EXPENSE,
            amount = amount.groupValues[1].toMoney(),
            currency = amount.groupValues[2],
            counterParty = details.joinToString(" ").ifBlank { "Mobile Balance Recharge" },
            accountLabel = ids.joinToString(" ").ifBlank { null },
            cardLast4 = null,
            date = LocalDate.parse(lines[dateIndex], LONG_DATE),
            time = null,
            balanceAfter = null,
        )
    }

    private fun String.toMoney(): BigDecimal = BigDecimal(replace(",", ""))
}
