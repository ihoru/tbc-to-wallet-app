package su.iho.tbc2wallet.data

import android.content.Context
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
import kotlinx.coroutines.flow.Flow

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
)

@Entity(tableName = "user_rules")
data class UserRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val matchType: String,   // CONTAINS / EXACT
    val appliesTo: String,   // ANY / EXPENSE / INCOME
    val categoryId: String,
    val categoryName: String,
)

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

    @Upsert abstract suspend fun upsertTx(tx: TransactionEntity)

    @Query("UPDATE transactions SET status = :status, error = NULL WHERE dedupKey = :key")
    abstract suspend fun setStatus(key: String, status: TxStatus)

    @Query("UPDATE transactions SET status = 'PENDING', error = NULL WHERE status = 'NEEDS_ACCOUNT'")
    abstract suspend fun requeueNeedsAccount()

    // --- rules & caches
    @Query("SELECT * FROM user_rules ORDER BY pattern") abstract fun observeUserRules(): Flow<List<UserRuleEntity>>
    @Query("SELECT * FROM user_rules") abstract suspend fun userRules(): List<UserRuleEntity>
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
    @Query("DELETE FROM learned") abstract suspend fun clearLearned()
    @Insert abstract suspend fun insertLearned(items: List<LearnedEntity>)
    @Transaction open suspend fun replaceLearned(items: List<LearnedEntity>) { clearLearned(); insertLearned(items) }

    @Query("SELECT * FROM categories ORDER BY parentName, name") abstract fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("DELETE FROM categories") abstract suspend fun clearCategories()
    @Insert abstract suspend fun insertCategories(items: List<CategoryEntity>)
    @Transaction open suspend fun replaceCategories(items: List<CategoryEntity>) { clearCategories(); insertCategories(items) }

    @Query("SELECT * FROM accounts ORDER BY name") abstract fun observeAccounts(): Flow<List<AccountEntity>>
    @Query("DELETE FROM accounts") abstract suspend fun clearAccounts()
    @Insert abstract suspend fun insertAccounts(items: List<AccountEntity>)
    @Transaction open suspend fun replaceAccounts(items: List<AccountEntity>) { clearAccounts(); insertAccounts(items) }
}

data class SentAssignment(val dedupKey: String, val walletRecordId: String?, val categoryId: String?)

@Database(
    entities = [
        TransactionEntity::class, UserRuleEntity::class, WalletRuleEntity::class,
        LearnedEntity::class, CategoryEntity::class, AccountEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tbc2wallet.db")
                .build().also { instance = it }
        }
    }
}
