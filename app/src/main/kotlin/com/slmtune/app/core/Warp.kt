package com.slmtune.app.core

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Free Cloudflare WARP: registers a device (the call the official app makes) and returns a WireGuard server. */
object Warp {
    suspend fun register(): Server = withContext(Dispatchers.IO) {
        val (priv, pub) = X25519.keyPair()
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = """{"key":"$pub","install_id":"","fcm_token":"","tos":"$tos","model":"PC","type":"Android","locale":"en_US"}"""
        val c = URL("https://api.cloudflareclient.com/v0a2158/reg").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "okhttp/3.12.1")
        c.setRequestProperty("CF-Client-Version", "a-6.30-3596")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) error("Cloudflare не зарегистрировал WARP (HTTP ${c.responseCode}). Попробуйте позже.")
        val root = Store.json.parseToJsonElement(c.inputStream.use { String(it.readBytes()) }).jsonObject
        val cfg = root["config"]!!.jsonObject
        val peer = cfg["peers"]!!.jsonArray[0].jsonObject
        val addr = cfg["interface"]?.jsonObject?.get("addresses") as? JsonObject
        val v4 = addr?.get("v4")?.jsonPrimitive?.content ?: "172.16.0.2"
        val v6 = addr?.get("v6")?.jsonPrimitive?.content ?: ""
        val reserved = runCatching {
            Base64.getDecoder().decode(cfg["client_id"]!!.jsonPrimitive.content).take(3).joinToString(",") { (it.toInt() and 0xFF).toString() }
        }.getOrDefault("")
        val params = mutableMapOf(
            "publickey" to peer["public_key"]!!.jsonPrimitive.content,
            "address" to if (v6.isNotEmpty()) "$v4/32,$v6/128" else "$v4/32",
            "mtu" to "1280", "warp" to "1",
        )
        if (reserved.isNotEmpty()) params["reserved"] = reserved
        Server(name = "Cloudflare WARP", kind = "wireguard", server = "162.159.192.1", port = 2408, password = priv, params = params)
    }
}

/** Curve25519 key agreement (RFC 7748), just enough to make a WireGuard key pair. */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)

    private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())
    private fun toLe(x: BigInteger): ByteArray {
        val be = x.mod(P).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32).also { out -> be.reversedArray().copyInto(out, 0, 0, minOf(32, be.size)) }
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val scalar = k.copyOf().also { it[0] = (it[0].toInt() and 248).toByte(); it[31] = ((it[31].toInt() and 127) or 64).toByte() }
        val x1 = le(u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() })
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = (scalar[t / 8].toInt() shr (t % 8)) and 1
            swap = swap xor kt
            if (swap == 1) { x2 = x3.also { x3 = x2 }; z2 = z3.also { z3 = z2 } }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return toLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)))
    }

    /** (private, public) in base64, the WireGuard format. */
    fun keyPair(): Pair<String, String> {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = ((priv[31].toInt() and 127) or 64).toByte()
        val base = ByteArray(32).also { it[0] = 9 }
        val pub = scalarMult(priv, base)
        return Base64.getEncoder().encodeToString(priv) to Base64.getEncoder().encodeToString(pub)
    }
}
