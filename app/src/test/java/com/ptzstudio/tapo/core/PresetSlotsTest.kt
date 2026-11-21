package com.ptzstudio.tapo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetSlotsTest {
    private val cam = listOf(TapoPreset("1", "Altar"), TapoPreset("2", "Coral"), TapoPreset("5", "Porta"))

    @Test
    fun semNadaLigado_usaAOrdemDaCamera() {
        val r = PresetSlots.resolve(emptyMap(), cam)
        assertEquals("Altar", r[1]?.name)
        assertEquals("Coral", r[2]?.name)
        assertEquals("Porta", r[3]?.name)
        assertNull(r[4])
    }

    @Test
    fun ligadosDePropositoFicam_eOsOutrosCompletam() {
        val r = PresetSlots.resolve(mapOf(4 to "1", 1 to "5"), cam)
        assertEquals("Porta", r[1]?.name)
        assertEquals("Coral", r[2]?.name)
        assertNull(r[3])
        assertEquals("Altar", r[4]?.name)
    }

    @Test
    fun presetApagadoNaCamera_botaoFicaVazio() {
        val r = PresetSlots.resolve(mapOf(1 to "9"), cam)
        assertNull(r[1])
        assertEquals("Altar", r[2]?.name)
    }

    @Test
    fun descobreOPresetNovo_mesmoReaproveitandoONumero() {
        val after = listOf(TapoPreset("1", "Altar"), TapoPreset("2", "Novo"), TapoPreset("5", "Porta"))
        assertEquals("Novo", PresetSlots.newPreset(setOf("1", "2", "5"), "2", after)?.name)
        val after2 = cam + TapoPreset("6", "Extra")
        assertEquals("6", PresetSlots.newPreset(setOf("1", "2", "5"), null, after2)?.id)
    }
}
