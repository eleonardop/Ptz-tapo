package com.ptzstudio.tapo.core

import org.json.JSONArray
import org.json.JSONObject

data class TapoPreset(val id: String, val name: String)

data class TapoInfo(val model: String, val name: String, val firmware: String)

/** Que tipo de luz a câmera tem (cada modelo usa um comando diferente). */
enum class LightKind {
    /** Luz branca (holofote) ligada na mão: C320WS e parecidas. */
    WHITELAMP,
    /** Refletor (floodlight) das câmeras com luz forte. */
    FLOODLIGHT,
    /** Luz branca forçada pela chave de imagem. */
    FORCE_WTL,
}

/** Funções da câmera usadas pelo app, nos mesmos formatos do pytapo. */
class TapoApi(val client: TapoClient) {

    fun info(): TapoInfo {
        val r = client.call("getDeviceInfo", JSONObject().put("device_info", JSONObject().put("name", JSONArray().put("basic_info"))))
        val b = r.optJSONObject("device_info")?.optJSONObject("basic_info") ?: JSONObject()
        return TapoInfo(
            model = b.optString("device_model").ifEmpty { b.optString("device_type") },
            name = b.optString("device_alias"),
            firmware = b.optString("sw_version"),
        )
    }

    /** Movimento relativo (passos da câmera). x > 0 = direita, y > 0 = cima. */
    fun move(x: Int, y: Int) {
        client.call(
            "motorMove",
            JSONObject().put("motor", JSONObject().put("move", JSONObject().put("x_coord", x.toString()).put("y_coord", y.toString()))),
        )
    }

    fun presets(): List<TapoPreset> {
        val r = client.call("getPresetConfig", JSONObject().put("preset", JSONObject().put("name", JSONArray().put("preset"))))
        return parsePresets(r)
    }

    fun gotoPreset(id: String) {
        client.call("motorMoveToPreset", JSONObject().put("preset", JSONObject().put("goto_preset", JSONObject().put("id", id))))
    }

    /** Salva a posição atual como um preset novo (o nome do método tem mesmo o erro de digitação da Tapo). */
    fun addPreset(name: String) {
        client.call(
            "addMotorPostion",
            JSONObject().put("preset", JSONObject().put("set_preset", JSONObject().put("name", name).put("save_ptz", "1"))),
        )
    }

    fun deletePreset(id: String) {
        client.call("deletePreset", JSONObject().put("preset", JSONObject().put("remove_preset", JSONObject().put("id", JSONArray().put(id)))))
    }

    // ---------------- Luz ----------------

    /** Descobre como a luz desta câmera é comandada. Devolve o tipo e se está acesa (nulo = não informou), ou nulo sem luz. */
    fun detectLight(): Pair<LightKind, Boolean?>? {
        for (kind in LightKind.entries) {
            try {
                return kind to lightOn(kind)
            } catch (e: TapoException) {
                // Sem essa função: tenta a próxima. Outros erros (rede, senha) sobem.
                if (e.code == null || e.code !in NOT_SUPPORTED) throw e
            }
        }
        return null
    }

    fun lightOn(kind: LightKind): Boolean? = when (kind) {
        LightKind.WHITELAMP -> {
            val r = client.call("getWhitelampStatus", JSONObject().put("image", JSONObject().put("get_wtl_status", JSONArray().put("null"))))
            findStatus(r)
        }
        LightKind.FLOODLIGHT -> {
            val r = client.call("getFloodlightStatus", JSONObject().put("floodlight", JSONObject().put("get_floodlight_status", "")))
            findStatus(r)
        }
        LightKind.FORCE_WTL -> {
            val r = client.call("getLdc", JSONObject().put("image", JSONObject().put("name", JSONArray().put("switch"))))
            val v = r.optJSONObject("image")?.optJSONObject("switch")?.opt("force_wtl_state")
                ?: throw TapoException("Sem luz", -40106)
            v.toString() == "on"
        }
    }

    /** Liga ou desliga a luz. [current] = estado conhecido (para o comando de inverter). */
    fun setLight(kind: LightKind, on: Boolean, current: Boolean?) {
        when (kind) {
            LightKind.WHITELAMP -> {
                if (current == null || current != on) {
                    client.call("reverseWhitelampStatus", JSONObject().put("image", JSONObject().put("reverse_wtl_status", JSONArray().put("null"))))
                }
            }
            LightKind.FLOODLIGHT -> client.call(
                "manualFloodlightOp",
                JSONObject().put("floodlight", JSONObject().put("manual_floodlight_op", JSONObject().put("action", if (on) "start" else "stop"))),
            )
            LightKind.FORCE_WTL -> client.call(
                "setLdc",
                JSONObject().put("image", JSONObject().put("switch", JSONObject().put("force_wtl_state", if (on) "on" else "off"))),
            )
        }
    }

    companion object {
        /** Códigos que querem dizer "esta câmera não tem essa função". */
        val NOT_SUPPORTED = setOf(-40105, -40106, -40210, -40101, -40209, -1)

        fun parsePresets(r: JSONObject): List<TapoPreset> {
            val p = r.optJSONObject("preset")?.optJSONObject("preset") ?: return emptyList()
            val ids = p.optJSONArray("id") ?: return emptyList()
            val names = p.optJSONArray("name") ?: JSONArray()
            return (0 until ids.length()).map { i ->
                val id = ids.optString(i)
                TapoPreset(id, names.optString(i).ifEmpty { "Preset $id" })
            }.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
        }

        /** Procura um campo "status"/"enabled" em qualquer nível da resposta (cada firmware responde de um jeito). */
        fun findStatus(o: JSONObject): Boolean? {
            for (k in listOf("status", "enabled", "floodlight_status", "wtl_status")) {
                if (o.has(k)) {
                    val v = o.opt(k).toString().lowercase()
                    return v == "1" || v == "on" || v == "true" || v == "start"
                }
            }
            for (k in o.keys()) {
                val child = o.optJSONObject(k) ?: continue
                findStatus(child)?.let { return it }
            }
            return null
        }
    }
}
