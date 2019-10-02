package org.blockstack.android.sdk

import android.util.Base64
import com.colendi.ecies.EncryptedResultForm
import com.colendi.ecies.Encryption
import kotlinx.serialization.json.JsonException
import me.uport.sdk.core.decodeBase64
import me.uport.sdk.jwt.InvalidJWTException
import me.uport.sdk.jwt.JWTEncodingException
import me.uport.sdk.jwt.JWTTools
import me.uport.sdk.jwt.JWTUtils
import me.uport.sdk.jwt.model.JwtHeader
import me.uport.sdk.signer.KPSigner
import me.uport.sdk.universaldid.UniversalDID
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.blockstack.android.sdk.model.BlockstackAccount
import org.blockstack.android.sdk.model.CipherObject
import org.blockstack.android.sdk.model.CryptoOptions
import org.blockstack.android.sdk.model.Profile
import org.json.JSONArray
import org.json.JSONObject
import org.kethereum.crypto.getCompressedPublicKey
import org.kethereum.encodings.encodeToBase58String
import org.kethereum.extensions.toBytesPadded
import org.kethereum.extensions.toHexStringNoPrefix
import org.kethereum.hashes.ripemd160
import org.kethereum.hashes.sha256
import org.kethereum.model.ECKeyPair
import org.kethereum.model.PUBLIC_KEY_SIZE
import org.kethereum.model.PublicKey
import org.komputing.khex.extensions.hexToByteArray
import org.komputing.khex.extensions.toNoPrefixHexString
import java.security.InvalidParameterException
import java.util.*

class Blockstack(private val callFactory: Call.Factory = OkHttpClient()) {

    init {
        UniversalDID.registerResolver(BitAddrResolver(callFactory))
    }


    suspend fun makeAuthResponse(account: BlockstackAccount, authRequest: String): String {
        val jwt = JWTTools()
        val authRequestTriple = decodeToken(authRequest)
        val transitPublicKey = authRequestTriple.second.getJSONArray("public_keys").getString(0)
        val appPrivateKey = account.getAppsNode().getAppNode(authRequestTriple.second.getString("domain_name"))

        val privateKeyPayload = encryptContent(
                appPrivateKey.keyPair.privateKey.key.toHexStringNoPrefix(),
                CryptoOptions(publicKey = transitPublicKey)
        ).value?.json?.toString()?.toByteArray()?.toNoPrefixHexString()

        val profile = if (account.username != null) {
            lookupProfile(account.username, null)
        } else {
            Profile(JSONObject())
        }

        val expiresAt = (Date().time + 3600 * 24 * 30) / 1000
        val payload = mapOf(
                "jti" to UUID.randomUUID().toString(),
                "iat" to Date().time / 1000,
                "exp" to expiresAt,
                "private_key" to privateKeyPayload,
                "public_keys" to arrayOf(account.keys.keyPair.toHexPublicKey64()),
                "profile" to profile.json.toMap(),
                "username" to (account.username ?: ""),
                "email" to "",
                "profile_url" to null,
                "hubUrl" to "https://hub.blockstack.org",
                "blockstackAPIUrl" to "https://core.blockstack.org",
                "associationToken" to null,
                "version" to VERSION
        )

        val signer = KPSigner(
                account.keys.keyPair.privateKey.key.toHexStringNoPrefix()
        )
        val issuerDID =
                "did:btc-addr:${account.keys.keyPair.toBtcAddress()}"

        return jwt.createJWT(payload, issuerDID, signer, algorithm = JwtHeader.ES256K)
    }

    /**
     * Look up a user profile by blockstack ID
     *
     * @param {string} username - The Blockstack ID of the profile to look up
     * @param {string} [zoneFileLookupURL=null] - The URL
     * to use for zonefile lookup. If falsey, lookupProfile will use the
     * blockstack.js [[getNameInfo]] function.
     * @returns {Promise} that resolves to a profile object
     */
    suspend fun lookupProfile(username: String, zoneFileLookupUrl: String?): Profile {
        val request = buildLookupProfileRequest(username, zoneFileLookupUrl)
        val response = callFactory.newCall(request).execute()
        if (response.code() == 200) {
            return Profile(JSONObject(response.body()!!.string()))
        } else {
            return Profile(JSONObject())
        }
    }

    private fun buildLookupProfileRequest(username: String, zoneFileLookupUrl: String?): Request {
        val url = zoneFileLookupUrl ?: "$DEFAULT_CORE_API_ENDPOINT/v1/names/$username"
        val builder = Request.Builder()
                .url(url)
        builder.addHeader("Referrer-Policy", "no-referrer")
        return builder.build()

    }

    suspend fun verifyToken(token: String): Boolean {
        val tokenTriple = decodeToken(token)
        return isExpirationDateValid(tokenTriple.second) &&
                isIssuanceDateValid(tokenTriple.second) &&
                doSignaturesMatchPublicKeys(token, tokenTriple.second) &&
                doPublicKeysMatchIssuer(tokenTriple.second) &&
                doPublicKeysMatchUsername(tokenTriple.second, null)
    }

    private fun doPublicKeysMatchIssuer(payload: JSONObject): Boolean {
        val issAddress = DIDs.getAddressFromDID(payload.getString("iss"))
        val publicKey = payload.getJSONArray("public_keys").getString(0)
        val pkAddress = publicKey.toBtcAddress()
        return issAddress == pkAddress
    }

    private suspend fun doSignaturesMatchPublicKeys(token: String, payload: JSONObject): Boolean {
        JWTTools().verify(token, false) // throws an exception if invalid
        return true

    }

    private suspend fun doPublicKeysMatchUsername(payload: JSONObject, nameLookupURL: String?): Boolean {
        val username = payload.optString("username")
        if (username == null || username.isEmpty()) {
            return true
        }
        val profile = lookupProfile(username, null)
        val nameOwningAddress = profile.json.optString("address")
        val addressFromIssuer = DIDs.getAddressFromDID(payload.optString("iss"))
        return !nameOwningAddress.isEmpty() && nameOwningAddress == addressFromIssuer
    }

    private fun isIssuanceDateValid(payload: JSONObject): Boolean {
        val expirationDate = Date(payload.getString("iat").toLong() * 1000)
        val now = Date()
        return now.after(expirationDate)
    }

    private fun isExpirationDateValid(payload: JSONObject): Boolean {
        val expirationDate = Date(payload.getString("exp").toLong() * 1000)
        val now = Date()
        return now.before(expirationDate)
    }

    fun decodeToken(token: String): Triple<JwtHeader, JSONObject, ByteArray> {

        //Split token by . from jwtUtils
        val (encodedHeader, encodedPayload, encodedSignature) = JWTUtils.splitToken(token)
        if (encodedHeader.isEmpty())
            throw InvalidJWTException("Header cannot be empty")
        else if (encodedPayload.isEmpty())
            throw InvalidJWTException("Payload cannot be empty")
        //Decode the pieces
        val headerString = String(encodedHeader.decodeBase64())
        val payloadString = String(encodedPayload.decodeBase64())
        val signatureBytes = encodedSignature.decodeBase64()

        try {
            //Parse Json
            val header = JwtHeader.fromJson(headerString)
            val payload = JSONObject(payloadString)
            return Triple(header, payload, signatureBytes)
        } catch (ex: JsonException) {
            throw JWTEncodingException("cannot parse the JWT($token)", ex)
        }
    }

    /**
     * Encrypt content
     *
     * @plainContent can be a String or ByteArray
     * @options defines how to encrypt
     * @return result object with `CipherObject` or error if encryption failed
     */
    fun encryptContent(plainContent: Any, options: CryptoOptions): Result<CipherObject> {
        val valid = plainContent is String || plainContent is ByteArray
        if (!valid) {
            throw IllegalArgumentException("encrypt content only supports String or ByteArray")
        }

        val isBinary = plainContent is ByteArray

        val contentString = if (isBinary) {
            Base64.encodeToString(plainContent as ByteArray, Base64.NO_WRAP)
        } else {
            plainContent as String
        }

        val result = Encryption().encryptWithPublicKey(contentString, options.publicKey)

        if (result.iv.isNotEmpty()) {
            return Result(CipherObject(result.iv, result.ephemPublicKey, result.ciphertext, result.mac, !isBinary))
        } else {
            return Result(null, "failed to encrypt")
        }
    }


    /**
     * Decrypt content
     * @cipherObject can be a String or ByteArray representing the cipherObject returned by  @see encryptContent
     * @binary flag indicating whether a ByteArray or String was encrypted
     * @options defines how to decrypt the cipherObject
     * @return result object with plain content as String or ByteArray depending on the given binary flag or error
     */
    fun decryptContent(cipherObject: Any, binary: Boolean, options: CryptoOptions): Result<Any> {

        val valid = cipherObject is String || cipherObject is ByteArray
        if (!valid) {
            throw IllegalArgumentException("decrypt content only supports (json) String or ByteArray not " + cipherObject::class.java)
        }

        val isByteArray = cipherObject is ByteArray
        val cipherObjectString = if (isByteArray) {
            Base64.encodeToString(cipherObject as ByteArray, Base64.NO_WRAP)
        } else {
            cipherObject as String
        }
        val cipher = CipherObject(JSONObject(cipherObjectString))
        val plainContent = Encryption().decryptWithPrivateKey(EncryptedResultForm(cipher.ephemeralPK, cipher.iv, cipher.mac, cipher.cipherText, options.privateKey))

        if (plainContent != null && !"null".equals(plainContent)) {
            if (!binary) {
                return Result(plainContent)
            } else {
                return Result(Base64.decode(plainContent, Base64.DEFAULT))
            }
        } else {
            return Result(null, "failed to decrypt")
        }
    }

    companion object {
        val TAG = BlockstackSession2::class.java.simpleName
    }
}

private fun JSONObject.toMap(): Map<String, Any> {
    val result = mutableMapOf<String, Any>()
    this.keys().forEach {
        val value = this.get(it)
        result.put(it, when (value) {
            null -> value
            is Number -> value
            is String -> value
            is Boolean -> value
            is JSONObject -> value.toMap()
            is JSONArray -> (0..value.length()).asSequence().map { (value.getJSONObject(it)).toMap() }
            else -> {
                throw InvalidParameterException("$it has unsupported type $value")
            }
        })
    }
    return result
}

private fun String.toBtcAddress(): String {
    val sha256 = this.hexToByteArray().sha256()
    val hash160 = sha256.ripemd160()
    val extended = "00${hash160.toNoPrefixHexString()}"
    val checksum = checksum(extended)
    val address = (extended + checksum).hexToByteArray().encodeToBase58String()
    return address
}

private fun checksum(extended: String): String {
    val checksum = extended.hexToByteArray().sha256().sha256()
    val shortPrefix = checksum.slice(0..3)
    return shortPrefix.toNoPrefixHexString()
}


fun ECKeyPair.toHexPublicKey64(): String {
    return this.getCompressedPublicKey().toNoPrefixHexString()
}

fun ECKeyPair.toBtcAddress(): String {
    val publicKey = toHexPublicKey64()
    val sha256 = publicKey.hexToByteArray().sha256()
    val hash160 = sha256.ripemd160()
    val extended = "00${hash160.toNoPrefixHexString()}"
    val checksum = checksum(extended)
    val address = (extended + checksum).hexToByteArray().encodeToBase58String()
    return address
}

fun PublicKey.toBtcAddress(): String {
    //add the uncompressed prefix
    val ret = this.key.toBytesPadded(PUBLIC_KEY_SIZE + 1)
    ret[0] = 4
    val point = org.kethereum.crypto.CURVE.decodePoint(ret)
    val compressedPublicKey = point.encoded(true).toNoPrefixHexString()
    val sha256 = compressedPublicKey.hexToByteArray().sha256()
    val hash160 = sha256.ripemd160()
    val extended = "00${hash160.toNoPrefixHexString()}"
    val checksum = checksum(extended)
    val address = (extended + checksum).hexToByteArray().encodeToBase58String()
    return address
}
