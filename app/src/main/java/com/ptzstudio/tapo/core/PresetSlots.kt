package com.ptzstudio.tapo.core

/**
 * Liga os 8 botões de preset do app aos presets guardados na câmera Tapo (que têm número e nome próprios).
 * Botões já ligados a um preset que ainda existe ficam como estão; os outros recebem, em ordem, os presets
 * da câmera que ainda não estão em nenhum botão.
 */
object PresetSlots {
    fun resolve(slots: Map<Int, String>, camera: List<TapoPreset>, count: Int = AppSettings.PRESET_COUNT): Map<Int, TapoPreset> {
        val byId = camera.associateBy { it.id }
        val out = mutableMapOf<Int, TapoPreset>()
        for (n in 1..count) slots[n]?.let { id -> byId[id]?.let { out[n] = it } }
        val used = out.values.map { it.id }.toMutableSet()
        val free = camera.filter { it.id !in used }.iterator()
        for (n in 1..count) {
            if (n in out) continue
            // Um botão ligado de propósito a um preset que foi apagado fica vazio (não "pula" para outro).
            if (slots.containsKey(n)) continue
            if (free.hasNext()) out[n] = free.next()
        }
        return out
    }

    /** Depois de gravar: qual preset é o novo (o que não existia antes; o número do apagado pode ser reaproveitado). */
    fun newPreset(beforeIds: Set<String>, deletedId: String?, after: List<TapoPreset>): TapoPreset? {
        val old = beforeIds - setOfNotNull(deletedId)
        return after.filter { it.id !in old }.maxByOrNull { it.id.toIntOrNull() ?: 0 }
    }
}
