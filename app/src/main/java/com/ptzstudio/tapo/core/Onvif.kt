package com.ptzstudio.tapo.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/** POST HTTP simples (o ONVIF da Tapo fica na porta 2020, sem HTTPS). */
fun interface OnvifHttp {
    fun post(url: String, body: ByteArray, contentType: String): HttpReply
}

data class OnvifInfo(val manufacturer: String, val model: String, val firmware: String)

/**
 * Controle da câmera pelo padrão ONVIF, com a "Conta da câmera" criada no app Tapo (a mesma do vídeo).
 * Não usa a conta Tapo nem a Compatibilidade com terceiros. Faz movimento contínuo com velocidade, parada e presets.
 */
class OnvifClient(
    private val http: OnvifHttp,
    private val host: String,
    private val user: String,
    private val pass: String,
    private val port: Int = 2020,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val deviceUrl get() = "http://$host:$port/onvif/device_service"
    private var ptzUrl: String? = null
    private var mediaUrl: String? = null
    private var profile: String? = null
    /** Diferença entre o relógio da câmera e o do celular (a senha cifrada leva a hora junto). */
    @Volatile var clockOffsetMs = 0L
        private set

    val connected: Boolean get() = profile != null

    // ---------------- SOAP ----------------

    private fun created(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(clock() + clockOffsetMs))
    }

    private fun security(): String {
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        val c = created()
        val digest = passwordDigest(nonce, c, pass)
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">""" +
            "<UsernameToken><Username>${esc(user)}</Username>" +
            """<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password>""" +
            """<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${TapoHash.b64(nonce)}</Nonce>""" +
            """<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$c</Created>""" +
            "</UsernameToken></Security>"
    }

    private fun envelope(body: String, auth: Boolean) =
        """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" """ +
            """xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" """ +
            """xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">""" +
            (if (auth) "<s:Header>${security()}</s:Header>" else "") +
            "<s:Body>$body</s:Body></s:Envelope>"

    private fun call(url: String, body: String, auth: Boolean = true): Element {
        val r = try {
            http.post(url, envelope(body, auth).toByteArray(), "application/soap+xml; charset=utf-8")
        } catch (e: java.io.IOException) {
            throw TapoException("A câmera não respondeu no ONVIF (porta $port)")
        }
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            f.newDocumentBuilder().parse(ByteArrayInputStream(r.body)).documentElement
        } catch (_: Exception) {
            throw TapoException("Resposta estranha da câmera no ONVIF (HTTP ${r.status})")
        }
        val fault = first(doc, "Fault")
        if (fault != null || r.status >= 400) {
            val text = (fault?.textContent ?: "").replace(Regex("\\s+"), " ").trim()
            if (r.status == 401 || text.contains("NotAuthorized", true) || text.contains("Sender not Authorized", true) ||
                text.contains("authority", true)
            ) {
                throw TapoException(
                    "Conta da câmera recusada. Confira o usuário e a senha da Conta da câmera (os mesmos do vídeo).",
                    ONVIF_AUTH,
                )
            }
            throw TapoException("A câmera recusou o comando ONVIF" + (if (text.isNotEmpty()) ": ${text.take(120)}" else " (HTTP ${r.status})"))
        }
        return doc
    }

    // ---------------- Conexão ----------------

    /** Acerta o relógio, descobre os endereços de PTZ e vídeo e o perfil usado nos comandos. */
    @Synchronized
    fun connect() {
        try {
            val t = call(deviceUrl, "<tds:GetSystemDateAndTime/>", auth = false)
            parseUtc(t)?.let { cam -> clockOffsetMs = cam - clock() }
        } catch (_: TapoException) {
            // Sem hora da câmera: usa a do celular.
        }
        val caps = call(deviceUrl, "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>")
        mediaUrl = xaddr(caps, "Media") ?: deviceUrl
        ptzUrl = xaddr(caps, "PTZ") ?: mediaUrl
        val profiles = call(mediaUrl!!, "<trt:GetProfiles/>")
        val all = all(profiles, "Profiles")
        val withPtz = all.firstOrNull { first(it, "PTZConfiguration") != null } ?: all.firstOrNull()
        profile = withPtz?.getAttribute("token")?.takeIf { it.isNotEmpty() }
            ?: throw TapoException("A câmera não informou o perfil ONVIF")
    }

    private fun ensure() {
        if (!connected) connect()
    }

    fun info(): OnvifInfo {
        val r = call(deviceUrl, "<tds:GetDeviceInformation/>")
        return OnvifInfo(text(r, "Manufacturer"), text(r, "Model"), text(r, "FirmwareVersion"))
    }

    // ---------------- PTZ ----------------

    /** Movimento contínuo. x: -1 (esquerda) a 1 (direita). y: -1 (baixo) a 1 (cima). */
    @Synchronized
    fun move(x: Double, y: Double) {
        ensure()
        call(
            ptzUrl!!,
            "<tptz:ContinuousMove><tptz:ProfileToken>${esc(profile!!)}</tptz:ProfileToken><tptz:Velocity>" +
                """<tt:PanTilt x="${num(x)}" y="${num(y)}"/></tptz:Velocity></tptz:ContinuousMove>""",
        )
    }

    @Synchronized
    fun stop() {
        ensure()
        call(
            ptzUrl!!,
            "<tptz:Stop><tptz:ProfileToken>${esc(profile!!)}</tptz:ProfileToken>" +
                "<tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>",
        )
    }

    @Synchronized
    fun presets(): List<TapoPreset> {
        ensure()
        val r = call(ptzUrl!!, "<tptz:GetPresets><tptz:ProfileToken>${esc(profile!!)}</tptz:ProfileToken></tptz:GetPresets>")
        return all(r, "Preset").mapNotNull { p ->
            val token = p.getAttribute("token").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TapoPreset(token, text(p, "Name").ifEmpty { "Preset $token" })
        }
    }

    @Synchronized
    fun gotoPreset(token: String) {
        ensure()
        call(ptzUrl!!, "<tptz:GotoPreset><tptz:ProfileToken>${esc(profile!!)}</tptz:ProfileToken><tptz:PresetToken>${esc(token)}</tptz:PresetToken></tptz:GotoPreset>")
    }

    /** Grava a posição atual. Com [token], regrava aquele preset; sem, cria um novo. Devolve o token. */
    @Synchronized
    fun setPreset(name: String, token: String?): String {
        ensure()
        val r = call(
            ptzUrl!!,
            "<tptz:SetPreset><tptz:ProfileToken>${esc(profile!!)}</tptz:ProfileToken><tptz:PresetName>${esc(name)}</tptz:PresetName>" +
                (if (token != null) "<tptz:PresetToken>${esc(token)}</tptz:PresetToken>" else "") + "</tptz:SetPreset>",
        )
        return text(r, "PresetToken").ifEmpty { token ?: throw TapoException("A câmera não confirmou o preset") }
    }

    @Synchronized
    fun removePreset(token: String) {
        ensure()
        call(ptzUrl!!, "<tptz:RemovePreset><tptz:ProfileToken>${esc(profile!!)}</tptz:ProfileToken><tptz:PresetToken>${esc(token)}</tptz:PresetToken></tptz:RemovePreset>")
    }

    companion object {
        /** Código usado para "usuário ou senha do ONVIF errados" (o app para de tentar, como no login Tapo). */
        const val ONVIF_AUTH = -40411

        /** WS-Security: Base64(SHA1(nonce + created + senha)). */
        fun passwordDigest(nonce: ByteArray, created: String, password: String): String =
            TapoHash.b64(TapoHash.sha1(nonce + created.toByteArray() + password.toByteArray()))

        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        fun num(v: Double): String = String.format(Locale.US, "%.3f", v.coerceIn(-1.0, 1.0))

        private fun allIn(e: Element, local: String): List<Element> {
            val nl = e.getElementsByTagNameNS("*", local)
            return (0 until nl.length).map { nl.item(it) as Element }
        }

        fun all(e: Element, local: String) = allIn(e, local)
        fun first(e: Element, local: String): Element? = allIn(e, local).firstOrNull()
        fun text(e: Element, local: String): String = first(e, local)?.textContent?.trim().orEmpty()

        /** XAddr de um serviço dentro da resposta do GetCapabilities. */
        fun xaddr(caps: Element, service: String): String? =
            first(caps, service)?.let { first(it, "XAddr")?.textContent?.trim() }?.takeIf { it.isNotEmpty() }

        /** Hora UTC da câmera (GetSystemDateAndTime), em milissegundos. */
        fun parseUtc(r: Element): Long? {
            val utc = first(r, "UTCDateTime") ?: return null
            fun n(tag: String) = text(utc, tag).toIntOrNull()
            val y = n("Year") ?: return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y, (n("Month") ?: 1) - 1, n("Day") ?: 1, n("Hour") ?: 0, n("Minute") ?: 0, n("Second") ?: 0)
            return cal.timeInMillis
        }
    }
}

/**
 * Ponte entre o controlador de movimento e o ONVIF: a velocidade pedida vira um movimento contínuo da câmera,
 * mandado de novo só quando muda (ou a cada [refreshMs] enquanto anda); soltar manda a parada.
 */
class OnvifMotor(
    private val move: (Double, Double) -> Unit,
    private val stop: () -> Unit,
    private val onError: (Exception) -> Unit = {},
    private val refreshMs: Long = 1500,
    private val clock: () -> Long = System::currentTimeMillis,
) : PacketSender {
    private val lock = Object()
    @Volatile private var want = 0.0 to 0.0
    private var sent = 0.0 to 0.0
    private var sentAt = 0L
    @Volatile private var running = false

    override fun send(packet: ByteArray) {
        val pt = TapoMotor.parsePanTilt(packet) ?: return
        synchronized(lock) {
            want = pt.first.toDouble() / ViscaCommands.MAX_PAN_SPEED to pt.second.toDouble() / ViscaCommands.MAX_TILT_SPEED
            lock.notifyAll()
        }
    }

    fun halt() = synchronized(lock) {
        want = 0.0 to 0.0
        lock.notifyAll()
    }

    /** O que mandar agora: velocidade, parada (0,0) ou nada. Separado para os testes. */
    fun next(now: Long): Pair<Double, Double>? {
        val w = want
        val moving = w.first != 0.0 || w.second != 0.0
        val wasMoving = sent.first != 0.0 || sent.second != 0.0
        if (!moving) return if (wasMoving) (0.0 to 0.0) else null
        val changed = abs(w.first - sent.first) > 0.04 || abs(w.second - sent.second) > 0.04 ||
            (w.first == 0.0) != (sent.first == 0.0) || (w.second == 0.0) != (sent.second == 0.0)
        return if (changed || now - sentAt >= refreshMs) w else null
    }

    /** Manda um comando (se for preciso). Devolve true se mandou. */
    fun step(): Boolean {
        val now = clock()
        val cmd = synchronized(lock) { next(now) } ?: return false
        try {
            if (cmd.first == 0.0 && cmd.second == 0.0) stop() else move(cmd.first, cmd.second)
            synchronized(lock) {
                sent = cmd
                sentAt = now
            }
        } catch (e: Exception) {
            onError(e)
            // Falhou: na próxima volta tenta de novo (inclusive a parada).
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        return true
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        val t = Thread({
            while (running) {
                if (!step()) synchronized(lock) { lock.wait(80) }
            }
        }, "onvif-motor")
        t.isDaemon = true
        t.start()
    }

    fun stopThread() = synchronized(lock) {
        running = false
        lock.notifyAll()
    }
}
