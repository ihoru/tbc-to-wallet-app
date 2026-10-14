package su.iho.tbc2wallet.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import su.iho.tbc2wallet.categorize.AppliesTo
import su.iho.tbc2wallet.categorize.MatchType
import su.iho.tbc2wallet.categorize.UserRule

enum class TxStatus {
    PENDING,        // parsed, waiting to be sent
    NEEDS_ACCOUNT,  // no Wallet account mapped for this currency yet
    SENT,           // created in Wallet
    FAILED,         // Wallet rejected it (bad input); needs a look
    UNPARSED,       // TBC notification in a format the parser doesn't know
    IGNORED,        // dismissed by you
}

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val dedupKey: String,
    val kind: String?,              // EXPENSE / INCOME, null when unparsed
    val amount: String?,            // positive decimal as text
    val currency: String?,
    val counterParty: String?,
    val accountLabel: String?,
    val cardLast4: String?,
    val recordDateUtc: String?,     // ISO-8601 instant sent to Wallet
    val rawText: String,
    val status: TxStatus,
    val walletRecordId: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val categorySource: String? = null,
    val error: String? = null,
    val receivedAt: Long,
    @ColumnInfo(defaultValue = "0") val isFake: Boolean = false,    // typed in by hand from the Activity tab to test parsing and rules
)

@Entity(tableName = "user_rules")
data class UserRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val matchType: String,   // CONTAINS / EXACT
    val appliesTo: String,   // ANY / EXPENSE / INCOME
    val categoryId: String,
    val categoryName: String,
    val labelIds: String = "",     // Wallet label ids, joined with \n
    val labelNames: String = "",   // their names at the time, same order
    // Set = record matches as a transfer to (expense) / from (income) this Wallet account, not a category.
    val transferAccountId: String? = null,
    val transferAccountName: String? = null,
)

val UserRuleEntity.labelIdList: List<String> get() = labelIds.split("\n").filter { it.isNotBlank() }
val UserRuleEntity.labelNameList: List<String> get() = labelNames.split("\n").filter { it.isNotBlank() }

fun UserRuleEntity.toUserRule() =
    UserRule(pattern, MatchType.valueOf(matchType), AppliesTo.valueOf(appliesTo), categoryId, categoryName, labelIdList,
        transferAccountId, transferAccountName)

@Entity(tableName = "wallet_rules")
data class WalletRuleEntity(
    @PrimaryKey val id: String,
    val keywords: String,    // joined with \n
    val categoryId: String,
    val categoryName: String,
    val fromAccountId: String?,
)

@Entity(tableName = "learned")
data class LearnedEntity(
    @PrimaryKey val payeeKey: String,
    val categoryId: String,
    val categoryName: String,
    val votes: Int,
    val totalVotes: Int,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentName: String?,
)

val CategoryEntity.displayName: String
    get() = if (parentName.isNullOrBlank()) name else "$parentName › $name"

@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val isBankSync: Boolean,
)

@Dao
abstract class AppDao {
    // --- transactions
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfNew(tx: TransactionEntity): Long   // -1 when it already existed

    @Query("SELECT * FROM transactions ORDER BY receivedAt DESC LIMIT 300")
    abstract fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE status IN ('PENDING','NEEDS_ACCOUNT') ORDER BY receivedAt")
    abstract suspend fun toSend(): List<TransactionEntity>

    @Query("SELECT dedupKey, walletRecordId, categoryId FROM transactions WHERE walletRecordId IS NOT NULL")
    abstract suspend fun sentAssignments(): List<SentAssignment>

    @Query("SELECT walletRecordId FROM transactions WHERE isFake = 1 AND walletRecordId IS NOT NULL")
    abstract suspend fun fakeWalletRecordIds(): List<String>

    @Upsert abstract suspend fun upsertTx(tx: TransactionEntity)

    @Query("UPDATE transactions SET status = :status, error = NULL WHERE dedupKey = :key")
    abstract suspend fun setStatus(key: String, status: TxStatus)

    @Query("UPDATE transactions SET status = 'PENDING', error = NULL WHERE status = 'NEEDS_ACCOUNT'")
    abstract suspend fun requeueNeedsAccount()

    // --- rules & caches
    @Query("SELECT * FROM user_rules ORDER BY pattern COLLATE NOCASE, id") abstract fun observeUserRules(): Flow<List<UserRuleEntity>>
    @Query("SELECT * FROM user_rules ORDER BY id") abstract suspend fun userRules(): List<UserRuleEntity>
    @Insert abstract suspend fun insertUserRule(rule: UserRuleEntity)
    @Update abstract suspend fun updateUserRule(rule: UserRuleEntity)
    @Query("DELETE FROM user_rules WHERE id = :id") abstract suspend fun deleteUserRule(id: Long)

    @Query("SELECT * FROM wallet_rules") abstract suspend fun walletRules(): List<WalletRuleEntity>
    @Query("SELECT COUNT(*) FROM wallet_rules") abstract fun observeWalletRuleCount(): Flow<Int>
    @Query("DELETE FROM wallet_rules") abstract suspend fun clearWalletRules()
    @Insert abstract suspend fun insertWalletRules(rules: List<WalletRuleEntity>)
    @Transaction open suspend fun replaceWalletRules(rules: List<WalletRuleEntity>) { clearWalletRules(); insertWalletRules(rules) }

    @Query("SELECT * FROM learned") abstract suspend fun learned(): List<LearnedEntity>
    @Query("SELECT * FROM learned ORDER BY payeeKey") abstract fun observeLearned(): Flow<List<LearnedEntity>>
    @Query("SELECT COUNT(*) FROM learned") abstract fun observeLearnedCount(): Flow<Int>
    @Query("DELETE FROM learned WHERE payeeKey = :payeeKey") abstract suspend fun deleteLearned(payeeKey: String)
    @Query("DELETE FROM learned") abstract suspend fun clearLearned()
    @Insert abstract suspend fun insertLearned(items: List<LearnedEntity>)
    @Transaction open suspend fun replaceLearned(items: List<LearnedEntity>) { clearLearned(); insertLearned(items) }

    @Query("SELECT * FROM categories ORDER BY parentName, name") abstract fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("DELETE FROM categories") abstract suspend fun clearCategories()
    @Insert abstract suspend fun insertCategories(items: List<CategoryEntity>)
    @Transaction open suspend fun replaceCategories(items: List<CategoryEntity>) { clearCategories(); insertCategories(items) }

    @Query("SELECT * FROM labels ORDER BY name") abstract fun observeLabels(): Flow<List<LabelEntity>>
    @Query("DELETE FROM labels") abstract suspend fun clearLabels()
    @Insert abstract suspend fun insertLabels(items: List<LabelEntity>)
    @Transaction open suspend fun replaceLabels(items: List<LabelEntity>) { clearLabels(); insertLabels(items) }

    @Query("SELECT * FROM accounts ORDER BY name") abstract fun observeAccounts(): Flow<List<AccountEntity>>
    @Query("DELETE FROM accounts") abstract suspend fun clearAccounts()
    @Insert abstract suspend fun insertAccounts(items: List<AccountEntity>)
    @Transaction open suspend fun replaceAccounts(items: List<AccountEntity>) { clearAccounts(); insertAccounts(items) }
}

data class SentAssignment(val dedupKey: String, val walletRecordId: String?, val categoryId: String?)

@Database(
    entities = [
        TransactionEntity::class, UserRuleEntity::class, WalletRuleEntity::class,
        LearnedEntity::class, CategoryEntity::class, AccountEntity::class, LabelEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: RulesBackup(context).let { backup ->
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tbc2wallet.db")
                    .addMigrations(*(1..3).map(::catchUp).toTypedArray())
                    .addCallback(backup.callback)
                    .build().also { backup.watch(it); instance = it }
            }
        }

        /**
         * Version 2 and 3 were reached by different branches (fake transactions; rule labels;
         * transfer rules), so a phone may be on any mix of them. Every old version jumps straight
         * to the current one and adds only what's missing.
         */
        private fun catchUp(from: Int) = object : Migration(from, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("transactions", "isFake", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("user_rules", "labelIds", "TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("user_rules", "labelNames", "TEXT NOT NULL DEFAULT ''")
                db.addColumnIfMissing("user_rules", "transferAccountId", "TEXT")
                db.addColumnIfMissing("user_rules", "transferAccountName", "TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS labels (id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
            }
        }

        private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, type: String) {
            val exists = query("PRAGMA table_info($table)").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == column }
            }
            if (!exists) execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }
}
