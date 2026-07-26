package com.example.line_chrome.line

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives shared by login, the legy proxy and letter sealing.
 *
 * Curve25519 and AES-GCM go through BouncyCastle's lightweight API rather than
 * JCA on purpose:
 *  - `KeyAgreement.getInstance("XDH")` only exists from API 33, and minSdk here
 *    is 24.
 *  - LINE uses a **16-byte** GCM nonce, which Conscrypt rejects outright (it
 *    only accepts 12).  BouncyCastle's [GCMBlockCipher] takes any nonce length,
 *    which is what the Python `cryptography` AESGCM does.
 */
object Crypto {

    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** XOR the two halves of a buffer together — LINE's way of folding a hash to 16 bytes. */
    fun xorHalf(b: ByteArray): ByteArray {
        val half = b.size / 2
        return ByteArray(half) { (b[it].toInt() xor b[half + it].toInt()).toByte() }
    }

    /** 4-byte big-endian signed int, as LINE encodes key ids inside the E2EE AAD. */
    fun i32be(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
    )

    // -- AES ---------------------------------------------------------------

    fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    fun aesCbcEncryptNoPad(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    fun aesCbcDecryptNoPad(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    fun pkcs7Pad(data: ByteArray, block: Int = 16): ByteArray {
        val padLen = block - (data.size % block)
        return data + ByteArray(padLen) { padLen.toByte() }
    }

    /** Strict PKCS#7 unpad, matching pycryptodome's `unpad` (which raises on bad padding). */
    fun pkcs7Unpad(data: ByteArray, block: Int = 16): ByteArray {
        require(data.isNotEmpty() && data.size % block == 0) { "bad padded length ${data.size}" }
        val padLen = data[data.size - 1].toInt() and 0xFF
        require(padLen in 1..block) { "bad padding byte $padLen" }
        for (i in data.size - padLen until data.size) {
            require((data[i].toInt() and 0xFF) == padLen) { "inconsistent padding" }
        }
        return data.copyOfRange(0, data.size - padLen)
    }

    /** AES-256-GCM with a 128-bit tag and an arbitrary-length nonce. */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        gcm(true, key, nonce, plaintext, aad)

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        gcm(false, key, nonce, ciphertext, aad)

    private fun gcm(
        encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray
    ): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        val total = n + cipher.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** Constant-time compare, so a MAC check cannot be timed. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * HKDF-SHA256 (RFC 5869) with no salt, matching the `cryptography` library's
     * `HKDF(salt=None)` — which substitutes a block of zeros.
     */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(ByteArray(32), ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var position = 0
        var counter = 1
        while (position < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - position)
            previous.copyInto(out, position, 0, take)
            position += take
            counter++
        }
        return out
    }

    /**
     * AES-CTR with a 12-byte nonce, which is how pycryptodome's
     * `AES.new(key, MODE_CTR, nonce=…)` builds its counter block: the nonce
     * followed by a 4-byte big-endian counter starting at zero.
     */
    fun aesCtr(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val iv = nonce.copyOf(16)   // remaining bytes are the zeroed counter
        return Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
    }

    // -- RSA ---------------------------------------------------------------

    /**
     * PKCS#1 v1.5 encryption of the login credentials, matching Python `rsa.encrypt`.
     *
     * The plaintext is a length-prefixed concatenation where each length is a
     * single character — safe because session key, email and password are all
     * well under 128 bytes, so the char encodes as one UTF-8 byte.
     */
    fun rsaEncryptCredentials(
        nHex: String, eHex: String, sessionKey: String, email: String, password: String
    ): String {
        val message = buildString {
            append(sessionKey.length.toChar()); append(sessionKey)
            append(email.length.toChar()); append(email)
            append(password.length.toChar()); append(password)
        }.toByteArray(Charsets.UTF_8)

        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(nHex, 16), BigInteger(eHex, 16))
        )
        val enc = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(message)
        }
        return enc.joinToString("") { "%02x".format(it) }
    }

    /** RSA-OAEP (SHA-1 / MGF1-SHA1) — the default pycryptodome PKCS1_OAEP uses. */
    fun rsaOaepEncrypt(publicKeyPem: String, data: ByteArray): ByteArray {
        val body = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val der = android.util.Base64.decode(body, android.util.Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(der))
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(data)
        }
    }

    // -- Curve25519 --------------------------------------------------------

    /** @return (privateKey, publicKey), both raw 32-byte. */
    fun x25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(randomBytes(32), 0)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun x25519Shared(myPriv: ByteArray, theirPub: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(myPriv, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPub, 0), out, 0)
        return out
    }
}

fun ByteArray.b64(): String =
    android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

fun String.unb64(): ByteArray =
    android.util.Base64.decode(this, android.util.Base64.DEFAULT)
