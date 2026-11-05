package dev.hatv.launcher.phone

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Independent peer implements the browser side of the AES-GCM wire protocol. */
object PhonePeer {
    fun request(
        key: ByteArray,
        json: JSONObject,
        iv: ByteArray = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) },
    ): JSONObject {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("OpenHomeTV setup v1 request".toByteArray())
        return JSONObject()
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put(
                "data",
                Base64.getEncoder().encodeToString(c.doFinal(json.toString().toByteArray())),
            )
    }

    fun reply(key: ByteArray, envelope: JSONObject, nonce: String): JSONObject {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, Base64.getDecoder().decode(envelope.getString("iv"))),
        )
        c.updateAAD("OpenHomeTV setup v1 response:$nonce".toByteArray())
        return JSONObject(String(c.doFinal(Base64.getDecoder().decode(envelope.getString("data")))))
    }
}

class PairingCryptoTest {
    @Test
    fun authenticatedRoundTripHidesTokenAndRejectsReplay() {
        val key = ByteArray(32) { it.toByte() }
        val crypto = PairingCrypto(key.copyOf())
        val request = PhonePeer.request(key, JSONObject().put("token", "sensitive-test-token"))
        assertFalse(request.toString().contains("sensitive-test-token"))
        val (nonce, json) = crypto.open(request)
        assertEquals("sensitive-test-token", json.getString("token"))
        assertTrue(runCatching { crypto.open(request) }.isFailure)
        val response = crypto.seal(JSONObject().put("ok", true), nonce)
        assertTrue(PhonePeer.reply(key, response, nonce).getBoolean("ok"))
        assertTrue(runCatching { PhonePeer.reply(key, response, "wrong-request") }.isFailure)
    }

    @Test
    fun tamperedAndWrongKeyMessagesNeverAuthenticate() {
        val key = ByteArray(32) { it.toByte() }
        val crypto = PairingCrypto(key)
        val valid = PhonePeer.request(key, JSONObject().put("op", "pair"))
        val corrupted =
            JSONObject(valid.toString())
                .put("data", Base64.getEncoder().encodeToString(ByteArray(32)))
        assertTrue(runCatching { crypto.open(corrupted) }.isFailure)
        assertEquals(
            "pair",
            crypto.open(valid).second.getString("op"),
        ) // Forgery did not reserve nonce.
        assertTrue(
            runCatching { crypto.open(PhonePeer.request(ByteArray(32) { 4 }, JSONObject())) }
                .isFailure
        )
        crypto.destroy()
        assertTrue(key.all { it == 0.toByte() })
    }
}
