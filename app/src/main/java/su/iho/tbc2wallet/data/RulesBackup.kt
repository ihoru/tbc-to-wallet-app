package su.iho.tbc2wallet.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.AtomicFile
import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Keeps your categorization rules in Android's backup.
 *
 * The database itself stays out of backup (it holds the activity log and caches), so every change
 * to `user_rules` is mirrored to `files/backup/user_rules.json`, which backup includes. When the
 * database is created fresh (new install, restore onto a new phone) the rules are read back from it.
 *
 * Rows are stored column → value, so rule columns added later round-trip without changes here.
 */
class RulesBackup(context: Context) {
    private val file = AtomicFile(File(File(context.applicationContext.filesDir, DIR), FILE))

    /** Room callback: restores on first creation, refreshes the snapshot on every open. */
    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = restore(db)
        override fun onOpen(db: SupportSQLiteDatabase) = save(db)
    }

    /** Re-saves the snapshot whenever rules change, whichever code path changed them. */
    fun watch(database: RoomDatabase) {
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(TABLE) {
            override fun onInvalidated(tables: Set<String>) = save(database.openHelper.readableDatabase)
        })
    }

    private fun save(db: SupportSQLiteDatabase) = runCatching {
        val rows = db.query("SELECT * FROM $TABLE ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(rowOf(c)) }
        }
        val out = file.startWrite()
        try {
            out.write(Json.encodeToString(JsonElement.serializer(), JsonArray(rows)).toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }.onFailure { Log.w(TAG, "Couldn't save rules backup", it) }

    private fun restore(db: SupportSQLiteDatabase) = runCatching {
        if (!file.baseFile.exists()) return@runCatching
        val rows = Json.parseToJsonElement(String(file.readFully())).jsonArray.map { it.jsonObject }
        val columns = db.query("PRAGMA table_info($TABLE)").use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Column(
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        type = c.getString(c.getColumnIndexOrThrow("type")),
                        notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) != 0,
                        hasDefault = !c.isNull(c.getColumnIndexOrThrow("dflt_value")),
                    )
                )
            }
        }
        rows.forEach { row -> db.insert(TABLE, 0, contentValuesOf(completeRow(columns, row))) }
        Log.i(TAG, "Restored ${rows.size} rules from backup")
    }.onFailure { Log.w(TAG, "Couldn't restore rules backup", it) }

    private fun rowOf(c: Cursor) = JsonObject(c.columnNames.indices.associate { i ->
        c.getColumnName(i) to when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JsonNull
            Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
            else -> JsonPrimitive(c.getString(i))
        }
    })

    private fun contentValuesOf(row: Map<String, JsonElement>) = ContentValues().apply {
        row.forEach { (k, v) ->
            val p = v as? JsonPrimitive
            when {
                p == null || p is JsonNull -> putNull(k)
                p.isString -> put(k, p.content)
                p.longOrNull != null -> put(k, p.longOrNull)
                p.doubleOrNull != null -> put(k, p.doubleOrNull)
                p.booleanOrNull != null -> put(k, if (p.booleanOrNull == true) 1L else 0L)
                else -> put(k, p.contentOrNull)
            }
        }
    }

    data class Column(val name: String, val type: String, val notNull: Boolean, val hasDefault: Boolean)

    companion object {
        private const val TAG = "RulesBackup"
        private const val TABLE = "user_rules"
        const val DIR = "backup"
        const val FILE = "user_rules.json"

        /**
         * Fits a saved row to today's table: drops columns that no longer exist, and fills
         * NOT NULL columns the backup predates (no SQL default) with an empty value.
         */
        fun completeRow(columns: List<Column>, row: Map<String, JsonElement>): Map<String, JsonElement> =
            columns.mapNotNull { col ->
                val v = row[col.name]
                when {
                    v != null -> col.name to v
                    col.notNull && !col.hasDefault ->
                        col.name to if (col.type.uppercase().let { "INT" in it || "REAL" in it }) JsonPrimitive(0) else JsonPrimitive("")
                    else -> null
                }
            }.toMap()
    }
}
