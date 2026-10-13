package su.iho.tbc2wallet.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Plain settings (TBC package, refresh timestamps) plus the Wallet API token, which is
 * encrypted with a key that never leaves the Android Keystore.
 *
 * The currency → account mapping lives in its own file, [MAPPING_PREFS], which is the only
 * prefs file included in Android backup. Everything in "settings" stays on the device:
 * a restored token would be undecryptable without its Keystore key.
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mapping = context.applicationContext.getSharedPreferences(MAPPING_PREFS, Context.MODE_PRIVATE)

    init { moveMappingOutOfSettings() }

    var apiToken: String?
        get() = prefs.getString(KEY_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, encrypt(value.trim()))
        }.apply()

    val hasToken get() = prefs.contains(KEY_TOKEN)

    /** Package name of the TBC app. Blank = auto-detect from the first "TBC" notification. */
    var tbcPackage: String
        get() = prefs.getString("tbc_package", "") ?: ""
        set(value) = prefs.edit().putString("tbc_package", value.trim()).apply()

    fun accountFor(currency: String): String? = mapping.getString("account_$currency", null)
    fun setAccountFor(currency: String, accountId: String?) =
        mapping.edit().apply { if (accountId == null) remove("account_$currency") else putString("account_$currency", accountId) }.apply()

    /** Currencies seen in notifications, so Settings can offer a mapping for each. Backed up with the mapping. */
    var seenCurrencies: Set<String>
        get() = mapping.getStringSet("seen_currencies", setOf("GEL")) ?: setOf("GEL")
        set(value) = mapping.edit().putStringSet("seen_currencies", value).apply()

    /** One-time move for installs that kept the mapping in "settings" before it was backed up. */
    private fun moveMappingOutOfSettings() {
        val old = prefs.all.filterKeys { it.startsWith("account_") || it == "seen_currencies" }
        if (old.isEmpty()) return
        val copied = mapping.edit().apply {
            old.forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is String -> putString(k, v)
                    is Set<*> -> putStringSet(k, v as Set<String>)
                }
            }
        }.commit()
        if (copied) prefs.edit().apply { old.keys.forEach(::remove) }.apply()   // else retry next time
    }

    var lastCatalogRefresh: Long
        get() = prefs.getLong("last_catalog_refresh", 0)
        set(value) = prefs.edit().putLong("last_catalog_refresh", value).apply()

    var lastLearnRefresh: Long
        get() = prefs.getLong("last_learn_refresh", 0)
        set(value) = prefs.edit().putLong("last_learn_refresh", value).apply()

    /** Wallet's "Unknown income / Unknown expense / Uncategorized" category ids. */
    var unknownCategoryIds: Set<String>
        get() = prefs.getStringSet("unknown_category_ids", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("unknown_category_ids", value).apply()

    var lastSyncMessage: String?
        get() = prefs.getString("last_sync_message", null)
        set(value) = prefs.edit().putString("last_sync_message", value).apply()

    // --- Keystore encryption

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }

    companion object {
        const val MAPPING_PREFS = "account_mapping"
        private const val KEY_TOKEN = "wallet_token_enc"
        private const val KEY_ALIAS = "tbc2wallet_token_key"
    }
}
