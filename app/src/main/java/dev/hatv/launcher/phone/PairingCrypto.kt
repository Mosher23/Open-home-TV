package dev.hatv.launcher.phone

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * One session key lives in RAM and in the QR's URL fragment, never in an HTTP URL or preference.
 */
class PairingCrypto(
    private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) {
    init {
        require(key.size == 32)
    }

    val fragment: String
        get() = Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    private val seen = HashSet<String>()

    @Synchronized
    fun open(envelope: JSONObject): Pair<String, JSONObject> {
        val nonce = envelope.getString("iv")
        val iv = Base64.getDecoder().decode(nonce)
        require(iv.size == 12 && seen.size < 1024 && nonce !in seen) {
            "Invalid or repeated message"
        }
        val ciphertext = Base64.getDecoder().decode(envelope.getString("data"))
        require(ciphertext.size in 16..131072)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("OpenHomeTV setup v1 request".toByteArray())
        val plain = cipher.doFinal(ciphertext)
        val message = JSONObject(String(plain, Charsets.UTF_8))
        seen.add(nonce) // A forged envelope cannot reserve a nonce.
        plain.fill(0)
        return nonce to message
    }

    @Synchronized
    fun seal(reply: JSONObject, requestNonce: String): JSONObject {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("OpenHomeTV setup v1 response:$requestNonce".toByteArray())
        return JSONObject()
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put(
                "data",
                Base64.getEncoder()
                    .encodeToString(cipher.doFinal(reply.toString().toByteArray(Charsets.UTF_8))),
            )
    }

    @Synchronized
    fun destroy() {
        key.fill(0)
        seen.clear()
    }
}
