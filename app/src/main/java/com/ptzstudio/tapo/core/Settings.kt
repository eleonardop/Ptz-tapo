package com.ptzstudio.tapo.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Uma câmera Tapo salva. */
data class CameraConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Câmera",
    val ip: String = "",
    /** Senha da conta Tapo (TP-Link), usada para mexer a câmera, presets e luz. */
    val password: String = "",
    /** "Conta da câmera" criada no app Tapo (Avançado › Conta da câmera), usada só para o vídeo ao vivo. */
    val cameraUser: String = "",
    val cameraPass: String = "",
    /** Vídeo em alta (stream1) ou baixa (stream2) qualidade. */
    val highQuality: Boolean = true,
    /** Endereço RTSP escrito à mão (vazio = montado sozinho com a conta da câmera). */
    val rtspUrl: String = "",
    /** Mover e usar presets pela Conta da câmera (ONVIF), sem depender da conta Tapo. */
    val useOnvif: Boolean = true,
    /** Qual preset da câmera fica em cada botão de preset do app (1..8 -> id na câmera). */
    val presetSlots: Map<Int, String> = emptyMap(),
) {
    val limits: CameraLimits get() = CameraLimits()

    /** O ONVIF comanda a câmera (precisa da Conta da câmera). */
    val onvifActive: Boolean get() = useOnvif && cameraUser.isNotBlank()

    /** Endereço do vídeo ao vivo (vazio = falta a conta da câmera). */
    val videoUrl: String
        get() {
            if (rtspUrl.isNotBlank()) return rtspUrl.trim()
            if (ip.isBlank() || cameraUser.isBlank()) return ""
            fun enc(t: String) = java.net.URLEncoder.encode(t, "UTF-8").replace("+", "%20")
            return "rtsp://${enc(cameraUser)}:${enc(cameraPass)}@${ip.trim()}:554/${if (highQuality) "stream1" else "stream2"}"
        }
}

/** Tudo o que o app guarda no aparelho. */
data class AppSettings(
    val cameras: List<CameraConfig> = emptyList(),
    val selectedCameraId: String? = null,
    val mapping: Map<PadButton, PadAction> = DefaultMapping.map,
    val motion: MotionConfig = MotionConfig(),
    val presetNames: List<String> = defaultPresetNames(),
    /** Códigos de tecla que o usuário ensinou na detecção (ex.: L4/R4 que o Android não conhece). */
    val learnedKeys: Map<Int, PadButton> = emptyMap(),
    /** Vídeo ao vivo ligado por padrão. */
    val videoEnabled: Boolean = true,
    /** Tamanho do passo de cada pedido de movimento com o analógico todo inclinado (1..[MAX_STEP]). */
    val moveStep: Int = DEFAULT_STEP,
) {
    val selectedCamera: CameraConfig?
        get() = cameras.firstOrNull { it.id == selectedCameraId } ?: cameras.firstOrNull()

    companion object {
        const val PRESET_COUNT = 8
        const val DEFAULT_STEP = 10
        const val MAX_STEP = 40
        fun defaultPresetNames(): List<String> = (1..PRESET_COUNT).map { "Preset $it" }
    }
}

/** Conversão das configurações para JSON (e de volta), tolerante a arquivos antigos ou incompletos. */
object SettingsCodec {
    const val VERSION = 1

    fun toJson(s: AppSettings): String {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("cameras", JSONArray().apply {
            s.cameras.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("ip", c.ip)
                    put("password", c.password)
                    put("cameraUser", c.cameraUser)
                    put("cameraPass", c.cameraPass)
                    put("highQuality", c.highQuality)
                    put("useOnvif", c.useOnvif)
                    put("rtspUrl", c.rtspUrl)
                    put("presetSlots", JSONObject().apply { c.presetSlots.forEach { (k, v) -> put(k.toString(), v) } })
                })
            }
        })
        s.selectedCameraId?.let { o.put("selectedCameraId", it) }
        o.put("mapping", JSONObject().apply { s.mapping.forEach { (b, a) -> put(b.name, a.name) } })
        val m = s.motion
        o.put("motion", JSONObject().apply {
            put("speedPercent", m.speedPercent)
            put("rampPercent", m.rampPercent)
            put("deadzonePercent", m.deadzonePercent)
            put("finePercent", m.finePercent)
            put("zoomSpeedPercent", m.zoomSpeedPercent)
            put("zoomSlowdownEnabled", m.zoomSlowdownEnabled)
            put("zoomSlowdownPercent", m.zoomSlowdownPercent)
            put("zoomMaxPosition", m.zoomMaxPosition)
            put("inputWatchdogEnabled", m.inputWatchdogEnabled)
            put("invertPan", m.invertPan)
            put("invertTilt", m.invertTilt)
            put("diagonalLockDeg", m.diagonalLockDeg)
        })
        o.put("presetNames", JSONArray().apply { s.presetNames.forEach { put(it) } })
        o.put("learnedKeys", JSONObject().apply { s.learnedKeys.forEach { (k, b) -> put(k.toString(), b.name) } })
        o.put("videoEnabled", s.videoEnabled)
        o.put("moveStep", s.moveStep)
        return o.toString()
    }

    fun fromJson(text: String?): AppSettings = tryParse(text) ?: AppSettings()

    /** Igual a [fromJson], mas devolve nulo se o texto existe e está estragado (para guardar uma cópia antes de sobrescrever). */
    fun tryParse(text: String?): AppSettings? {
        if (text.isNullOrBlank()) return AppSettings()
        return try {
            val o = JSONObject(text)
            val def = AppSettings()
            val cameras = mutableListOf<CameraConfig>()
            o.optJSONArray("cameras")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val slots = mutableMapOf<Int, String>()
                    c.optJSONObject("presetSlots")?.let { ps ->
                        ps.keys().forEach { k ->
                            val n = k.toIntOrNull()
                            val v = ps.optString(k)
                            if (n != null && n in 1..AppSettings.PRESET_COUNT && v.isNotBlank()) slots[n] = v
                        }
                    }
                    cameras += CameraConfig(
                        id = c.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = c.optString("name", "Câmera"),
                        ip = c.optString("ip", ""),
                        password = c.optString("password", ""),
                        cameraUser = c.optString("cameraUser", ""),
                        cameraPass = c.optString("cameraPass", ""),
                        highQuality = c.optBoolean("highQuality", true),
                        useOnvif = c.optBoolean("useOnvif", true),
                        rtspUrl = c.optString("rtspUrl", ""),
                        presetSlots = slots,
                    )
                }
            }
            val mapping = mutableMapOf<PadButton, PadAction>()
            o.optJSONObject("mapping")?.let { mo ->
                mo.keys().forEach { k ->
                    val b = PadButton.fromName(k)
                    val a = PadAction.fromName(mo.optString(k))
                    if (b != null && a != null) mapping[b] = a
                }
            }
            val mj = o.optJSONObject("motion")
            val dm = def.motion
            val motion = if (mj == null) dm else MotionConfig(
                speedPercent = mj.optInt("speedPercent", dm.speedPercent),
                rampPercent = mj.optInt("rampPercent", dm.rampPercent),
                deadzonePercent = mj.optInt("deadzonePercent", dm.deadzonePercent),
                finePercent = mj.optInt("finePercent", dm.finePercent),
                zoomSpeedPercent = mj.optInt("zoomSpeedPercent", dm.zoomSpeedPercent),
                zoomSlowdownEnabled = mj.optBoolean("zoomSlowdownEnabled", dm.zoomSlowdownEnabled),
                zoomSlowdownPercent = mj.optInt("zoomSlowdownPercent", dm.zoomSlowdownPercent),
                zoomMaxPosition = mj.optInt("zoomMaxPosition", dm.zoomMaxPosition),
                inputWatchdogEnabled = mj.optBoolean("inputWatchdogEnabled", dm.inputWatchdogEnabled),
                invertPan = mj.optBoolean("invertPan", dm.invertPan),
                invertTilt = mj.optBoolean("invertTilt", dm.invertTilt),
                diagonalLockDeg = mj.optInt("diagonalLockDeg", dm.diagonalLockDeg),
            ).sanitized()
            val names = def.presetNames.toMutableList()
            o.optJSONArray("presetNames")?.let { arr ->
                for (i in 0 until minOf(arr.length(), names.size)) {
                    val n = arr.optString(i).trim()
                    if (n.isNotEmpty()) names[i] = n
                }
            }
            val learned = mutableMapOf<Int, PadButton>()
            o.optJSONObject("learnedKeys")?.let { lk ->
                lk.keys().forEach { k ->
                    val code = k.toIntOrNull()
                    val b = PadButton.fromName(lk.optString(k))
                    if (code != null && b != null) learned[code] = b
                }
            }
            val selected = o.optString("selectedCameraId").takeIf { id -> cameras.any { it.id == id } }
            AppSettings(
                cameras = cameras,
                selectedCameraId = selected ?: cameras.firstOrNull()?.id,
                mapping = DefaultMapping.sanitize(mapping),
                motion = motion,
                presetNames = names,
                learnedKeys = learned,
                videoEnabled = o.optBoolean("videoEnabled", true),
                moveStep = o.optInt("moveStep", AppSettings.DEFAULT_STEP).coerceIn(1, AppSettings.MAX_STEP),
            )
        } catch (_: Exception) {
            null
        }
    }
}
