package com.ptzstudio.tapo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ptzstudio.tapo.BuildConfig
import com.ptzstudio.tapo.Engine
import com.ptzstudio.tapo.core.AppSettings
import com.ptzstudio.tapo.core.MotionConfig

@Composable
fun SettingsScreen(engine: Engine, onBack: () -> Unit, onMapping: () -> Unit) {
    val settings by engine.settings.collectAsState()
    val gamepad by engine.gamepad.collectAsState()
    val info by engine.info.collectAsState()
    val auth by engine.auth.collectAsState()
    val light by engine.light.collectAsState()
    val link by engine.link.collectAsState()
    val detail by engine.linkDetail.collectAsState()
    val m = settings.motion
    fun set(new: MotionConfig) = engine.setMotion(new)

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        ScreenBar("Ajustes", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Vídeo ao vivo")
            Panel(Modifier.fillMaxWidth()) {
                SwitchRow("Mostrar o vídeo da câmera na tela de controle", settings.videoEnabled) { engine.setVideoEnabled(it) }
                Hint("O vídeo usa a \"Conta da câmera\" criada no app Tapo (Câmeras › Editar).")
            }

            SectionTitle("Controle físico")
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusChip(
                        gamepad ?: "Nenhum controle conectado",
                        if (gamepad != null) Palette.Green else Palette.Muted,
                        Palette.Chip,
                        if (gamepad != null) Palette.Text else Palette.Muted,
                    )
                }
                Hint("Conecte o GameSir pelo Bluetooth do celular (modo Android/Xbox) ou por cabo USB-C.")
                PadTile("Mapear botões do controle", Modifier.fillMaxWidth(), onClick = onMapping)
                SwitchRow("Segurança: parar se o controle parar de mandar sinal", m.inputWatchdogEnabled) { set(m.copy(inputWatchdogEnabled = it)) }
                Hint(
                    "Se um analógico estiver fora do centro e o controle ficar 5 s sem mandar nenhum sinal " +
                        "(Bluetooth travado), a câmera para. Se ela parar sozinha em movimentos longos e lentos, desligue.",
                )
            }

            SectionTitle("Movimento")
            Panel(Modifier.fillMaxWidth()) {
                LevelSlider("Velocidade do Pan/Tilt", m.speedLevel(engine.maxPanSpeed), engine.maxPanSpeed, Modifier.fillMaxWidth()) {
                    set(m.withSpeedLevel(it, engine.maxPanSpeed))
                }
                LevelSlider("Tamanho máximo do passo", settings.moveStep, AppSettings.MAX_STEP, Modifier.fillMaxWidth()) {
                    engine.setMoveStep(it)
                }
                Hint(
                    "A Tapo não tem velocidade: o app manda passos seguidos enquanto você segura. Este é o tamanho do passo com o " +
                        "analógico todo inclinado e a velocidade no máximo. Passo maior = anda mais rápido, mas para um pouco depois de soltar.",
                )
                PercentSlider("Aceleração gradual", m.rampPercent, 0..100) { set(m.copy(rampPercent = it)) }
                Hint("0% = começa na velocidade final. 100% = leva cerca de 2 segundos para chegar à velocidade final.")
                PercentSlider("Zona morta", m.deadzonePercent, 0..30) { set(m.copy(deadzonePercent = it)) }
                Hint("Folga do centro do analógico que é ignorada (evita a câmera andar sozinha).")
                PercentSlider("Movimento fino", m.finePercent, 5..100) { set(m.copy(finePercent = it)) }
                PercentSlider("Trava de diagonal", m.diagonalLockDeg, 0..44, unit = "°") { set(m.copy(diagonalLockDeg = it)) }
                Hint("Se o analógico está quase na horizontal ou na vertical, o outro eixo é ignorado (sem diagonais sem querer).")
                SwitchRow("Inverter vertical (Tilt)", m.invertTilt) { set(m.copy(invertTilt = it)) }
                SwitchRow("Inverter horizontal (Pan)", m.invertPan) { set(m.copy(invertPan = it)) }
            }

            SectionTitle("Câmera em uso")
            Panel(Modifier.fillMaxWidth()) {
                LinkChip(link)
                detail?.let { Text(it, color = Palette.Soft, fontSize = 13.sp) }
                val i = info
                if (i != null) {
                    Text("Modelo: ${i.model}" + (if (i.name.isNotBlank()) " · ${i.name}" else ""), color = Palette.Text, fontSize = 14.sp)
                    Text("Firmware: ${i.firmware}", color = Palette.Soft, fontSize = 13.sp)
                }
                auth?.let { Text(it.label, color = Palette.Soft, fontSize = 13.sp) }
                Text(
                    when (val l = light) {
                        null -> "Luz: não encontrada nesta câmera"
                        else -> "Luz: comando ${l.kind.name.lowercase()}" + when (l.on) { true -> " (acesa)"; false -> " (apagada)"; null -> "" }
                    },
                    color = Palette.Soft, fontSize = 13.sp,
                )
                PadTile("Conectar de novo", Modifier.fillMaxWidth()) { engine.reconnect() }
                var showTrace by remember { mutableStateOf(false) }
                val clipboard = LocalClipboardManager.current
                if (!showTrace) {
                    PadTile("Ver detalhes do login", Modifier.fillMaxWidth()) { showTrace = true }
                } else {
                    val trace = engine.loginTrace()
                    val text = (listOf("PTZ Tapo ${BuildConfig.VERSION_NAME}", "Modelo: ${info?.model ?: "?"} ${info?.firmware ?: ""}") +
                        trace.ifEmpty { listOf("(ainda sem tentativa de login)") }).joinToString("\n")
                    Text(text, color = Palette.Soft, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PadTile("Copiar", Modifier.weight(1f)) {
                            clipboard.setText(AnnotatedString(text))
                            engine.say("Detalhes copiados")
                        }
                        PadTile("Fechar", Modifier.weight(1f)) { showTrace = false }
                    }
                    Hint("Não mostra a senha. Copie e envie para quem está ajudando a configurar.")
                }
                Hint("Depois de uma senha errada o app para de tentar, porque a câmera bloqueia após várias tentativas. Corrija a senha e toque aqui.")
            }

            Text(
                "PTZ Tapo ${BuildConfig.VERSION_NAME}",
                color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PercentSlider(label: String, value: Int, range: IntRange, unit: String = "%", onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("$value$unit", color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Palette.AccentBright, activeTrackColor = Palette.AccentBright),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.Muted, fontSize = 12.sp)
}
