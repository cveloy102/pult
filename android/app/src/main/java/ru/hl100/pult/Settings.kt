package ru.hl100.pult

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Connection(val address: String, val fingerprint: String, val token: String, val name: String)

class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("pult", Context.MODE_PRIVATE)
    var theme: String
        get() = prefs.getString("theme", "signal") ?: "signal"
        set(value) { prefs.edit().putString("theme", value).apply() }
    var checkUpdates: Boolean
        get() = prefs.getBoolean("checkUpdates", true)
        set(value) { prefs.edit().putBoolean("checkUpdates", value).apply() }
    var widgetMessage: String
        get() = prefs.getString("widgetMessage", "Открыть Пульт") ?: "Открыть Пульт"
        set(value) { prefs.edit().putString("widgetMessage", value).apply() }
    var lastVolume: Int
        get() = prefs.getInt("lastVolume", 0)
        set(value) { prefs.edit().putInt("lastVolume", value).apply() }
    /** Last Wake-on-LAN targets reported by the agent; not secret, kept while the PC is off. */
    var wake: List<WakeTarget>
        get() = runCatching {
            val array = JSONArray(prefs.getString("wake", "[]"))
            (0 until array.length()).map { array.getJSONObject(it).let { item -> WakeTarget(item.getString("mac"), item.getString("broadcast")) } }
        }.getOrDefault(emptyList())
        set(value) { prefs.edit().putString("wake", JSONArray(value.map { JSONObject().put("mac", it.mac).put("broadcast", it.broadcast) }).toString()).apply() }
    var connection: Connection?
        get() {
            val encoded = prefs.getString("connection", null) ?: return null
            cache?.let { (raw, value) -> if (raw == encoded) return value }
            return try {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
                Connection(json.getString("address"), json.getString("fingerprint"), json.getString("token"), json.getString("name")).also { cache = encoded to it }
            } catch (_: Exception) { null } // Keep the stored pairing: a transient Keystore error must not silently unpair the PC.
        }
        set(value) {
            cache = null
            if (value == null) { prefs.edit().remove("connection").commit(); return }
            val json = JSONObject().put("address", value.address).put("fingerprint", value.fingerprint).put("token", value.token).put("name", value.name)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            prefs.edit().putString("connection", encoded).commit()
            cache = encoded to value
        }
    private companion object {
        // Decrypted pairing for widget and status refreshes; avoids a Keystore call on every update.
        @Volatile var cache: Pair<String, Connection>? = null
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("PultCredentials", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("PultCredentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
