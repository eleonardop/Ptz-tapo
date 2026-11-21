package com.ptzstudio.tapo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun idaEVolta() {
        val cam = CameraConfig(name = "Igreja", ip = "192.168.4.163", password = "conta#1", cameraUser = "cam", cameraPass = "p@ss w", presetSlots = mapOf(1 to "3", 2 to "7"))
        val s = AppSettings(
            cameras = listOf(cam, CameraConfig(name = "Lateral", ip = "192.168.4.170", highQuality = false, rtspUrl = "rtsp://x/y", useOnvif = false)),
            selectedCameraId = cam.id,
            mapping = DefaultMapping.map + (PadButton.HOME to PadAction.PHOTO),
            moveStep = 25,
            motion = MotionConfig(speedPercent = 40, rampPercent = 70, invertTilt = true),
            presetNames = listOf("Púlpito", "Coral", "Altar", "Plateia", "Preset 5", "Preset 6", "Preset 7", "Preset 8"),
            learnedKeys = mapOf(188 to PadButton.L4),
            videoEnabled = true,
        )
        val back = SettingsCodec.fromJson(SettingsCodec.toJson(s))
        assertEquals(s, back)
    }

    @Test
    fun textoVazioOuEstragado_voltaAoPadrao() {
        assertEquals(AppSettings(), SettingsCodec.fromJson(null))
        assertEquals(AppSettings(), SettingsCodec.fromJson("{isso não é json"))
    }

    @Test
    fun arquivoIncompleto_completaComPadroes() {
        val s = SettingsCodec.fromJson("""{"mapping":{"A":"LIGHT","LS":"PRESET_1","XX":"NONE"},"motion":{"speedPercent":500},"moveStep":999}""")
        assertEquals(PadAction.LIGHT, s.mapping[PadButton.A])
        assertEquals(AppSettings.MAX_STEP, s.moveStep)
        assertEquals(PadAction.STICK_MOVE, s.mapping[PadButton.LS])
        assertEquals(23, s.mapping.size)
        assertEquals(100, s.motion.speedPercent)
        assertTrue(s.cameras.isEmpty())
    }

    @Test
    fun enderecoDoVideo() {
        val c = CameraConfig(ip = "192.168.4.50", cameraUser = "igreja", cameraPass = "a b@c")
        assertEquals("rtsp://igreja:a%20b%40c@192.168.4.50:554/stream1", c.videoUrl)
        assertEquals("rtsp://igreja:a%20b%40c@192.168.4.50:554/stream2", c.copy(highQuality = false).videoUrl)
        assertEquals("", c.copy(cameraUser = "").videoUrl)
        assertEquals("rtsp://manual", c.copy(rtspUrl = " rtsp://manual ").videoUrl)
    }

    @Test
    fun video_ligadoPorPadrao() {
        assertTrue(AppSettings().videoEnabled)
        val desligado = AppSettings(videoEnabled = false)
        assertEquals(false, SettingsCodec.fromJson(SettingsCodec.toJson(desligado)).videoEnabled)
    }
}
