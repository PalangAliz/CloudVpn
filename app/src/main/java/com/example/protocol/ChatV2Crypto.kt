package com.example.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Exact cryptographic primitives matching Cloudflare Worker `worker.js` (`chat.v2` subprotocol):
 * - Repeating XOR cipher (`xorBuf`)
 * - 16-byte session key derivation: `key[i] = tb[i % tb.length] ^ nonce[i]`
 * - HMAC-SHA256 handshake ACK verification: `xorBuf(hmacSha256(key, "OK")[0..15], key)`
 */
object ChatV2Crypto {

  const val NONCE_SIZE = 16
  const val SESSION_KEY_SIZE = 16
  const val ACK_SIZE = 16
  const val INIT_TRAILER_SIZE = 8

  private val secureRandom = SecureRandom()

  fun randomBytes(length: Int): ByteArray {
    require(length >= 0) { "Length must be non-negative" }
    return ByteArray(length).also { secureRandom.nextBytes(it) }
  }

  /**
   * Repeating XOR cipher matching `xorBuf(buf, key)` in `worker.js`.
   */
  fun xorBuf(data: ByteArray, key: ByteArray): ByteArray {
    require(key.isNotEmpty()) { "XOR key must not be empty" }
    val out = ByteArray(data.size)
    val kLen = key.size
    for (i in data.indices) {
      out[i] = (data[i].toInt() xor key[i % kLen].toInt()).toByte()
    }
    return out
  }

  /**
   * Derives the 16-byte session key from UTF-8 encoded UUID token `tb` and 16-byte `nonce`:
   * `for (i = 0..15) key[i] = tb[i % tb.length] ^ nonce[i]`
   */
  fun deriveSessionKey(uuidToken: String, nonce: ByteArray): ByteArray {
    require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes" }
    val tb = uuidToken.trim().toByteArray(Charsets.UTF_8)
    require(tb.isNotEmpty()) { "UUID token must not be empty" }
    val key = ByteArray(SESSION_KEY_SIZE)
    for (i in 0 until SESSION_KEY_SIZE) {
      key[i] = (tb[i % tb.size].toInt() xor nonce[i].toInt()).toByte()
    }
    return key
  }

  /**
   * Computes standard HMAC-SHA256(key, data).
   */
  fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }

  /**
   * Computes the expected 16-byte Worker ACK payload:
   * `expectedAck = xorBuf(hmacSha256(sessionKey, "OK")[0..15], sessionKey)`
   */
  fun computeExpectedAck(sessionKey: ByteArray): ByteArray {
    require(sessionKey.size == SESSION_KEY_SIZE) { "Session key must be $SESSION_KEY_SIZE bytes" }
    val digest = hmacSha256(sessionKey, "OK".toByteArray(Charsets.UTF_8))
    val first16 = digest.copyOfRange(0, ACK_SIZE)
    return xorBuf(first16, sessionKey)
  }

  /**
   * Constant-time verification of the first binary WebSocket frame returned by the Worker.
   */
  fun verifyAck(receivedAck: ByteArray, sessionKey: ByteArray): Boolean {
    if (receivedAck.size != ACK_SIZE) return false
    val expected = computeExpectedAck(sessionKey)
    return MessageDigest.isEqual(receivedAck, expected)
  }
}
