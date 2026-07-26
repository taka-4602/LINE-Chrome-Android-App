package com.example.line_chrome.line

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A transport-level failure, as opposed to [LineServiceError] which is LINE
 * telling us it understood the request and refused it.  Carries the HTTP status
 * and a slice of the body, because those are the only clues a poll failure
 * leaves behind.
 */
class LineTransportError(message: String) : Exception(message)

/**
 * A LINE client that presents itself as the desktop app (DESKTOPWIN), which —
 * unlike a phone — is allowed to hold a session concurrently with the user's
 * primary device.
 *
 * Port of `line_chrome/client.py`.  Every method here blocks; callers are
 * expected to be on a background dispatcher.
 */
class LineClient(
    private val savePath: File,
    val onTokenChanged: (accessToken: String, refreshToken: String?) -> Unit = { _, _ -> },
) {

    // -- wire constants ----------------------------------------------------

    private companion object {
        const val TAG = "LineClient"

        const val APP_TYPE = "DESKTOPWIN"
        const val APP_VER = "8.6.0.3277"
        const val SYSTEM_NAME = "WINDOWS"
        const val SYSTEM_VER = "10.0.0-NT-x64"
        const val SYSTEM_MODEL = "WindowsPC"
        const val APP_NAME = "$APP_TYPE\t$APP_VER\t$SYSTEM_NAME\t$SYSTEM_VER"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

        // encType=0: talk/poll go through gwz; auth must go through legy.
        const val HOST_GW = "https://gwz.line.naver.jp"
        const val HOST_LEGY = "https://gf.line.naver.jp"
        const val EP_LEGY = "/enc"

        const val EP_TALK = "/S4"        // TalkService, TCompact

        /**
         * Long-poll routes to try, confirmed-working first.
         *
         * `/P3 fetchOperations` is the one that actually delivers on a
         * DESKTOPWIN session — established by running this probe against a live
         * account.  The rest are kept as fallbacks and because their answers
         * are useful diagnostics if LINE moves things again:
         *  - `/P5 fetchOps` is what CHRLINE uses, but it replies in
         *    TMoreCompact, which this port has no decoder for.
         *  - `/P4 fetchOps` is what the Python client uses; the server rejects
         *    it outright with `invalid method name`.
         */
        val POLL_CANDIDATES = listOf(
            "/P3" to "fetchOperations",
            "/P4" to "fetchOperations",
            "/P5" to "fetchOps",
            "/P4" to "fetchOps",
        )

        /**
         * Object storage rides the same gateway under an /oa prefix — *not*
         * obs.line-apps.com, which is what the config domain suggests.
         */
        const val EP_OBS = "/oa/r/talk/m/reqseq"

        const val EP_RS = "/api/v3p/rs"
        const val EP_TALK_DO = "/api/v3/TalkService.do"

        const val ERR_INVALID_LENGTH = 6
        const val ERR_E2EE_RETRY_ENCRYPT = 82
        const val ERR_E2EE_SECRET_REFUSED = 89
        /** Returned when a sealed group will not accept what we sent. */
        const val ERR_OLD_GROUP_KEY = 99

        /**
         * MIDs per request.  LINE does not publish its limit and rejects the
         * whole call when exceeded, so this starts modestly and [batched] backs
         * off further if even this is too many.
         */
        const val MID_BATCH = 100
        const val MID_BATCH_MIN = 10

        /** Record separator that fences the revision off in an op's param fields. */
        const val RS = "\u001E"

        /** A type letter plus 32 hex digits. */
        const val MID_LENGTH = 33

        val MEDIA_TYPES = setOf("image", "video", "audio", "file", "gif")

        val DOWNLOADABLE_TYPES = setOf(
            ContentType.IMAGE, ContentType.VIDEO, ContentType.AUDIO, ContentType.FILE
        )

        val THRIFT_COMPACT = "application/x-thrift; protocol=TCOMPACT".toMediaType()
        val THRIFT_BINARY = "application/x-thrift; protocol=TBINARY".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }

    // -- state -------------------------------------------------------------

    var authToken: String? = null
        private set
    var refreshToken: String? = null
        private set
    var mid: String = ""
        private set
    /** Our own profile, cached by [verifyLogin].  Named apart from [getProfile] to avoid a JVM signature clash. */
    var myProfile: Profile? = null
        private set

    private var revision = 0L
    private var globalRev = 0L
    private var individualRev = 0L
    private var reqId = 0

    private val store = E2eeStore(savePath)
    private var e2eeKey: E2eeKey? = null
    private val peerNegotiationCache = ConcurrentHashMap<String, Map<Int, Any?>>()
    private val peerPubCache = ConcurrentHashMap<Int, ByteArray>()
    private val groupKeyCache = ConcurrentHashMap<String, Pair<Int, ByteArray>>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val pollHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(185, TimeUnit.SECONDS)
        .build()

    /** Same client, short read timeout — used only while probing poll routes. */
    private val probeHttp by lazy {
        pollHttp.newBuilder().readTimeout(25, TimeUnit.SECONDS).build()
    }

    /** Uploads need room to push a few megabytes over a phone connection. */
    private val uploadHttp by lazy {
        http.newBuilder()
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** legy speaks HTTP/1.1, like CHRLINE — HTTP/2 upgrades confuse the proxy. */
    private val legyHttp = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun nextReqId(): Int = ++reqId

    // -- headers -----------------------------------------------------------

    private fun Request.Builder.baseHeaders(token: String? = authToken) = apply {
        header("x-line-application", APP_NAME)
        header("X-Line-Chrome-Version", "3.6.0")
        header("User-Agent", USER_AGENT)
        header("x-lal", "ja_JP")
        header("x-lap", "5")
        header("x-lpv", "1")
        header("x-lhm", "POST")
        if (token != null) header("X-Line-Access", token)
    }

    // -- transports --------------------------------------------------------

    /**
     * Send a request through the legy AES-CBC encryption proxy.  The real path
     * and token ride inside the encrypted body, so the outer request always
     * looks like a POST to /enc.
     */
    private fun postLegy(
        endpoint: String, data: ByteArray, decode: String = "binary",
        lhm: String = "POST", innerToken: String? = null,
    ): Any {
        val aesKey = Crypto.randomBytes(16)
        val body = Legy.encrypt(aesKey, endpoint, data, innerToken)

        val req = Request.Builder()
            .url(HOST_LEGY + EP_LEGY)
            .post(body.toRequestBody(THRIFT_BINARY))
            .baseHeaders(token = null)
            .header("x-le", "18")
            .header("x-lcs", Legy.makeXcs(aesKey))
            .header("x-lhm", lhm)
            .build()

        legyHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Legy HTTP ${resp.code} for $endpoint")
            }
            val raw = resp.body?.bytes() ?: ByteArray(0)
            if (raw.isEmpty()) {
                error("Legy empty response for $endpoint (x-lcr=${resp.header("x-lcr")})")
            }
            val inner = try {
                Legy.decrypt(aesKey, raw).second
            } catch (e: Exception) {
                throw IllegalStateException("Legy decrypt failed for $endpoint: $e", e)
            }
            return when (decode) {
                "json" -> if (inner.isEmpty()) JSONObject() else JSONObject(String(inner))
                "compact" -> unpackCompact(inner)
                else -> unpackBinary(inner)
            }
        }
    }

    /**
     * A TalkService call, with the method name attached to whatever comes back
     * wrong.
     *
     * LINE's errors are terse — "Invalid Length" and the like — and identical
     * whichever request produced them, so without the method name there is no
     * way to tell which call to look at.
     */
    private fun callTalk(method: String, params: List<TField> = emptyList()): Map<Int, Any?> =
        try {
            postTalk(EP_TALK, packCompact(method, params))
        } catch (e: LineServiceError) {
            throw LineServiceError(e.code, "${e.message} (from $method)")
        }

    private fun postTalk(endpoint: String, data: ByteArray): Map<Int, Any?> {
        val req = Request.Builder()
            .url(HOST_GW + endpoint)
            .post(data.toRequestBody(THRIFT_COMPACT))
            .baseHeaders()
            .build()

        http.newCall(req).execute().use { resp ->
            resp.header("x-line-next-access")?.let { rotateToken(it) }
            val raw = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                throw LineTransportError(
                    "$endpoint HTTP ${resp.code}" +
                        resp.header("x-lcr")?.let { " (x-lcr=$it)" }.orEmpty() +
                        ": " + raw.decodeToString().take(200)
                )
            }
            return if (raw.isEmpty()) emptyMap() else unpackCompact(raw)
        }
    }

    private fun postPoll(
        endpoint: String,
        data: ByteArray,
        http: OkHttpClient = pollHttp,
    ): List<Any?> {
        val req = Request.Builder()
            .url(HOST_GW + endpoint)
            .post(data.toRequestBody(THRIFT_COMPACT))
            .baseHeaders()
            .header("x-las", "F")
            .header("x-lam", "w")
            .build()

        val response = try {
            http.newCall(req).execute().use { resp ->
                resp.header("x-line-next-access")?.let { rotateToken(it) }
                Triple(resp.code, resp.header("x-lcr"), resp.body?.bytes() ?: ByteArray(0))
            }
        } catch (e: InterruptedIOException) {
            // A long-poll that times out having seen nothing is the normal
            // quiet case, not an error.
            return emptyList()
        }
        val (code, lcr, raw) = response

        if (code != 200) {
            // Without this check the error body goes straight into the Thrift
            // decoder and comes back out as "bad compact protocol id", which
            // says nothing about what actually happened.
            throw LineTransportError(
                "poll HTTP $code${lcr?.let { " (x-lcr=$it)" }.orEmpty()}: " +
                    raw.decodeToString().take(200)
            )
        }
        if (raw.isEmpty()) return emptyList()

        val result = try {
            unpackCompact(raw)
        } catch (e: LineServiceError) {
            throw e
        } catch (e: Exception) {
            throw LineTransportError(
                "poll returned ${raw.size} bytes that are not TCompact " +
                    "(head=${raw.take(16).joinToString("") { "%02x".format(it) }}): ${e.message}"
            )
        }
        // fetchOps returns a bare list, which the decoder keys at 0.
        return result[0] as? List<Any?> ?: emptyList()
    }

    // -- token management --------------------------------------------------

    private fun rotateToken(newToken: String) {
        if (newToken == authToken) return
        authToken = newToken
        Log.i(TAG, "token rotated -> ${newToken.take(20)}…")
        onTokenChanged(newToken, refreshToken)
    }

    fun useToken(token: String, refresh: String? = null) {
        authToken = token
        refreshToken = refresh
    }

    /** Trade the stored refresh token for a fresh access token. */
    fun refreshAccessToken(): Boolean {
        val rt = refreshToken ?: error("No refresh token available.")
        val data = packCompact("refreshAccessToken", fields { str(1, rt) })
        val res = postTalk("/ACCT/authfactor/token/refresh/v1", data)
        val newToken = tStr(res, 1) ?: error("Token refresh failed.")
        authToken = newToken
        refreshToken = tStr(res, 5) ?: refreshToken
        onTokenChanged(newToken, refreshToken)
        return true
    }

    // -- login -------------------------------------------------------------

    private fun getRsaKeyInfo(): Map<Int, Any?> {
        val data = packBinary("getRSAKeyInfo", fields { i32(2, 1) })   // provider=LINE
        @Suppress("UNCHECKED_CAST")
        return postLegy(EP_TALK_DO, data) as Map<Int, Any?>
    }

    private fun loginV2(
        keynm: String, encData: String, secret: ByteArray?, cert: String?, verifier: String?,
    ): Map<Int, Any?> {
        // verifier takes precedence (loginType=QRCODE=1) even when secret is set
        val loginType = if (verifier != null) 1 else if (secret != null) 2 else 0
        val data = packBinary("loginV2", fields {
            struct(2) {
                i32(1, loginType)
                i32(2, 1)               // provider = LINE
                str(3, keynm)
                str(4, encData)
                bool(5, false)
                str(6, "")
                str(7, SYSTEM_MODEL)
                str(8, cert)
                str(9, verifier)
                bin(10, secret)
                i32(11, 1)
                str(12, SYSTEM_MODEL)
            }
        })
        @Suppress("UNCHECKED_CAST")
        return postLegy(EP_RS, data) as Map<Int, Any?>
    }

    /**
     * Long-poll until the user confirms the pincode on their primary device.
     *
     * /LF1 is a plain HTTPS GET to legy.line-apps.com — not gwz, not gf, and
     * not through the legy body encryption.  CANCEL events here are stale
     * leftovers; consume and retry rather than failing the login.
     */
    private fun checkPinCodeV2(accessSession: String): JSONObject? {
        val req = Request.Builder()
            .url("https://legy.line-apps.com/LF1")
            .get()
            .header("x-line-application", APP_NAME)
            .header("X-Line-Access", accessSession)
            .header("x-lal", "ja_JP")
            .header("x-lpv", "1")
            .header("x-lhm", "GET")
            .header("User-Agent", USER_AGENT)
            .header("accept", "application/x-thrift")
            .build()

        repeat(6) { attempt ->
            try {
                pollHttp.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || text.isEmpty()) return null
                    val result = JSONObject(text).optJSONObject("result") ?: return null
                    val errorCode = result.optJSONObject("metadata")?.optString("errorCode")
                    if (errorCode == "CANCEL") {
                        Log.d(TAG, "/LF1 CANCEL (stale, attempt ${attempt + 1}), retrying")
                        return@use
                    }
                    return result
                }
                Thread.sleep(1000)
            } catch (e: Exception) {
                Log.w(TAG, "/LF1 error: $e")
                return null
            }
        }
        return null
    }

    private fun confirmE2eeLogin(verifier: String, deviceSecret: ByteArray): String {
        val data = packBinary("confirmE2EELogin", fields {
            str(1, verifier)
            bin(2, deviceSecret)   // Thrift BINARY — raw bytes, never base64
        })
        @Suppress("UNCHECKED_CAST")
        val res = postLegy(EP_RS, data) as Map<Int, Any?>
        // A scalar result is keyed at 0, but a struct result comes back
        // unwrapped — hence the field-1 fallback.
        return tStr(res, 0) ?: tStr(res, 1) ?: ""
    }

    class LoginPinRequired(val pincode: String) : Exception("PIN confirmation required")

    /**
     * Full email+password login.
     *
     * On a device LINE has not seen before this needs a PIN confirmation:
     * [onPinCode] fires with the code to display, then the call blocks in a
     * /LF1 long-poll until the user approves on their phone.  That confirmation
     * is what hands over the E2EE key chain, so it is not optional if sealed
     * chats should work.
     */
    fun loginWithEmail(
        email: String,
        password: String,
        pincode: String,
        onPinCode: (String) -> Unit,
    ) {
        val rsaInfo = getRsaKeyInfo()
        val keynm = tStr(rsaInfo, 1) ?: ""
        val nvalue = tStr(rsaInfo, 2) ?: ""
        val evalue = tStr(rsaInfo, 3) ?: ""
        val sessionKey = tStr(rsaInfo, 4) ?: ""

        val encData = Crypto.rsaEncryptCredentials(nvalue, evalue, sessionKey, email, password)

        // Curve25519 keypair for the E2EE device registration.
        val (privKey, pubKey) = Crypto.x25519KeyPair()

        // Field 10 is Thrift BINARY: the phone decrypts these bytes with
        // SHA256(pincode) to recover pubKey.  Base64-encoding it here makes the
        // phone decrypt garbage and report "unknown error".
        var secret: ByteArray? =
            Crypto.aesEcbEncrypt(Crypto.sha256(pincode.toByteArray()), pubKey)

        val cert = store.loadCert(email)

        var res = try {
            loginV2(keynm, encData, secret, cert, null)
        } catch (e: LineServiceError) {
            if (e.code == ERR_E2EE_SECRET_REFUSED) {
                secret = null
                loginV2(keynm, encData, null, cert, null)
            } else throw e
        }

        var tokenInfo = tGet(res, 9)
        if (tokenInfo == null) {
            val verifier = tStr(res, 3) ?: ""
            onPinCode(pincode)

            val e2eeInfo = checkPinCodeV2(verifier)
                ?: error("Pincode confirmation timed out.")
            val metadata = e2eeInfo.optJSONObject("metadata") ?: e2eeInfo

            // errorCode is always present and carries the outcome — "SUCCESS"
            // on approval.  It is not an error flag.
            val errorCode = metadata.optString("errorCode").takeIf { it.isNotEmpty() }
            if (errorCode != null && errorCode != "SUCCESS") {
                error("PIN rejected by primary device: $errorCode. Tap APPROVE, not cancel.")
            }

            val pubKeyB64 = metadata.optString("publicKey")
            val encKcB64 = metadata.optString("encryptedKeyChain")
            if (pubKeyB64.isEmpty() || encKcB64.isEmpty()) {
                error("PIN confirmed but E2EE key data missing.")
            }

            val serverPub = pubKeyB64.unb64()
            val encKc = encKcB64.unb64()

            // Decrypt the key chain first.  It shares the Curve25519 secret
            // with the device secret below, so failing here pins the fault on
            // the key exchange rather than on confirmE2EELogin — which the
            // server never explains.
            val (e2eePriv, e2eePub) = try {
                decryptKeyChain(serverPub, privKey, encKc)
            } catch (e: Exception) {
                error("E2EE key chain failed to decrypt ($e). The Curve25519 shared secret is wrong.")
            }

            val key = E2eeKey(
                keyId = metadata.optInt("keyId", 0),
                privKey = e2eePriv,
                pubKey = e2eePub,
                e2eeVersion = metadata.optInt("e2eeVersion", 1),
            )
            // Persist immediately: this key is only ever handed out here, and
            // losing it costs another device confirmation.
            store.saveSelfKey(key)
            e2eeKey = key

            val deviceSecret = encryptDeviceSecret(serverPub, privKey, encKc)
            val e2eeVerifier = confirmE2eeLogin(verifier, deviceSecret)
            if (e2eeVerifier.isEmpty()) error("confirmE2EELogin returned an empty verifier.")

            res = loginV2(keynm, encData, secret, cert, e2eeVerifier)
            tokenInfo = tGet(res, 9) ?: error("Login failed: no token in response.")

            // Never let a caching problem discard a login that just cost a
            // device confirmation — warn and carry on with the token in hand.
            tStr(res, 2)?.takeIf { it.isNotEmpty() }?.let {
                runCatching { store.saveCert(email, it) }
                    .onFailure { e -> Log.w(TAG, "could not save device cert: $e") }
            }
        }

        val access = tStr(tokenInfo, 1) ?: error("Login failed: empty access token.")
        authToken = access
        refreshToken = tStr(tokenInfo, 2)
        onTokenChanged(access, refreshToken)
    }

    /** Confirm the token works and learn our own mid. */
    fun verifyLogin(): Profile {
        val p = getProfile()
        if (p.mid.isEmpty()) error("Token verification failed: could not get profile.")
        mid = p.mid
        myProfile = p
        // Now that the mid is known, settle the E2EE key under its mid-keyed
        // name so later sessions find it without a server round trip.
        e2eeKey?.let { store.saveSelfKey(it, mid) }
        return p
    }

    private fun encryptDeviceSecret(
        serverPub: ByteArray, myPriv: ByteArray, encKeyChain: ByteArray,
    ): ByteArray {
        val shared = Crypto.x25519Shared(myPriv, serverPub)
        val aesKey = Crypto.sha256(shared, "Key".toByteArray())
        return Crypto.aesEcbEncrypt(aesKey, Crypto.xorHalf(Crypto.sha256(encKeyChain)))
    }

    /** @return (privateKey, publicKey) for this device's E2EE identity. */
    private fun decryptKeyChain(
        serverPub: ByteArray, myPriv: ByteArray, encKeyChain: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val shared = Crypto.x25519Shared(myPriv, serverPub)
        val aesKey = Crypto.sha256(shared, "Key".toByteArray())
        val aesIv = Crypto.xorHalf(Crypto.sha256(shared, "IV".toByteArray()))
        val plain = Crypto.aesCbcDecryptNoPad(aesKey, aesIv, encKeyChain)
        val entry = (unpackCompactStruct(plain)[1] as? List<*>)?.firstOrNull()
        return asBytes(tGet(entry, 5)) to asBytes(tGet(entry, 4))
    }

    // -- TalkService -------------------------------------------------------

    fun getProfile(): Profile = Profile.from(callTalk("getProfile"))

    /**
     * The MID of every contact.  A 1:1 conversation is not a server-side room —
     * it is addressed by the peer's own MID — so these double as chat MIDs.
     */
    fun getAllContactIds(): List<String> {
        val res = callTalk("getAllContactIds")
        return tList(res, 0).filterIsInstance<String>()
    }

    private fun getAllChatMidsRaw(): Map<Int, Any?> = callTalk(
        "getAllChatMids",
        fields { struct(1) { bool(1, true); bool(2, true) } }   // member + invited
    )

    /**
     * Every chat MID.  getAllChatMids covers only groups and rooms; pass
     * [includePeers] to append one MID per contact for the 1:1 chats.
     */
    fun getChatMids(includePeers: Boolean = false): List<String> {
        val res = getAllChatMidsRaw()
        val mids = (tList(res, 1) + tList(res, 2)).filterIsInstance<String>().toMutableList()
        if (includePeers) mids += getAllContactIds()
        return mids
    }

    /**
     * Drop identifiers that are not the length LINE uses.
     *
     * A MID is a type letter and 32 hex digits — 33 characters, always.  Send
     * one that is not and the server answers with a length complaint that names
     * neither the field nor the value, so anything odd is dropped here and
     * logged instead.
     */
    private fun sanitizeMids(mids: List<String>, where: String): List<String> {
        val (ok, bad) = mids.partition { it.length == MID_LENGTH }
        if (bad.isNotEmpty()) {
            Log.w(TAG, "$where: dropped ${bad.size} mid(s) of unexpected length: ${bad.take(3)}")
        }
        return ok
    }

    /**
     * Run a MID-batched request, halving the batch whenever LINE says the list
     * is too long.
     *
     * getChats and getContactsV2 both take the whole list in one struct and both
     * answer INVALID_LENGTH (6) when it is too big — without saying what the
     * limit is.  An account with a few dozen contacts never finds out; one with
     * a few hundred fails on the very first sweep and gets no friend list at
     * all.  Rather than hard-code a guess at LINE's limit, back off until it
     * fits.
     */
    private fun <T> batched(
        mids: List<String>,
        where: String,
        call: (List<String>) -> List<T>,
    ): List<T> {
        var size = MID_BATCH
        while (true) {
            try {
                return mids.chunked(size).flatMap(call)
            } catch (e: LineServiceError) {
                if (e.code != ERR_INVALID_LENGTH || size <= MID_BATCH_MIN) throw e
                size /= 2
                Log.w(TAG, "$where: batch rejected as too long, retrying at $size")
            }
        }
    }

    /**
     * Requests are chunked because getChats takes the whole MID list in one
     * struct, and a large friend list will otherwise overflow the request.
     */
    fun getChats(
        mids: List<String>? = null,
        withMembers: Boolean = true,
        includePeers: Boolean = false,
    ): List<Chat> {
        val targets = sanitizeMids(mids ?: getChatMids(includePeers), "getChats")
        if (targets.isEmpty()) return emptyList()
        return batched(targets, "getChats") { batch ->
            val res = callTalk("getChats", fields {
                struct(1) { strList(1, batch); bool(2, withMembers) }
            })
            tList(res, 1).mapNotNull { c -> (c as? Map<*, *>)?.let { Chat.from(it) } }
        }
    }

    fun getContacts(mids: List<String>): List<Contact> =
        batched(sanitizeMids(mids, "getContactsV2"), "getContactsV2") { batch ->
            val res = callTalk("getContactsV2", fields { struct(1) { strList(1, batch) } })
            // GetContactsV2Response{1: map<mid, ContactEntry>} — the Contact
            // itself is nested at ContactEntry field 3, not at the entry root.
            tMap(res, 1).values.mapNotNull { entry ->
                (tGet(entry, 3) as? Map<*, *>)?.let { Contact.from(it) }
            }
        }

    /**
     * Recent messages in a chat, **oldest first**.
     *
     * getRecentMessagesV2 hands them back newest first, and the ordering is
     * sorted here rather than reversed so the result is right whichever way the
     * server chooses to answer.
     */
    fun getRecentMessages(chatMid: String, count: Int = 20): List<Message> {
        val res = callTalk("getRecentMessagesV2", fields { str(2, chatMid); i32(3, count) })
        // This method's Thrift result is a bare list, so the decoder keys it at 0.
        return tList(res, 0)
            .mapNotNull { m -> (m as? Map<*, *>)?.let { decrypted(Message.from(it)) } }
            .sortedBy { it.createdTime ?: 0L }
    }

    /**
     * Send a text message.
     *
     * MID prefixes: `u` user, `c` group, `r` room.
     *
     * With [e2ee] left null this sends plain and, if the server answers
     * E2EE_RETRY_ENCRYPT (82), transparently re-sends encrypted — which is what
     * LINE's own clients do, since whether a chat is sealed is the *recipient's*
     * setting and is not knowable up front.
     */
    fun sendMessage(
        to: String,
        text: String,
        contentType: Int = ContentType.TEXT,
        contentMetadata: Map<String, String> = emptyMap(),
        replyTo: String? = null,
        e2ee: Boolean? = null,
    ): Message? {

        fun build(sealed: Pair<List<ByteArray>, Int>?): ByteArray {
            val meta = contentMetadata.toMutableMap()
            return packCompact("sendMessage", fields {
                i32(1, nextReqId())
                struct(2) {
                    str(2, to)
                    i64(5, 0L)              // createdTime
                    i64(6, 0L)              // deliveredTime
                    bool(14, false)         // hasContent
                    i32(15, contentType)
                    byte(19, 0)             // sessionId
                    if (sealed == null) {
                        str(10, text)
                    } else {
                        val (chunks, specVersion) = sealed
                        // Must match the version baked into the AAD, not a
                        // constant: the recipient rebuilds the AAD from this.
                        meta["e2eeVersion"] = specVersion.toString()
                        meta["contentType"] = contentType.toString()
                        meta["e2eeMark"] = "2"
                        binList(20, chunks)
                    }
                    strMap(18, meta)
                    if (replyTo != null) {
                        str(21, replyTo)
                        i32(22, MessageRelationType.REPLY)
                        i32(24, 1)          // relatedMessageServiceCode
                    }
                }
            })
        }

        fun post(body: ByteArray) = try {
            postTalk(EP_TALK, body)
        } catch (e: LineServiceError) {
            throw LineServiceError(e.code, "${e.message} (from sendMessage)")
        }

        if (e2ee == true) {
            return echoed(post(build(encryptE2eeText(to, text, contentType))), text)
        }

        val res = try {
            postTalk(EP_TALK, build(null))
        } catch (err: LineServiceError) {
            if (err.code != ERR_E2EE_RETRY_ENCRYPT || e2ee == false) {
                throw LineServiceError(err.code, "${err.message} (from sendMessage)")
            }
            Log.i(TAG, "$to is letter-sealed; re-sending encrypted.")
            post(build(encryptE2eeText(to, text, contentType)))
        }
        return echoed(res, text)
    }

    /**
     * The created message, with the plaintext restored.
     *
     * sendMessage's Thrift result is a Message at success field 0, which the
     * decoder returns unwrapped — so the response *is* the message.  For a
     * sealed send it echoes back the ciphertext chunks we just built, and with
     * no plaintext field the message would render as "[Encrypted]" in our own
     * UI.  We know what we sent, so put it back rather than round-tripping it
     * through decryption.
     */
    private fun echoed(res: Map<Int, Any?>, text: String): Message? {
        if (res.isEmpty()) return null
        val msg = Message.from(res)
        return if (msg.text.isNullOrEmpty() && !msg.chunks.isNullOrEmpty()) {
            msg.copy(text = text)
        } else {
            msg
        }
    }

    // -- object storage (images) -------------------------------------------

    /** What an OBS upload hands back. */
    data class UploadedObject(val objectId: String, val hash: String)

    private var obsToken: String? = null

    /**
     * Access token for OBS uploads.
     *
     * A CHROMEOS client may send the plain auth token, but every other device
     * type has to trade it via acquireEncryptedAccessToken and use the part
     * after the record separator.  We identify as DESKTOPWIN, so that is the
     * path taken here — the plain token is simply rejected.
     */
    private fun obsAccessToken(): String {
        obsToken?.let { return it }
        val res = callTalk("acquireEncryptedAccessToken", fields { i32(2, 2) })   // OBS
        val token = tStr(res, 0)?.takeIf { it.isNotEmpty() }
            ?: error("acquireEncryptedAccessToken returned nothing")
        return token.split(RS).getOrElse(1) { token }.also { obsToken = it }
    }

    /**
     * Send an image, video, audio clip or file.
     *
     * There is no "attach a file to a message" call: the recipient travels in
     * the upload parameters as `tomid`, so **the upload itself creates the
     * message**.  That is why this returns an object id rather than a [Message].
     *
     * [durationMs] applies to video and audio.  CHRLINE sets it when forwarding
     * but never on a direct upload, so whether LINE needs it here is
     * unconfirmed — send it if clip lengths come out wrong.
     *
     * Only works for chats that accept unencrypted media.  A letter-sealed chat
     * rejects a plain upload exactly as it rejects plain text, and the
     * encrypted-media flow is not implemented.
     */
    fun sendMedia(
        to: String,
        data: ByteArray,
        mediaType: String = "image",
        name: String,
        durationMs: Long? = null,
    ): UploadedObject {
        require(data.isNotEmpty()) { "No media data to send." }
        require(mediaType in MEDIA_TYPES) { "Unsupported media type: $mediaType" }

        val fields = mutableListOf(
            // A GIF goes up as an image with the original category, or LINE
            // transcodes the animation away.
            "type" to if (mediaType == "gif") "image" else mediaType,
            "ver" to "2.0",
            "name" to name,
            "oid" to "reqseq",
            "reqseq" to nextReqId().toString(),
            "tomid" to to,
        )
        if (mediaType == "gif") fields += "cat" to "original"
        if (durationMs != null) fields += "duration" to durationMs.toString()

        val params = obsParamsJson(*fields.toTypedArray())

        val req = Request.Builder()
            .url(HOST_GW + EP_OBS)
            .post(data.toRequestBody(OCTET_STREAM))
            .header("User-Agent", USER_AGENT)
            .header("X-Line-Application", APP_NAME)
            .header("X-Line-Access", obsAccessToken())
            .header("X-Line-Mid", mid)
            .header("x-lal", "ja_JP")
            .header("X-Obs-Params", params.toByteArray().b64())
            .build()

        uploadHttp.newCall(req).execute().use { resp ->
            // 200 means the object was already cached server-side, 201 that it
            // was newly created.  Both are successful sends.
            if (resp.code != 200 && resp.code != 201) {
                throw LineTransportError(
                    "OBS upload failed: HTTP ${resp.code} " +
                        (resp.body?.string()?.take(200).orEmpty())
                )
            }
            return UploadedObject(
                objectId = resp.header("x-obs-oid").orEmpty(),
                hash = resp.header("x-obs-hash").orEmpty(),
            )
        }
    }

    private fun obsHeaders(builder: Request.Builder) = builder
        .header("User-Agent", USER_AGENT)
        .header("X-Line-Application", APP_NAME)
        .header("X-Line-Access", obsAccessToken())
        .header("X-Line-Mid", mid)
        .header("x-lal", "ja_JP")

    /**
     * The X-Talk-Meta header that proves we are allowed to fetch a sealed object.
     *
     * base64( json( {"message": base64( TBinary struct )} ) ), where the struct
     * carries the message id at field 4 and an empty list at 27.
     */
    private fun talkMeta(messageId: String): String {
        val inner = packBinaryStruct(fields {
            str(4, messageId)
            emptyStructList(27)
        })
        val payload = obsParamsJson("message" to inner.b64())
        return payload.toByteArray().b64()
    }

    /**
     * Undo the file encryption used for media in sealed chats.
     *
     * HKDF-SHA256 over the key material yields encKey/macKey/nonce, then
     * AES-CTR.  The uploader appends a 32-byte HMAC of the ciphertext; it is
     * stripped only when it verifies, so this still works if a future server
     * stops appending one.
     */
    private fun decryptKeyMaterial(data: ByteArray, keyMaterial: ByteArray): ByteArray {
        val t = Crypto.hkdfSha256(keyMaterial, "FileEncryption".toByteArray(), 32 + 32 + 12)
        val encKey = t.copyOfRange(0, 32)
        val macKey = t.copyOfRange(32, 64)
        val nonce = t.copyOfRange(64, 76)

        var body = data
        if (data.size > 32) {
            val expected = Crypto.hmacSha256(macKey, data.copyOfRange(0, data.size - 32))
            if (Crypto.constantTimeEquals(expected, data.copyOfRange(data.size - 32, data.size))) {
                body = data.copyOfRange(0, data.size - 32)
            } else {
                Log.w(TAG, "media MAC did not verify; decrypting as-is")
            }
        }
        return Crypto.aesCtr(encKey, nonce, body)
    }

    /**
     * Download the object attached to a message.
     *
     * Media lives in object storage keyed by the message id, not inside the
     * message itself.  In a sealed chat the object is encrypted and the message
     * carries only the key material.
     *
     * @param preview fetch the thumbnail.  Plain and sealed media address it
     *   completely differently — a `/preview` path segment versus a separate
     *   `<OID>__ud-preview` object — so use this rather than building a URL.
     * @param size a dimension string such as "m800x1200" or "w800".  Plain
     *   media only; a sealed object cannot be resized by a server that cannot
     *   read it.
     */
    fun downloadMedia(msg: Message, preview: Boolean = false, size: String? = null): ByteArray {
        val messageId = msg.id ?: error("Message has no id, cannot locate its object.")
        require(msg.contentType in DOWNLOADABLE_TYPES) {
            "Message $messageId is content type ${msg.contentType}, not a downloadable object."
        }

        val builder = Request.Builder()
            .header("X-LHM", "GET")
            .header("X-LPV", "1")

        // Sealed media does not live under talk/m keyed by message id: the
        // sender records the real namespace and object id in contentMetadata,
        // and the fetch has to be authorised with X-Talk-Meta.
        val oid = msg.contentMetadata["OID"]
        val sid = msg.contentMetadata["SID"]
        val sealed = !msg.chunks.isNullOrEmpty()
        if (sealed && oid != null && sid != null) {
            builder.header("X-Talk-Meta", talkMeta(messageId))
            if (size != null) Log.d(TAG, "size is ignored for sealed media")
        }
        val url = mediaObjectUrl(HOST_GW, messageId, oid, sid, sealed, preview, size)

        obsHeaders(builder).url(url).get()
        uploadHttp.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200) {
                throw LineTransportError(
                    "OBS download failed: HTTP ${resp.code} " +
                        (resp.body?.string()?.take(200).orEmpty())
                )
            }
            val data = resp.body?.bytes() ?: ByteArray(0)
            if (!sealed) return data

            val keyMaterial = e2eePayload(msg).optString("keyMaterial")
                .takeIf { it.isNotEmpty() }
                ?: error("Sealed media message $messageId carried no keyMaterial.")
            return decryptKeyMaterial(data, keyMaterial.unb64())
        }
    }

    fun unsendMessage(messageId: String) {
        callTalk("unsendMessage", fields { str(2, messageId) })
    }

    fun sendReadReceipt(chatMid: String, lastMessageId: String) {
        callTalk("sendChatChecked", fields {
            i32(1, nextReqId()); str(2, chatMid); str(3, lastMessageId)
        })
    }

    // -- long-polling ------------------------------------------------------

    fun getLastOpRevision(): Long {
        val res = callTalk("getLastOpRevision")
        return tLong(res, 0) ?: 0L
    }

    /**
     * Seed the poll cursor at the present, rather than replaying all history.
     *
     * Deliberately allowed to throw.  Falling back to revision 0 is not a
     * graceful degradation — it asks the server for every operation the account
     * has ever seen, which is how a poll loop ends up timing out forever.
     */
    fun seedRevision() {
        if (revision == 0L) {
            revision = getLastOpRevision()
            Log.i(TAG, "starting from revision $revision")
        }
    }

    /** Where the long-poll actually lives for this account. */
    data class PollRoute(val endpoint: String, val method: String) {
        override fun toString() = "$endpoint $method"
    }

    /**
     * What each candidate route said, when none of them worked.
     *
     * Empty whenever polling is fine — anything left in here would be read as a
     * live complaint about a working connection.
     */
    var pollDiagnostics: String = ""
        private set

    private var pollRoute: PollRoute? = null
    private var pollProven = false

    /** The route in use, once one has been resolved. */
    val activePollRoute: PollRoute? get() = pollRoute

    /** Forget the resolved route so the next poll probes again. */
    fun resetPollRoute() {
        pollRoute = null
        pollProven = false
    }

    private fun pollRequest(method: String, count: Int): ByteArray = when (method) {
        "fetchOps" -> packCompact(method, fields {
            i64(2, revision); i32(3, count); i64(4, globalRev); i64(5, individualRev)
        })
        // The older signature takes a 32-bit revision and nothing else.
        else -> packCompact(method, fields { i32(2, revision.toInt()); i32(3, count) })
    }

    /**
     * Find the endpoint and method this account's server serves the long-poll on.
     *
     * LINE has moved this around and there is no way to ask.  `/P4` speaks
     * TCompact happily but answers `invalid method name: "fetchOps"`, so the
     * path the Python client uses is simply wrong here.  The server does give a
     * clear Thrift error for a method it does not host, which makes trying the
     * candidates in order and keeping whichever answers more reliable than
     * hard-coding another guess.
     *
     * A candidate that returns TMoreCompact instead of TCompact fails to decode
     * and is reported with the leading bytes of its response, which is the
     * evidence needed to add that decoder.
     */
    private fun probePollRoute(count: Int): Pair<PollRoute, List<Any?>> {
        val notes = mutableListOf<String>()
        for ((endpoint, method) in POLL_CANDIDATES) {
            val route = PollRoute(endpoint, method)
            try {
                // Bounded, so one endpoint that accepts the request and then
                // sits on it cannot hold up the candidates behind it.
                val ops = postPoll(endpoint, pollRequest(method, count), probeHttp)
                // Answering at all is enough.  An empty answer is what a quiet
                // account looks like, and the service has its own safety net
                // regardless, so there is nothing here worth reporting as a
                // fault.
                pollDiagnostics = ""
                Log.i(TAG, "poll route resolved: $route (${ops.size} ops)")
                return route to ops
            } catch (e: Exception) {
                notes += "$route -> ${e.message?.take(100)}"
                Log.w(TAG, "poll candidate $route rejected: ${e.message}")
            }
        }
        pollDiagnostics = notes.joinToString(" | ")
        throw LineTransportError("no working poll route. $pollDiagnostics")
    }

    /** One long-poll round.  Blocks up to ~3 minutes; returns [] on timeout. */
    fun fetchOps(count: Int = 100): List<Operation> {
        val route = pollRoute
        val ops = if (route != null) {
            postPoll(route.endpoint, pollRequest(route.method, count))
        } else {
            val (found, firstOps) = probePollRoute(count)
            pollRoute = found
            firstOps
        }
        if (ops.isNotEmpty()) pollProven = true
        return ops.mapNotNull { raw ->
            val d = raw as? Map<*, *> ?: return@mapNotNull null
            val opType = tInt(d, 3)
            tLong(d, 1)?.let { if (opType != -1) revision = maxOf(revision, it) }
            // op_type 0 carries revision updates in param1/param2, ahead of a
            // record separator.  No separator means no revision to read.
            if (opType == OpType.END_OF_OPERATION) {
                tStr(d, 10)?.let { p ->
                    if (RS in p) p.substringBefore(RS).toLongOrNull()?.let { individualRev = it }
                }
                tStr(d, 11)?.let { p ->
                    if (RS in p) p.substringBefore(RS).toLongOrNull()?.let { globalRev = it }
                }
            }
            Operation.from(d)
        }
    }

    /** Decrypt an op's message in place, if it carries one. */
    fun decorate(op: Operation): Operation =
        if (op.message == null) op else op.copy(message = decrypted(op.message))

    // -- E2EE --------------------------------------------------------------

    fun getE2eePublicKeys(): List<Any?> =
        tList(callTalk("getE2EEPublicKeys"), 0)

    /**
     * A peer's current E2EE public key.  specVersion -1 means the peer cannot
     * do E2EE at all.
     */
    fun negotiateE2eePublicKey(peerMid: String): Map<Int, Any?> =
        peerNegotiationCache.getOrPut(peerMid) {
            callTalk("negotiateE2EEPublicKey", fields { str(2, peerMid) })
        }

    /**
     * Our own E2EE identity key.  Tries the mid-keyed file, then falls back to
     * asking the server which key ids belong to us and looking for a matching
     * local private key.
     */
    private fun loadSelfKey(): E2eeKey? {
        e2eeKey?.let { return it }
        store.loadSelfKeyByMid(mid)?.let { e2eeKey = it; return it }
        // The login that stored the key did not know our mid yet, so the file
        // is named after the key id.  Ask the server for our key ids.
        runCatching {
            for (key in getE2eePublicKeys()) {
                val kid = tInt(key, 2) ?: continue
                store.loadSelfKeyById(kid)?.let {
                    if (mid.isNotEmpty()) store.backfillMid(mid, it)
                    e2eeKey = it
                    return it
                }
            }
        }.onFailure { Log.w(TAG, "could not list E2EE public keys: $it") }
        return null
    }

    private fun selfKeyById(keyId: Int): E2eeKey? =
        store.loadSelfKeyById(keyId) ?: loadSelfKey()?.takeIf { it.keyId == keyId }

    private fun peerPublicKey(peerMid: String, keyId: Int): ByteArray {
        peerPubCache[keyId]?.let { return it }
        store.loadPeerKey(keyId)?.let { peerPubCache[keyId] = it; return it }

        val res = negotiateE2eePublicKey(peerMid)
        val kid = tInt(res, 2, 2) ?: error("No usable E2EE key for $peerMid")
        val data = asBytes(tGet(res, 2, 4))
        if (data.size != 32) error("Bad E2EE key length for $peerMid")
        peerPubCache[kid] = data
        store.savePeerKey(peerMid, kid, data)
        if (kid != keyId) {
            error("$peerMid has rotated E2EE keys: this message needs key $keyId, the server now offers $kid.")
        }
        return data
    }

    /**
     * The group's shared Curve25519 private key.
     *
     * A sealed group does not do pairwise ECDH.  The creator generates one key
     * pair and hands each member an encrypted copy of the private half; every
     * member then derives the same secret.  Our copy arrives wrapped with
     * ECDH(our key, creator's key).
     */
    private fun groupSharedKey(groupMid: String, keyId: Int?): Pair<Int, ByteArray> {
        groupKeyCache[groupMid]?.let { if (keyId == null || it.first == keyId) return it }
        store.loadGroupKey(groupMid)?.let {
            if (keyId == null || it.first == keyId) { groupKeyCache[groupMid] = it; return it }
        }

        val res = callTalk("getLastE2EEGroupSharedKey", fields {
            i32(2, 2)                       // keyVersion
            str(3, groupMid)
        })
        val groupKeyId = tInt(res, 2) ?: 0
        val creator = tStr(res, 3) ?: error("No group shared key for $groupMid")
        val creatorKid = tInt(res, 4) ?: 0
        val receiverKid = tInt(res, 6) ?: 0
        val encrypted = asBytes(tGet(res, 7))
        if (encrypted.isEmpty()) error("No group shared key for $groupMid")

        val self = selfKeyById(receiverKid)
            ?: error("Group key for $groupMid was wrapped for key $receiverKid, which is not stored locally.")
        val creatorPub = peerPublicKey(creator, creatorKid)

        val shared = Crypto.x25519Shared(self.privKey, creatorPub)
        // Note: no salt here, unlike message keys.
        val aesKey = Crypto.sha256(shared, "Key".toByteArray())
        val aesIv = Crypto.xorHalf(Crypto.sha256(shared, "IV".toByteArray()))
        val decrypted = Crypto.aesCbcDecryptNoPad(aesKey, aesIv, encrypted)
        // The wrapped key is exactly one block on some groups, so it may not be padded.
        val plain = runCatching { Crypto.pkcs7Unpad(decrypted) }.getOrDefault(decrypted)

        val entry = groupKeyId to plain
        groupKeyCache[groupMid] = entry
        store.saveGroupKey(groupMid, groupKeyId, plain)
        return entry
    }

    /**
     * Encrypt a text message for a 1:1 chat.
     *
     * @return (chunks, specVersion) where chunks =
     *   [salt, ciphertext+tag, nonce, senderKeyId, receiverKeyId].
     *
     * The version is returned rather than assumed because the recipient
     * rebuilds the AAD from contentMetadata: signing with one version while
     * advertising another makes their GCM tag check fail, and the message shows
     * as undecryptable on every device but ours.
     */
    private fun encryptE2eeText(
        to: String, text: String, contentType: Int,
    ): Pair<List<ByteArray>, Int> {
        val self = loadSelfKey() ?: error(
            "No E2EE key available. It is only handed out during a PIN login — sign out, " +
                "clear the device cert and log in again to capture it."
        )
        if (!to.startsWith("u")) {
            throw UnsupportedOperationException(
                "This group is letter-sealed. Sending to a sealed group needs the " +
                    "encrypted-group flow (registerE2EEGroupKey), which is not implemented."
            )
        }

        val peer = negotiateE2eePublicKey(to)
        val negotiated = tInt(peer, 3)
        if (negotiated == -1) error("$to does not support E2EE.")
        val specVersion = negotiated ?: 2

        val receiverKeyId = tInt(peer, 2, 2) ?: error("Bad peer key for $to")
        val receiverKey = asBytes(tGet(peer, 2, 4))
        if (receiverKey.size != 32) error("Bad peer key for $to")

        val shared = Crypto.x25519Shared(self.privKey, receiverKey)
        val salt = Crypto.randomBytes(16)
        val gcmKey = Crypto.sha256(shared, salt, "Key".toByteArray())
        val nonce = Crypto.randomBytes(16)
        val aad = to.toByteArray() + mid.toByteArray() +
            Crypto.i32be(self.keyId) + Crypto.i32be(receiverKeyId) +
            Crypto.i32be(specVersion) + Crypto.i32be(contentType)
        val payload = JSONObject().put("text", text).toString().toByteArray()
        val enc = Crypto.aesGcmEncrypt(gcmKey, nonce, payload, aad)

        return listOf(
            salt, enc, nonce, Crypto.i32be(self.keyId), Crypto.i32be(receiverKeyId)
        ) to specVersion
    }

    /**
     * The plaintext of a letter-sealed message, in either direction.
     *
     * Which chunk key id is *ours* depends on who sent it: for an incoming
     * message we are the receiver, but for one we sent our key is the sender
     * key id.  Getting this backwards makes your own messages undecryptable
     * while everyone else's work.
     */
    fun decryptMessage(msg: Message): String? {
        if (msg.chunks.isNullOrEmpty()) return msg.text
        return e2eePayload(msg).optString("text").takeIf { it.isNotEmpty() }
    }

    /**
     * Decrypt a sealed message to its JSON payload.
     *
     * A text message carries `{"text": …}`; a media message carries
     * `{"keyMaterial": …}`, which is what decrypts the uploaded object.
     */
    fun e2eePayload(msg: Message): JSONObject {
        val rawChunks = msg.chunks ?: error("Message is not sealed")
        val chunks = rawChunks.map { asBytes(it) }
        if (chunks.size < 5) error("Unexpected chunk count ${chunks.size}")

        val (salt, enc, nonce) = Triple(chunks[0], chunks[1], chunks[2])
        val senderKid = java.math.BigInteger(1, chunks[3]).toInt()
        val receiverKid = java.math.BigInteger(1, chunks[4]).toInt()

        val priv: ByteArray
        val peerPub: ByteArray
        val target = msg.to.orEmpty()

        if (target.startsWith("c") || target.startsWith("r")) {
            // Group: ECDH is between the group's shared private key and the
            // *sender's* public key, not a pairwise exchange with us.
            priv = groupSharedKey(target, receiverKid).second
            peerPub = if (msg.sender == mid) {
                (selfKeyById(senderKid) ?: loadSelfKey()
                    ?: error("No local E2EE key with id $senderKid")).pubKey
            } else {
                peerPublicKey(msg.sender ?: error("message has no sender"), senderKid)
            }
        } else {
            val (selfKid, peerKid, peerMid) = if (msg.sender == mid) {
                Triple(senderKid, receiverKid, msg.to)
            } else {
                Triple(receiverKid, senderKid, msg.sender)
            }
            priv = (selfKeyById(selfKid) ?: error("No local E2EE private key with id $selfKid")).privKey
            peerPub = peerPublicKey(peerMid ?: error("message has no peer"), peerKid)
        }

        val spec = msg.contentMetadata["e2eeVersion"]?.toIntOrNull() ?: 2
        val shared = Crypto.x25519Shared(priv, peerPub)
        // The AAD always uses the message's own to/from and the on-wire key
        // ids, regardless of which side is decrypting.
        val aad = (msg.to ?: "").toByteArray() + (msg.sender ?: "").toByteArray() +
            Crypto.i32be(senderKid) + Crypto.i32be(receiverKid) +
            Crypto.i32be(spec) + Crypto.i32be(msg.contentType)

        val plain = if (spec == 2) {
            Crypto.aesGcmDecrypt(Crypto.sha256(shared, salt, "Key".toByteArray()), nonce, enc, aad)
        } else {
            val aesKey = Crypto.sha256(shared, salt, "Key".toByteArray())
            val aesIv = Crypto.xorHalf(Crypto.sha256(shared, salt, "IV".toByteArray()))
            Crypto.pkcs7Unpad(Crypto.aesCbcDecryptNoPad(aesKey, aesIv, enc))
        }
        return JSONObject(String(plain))
    }

    /** Best-effort decrypt; a failure leaves the message untouched. */
    private fun decrypted(msg: Message): Message {
        if (msg.chunks.isNullOrEmpty()) return msg
        return try {
            msg.copy(text = decryptMessage(msg))
        } catch (e: Exception) {
            Log.w(TAG, "could not decrypt ${msg.id}: $e")
            msg
        }
    }

    fun logout() {
        authToken = null
        refreshToken = null
        mid = ""
        myProfile = null
        e2eeKey = null
        revision = 0
        peerNegotiationCache.clear()
        peerPubCache.clear()
        groupKeyCache.clear()
        store.clearAll()
    }
}
