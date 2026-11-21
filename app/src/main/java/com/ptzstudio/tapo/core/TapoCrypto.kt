package com.ptzstudio.tapo.core

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Contas usadas pelo login local das câmeras Tapo. Tudo aqui é cálculo puro (sem rede), conferido nos
 * testes com valores gerados pela biblioteca pytapo.
 */
object TapoHash {
    fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)
    fun sha1(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(b)
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    fun md5Hex(s: String): String = hex(md5(s.toByteArray()))
    fun md5HexUpper(s: String): String = md5Hex(s).uppercase()
    fun sha256Hex(s: String): String = hex(sha256(s.toByteArray()))
    fun sha256HexUpper(s: String): String = sha256Hex(s).uppercase()
    fun sha1Hex(s: String): String = hex(sha1(s.toByteArray()))

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (sal nulo = 32 zeros). */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt ?: ByteArray(32), ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun pbkdf2(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)).encoded
    }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private const val HEX = "0123456789abcdef"
}

/** Login "encrypt_type 3" (firmware mais antigo) e o canal securePassthrough (AES-CBC). */
object LegacyAuth {
    /** Senha "hasheada" nos dois jeitos que a câmera pode usar. */
    fun hashedMd5(password: String) = TapoHash.md5HexUpper(password)
    fun hashedSha256(password: String) = TapoHash.sha256HexUpper(password)

    private fun confirmBase(cnonce: String, hashed: String, nonce: String) =
        TapoHash.sha256HexUpper(cnonce + hashed + nonce)

    /** Confere a prova da câmera. Devolve a senha hasheada que bateu (SHA-256 ou MD5) ou nulo se a senha está errada. */
    fun matchDeviceConfirm(password: String, cnonce: String, nonce: String, deviceConfirm: String): String? {
        val sha = hashedSha256(password)
        if (deviceConfirm == confirmBase(cnonce, sha, nonce) + nonce + cnonce) return sha
        val md5 = hashedMd5(password)
        if (deviceConfirm == confirmBase(cnonce, md5, nonce) + nonce + cnonce) return md5
        return null
    }

    fun digestPasswd(hashed: String, cnonce: String, nonce: String): String =
        TapoHash.sha256HexUpper(hashed + cnonce + nonce) + cnonce + nonce

    /** Chave (lsk) ou vetor (ivb) da sessão, 16 bytes. */
    fun token(type: String, hashed: String, cnonce: String, nonce: String): ByteArray =
        TapoHash.sha256((type + cnonce + nonce + confirmBase(cnonce, hashed, nonce)).toByteArray()).copyOf(16)

    /** Cabeçalho Tapo_tag de cada pedido: depende do texto exato enviado e do número de sequência. */
    fun tag(hashed: String, cnonce: String, body: String, seq: Int): String {
        val first = TapoHash.sha256HexUpper(hashed + cnonce)
        return TapoHash.sha256HexUpper(first + body + seq.toString())
    }

    fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}

/**
 * AES-CCM (marca de 16 bytes, sem dados associados). O Android não traz CCM pronto, então é montado aqui
 * em cima do AES puro, seguindo a RFC 3610.
 */
object AesCcm {
    private fun ecb(key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    private fun counterBlock(nonce: ByteArray, l: Int, i: Int): ByteArray {
        val a = ByteArray(16)
        a[0] = (l - 1).toByte()
        System.arraycopy(nonce, 0, a, 1, nonce.size)
        for (k in 0 until l) a[15 - k] = ((i ushr (8 * k)) and 0xFF).toByte()
        return a
    }

    private fun mac(aes: Cipher, nonce: ByteArray, l: Int, msg: ByteArray, tagLen: Int): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = ((((tagLen - 2) / 2) shl 3) or (l - 1)).toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        for (k in 0 until l) b0[15 - k] = ((msg.size ushr (8 * k)) and 0xFF).toByte()
        var x = aes.doFinal(b0)
        var off = 0
        while (off < msg.size) {
            val blk = ByteArray(16)
            val n = minOf(16, msg.size - off)
            System.arraycopy(msg, off, blk, 0, n)
            for (k in 0 until 16) blk[k] = (blk[k].toInt() xor x[k].toInt()).toByte()
            x = aes.doFinal(blk)
            off += 16
        }
        return x.copyOf(tagLen)
    }

    private fun ctr(aes: Cipher, nonce: ByteArray, l: Int, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var off = 0
        var i = 1
        while (off < data.size) {
            val s = aes.doFinal(counterBlock(nonce, l, i))
            val n = minOf(16, data.size - off)
            for (k in 0 until n) out[off + k] = (data[off + k].toInt() xor s[k].toInt()).toByte()
            off += 16
            i++
        }
        return out
    }

    /** Devolve texto cifrado + marca (16 bytes), igual ao encrypt()+digest() do pycryptodome. */
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, tagLen: Int = 16): ByteArray {
        val l = 15 - nonce.size
        val aes = ecb(key)
        val t = mac(aes, nonce, l, plain, tagLen)
        val s0 = aes.doFinal(counterBlock(nonce, l, 0))
        val tag = ByteArray(tagLen) { (t[it].toInt() xor s0[it].toInt()).toByte() }
        return ctr(aes, nonce, l, plain) + tag
    }

    /** Abre texto cifrado + marca. Nulo se a marca não confere (resposta adulterada ou chave errada). */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, tagLen: Int = 16): ByteArray? {
        if (sealed.size < tagLen) return null
        val l = 15 - nonce.size
        val aes = ecb(key)
        val body = sealed.copyOf(sealed.size - tagLen)
        val plain = ctr(aes, nonce, l, body)
        val t = mac(aes, nonce, l, plain, tagLen)
        val s0 = aes.doFinal(counterBlock(nonce, l, 0))
        var diff = 0
        for (k in 0 until tagLen) diff = diff or ((t[k].toInt() xor s0[k].toInt()) xor sealed[body.size + k].toInt())
        return if ((diff and 0xFF) == 0) plain else null
    }
}

/** Transformações de senha pedidas pela câmera no login novo (extra_crypt). */
object ExtraCrypt {
    private const val B64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val ORDER = arrayOf(
        intArrayOf(0, 10, 20), intArrayOf(21, 1, 11), intArrayOf(12, 22, 2), intArrayOf(3, 13, 23),
        intArrayOf(24, 4, 14), intArrayOf(15, 25, 5), intArrayOf(6, 16, 26), intArrayOf(27, 7, 17),
        intArrayOf(18, 28, 8), intArrayOf(9, 19, 29),
    )

    private fun to64(v0: Int, n: Int): String {
        var v = v0
        val sb = StringBuilder()
        repeat(n) { sb.append(B64[v and 0x3F]); v = v ushr 6 }
        return sb.toString()
    }

    private fun repeat(d: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = d[i % d.size]
        return out
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    /** SHA-256-crypt ("$5$", Drepper), como o app oficial: devolve "$5$[rounds=N$]sal$hash". */
    fun sha256Crypt(key: String, prefix: String): String {
        var rest = if (prefix.startsWith("$5$")) prefix.substring(3) else prefix
        var rounds = 5000
        var explicit = false
        if (rest.startsWith("rounds=")) {
            val dollar = rest.indexOf('$')
            if (dollar >= 0 && dollar < rest.length - 1) {
                rounds = rest.substring(7, dollar).toLong().coerceIn(1000, 999_999_999).toInt()
                rest = rest.substring(dollar + 1)
                explicit = true
            }
        }
        val end = rest.indexOf('$')
        val saltEnd = minOf(if (end > 0) end else rest.length, 16)
        val salt = rest.substring(0, saltEnd)
        val k = key.toByteArray()
        val s = salt.toByteArray()
        val md = { MessageDigest.getInstance("SHA-256") }

        val b = md().apply { update(k); update(s); update(k) }.digest()
        val aMd = md().apply { update(k); update(s); update(repeat(b, k.size)) }
        var n = k.size
        while (n != 0) {
            aMd.update(if (n and 1 != 0) b else k)
            n = n ushr 1
        }
        val a = aMd.digest()
        val dp = md().apply { repeat(k.size) { update(k) } }.digest()
        val p = repeat(dp, k.size)
        val ds = md().apply { repeat(16 + u(a[0])) { update(s) } }.digest()
        val sb = repeat(ds, s.size)

        var c = a
        for (i in 0 until rounds) {
            val h = md()
            h.update(if (i and 1 != 0) p else c)
            if (i % 3 != 0) h.update(sb)
            if (i % 7 != 0) h.update(p)
            h.update(if (i and 1 != 0) c else p)
            c = h.digest()
        }
        val enc = StringBuilder()
        for (o in ORDER) enc.append(to64((u(c[o[0]]) shl 16) or (u(c[o[1]]) shl 8) or u(c[o[2]]), 4))
        enc.append(to64((u(c[31]) shl 8) or u(c[30]), 3))
        val roundsPart = if (explicit) "rounds=$rounds$" else ""
        return "$5$$roundsPart$salt$$enc"
    }

    /** [type] e [params] vêm da resposta do pake_register. */
    fun apply(passcode: String, type: String?, passwdId: Int?, passwdPrefix: String?): String {
        if (type.isNullOrEmpty()) return passcode
        if (type.lowercase() != "password_shadow") throw TapoException("Tipo de senha da câmera não suportado ($type)")
        return when (passwdId) {
            5 -> sha256Crypt(passcode, passwdPrefix ?: "")
            2 -> TapoHash.sha1Hex(passcode)
            else -> throw TapoException("Tipo de senha da câmera não suportado (id $passwdId)")
        }
    }
}

/** Curva P-256 em coordenadas afins (só o necessário para o SPAKE2+). */
object P256 {
    val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    val A: BigInteger = P - BigInteger.valueOf(3)
    val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    val G = Pt(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16),
    )

    data class Pt(val x: BigInteger, val y: BigInteger)

    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    fun add(p1: Pt?, p2: Pt?): Pt? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x && (p1.y + p2.y).mod(P).signum() == 0) return null
        val m = if (p1 == p2) {
            (THREE * p1.x * p1.x + A).multiply((TWO * p1.y).modInverse(P)).mod(P)
        } else {
            (p2.y - p1.y).multiply((p2.x - p1.x).mod(P).modInverse(P)).mod(P)
        }
        val x3 = (m * m - p1.x - p2.x).mod(P)
        return Pt(x3, (m * (p1.x - x3) - p1.y).mod(P))
    }

    fun mul(k0: BigInteger, p: Pt?): Pt? {
        var k = k0.mod(N)
        var result: Pt? = null
        var addend = p
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    fun neg(p: Pt?): Pt? = p?.let { Pt(it.x, (-it.y).mod(P)) }

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun encode(p: Pt): ByteArray = byteArrayOf(4) + fixed(p.x) + fixed(p.y)

    fun decode(b: ByteArray): Pt {
        val x = BigInteger(1, b.copyOfRange(1, 33))
        return when (b[0].toInt()) {
            4 -> Pt(x, BigInteger(1, b.copyOfRange(33, 65)))
            2, 3 -> {
                var y = (x.pow(3) + A * x + B).mod(P).modPow((P + BigInteger.ONE).shiftRight(2), P)
                if (y.testBit(0) != (b[0].toInt() and 1 == 1)) y = P - y
                Pt(x, y)
            }
            else -> throw TapoException("Ponto inválido da câmera")
        }
    }

    fun scalarBytes(v: BigInteger): ByteArray = fixed(v)
}

/** Lado do celular numa troca SPAKE2+ (pake_register → pake_share) do login novo das Tapo. */
class Spake2pClient(
    devSalt: ByteArray,
    iterations: Int,
    devShare: ByteArray,
    userRandom: ByteArray,
    devRandom: ByteArray,
    credential: String,
    randomScalar: BigInteger,
) {
    val userShare: ByteArray
    val userConfirm: ByteArray
    private val kcb: ByteArray
    private val sharedKey: ByteArray

    init {
        val n = P256.N
        val dk = TapoHash.pbkdf2(credential, devSalt, iterations, 80)
        val w0 = BigInteger(1, dk.copyOfRange(0, 40)).mod(n)
        val w1 = BigInteger(1, dk.copyOfRange(40, 80)).mod(n)
        val y = P256.decode(devShare)
        val x = randomScalar.mod(n - BigInteger.ONE) + BigInteger.ONE
        val bigX = P256.add(P256.mul(x, P256.G), P256.mul(w0, M))!!
        val h = P256.add(y, P256.neg(P256.mul(w0, NP)))
        userShare = P256.encode(bigX)
        val yb = P256.encode(y)
        val context = TapoHash.sha256("PAKE V1".toByteArray() + userRandom + devRandom)
        val transcript = lenPrefixed(
            context, ByteArray(0), ByteArray(0), P256.encode(M), P256.encode(NP), userShare, yb,
            P256.encode(P256.mul(x, h)!!), P256.encode(P256.mul(w1, h)!!), P256.scalarBytes(w0),
        )
        val ke = TapoHash.sha256(transcript)
        val conf = TapoHash.hkdf(ke, null, "ConfirmationKeys".toByteArray(), 64)
        kcb = conf.copyOfRange(32, 64)
        userConfirm = TapoHash.hmacSha256(conf.copyOfRange(0, 32), yb)
        sharedKey = TapoHash.hkdf(ke, null, "SharedKey".toByteArray(), 32)
    }

    fun deviceConfirmOk(devConfirm: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(devConfirm, TapoHash.hmacSha256(kcb, userShare))

    /** Chave AES-128 e nonce base (12 bytes) do canal cifrado. */
    fun sessionKeyAndNonce(): Pair<ByteArray, ByteArray> {
        val key = TapoHash.hkdf(sharedKey, "tp-kdf-salt-aes128-key".toByteArray(), "tp-kdf-info-aes128-key".toByteArray(), 32).copyOf(16)
        val nonce = TapoHash.hkdf(sharedKey, "tp-kdf-salt-aes128-iv".toByteArray(), "tp-kdf-info-aes128-iv".toByteArray(), 32).copyOf(12)
        return key to nonce
    }

    companion object {
        val M = P256.decode(TapoHash.unhex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
        val NP = P256.decode(TapoHash.unhex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

        private fun lenPrefixed(vararg chunks: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (c in chunks) {
                val len = c.size.toLong()
                for (k in 0 until 8) out.write(((len ushr (8 * k)) and 0xFF).toInt())
                out.write(c)
            }
            return out.toByteArray()
        }
    }
}

class TapoException(message: String, val code: Int? = null) : Exception(message)
