package com.ptzstudio.tapo.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ponte entre o controlador de movimento (que fala "velocidade", como numa câmera PTZ) e a Tapo,
 * que só aceita pedidos de "andar X passos". Recebe os comandos de Pan/Tilt do [PtzController] e,
 * enquanto houver velocidade, manda um pedido de movimento atrás do outro, cada um do tamanho
 * proporcional à velocidade. Ao soltar, para de mandar (o último passo pedido ainda termina).
 */
class TapoMotor(
    private val move: (Int, Int) -> Unit,
    private val stepMax: () -> Int,
    private val onError: (Exception) -> Unit = {},
    private val pauseMs: Long = 60,
) : PacketSender {
    private val lock = Object()
    @Volatile private var pan = 0
    @Volatile private var tilt = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Velocidades atuais pedidas pelo controlador (pan: + direita, tilt: + cima). */
    val current: Pair<Int, Int> get() = pan to tilt

    override fun send(packet: ByteArray) {
        val pt = parsePanTilt(packet) ?: return
        synchronized(lock) {
            pan = pt.first
            tilt = pt.second
            lock.notifyAll()
        }
    }

    /** Para na hora (o passo que já foi pedido ainda termina na câmera). */
    fun halt() = synchronized(lock) {
        pan = 0
        tilt = 0
    }

    /** Tamanho do próximo passo, ou nulo se não há movimento. */
    fun nextStep(): Pair<Int, Int>? {
        val p = pan
        val t = tilt
        if (p == 0 && t == 0) return null
        val max = stepMax().coerceIn(1, AppSettings.MAX_STEP)
        fun size(v: Int, top: Int): Int {
            if (v == 0) return 0
            val s = (max * abs(v).toDouble() / top).roundToInt().coerceIn(1, max)
            return if (v < 0) -s else s
        }
        return size(p, ViscaCommands.MAX_PAN_SPEED) to size(t, ViscaCommands.MAX_TILT_SPEED)
    }

    fun start() = synchronized(lock) {
        if (running) return
        running = true
        thread = Thread({ loop() }, "tapo-motor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() = synchronized(lock) {
        running = false
        lock.notifyAll()
    }

    private fun loop() {
        while (running) {
            val step = synchronized(lock) {
                var s = nextStep()
                while (running && s == null) {
                    lock.wait(500)
                    s = nextStep()
                }
                s
            } ?: continue
            try {
                move(step.first, step.second)
            } catch (e: TapoException) {
                // Chegou no fim do curso: não é erro, só não anda mais para aquele lado.
                if (e.code != -64304) onError(e)
                sleepQuietly(300)
            } catch (e: Exception) {
                onError(e)
                sleepQuietly(500)
            }
            sleepQuietly(pauseMs)
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        /** Lê um comando VISCA de Pan/Tilt (81 01 06 01 VV WW PP TT FF). Devolve (pan, tilt) com sinal, ou nulo se for outro comando. */
        fun parsePanTilt(p: ByteArray): Pair<Int, Int>? {
            if (p.size != 9 || p[1].toInt() != 0x01 || p[2].toInt() != 0x06 || p[3].toInt() != 0x01) return null
            val ps = p[4].toInt() and 0xFF
            val ts = p[5].toInt() and 0xFF
            val pan = when (p[6].toInt()) {
                0x01 -> -ps
                0x02 -> ps
                else -> 0
            }
            val tilt = when (p[7].toInt()) {
                0x01 -> ts
                0x02 -> -ts
                else -> 0
            }
            return pan to tilt
        }
    }
}
