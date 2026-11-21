package com.ptzstudio.tapo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TapoClientTest {
    private val pwd = "senha-da-conta"

    private fun run(mode: TapoAuth) {
        val cam = FakeTapoCamera(mode, pwd)
        val api = TapoApi(TapoClient(cam, pwd))
        assertEquals("C320WS", api.info().model)
        assertEquals(mode, api.client.auth)

        api.move(10, -5)
        val mv = cam.calls.last { it.getString("method") == "motorMove" }.getJSONObject("params").getJSONObject("motor").getJSONObject("move")
        assertEquals("10", mv.getString("x_coord"))
        assertEquals("-5", mv.getString("y_coord"))

        assertEquals(listOf("Altar", "Púlpito"), api.presets().map { it.name })
        api.gotoPreset("3")
        try {
            api.gotoPreset("99")
            fail("preset inexistente deveria dar erro")
        } catch (e: TapoException) {
            assertEquals(-64302, e.code)
        }
        api.addPreset("Coral")
        assertEquals("Coral", api.presets().last().name)
        api.deletePreset("1")
        assertEquals(listOf("3", "4"), api.presets().map { it.id })

        val (kind, on) = api.detectLight()!!
        assertEquals(LightKind.WHITELAMP, kind)
        assertEquals(false, on)
        api.setLight(kind, true, on)
        assertTrue(cam.light)
        api.setLight(kind, true, true) // já acesa: não inverte de novo
        assertTrue(cam.light)
        assertEquals(true, api.lightOn(kind))

        // Sessão expira: o cliente entra de novo sozinho e o pedido funciona.
        val before = cam.loginAttempts
        cam.expireNext = true
        api.move(1, 0)
        assertTrue(cam.loginAttempts > before)
    }

    @Test fun legacyFirmware() = run(TapoAuth.LEGACY)
    @Test fun tpapFirmware() = run(TapoAuth.TPAP)
    @Test fun plainFirmware() = run(TapoAuth.PLAIN)

    @Test
    fun wrongPasswordIsReportedWithoutHammering() {
        for (mode in TapoAuth.entries) {
            val cam = FakeTapoCamera(mode, pwd)
            val api = TapoApi(TapoClient(cam, "errada"))
            try {
                api.info()
                fail("senha errada deveria falhar ($mode)")
            } catch (e: TapoException) {
                assertTrue("$mode: ${e.message}", e.message!!.contains("Senha", ignoreCase = true) || e.message!!.contains("login", ignoreCase = true))
            }
            // Cada tentativa errada conta para o bloqueio da câmera: no máximo uma rodada de candidatos.
            assertTrue("$mode tentou ${cam.loginAttempts} vezes", cam.loginAttempts <= 2)
        }
    }

    @Test
    fun presetsAreSorted() {
        val o = org.json.JSONObject("""{"preset":{"preset":{"id":["10","2"],"name":["B",""]}}}""")
        val p = TapoApi.parsePresets(o)
        assertEquals(listOf("2", "10"), p.map { it.id })
        assertEquals("Preset 2", p[0].name)
    }

    @Test
    fun statusParsing() {
        assertEquals(true, TapoApi.findStatus(org.json.JSONObject("""{"image":{"status":1}}""")))
        assertEquals(false, TapoApi.findStatus(org.json.JSONObject("""{"status":"off"}""")))
        assertFalse(TapoApi.findStatus(org.json.JSONObject("""{"a":{"b":{"enabled":"0"}}}"""))!!)
    }
}
