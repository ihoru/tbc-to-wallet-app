package su.iho.tbc2wallet.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the Wallet by BudgetBakers REST API.
 * Docs: https://rest.budgetbakers.com/wallet/reference
 * Limits: ~300 requests/hour, POST /records takes up to 20 records per call.
 */
class WalletApi(private val token: String) {

    sealed class ApiException(message: String) : IOException(message) {
        class Unauthorized : ApiException("Wallet rejected the API token (401)")
        class RateLimited(val retryAfterSeconds: Long) : ApiException("Rate limited, retry in ${retryAfterSeconds}s")
        class InitialSync(val retryAfterMinutes: Long) : ApiException("Wallet is still doing its first sync")
        class Http(val code: Int, body: String) : ApiException("HTTP $code: ${body.take(300)}")
    }

    // ---------- DTOs (only the fields this app uses)

    @Serializable data class Account(
        val id: String, val name: String = "", val currencyCode: String = "",
        val archived: Boolean = false, val isBankSync: Boolean = false,
    )
    @Serializable data class Category(
        val id: String, val name: String = "", val parentName: String? = null,
        val archived: Boolean = false, val enabled: Boolean = true,
        /** Set for custom subcategories only: the base category they sit under. */
        val parentId: String? = null,
        /** Root-level group, e.g. food_and_drinks / "Food & Drinks". */
        val group: CategoryGroup? = null,
        /** Hex, e.g. "#26c6da". */
        val color: String? = null,
        /** Set for system categories, e.g. food_and_drinks__groceries. */
        val systemId: String? = null,
    )
    @Serializable data class CategoryGroup(val id: String? = null, val name: String? = null)
    @Serializable data class CategoryEmbed(val id: String? = null, val name: String? = null)
    @Serializable data class RecordRule(
        val id: String, val name: String = "", val keywords: List<String> = emptyList(),
        val category: CategoryEmbed? = null, val fromAccountId: String? = null,
    )
    @Serializable data class Record(
        val id: String, val counterParty: String? = null, val note: String? = null,
        val category: CategoryEmbed? = null, val source: String? = null,
        val accountId: String? = null, val amount: Amount? = null,
        /** ISO-8601, e.g. 2026-09-29T19:28:00Z. */
        val recordDate: String? = null,
        val labels: List<LabelEmbed> = emptyList(),
        /** Null for regular records. */
        val transfer: TransferEmbed? = null,
    )
    @Serializable data class LabelEmbed(val id: String? = null, val name: String? = null)
    @Serializable data class TransferEmbed(val transferId: String? = null, val mirrorRecord: MirrorRecord? = null)
    @Serializable data class MirrorRecord(val id: String? = null)

    @Serializable data class Label(val id: String, val name: String = "", val archived: Boolean = false)

    @Serializable private data class AccountsPage(val accounts: List<Account> = emptyList(), val nextOffset: Int? = null)
    @Serializable private data class CategoriesPage(val categories: List<Category> = emptyList(), val nextOffset: Int? = null)
    @Serializable private data class RulesPage(val recordRules: List<RecordRule> = emptyList(), val nextOffset: Int? = null)
    @Serializable private data class LabelsPage(val labels: List<Label> = emptyList(), val nextOffset: Int? = null)
    @Serializable private data class RecordsPage(val records: List<Record> = emptyList(), val nextOffset: Int? = null)

    @Serializable data class Amount(val value: Double, val currencyCode: String? = null)
    @Serializable data class NewRecord(
        val accountId: String,
        val amount: Amount,          // negative = expense, positive = income
        val recordDate: String,      // ISO-8601, e.g. 2026-09-29T19:28:00Z
        val categoryId: String? = null,
        val counterParty: String? = null,
        val note: String? = null,
        val labelIds: List<String>? = null,
        val transfer: Transfer? = null,
    )
    /**
     * Makes the record one side of a transfer with [accountId]. pairingMode "new" has Wallet create
     * the mirror record there, converting the amount by FX when the currencies differ.
     */
    @Serializable data class Transfer(val accountId: String, val pairingMode: String)
    @Serializable data class WriteResult(
        val inputIndex: Int, val id: String? = null, val success: Boolean = false,
        val error: String? = null, val errorType: String? = null,
    )
    @Serializable private data class WriteResponse(val results: List<WriteResult> = emptyList())
    @Serializable private data class DeleteRequest(val ids: List<String>)

    /** Changes to one existing record (`PATCH /v1/api/records`); null fields stay as they are. */
    @Serializable data class RecordPatch(
        val id: String,
        val categoryId: String? = null,
        /** Added to the record's labels; the ones it already has (e.g. "fake") stay. */
        @SerialName("labelIds") val addLabels: LabelChange? = null,
    )
    @Serializable data class LabelChange(val add: List<String>)

    // ---------- Endpoints

    suspend fun accounts(): List<Account> = paged { offset ->
        get<AccountsPage>("accounts", "limit" to "200", "offset" to "$offset").let { it.accounts to it.nextOffset }
    }

    suspend fun categories(): List<Category> = paged { offset ->
        get<CategoriesPage>("categories", "limit" to "200", "offset" to "$offset").let { it.categories to it.nextOffset }
    }

    suspend fun recordRules(): List<RecordRule> = paged { offset ->
        get<RulesPage>("record-rules", "limit" to "200", "offset" to "$offset").let { it.recordRules to it.nextOffset }
    }

    suspend fun labels(): List<Label> = paged { offset ->
        get<LabelsPage>("labels", "limit" to "200", "offset" to "$offset").let { it.labels to it.nextOffset }
    }

    /**
     * Creates a label (`POST /v1/api/labels` with one object) and returns its id.
     */
    suspend fun createLabel(name: String): String? {
        val body = json.encodeToString(mapOf("name" to name)).toRequestBody(JSON)
        val request = Request.Builder().url(url("labels")).post(body).auth().build()
        return execute(request, acceptedCodes = setOf(200, 201, 207)) { text ->
            val root = json.parseToJsonElement(text)
            fun JsonElement.id(): String? = (this as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            // The spec documents {"label": {"id": ...}}; also accept the batch envelope other writes use.
            val obj = root as? JsonObject
            obj?.get("label")?.id()
                ?: obj?.get("results")?.jsonArray?.firstOrNull()?.id()
                ?: root.id()
        }
    }

    /** Records on or after [fromDate] (YYYY-MM-DD). [categoryId] may be "unknown". */
    suspend fun records(fromDate: String, categoryId: String? = null, maxPages: Int = 15): List<Record> =
        paged(maxPages) { offset ->
            val params = buildList {
                add("recordDate" to "gte.$fromDate"); add("limit" to "200"); add("offset" to "$offset")
                categoryId?.let { add("categoryId" to it) }
            }
            get<RecordsPage>("records", *params.toTypedArray()).let { it.records to it.nextOffset }
        }

    /**
     * Non-transfer records on or after [fromDate] (YYYY-MM-DD) in [accountIds] (up to 10) whose payee
     * contains [payeeContains], ignoring case. [recordType] is "expense", "income" or null for both.
     * Newest first, at most [maxPages] pages of 200, to spare the hourly request limit.
     */
    suspend fun searchRecords(
        fromDate: String, accountIds: List<String>, payeeContains: String?, recordType: String?, maxPages: Int = 3,
    ): List<Record> {
        require(accountIds.size in 1..10)
        return paged(maxPages) { offset ->
            val params = buildList {
                add("recordDate" to "gte.$fromDate"); add("limit" to "200"); add("offset" to "$offset")
                add("accountId" to accountIds.joinToString(",")); add("isTransfer" to "false")
                payeeContains?.let { add("counterParty" to "contains-i.$it") }
                recordType?.let { add("recordType" to it) }
            }
            get<RecordsPage>("records", *params.toTypedArray()).let { it.records to it.nextOffset }
        }
    }

    /** Creates up to 20 records. Items succeed or fail independently. */
    suspend fun createRecords(records: List<NewRecord>): List<WriteResult> {
        require(records.size in 1..20)
        val body = json.encodeToString(records).toRequestBody(JSON)
        val request = Request.Builder().url(url("records")).post(body).auth().build()
        return execute(request, acceptedCodes = setOf(200, 201, 207, 400, 500)) {
            json.decodeFromString<WriteResponse>(it).results
        }
    }

    /** Updates up to 10 existing records. Items succeed or fail independently. */
    suspend fun updateRecords(patches: List<RecordPatch>): List<WriteResult> {
        require(patches.size in 1..10)
        val body = json.encodeToString(patches).toRequestBody(JSON)
        val url = url("records").newBuilder().addQueryParameter("returnData", "false").build()
        val request = Request.Builder().url(url).patch(body).auth().build()
        return execute(request, acceptedCodes = setOf(200, 207, 400, 500)) {
            json.decodeFromString<WriteResponse>(it).results
        }
    }

    /** The records with these ids that still exist (`GET /v1/api/records?id=…`). */
    suspend fun recordsById(ids: List<String>): List<Record> {
        require(ids.size in 1..30)
        return get<RecordsPage>("records", "id" to ids.joinToString(","), "limit" to "200").records
    }

    /** Deletes up to 10 records. Both legs of a transfer must be in the same call. */
    suspend fun deleteRecords(ids: List<String>): List<WriteResult> {
        require(ids.size in 1..10)
        val body = json.encodeToString(DeleteRequest(ids)).toRequestBody(JSON)
        val request = Request.Builder().url(url("records")).delete(body).auth().build()
        return execute(request, acceptedCodes = setOf(200, 207, 400, 500)) {
            json.decodeFromString<WriteResponse>(it).results
        }
    }

    // ---------- plumbing

    private suspend fun <T> paged(maxPages: Int = 20, page: suspend (Int) -> Pair<List<T>, Int?>): List<T> {
        val all = mutableListOf<T>()
        var offset: Int? = 0
        var pages = 0
        while (offset != null && pages < maxPages) {
            val (items, next) = page(offset)
            all += items
            offset = next
            pages++
        }
        return all
    }

    private suspend inline fun <reified T> get(path: String, vararg params: Pair<String, String>): T {
        val url = url(path).newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).get().auth().build()
        return execute(request) { json.decodeFromString<T>(it) }
    }

    private fun url(path: String): HttpUrl = "$BASE/v1/api/$path".toHttpUrl()
    private fun Request.Builder.auth() = header("Authorization", "Bearer $token").header("Accept", "application/json")

    private suspend fun <T> execute(
        request: Request,
        acceptedCodes: Set<Int> = setOf(200),
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.code in acceptedCodes -> parse(body)
                resp.code == 401 -> throw ApiException.Unauthorized()
                resp.code == 429 -> throw ApiException.RateLimited(resp.header("Retry-After")?.toLongOrNull() ?: 60)
                resp.code == 409 && body.contains("init_sync") -> throw ApiException.InitialSync(5)
                else -> throw ApiException.Http(resp.code, body)
            }
        }
    }

    companion object {
        const val BASE = "https://rest.budgetbakers.com/wallet"
        private val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
