package com.ptzstudio.tapo.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom

/** Resposta crua de um POST para a câmera. */
class HttpReply(val status: Int, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

/** Como o cliente fala HTTPS com a câmera (no celular: [com.ptzstudio.tapo.AndroidTapoHttp]; nos testes: uma câmera falsa). */
fun interface TapoHttp {
    fun post(path: String, body: ByteArray, headers: Map<String, String>): HttpReply
}

/** Jeito de login que a câmera usa. */
enum class TapoAuth(val label: String) {
    /** Firmware mais antigo: login "encrypt_type 3" e pedidos cifrados com AES-CBC. */
    LEGACY("Login seguro (firmware antigo)"),
    /** Firmware novo: login SPAKE2+ e pedidos cifrados com AES-CCM. */
    TPAP("Login TPAP (firmware novo)"),
    /** Firmware muito antigo, sem cifra nos pedidos. */
    PLAIN("Login simples (firmware muito antigo)"),
}

/**
 * Fala com uma câmera Tapo pela rede local (porta 443), com a senha da conta Tapo (TP-Link).
 * Porte para Kotlin do que a biblioteca pytapo faz. Tudo é síncrono: chame fora da thread da tela.
 */
class TapoClient(
    private val http: TapoHttp,
    private val password: String,
    private val random: SecureRandom = SecureRandom(),
) {
    @Volatile var auth: TapoAuth? = null
        private set

    // Sessão
    private var stok: String? = null
    private var seq = 0
    private var expiresAtMs = Long.MAX_VALUE
    // Firmware antigo
    private var cnonce = ""
    private var hashed = ""
    private var lsk = ByteArray(0)
    private var ivb = ByteArray(0)
    // Firmware novo
    private var tpapInfo: JSONObject? = null
    private var key = ByteArray(0)
    private var baseNonce = ByteArray(0)
    private var passcodeIndex: Int? = null

    private fun headers(contentType: String = "application/json; charset=UTF-8", extra: Map<String, String> = emptyMap()) =
        mapOf(
            "Accept" to "application/json",
            "User-Agent" to "Tapo CameraClient Android",
            "requestByApp" to "true",
            "Content-Type" to contentType,
        ) + extra

    private fun postJson(path: String, o: JSONObject, extra: Map<String, String> = emptyMap()): JSONObject {
        val r = http.post(path, o.toString().toByteArray(), headers(extra = extra))
        return parse(r)
    }

    private fun parse(r: HttpReply): JSONObject = try {
        JSONObject(r.text)
    } catch (_: Exception) {
        throw TapoException("Resposta estranha da câmera (HTTP ${r.status})")
    }

    private fun login(params: JSONObject) = postJson("/", JSONObject().put("method", "login").put("params", params))

    private fun hexNonce(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return TapoHash.hex(b).uppercase()
    }

    // ---------------- Descobrir o tipo de login ----------------

    private fun detect(): TapoAuth {
        auth?.let { return it }
        val d = try {
            login(JSONObject().put("sub_method", "discover"))
        } catch (e: TapoException) {
            null
        }
        val found = when {
            d != null && tpapFrom(d) != null -> {
                tpapInfo = tpapFrom(d)
                TapoAuth.TPAP
            }
            d != null && legacyOffered(d) -> TapoAuth.LEGACY
            else -> {
                // Mesma sondagem que o pytapo: o login antigo sem senha. -40211 = só aceita o login novo.
                val p = login(JSONObject().put("encrypt_type", "3").put("username", USER).put("cnonce", hexNonce(8)))
                when {
                    p.optInt("error_code") == -40211 -> TapoAuth.TPAP
                    legacyOffered(p) -> TapoAuth.LEGACY
                    else -> TapoAuth.PLAIN
                }
            }
        }
        auth = found
        return found
    }

    private fun tpapFrom(d: JSONObject): JSONObject? {
        val t = d.optJSONObject("result")?.optJSONObject("tpap") ?: return null
        val pake = t.optJSONArray("pake") ?: return null
        for (i in 0 until pake.length()) if (pake.optInt(i) == 2) return t
        return null
    }

    private fun legacyOffered(d: JSONObject): Boolean {
        if (d.optInt("error_code") != -40413) return false
        val enc = d.optJSONObject("result")?.optJSONObject("data")?.opt("encrypt_type") ?: return false
        return enc.toString().contains("3")
    }

    private fun lockout(o: JSONObject): Int {
        for (h in listOf(o.optJSONObject("error_info"), o.optJSONObject("data"), o.optJSONObject("result")?.optJSONObject("data"))) {
            val s = h?.optInt("sec_left") ?: 0
            if (s > 0) return s
        }
        return 0
    }

    private fun checkLockout(o: JSONObject) {
        val s = lockout(o)
        if (s > 0) throw TapoException("A câmera bloqueou novas tentativas de senha. Espere $s segundos.", -40404)
    }

    // ---------------- Login ----------------

    private fun loggedIn(nowMs: Long) = stok != null && nowMs < expiresAtMs

    fun dropSession() {
        stok = null
    }

    @Synchronized
    fun connect() {
        if (password.isEmpty()) throw TapoException("Falta a senha da conta Tapo")
        when (detect()) {
            TapoAuth.LEGACY -> loginLegacy()
            TapoAuth.TPAP -> loginTpap()
            TapoAuth.PLAIN -> loginPlain()
        }
    }

    private fun loginLegacy() {
        cnonce = hexNonce(8)
        val first = login(JSONObject().put("cnonce", cnonce).put("encrypt_type", "3").put("username", USER))
        checkLockout(first)
        val data = first.optJSONObject("result")?.optJSONObject("data")
        val nonce = data?.optString("nonce").orEmpty()
        val confirm = data?.optString("device_confirm").orEmpty()
        if (nonce.isEmpty() || confirm.isEmpty()) throw TapoException("A câmera não aceitou o login (código ${first.optInt("error_code")})", first.optInt("error_code"))
        hashed = LegacyAuth.matchDeviceConfirm(password, cnonce, nonce, confirm)
            ?: throw TapoException("Senha errada. Use a senha da conta Tapo (a mesma do aplicativo Tapo).", -40411)
        val second = login(
            JSONObject().put("cnonce", cnonce).put("encrypt_type", "3")
                .put("digest_passwd", LegacyAuth.digestPasswd(hashed, cnonce, nonce)).put("username", USER),
        )
        checkLockout(second)
        val r = second.optJSONObject("result")
        if (r == null || !r.has("start_seq") || r.optString("stok").isEmpty()) {
            throw TapoException("A câmera recusou o login (código ${second.optInt("error_code")})", second.optInt("error_code"))
        }
        if (r.has("user_group") && r.optString("user_group") != "root") {
            throw TapoException("Esta conta não é a dona da câmera. Use a conta Tapo em que a câmera foi cadastrada.")
        }
        lsk = LegacyAuth.token("lsk", hashed, cnonce, nonce)
        ivb = LegacyAuth.token("ivb", hashed, cnonce, nonce)
        seq = r.optInt("start_seq")
        stok = r.optString("stok")
        expiresAtMs = Long.MAX_VALUE
    }

    private fun loginPlain() {
        val res = login(JSONObject().put("hashed", true).put("password", LegacyAuth.hashedMd5(password)).put("username", USER))
        checkLockout(res)
        val s = res.optJSONObject("result")?.optString("stok").orEmpty()
        if (s.isEmpty()) throw TapoException("Senha errada ou login recusado (código ${res.optInt("error_code")})", res.optInt("error_code"))
        stok = s
        expiresAtMs = Long.MAX_VALUE
    }

    private fun tpapUser(): String =
        if (tpapInfo?.optInt("user_hash_type") == 1) TapoHash.sha256HexUpper(USER) else TapoHash.md5Hex(USER)

    private fun loginTpap() {
        val passcodes = listOf(TapoHash.md5Hex(password), TapoHash.sha256HexUpper(password))
        val known = passcodeIndex
        val attempts = if (known != null) listOf(known, known) else passcodes.indices.toList()
        var last: TapoException? = null
        for ((i, idx) in attempts.withIndex()) {
            if (i > 0 && idx == attempts[i - 1]) Thread.sleep(2000)
            try {
                loginTpapWith(passcodes[idx])
                passcodeIndex = idx
                return
            } catch (e: TapoException) {
                last = e
                if (e.code != -40401) throw e
            }
        }
        throw TapoException("Senha errada. Use a senha da conta Tapo (a mesma do aplicativo Tapo).", -40411)
    }

    private fun loginTpapWith(passcode: String) {
        val userRandom = ByteArray(32).also { random.nextBytes(it) }
        val reg = login(
            JSONObject().put("sub_method", "pake_register").put("username", tpapUser())
                .put("user_random", TapoHash.b64(userRandom)).put("cipher_suites", JSONArray().put(1))
                .put("encryption", JSONArray().put("aes_128_ccm")).put("passcode_type", "userpw"),
        )
        val r = reg.optJSONObject("result") ?: run {
            checkLockout(reg)
            throw TapoException("A câmera recusou o login (código ${reg.optInt("error_code")})", reg.optInt("error_code"))
        }
        val extra = r.optJSONObject("extra_crypt")
        val ep = extra?.optJSONObject("params")
        val credential = ExtraCrypt.apply(
            passcode, extra?.optString("type"),
            ep?.opt("passwd_id")?.toString()?.toIntOrNull(), ep?.optString("passwd_prefix"),
        )
        val scalar = ByteArray(32).also { random.nextBytes(it) }
        val client = Spake2pClient(
            devSalt = TapoHash.unb64(r.getString("dev_salt")),
            iterations = r.getString("iterations").toInt(),
            devShare = TapoHash.unb64(r.getString("dev_share")),
            userRandom = userRandom,
            devRandom = TapoHash.unb64(r.getString("dev_random")),
            credential = credential,
            randomScalar = BigInteger(1, scalar),
        )
        val share = login(
            JSONObject().put("sub_method", "pake_share")
                .put("user_share", TapoHash.b64(client.userShare)).put("user_confirm", TapoHash.b64(client.userConfirm)),
        )
        val sr = share.optJSONObject("result") ?: run {
            checkLockout(share)
            throw TapoException("Senha recusada (código ${share.optInt("error_code")})", share.optInt("error_code"))
        }
        if (!client.deviceConfirmOk(TapoHash.unb64(sr.getString("dev_confirm")))) {
            throw TapoException("A câmera não confirmou a senha", -40401)
        }
        val (k, n) = client.sessionKeyAndNonce()
        key = k
        baseNonce = n
        stok = sr.getString("stok")
        seq = sr.getString("start_seq").toInt()
        val life = sr.optString("expired").toLongOrNull() ?: 3600L
        expiresAtMs = System.currentTimeMillis() + life * 1000
    }

    // ---------------- Pedidos ----------------

    /** Manda um pedido completo (ex.: multipleRequest) e devolve a resposta já aberta. Refaz o login uma vez se a sessão caiu. */
    @Synchronized
    fun send(request: JSONObject): JSONObject {
        var lastError: Exception? = null
        for (attempt in 0..1) {
            try {
                if (!loggedIn(System.currentTimeMillis())) connect()
                val resp = sendOnce(request)
                val code = resp.optInt("error_code", 0)
                if (code in SESSION_ERRORS && attempt == 0) {
                    stok = null
                    continue
                }
                return resp
            } catch (e: TapoException) {
                lastError = e
                stok = null
                // Senha errada ou bloqueio: não insiste (cada tentativa conta para o bloqueio da câmera).
                if (attempt == 1 || e.code !in SESSION_ERRORS) throw e
            } catch (e: java.io.IOException) {
                lastError = e
                stok = null
                if (attempt == 1) throw TapoException("Câmera não respondeu (${e.message ?: "rede"})")
            }
        }
        throw (lastError as? TapoException) ?: TapoException("Câmera não respondeu")
    }

    private fun sendOnce(request: JSONObject): JSONObject = when (auth) {
        TapoAuth.LEGACY -> sendLegacy(request)
        TapoAuth.TPAP -> sendTpap(request)
        else -> postJson("/stok=$stok/ds", request)
    }

    private fun sendLegacy(request: JSONObject): JSONObject {
        val enc = LegacyAuth.encrypt(lsk, ivb, request.toString().toByteArray())
        val body = JSONObject().put("method", "securePassthrough")
            .put("params", JSONObject().put("request", TapoHash.b64(enc))).toString()
        val s = seq
        seq++
        val r = http.post(
            "/stok=$stok/ds", body.toByteArray(),
            headers(extra = mapOf("Seq" to s.toString(), "Tapo_tag" to LegacyAuth.tag(hashed, cnonce, body, s))),
        )
        val o = parse(r)
        val inner = o.optJSONObject("result")?.optString("response").orEmpty()
        if (inner.isEmpty()) return o
        val plain = try {
            LegacyAuth.decrypt(lsk, ivb, TapoHash.unb64(inner))
        } catch (_: Exception) {
            throw TapoException("Sessão expirou", -40401)
        }
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    private fun nonceFor(s: Int): ByteArray = baseNonce.copyOf(8) + intBytes(s)

    private fun sendTpap(request: JSONObject): JSONObject {
        val inner = if (request.optString("method") == "multipleRequest") request
        else JSONObject().put("method", "multipleRequest").put("params", JSONObject().put("requests", JSONArray().put(request)))
        val s = seq
        seq++
        val body = intBytes(s) + AesCcm.seal(key, nonceFor(s), inner.toString().toByteArray())
        val r = http.post("/stok=$stok/ds", body, headers(contentType = "application/octet-stream"))
        val raw = r.body
        if (raw.isNotEmpty() && raw[0] == '{'.code.toByte()) {
            val code = try { JSONObject(r.text).optInt("error_code") } catch (_: Exception) { 0 }
            throw TapoException("A câmera recusou o pedido (código $code)", code)
        }
        if (raw.size < 20) throw TapoException("Resposta curta da câmera (HTTP ${r.status})", -40401)
        val replySeq = ((raw[0].toInt() and 0xFF) shl 24) or ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val plain = AesCcm.open(key, nonceFor(replySeq), raw.copyOfRange(4, raw.size))
            ?: throw TapoException("Resposta da câmera não confere", -40401)
        val resp = JSONObject(String(plain, Charsets.UTF_8))
        if (request.optString("method") != "multipleRequest") {
            resp.optJSONObject("result")?.optJSONArray("responses")?.optJSONObject(0)?.let { return it }
        }
        return resp
    }

    /**
     * Chama uma função da câmera (como o executeFunction do pytapo) e devolve o "result" dela.
     * Erro da câmera vira [TapoException] com o código.
     */
    fun call(method: String, params: JSONObject?): JSONObject {
        val req = JSONObject().put("method", method)
        if (params != null) req.put("params", params)
        val full = JSONObject().put("method", "multipleRequest")
            .put("params", JSONObject().put("requests", JSONArray().put(req)))
        val resp = send(full)
        val top = resp.optInt("error_code", 0)
        val one = resp.optJSONObject("result")?.optJSONArray("responses")?.optJSONObject(0)
            ?: throw TapoException(TapoErrors.message(top), top)
        val code = one.optInt("error_code", 0)
        if (code != 0) throw TapoException(TapoErrors.message(code), code)
        return one.optJSONObject("result") ?: JSONObject()
    }

    companion object {
        const val USER = "admin"
        val SESSION_ERRORS = setOf(-40401, -40421, -40413, -1, 9999)

        fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}

/** Códigos de erro das câmeras Tapo em português. */
object TapoErrors {
    private val map = mapOf(
        -40401 to "Sessão expirou",
        -64324 to "O modo privacidade está ligado na câmera",
        -64302 to "Preset não encontrado na câmera",
        -64321 to "Preset apagado na câmera",
        -40105 to "A câmera não tem essa função",
        -40106 to "A câmera não tem essa função",
        -40210 to "A câmera não tem essa função",
        -40101 to "A câmera não tem essa função",
        -64304 to "Chegou no limite do movimento",
        -64303 to "Motor ocupado (patrulha ligada?)",
        -64305 to "Espere a câmera parar de mexer",
        -64306 to "A câmera não aceita mais presets",
        -64336 to "Área privada: a câmera não mexe para lá",
        -71103 to "Identificação de usuário expirou",
        -71105 to "A busca de gravações falhou",
        -52409 to "Sem cartão de memória",
        -71114 to "Sem cartão de memória",
        -71115 to "O cartão de memória ainda está carregando",
        -52407 to "Muitos aparelhos conectados na câmera",
        -52405 to "Pedidos demais, espere um pouco",
        -40404 to "A câmera bloqueou novas tentativas",
    )

    fun message(code: Int): String = map[code] ?: "A câmera respondeu com erro $code"
}
