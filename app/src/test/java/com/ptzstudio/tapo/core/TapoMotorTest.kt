package com.ptzstudio.tapo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapoMotorTest {
    @Test
    fun leOsComandosDoControlador() {
        assertEquals(-24 to 20, TapoMotor.parsePanTilt(ViscaCommands.panTiltDrive(1, -24, 20)))
        assertEquals(5 to -3, TapoMotor.parsePanTilt(ViscaCommands.panTiltDrive(1, 5, -3)))
        assertEquals(0 to 0, TapoMotor.parsePanTilt(ViscaCommands.panTiltStop(1)))
        assertNull(TapoMotor.parsePanTilt(ViscaCommands.zoom(1, 3)))
    }

    @Test
    fun passoProporcionalAVelocidade() {
        val m = TapoMotor(move = { _, _ -> }, stepMax = { 10 })
        assertNull(m.nextStep())
        m.send(ViscaCommands.panTiltDrive(1, 24, 0))
        assertEquals(10 to 0, m.nextStep())
        m.send(ViscaCommands.panTiltDrive(1, -12, -20))
        assertEquals(-5 to -10, m.nextStep())
        m.send(ViscaCommands.panTiltDrive(1, 1, 1))
        assertEquals(1 to 1, m.nextStep()) // devagar ainda anda
        m.halt()
        assertNull(m.nextStep())
    }

    @Test
    fun mandaPassosEnquantoHaVelocidadeEParaAoSoltar() {
        val steps = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
        val m = TapoMotor(move = { x, y -> steps += x to y; Thread.sleep(5) }, stepMax = { 8 }, pauseMs = 5)
        m.start()
        try {
            m.send(ViscaCommands.panTiltDrive(1, 24, 0))
            Thread.sleep(150)
            m.send(ViscaCommands.panTiltStop(1))
            Thread.sleep(60)
            val n = steps.size
            assertTrue("passos: $n", n >= 3)
            Thread.sleep(150)
            assertTrue("continuou depois de soltar", steps.size <= n + 1)
            assertTrue(steps.all { it == (8 to 0) })
        } finally {
            m.stop()
        }
    }

    @Test
    fun fimDeCursoNaoEhErro() {
        val errors = mutableListOf<Exception>()
        val m = TapoMotor(move = { _, _ -> throw TapoException("fim", -64304) }, stepMax = { 5 }, onError = { errors += it }, pauseMs = 1)
        m.start()
        m.send(ViscaCommands.panTiltDrive(1, 24, 0))
        Thread.sleep(100)
        m.stop()
        assertTrue(errors.isEmpty())
    }
}
