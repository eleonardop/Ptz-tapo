package com.ptzstudio.tapo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Câmera ONVIF de mentira: confere a senha cifrada de cada pedido e responde como a Tapo. */
class FakeOnvifCamera(private val user: String, private val pass: String, private val camClockMs: Long) : OnvifHttp {
    val bodies = mutableListOf<String>()
    val presets = linkedMapOf("1" to "Altar", "2" to "Coral")

    private fun env(body: String) = """<?xml version="1.0"?><SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema"><SOAP-ENV:Body>$body</SOAP-ENV:Body></SOAP-ENV:Envelope>"""
    private fun ok(body: String) = HttpReply(200, env(body).toByteArray())

    private fun tag(s: String, t: String) = Regex("<[^>]*$t[^>]*>([^<]*)</").find(s)?.groupValues?.get(1)

    override fun post(url: String, body: ByteArray, contentType: String): HttpReply {
        val s = String(body)
        bodies += s
        if (s.contains("GetSystemDateAndTime")) {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = camClockMs }
            return ok("<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Time><tt:Hour>${c.get(11)}</tt:Hour><tt:Minute>${c.get(12)}</tt:Minute><tt:Second>${c.get(13)}</tt:Second></tt:Time><tt:Date><tt:Year>${c.get(1)}</tt:Year><tt:Month>${c.get(2) + 1}</tt:Month><tt:Day>${c.get(5)}</tt:Day></tt:Date></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>")
        }
        // Senha: confere o digest e a hora (até 5 min de diferença do relógio da câmera).
        val u = tag(s, "Username")
        val d = tag(s, "Password")
        val n = tag(s, "Nonce")
        val c = tag(s, "Created")
        val okAuth = u == user && n != null && c != null && d == OnvifClient.passwordDigest(TapoHash.unb64(n), c, pass) &&
            kotlin.math.abs(java.time.Instant.parse(c).toEpochMilli() - camClockMs) < 300_000
        if (!okAuth) return HttpReply(400, env("<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Sender</SOAP-ENV:Value><SOAP-ENV:Subcode><SOAP-ENV:Value>ter:NotAuthorized</SOAP-ENV:Value></SOAP-ENV:Subcode></SOAP-ENV:Code></SOAP-ENV:Fault>").toByteArray())
        return when {
            s.contains("GetCapabilities") -> ok("<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr>http://cam:2020/onvif/service</tt:XAddr></tt:Media><tt:PTZ><tt:XAddr>http://cam:2020/onvif/service</tt:XAddr></tt:PTZ></tds:Capabilities></tds:GetCapabilitiesResponse>")
            s.contains("GetProfiles") -> ok("""<trt:GetProfilesResponse><trt:Profiles token="profile_1" fixed="true"><tt:Name>mainStream</tt:Name><tt:PTZConfiguration token="PTZ"/></trt:Profiles><trt:Profiles token="profile_2"><tt:Name>minor</tt:Name></trt:Profiles></trt:GetProfilesResponse>""")
            s.contains("GetDeviceInformation") -> ok("<tds:GetDeviceInformationResponse><tds:Manufacturer>tp-link</tds:Manufacturer><tds:Model>C500</tds:Model><tds:FirmwareVersion>1.3.5</tds:FirmwareVersion></tds:GetDeviceInformationResponse>")
            s.contains("GetPresets") -> ok("<tptz:GetPresetsResponse>" + presets.entries.joinToString("") { """<tptz:Preset token="${it.key}"><tt:Name>${it.value}</tt:Name></tptz:Preset>""" } + "</tptz:GetPresetsResponse>")
            s.contains("SetPreset") -> {
                val name = tag(s, "PresetName")!!
                val tok = tag(s, "PresetToken") ?: ((presets.keys.maxOf { it.toInt() } + 1).toString())
                presets[tok] = name
                ok("<tptz:SetPresetResponse><tptz:PresetToken>$tok</tptz:PresetToken></tptz:SetPresetResponse>")
            }
            s.contains("ContinuousMove") || s.contains("tptz:Stop") || s.contains("GotoPreset") || s.contains("RemovePreset") -> ok("<tptz:Response/>")
            else -> HttpReply(400, env("<SOAP-ENV:Fault><SOAP-ENV:Reason><SOAP-ENV:Text>Action not supported</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>").toByteArray())
        }
    }
}

class OnvifTest {
    @Test
    fun digestDaSenha_comoNaPython() {
        val n = ByteArray(16) { it.toByte() }
        assertEquals("tcZUQd2Lp7sCGKc8/VXUoWDCdUA=", OnvifClient.passwordDigest(n, "2026-11-21T06:30:00.000Z", "s3nh@&<"))
    }

    @Test
    fun conectaMoveParaEPresets_comRelogioDaCameraAtrasado() {
        val phone = 1_800_000_000_000L
        val cam = FakeOnvifCamera("igreja", "a&b<c", camClockMs = phone - 3_600_000) // câmera 1 h atrasada
        val c = OnvifClient(cam, "192.168.4.50", "igreja", "a&b<c", clock = { phone })
        c.connect()
        assertTrue(kotlin.math.abs(c.clockOffsetMs + 3_600_000) < 2000)
        assertEquals("C500", c.info().model)
        c.move(0.5, -1.0)
        assertTrue(cam.bodies.last().contains("""<tt:PanTilt x="0.500" y="-1.000"/>"""))
        assertTrue(cam.bodies.last().contains("profile_1"))
        c.stop()
        assertEquals(listOf("Altar", "Coral"), c.presets().map { it.name })
        assertEquals("3", c.setPreset("Púlpito", null))
        assertEquals("2", c.setPreset("Coral novo", "2"))
        assertEquals("Coral novo", c.presets().first { it.id == "2" }.name)
        c.gotoPreset("1")
    }

    @Test
    fun senhaErrada_avisaComCodigoDeLogin() {
        val cam = FakeOnvifCamera("igreja", "certa", 1_800_000_000_000L)
        val c = OnvifClient(cam, "x", "igreja", "errada", clock = { 1_800_000_000_000L })
        try {
            c.connect()
            fail("deveria recusar")
        } catch (e: TapoException) {
            assertEquals(OnvifClient.ONVIF_AUTH, e.code)
        }
    }

    @Test
    fun motor_mandaSoQuandoMuda_eParaAoSoltar() {
        var now = 0L
        val sent = mutableListOf<String>()
        val m = OnvifMotor(move = { x, y -> sent += "m ${OnvifClient.num(x)} ${OnvifClient.num(y)}" }, stop = { sent += "stop" }, clock = { now })
        assertEquals(false, m.step())
        m.send(ViscaCommands.panTiltDrive(1, 24, 0))
        assertTrue(m.step())
        assertEquals(false, m.step()) // igual: não repete
        now += 1600
        assertTrue(m.step()) // reforço enquanto anda
        m.send(ViscaCommands.panTiltDrive(1, 12, -10))
        m.step()
        m.send(ViscaCommands.panTiltStop(1))
        m.step()
        assertEquals(false, m.step())
        assertEquals(listOf("m 1.000 0.000", "m 1.000 0.000", "m 0.500 -0.500", "stop"), sent)
        assertNull(m.next(now))
    }

    @Test
    fun motor_paradaQueFalhaETentadaDeNovo() {
        var fails = 1
        val sent = mutableListOf<String>()
        val m = OnvifMotor(move = { _, _ -> sent += "m" }, stop = { if (fails-- > 0) throw java.io.IOException("x") else sent += "stop" })
        m.send(ViscaCommands.panTiltDrive(1, 24, 0)); m.step()
        m.halt(); m.step(); m.step()
        assertEquals(listOf("m", "stop"), sent)
    }
}
