package com.ptzstudio.tapo.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Câmera Tapo de mentira para os testes: faz o lado da câmera dos dois logins (antigo e TPAP) com as mesmas
 * contas, confere a senha, a sequência e a marca de cada pedido, e responde às funções usadas pelo app.
 * Serve para provar que o cliente segue o protocolo; não substitui o teste na câmera de verdade.
 */
class FakeTapoCamera(
    private val mode: TapoAuth,
    private val password: String,
) : TapoHttp {
    val calls = mutableListOf<JSONObject>()
    var loginAttempts = 0
    val presets = mutableListOf(TapoPreset("1", "Altar"), TapoPreset("3", "Púlpito"))
    var light = false
    /** Força a próxima resposta a dizer "sessão expirada" (para testar o novo login automático). */
    var expireNext = false

    private val rnd = SecureRandom()
    private var stok = ""
    private var seq = 0

    // Antigo
    private val hashed = LegacyAuth.hashedMd5(password)
    private var cnonce = ""
    private var nonce = ""

    // TPAP
    private val prefix = "\$5\$saltyfake\$"
    private val credential = ExtraCrypt.sha256Crypt(TapoHash.sha256HexUpper(password), prefix) // aceita só o 2º candidato
    private val devSalt = ByteArray(16) { (it * 3).toByte() }
    private val iterations = 1000
    private var w0 = BigInteger.ZERO
    private var w1 = BigInteger.ZERO
    private var y = BigInteger.ONE
    private var yb = ByteArray(0)
    private var userRandom = ByteArray(0)
    private val devRandom = ByteArray(32) { (it + 7).toByte() }
    private var key = ByteArray(0)
    private var baseNonce = ByteArray(0)

    private fun reply(o: JSONObject, status: Int = 200) = HttpReply(status, o.toString().toByteArray())

    override fun post(path: String, body: ByteArray, headers: Map<String, String>): HttpReply {
        // Como algumas câmeras de verdade: "\/" no texto não é desfeito, então o base64 com "/" chega estragado.
        if (path == "/" && String(body).contains("\\/")) return reply(JSONObject().put("error_code", -40401))
        if (path == "/") return login(JSONObject(String(body)))
        if (path != "/stok=$stok/ds" || stok.isEmpty()) return reply(JSONObject().put("error_code", -40401))
        if (expireNext) {
            expireNext = false
            stok = ""
            return reply(JSONObject().put("error_code", -40401))
        }
        return when (mode) {
            TapoAuth.LEGACY -> legacyRequest(String(body), headers)
            TapoAuth.TPAP -> tpapRequest(body)
            TapoAuth.PLAIN -> reply(handle(JSONObject(String(body))))
        }
    }

    // ---------------- Login ----------------

    private fun login(o: JSONObject): HttpReply {
        val p = o.getJSONObject("params")
        if (p.optString("sub_method") == "discover") {
            return when (mode) {
                TapoAuth.TPAP -> reply(JSONObject().put("error_code", 0).put("result", JSONObject().put("tpap", JSONObject().put("pake", JSONArray().put(2)).put("user_hash_type", 0))))
                else -> reply(JSONObject().put("error_code", -40210))
            }
        }
        return when (mode) {
            TapoAuth.LEGACY -> legacyLogin(p)
            TapoAuth.TPAP -> tpapLogin(p)
            TapoAuth.PLAIN -> {
                loginAttempts++
                if (p.optString("password") == hashed) {
                    stok = "plainstok"
                    reply(JSONObject().put("error_code", 0).put("result", JSONObject().put("stok", stok)))
                } else reply(JSONObject().put("error_code", -40411))
            }
        }
    }

    private fun legacyLogin(p: JSONObject): HttpReply {
        if (p.optString("encrypt_type") != "3") return reply(JSONObject().put("error_code", -40210))
        if (!p.has("digest_passwd")) {
            loginAttempts++
            cnonce = p.getString("cnonce")
            nonce = TapoHash.hex(ByteArray(8).also { rnd.nextBytes(it) }).uppercase()
            val confirm = TapoHash.sha256HexUpper(cnonce + hashed + nonce) + nonce + cnonce
            return reply(
                JSONObject().put("error_code", -40413).put(
                    "result",
                    JSONObject().put("data", JSONObject().put("code", -40413).put("encrypt_type", JSONArray().put("3")).put("nonce", nonce).put("device_confirm", confirm)),
                ),
            )
        }
        if (p.getString("digest_passwd") != LegacyAuth.digestPasswd(hashed, cnonce, nonce)) return reply(JSONObject().put("error_code", -40411), 401)
        stok = "legacystok"
        seq = 500
        return reply(JSONObject().put("error_code", 0).put("result", JSONObject().put("stok", stok).put("user_group", "root").put("start_seq", seq)))
    }

    private fun tpapLogin(p: JSONObject): HttpReply {
        when (p.optString("sub_method")) {
            "pake_register" -> {
                loginAttempts++
                require(p.getString("username") == TapoHash.md5Hex("admin"))
                userRandom = TapoHash.unb64(p.getString("user_random"))
                val dk = TapoHash.pbkdf2(credential, devSalt, iterations, 80)
                w0 = BigInteger(1, dk.copyOfRange(0, 40)).mod(P256.N)
                w1 = BigInteger(1, dk.copyOfRange(40, 80)).mod(P256.N)
                y = BigInteger(1, ByteArray(32).also { rnd.nextBytes(it) }).mod(P256.N - BigInteger.ONE) + BigInteger.ONE
                val bigY = P256.add(P256.mul(y, P256.G), P256.mul(w0, Spake2pClient.NP))!!
                yb = P256.encode(bigY)
                return reply(
                    JSONObject().put("error_code", 0).put(
                        "result",
                        JSONObject().put("dev_salt", TapoHash.b64(devSalt)).put("iterations", iterations)
                            .put("dev_share", TapoHash.b64(yb)).put("dev_random", TapoHash.b64(devRandom))
                            .put("extra_crypt", JSONObject().put("type", "password_shadow").put("params", JSONObject().put("passwd_id", 5).put("passwd_prefix", prefix))),
                    ),
                )
            }
            "pake_share" -> {
                val xb = TapoHash.unb64(p.getString("user_share"))
                val x = P256.decode(xb)
                val z = P256.mul(y, P256.add(x, P256.neg(P256.mul(w0, Spake2pClient.M))))!!
                val v = P256.mul(y, P256.mul(w1, P256.G))!!
                val context = TapoHash.sha256("PAKE V1".toByteArray() + userRandom + devRandom)
                val chunks = listOf(
                    context, ByteArray(0), ByteArray(0), P256.encode(Spake2pClient.M), P256.encode(Spake2pClient.NP),
                    xb, yb, P256.encode(z), P256.encode(v), P256.scalarBytes(w0),
                )
                val t = java.io.ByteArrayOutputStream()
                for (c in chunks) {
                    for (k in 0 until 8) t.write(((c.size.toLong() ushr (8 * k)) and 0xFF).toInt())
                    t.write(c)
                }
                val ke = TapoHash.sha256(t.toByteArray())
                val conf = TapoHash.hkdf(ke, null, "ConfirmationKeys".toByteArray(), 64)
                val expected = TapoHash.hmacSha256(conf.copyOfRange(0, 32), yb)
                if (!expected.contentEquals(TapoHash.unb64(p.getString("user_confirm")))) {
                    return reply(JSONObject().put("error_code", -40401))
                }
                val shared = TapoHash.hkdf(ke, null, "SharedKey".toByteArray(), 32)
                key = TapoHash.hkdf(shared, "tp-kdf-salt-aes128-key".toByteArray(), "tp-kdf-info-aes128-key".toByteArray(), 32).copyOf(16)
                baseNonce = TapoHash.hkdf(shared, "tp-kdf-salt-aes128-iv".toByteArray(), "tp-kdf-info-aes128-iv".toByteArray(), 32).copyOf(12)
                stok = "tpapstok"
                seq = 77
                return reply(
                    JSONObject().put("error_code", 0).put(
                        "result",
                        JSONObject().put("dev_confirm", TapoHash.b64(TapoHash.hmacSha256(conf.copyOfRange(32, 64), xb)))
                            .put("stok", stok).put("start_seq", seq).put("expired", 3600),
                    ),
                )
            }
        }
        // Login antigo numa câmera TPAP: recusa como as de firmware novo.
        return reply(JSONObject().put("error_code", -40211))
    }

    // ---------------- Pedidos ----------------

    private fun legacyRequest(body: String, headers: Map<String, String>): HttpReply {
        val s = headers["Seq"]?.toIntOrNull() ?: return reply(JSONObject().put("error_code", -40210))
        if (s != seq) return reply(JSONObject().put("error_code", -40413))
        if (headers["Tapo_tag"] != LegacyAuth.tag(hashed, cnonce, body, s)) return reply(JSONObject().put("error_code", -1))
        seq++
        val lsk = LegacyAuth.token("lsk", hashed, cnonce, nonce)
        val ivb = LegacyAuth.token("ivb", hashed, cnonce, nonce)
        val enc = JSONObject(body).getJSONObject("params").getString("request")
        val req = JSONObject(String(LegacyAuth.decrypt(lsk, ivb, TapoHash.unb64(enc))))
        val resp = handle(req).toString().toByteArray()
        return reply(JSONObject().put("error_code", 0).put("result", JSONObject().put("response", TapoHash.b64(LegacyAuth.encrypt(lsk, ivb, resp)))))
    }

    private fun tpapRequest(body: ByteArray): HttpReply {
        val s = ((body[0].toInt() and 0xFF) shl 24) or ((body[1].toInt() and 0xFF) shl 16) or ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        if (s != seq) return reply(JSONObject().put("error_code", -40401))
        seq++
        val plain = AesCcm.open(key, baseNonce.copyOf(8) + TapoClient.intBytes(s), body.copyOfRange(4, body.size))
            ?: return reply(JSONObject().put("error_code", -40401))
        val resp = handle(JSONObject(String(plain))).toString().toByteArray()
        val rs = s + 1000
        return HttpReply(200, TapoClient.intBytes(rs) + AesCcm.seal(key, baseNonce.copyOf(8) + TapoClient.intBytes(rs), resp))
    }

    private fun handle(req: JSONObject): JSONObject {
        require(req.getString("method") == "multipleRequest")
        val list = req.getJSONObject("params").getJSONArray("requests")
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            calls += r
            out.put(answer(r.getString("method"), r.optJSONObject("params") ?: JSONObject()))
        }
        return JSONObject().put("error_code", 0).put("result", JSONObject().put("responses", out))
    }

    private fun ok(method: String, result: JSONObject = JSONObject()) =
        JSONObject().put("method", method).put("result", result).put("error_code", 0)

    private fun answer(method: String, p: JSONObject): JSONObject = when (method) {
        "getDeviceInfo" -> ok(method, JSONObject().put("device_info", JSONObject().put("basic_info", JSONObject().put("device_model", "C320WS").put("device_alias", "Igreja").put("sw_version", "1.3.4"))))
        "motorMove" -> ok(method)
        "getPresetConfig" -> ok(
            method,
            JSONObject().put("preset", JSONObject().put("preset", JSONObject()
                .put("id", JSONArray(presets.map { it.id })).put("name", JSONArray(presets.map { it.name })))),
        )
        "motorMoveToPreset" -> {
            val id = p.getJSONObject("preset").getJSONObject("goto_preset").getString("id")
            if (presets.any { it.id == id }) ok(method) else JSONObject().put("method", method).put("error_code", -64302)
        }
        "addMotorPostion" -> {
            val name = p.getJSONObject("preset").getJSONObject("set_preset").getString("name")
            val next = (presets.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1
            presets += TapoPreset(next.toString(), name)
            ok(method)
        }
        "deletePreset" -> {
            val ids = p.getJSONObject("preset").getJSONObject("remove_preset").getJSONArray("id")
            val del = (0 until ids.length()).map { ids.getString(it) }.toSet()
            presets.removeAll { it.id in del }
            ok(method)
        }
        "getWhitelampStatus" -> ok(method, JSONObject().put("status", if (light) 1 else 0).put("rest_time", 0))
        "reverseWhitelampStatus" -> {
            light = !light
            ok(method)
        }
        else -> JSONObject().put("method", method).put("error_code", -40106)
    }
}
