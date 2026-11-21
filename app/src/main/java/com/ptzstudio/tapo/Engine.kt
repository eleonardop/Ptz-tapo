package com.ptzstudio.tapo

import android.content.Context
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ptzstudio.tapo.core.AppSettings
import com.ptzstudio.tapo.core.CameraConfig
import com.ptzstudio.tapo.core.DefaultMapping
import com.ptzstudio.tapo.core.FoundCamera
import com.ptzstudio.tapo.core.LightKind
import com.ptzstudio.tapo.core.MappingEngine
import com.ptzstudio.tapo.core.MotionConfig
import com.ptzstudio.tapo.core.PadAction
import com.ptzstudio.tapo.core.PadButton
import com.ptzstudio.tapo.core.PadState
import com.ptzstudio.tapo.core.PresetSlots
import com.ptzstudio.tapo.core.PtzController
import com.ptzstudio.tapo.core.SettingsCodec
import com.ptzstudio.tapo.core.TapoApi
import com.ptzstudio.tapo.core.TapoAuth
import com.ptzstudio.tapo.core.TapoClient
import com.ptzstudio.tapo.core.TapoException
import com.ptzstudio.tapo.core.TapoInfo
import com.ptzstudio.tapo.core.TapoMotor
import com.ptzstudio.tapo.core.TapoPreset
import com.ptzstudio.tapo.core.WsDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

/** Estado da ligação com a câmera. */
enum class LinkState(val label: String) {
    NO_CAMERA("Nenhuma câmera"),
    CONNECTING("Conectando…"),
    ONLINE("Câmera conectada"),
    NO_REPLY("Câmera sem resposta"),
    /** Senha errada ou bloqueio: o app para de tentar (cada tentativa errada conta para o bloqueio da câmera). */
    LOGIN_FAILED("Login recusado"),
}

/** O que a detecção de botão encontrou. */
sealed interface DetectEvent {
    data class Button(val button: PadButton) : DetectEvent
    data class UnknownKey(val keyCode: Int) : DetectEvent
}

/** Luz da câmera: tipo e se está acesa (nulo = a câmera não informou). */
data class LightState(val kind: LightKind, val on: Boolean?)

/**
 * Coração do app: guarda as configurações, fala com a câmera Tapo, lê o controle físico e roda o
 * ciclo de controle (50 vezes por segundo) numa thread própria, independente da tela.
 */
class Engine(private val context: Context) {
    private val prefs = context.getSharedPreferences("ptz_tapo", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _link = MutableStateFlow(LinkState.NO_CAMERA)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _linkDetail = MutableStateFlow<String?>(null)
    /** Explicação do último problema de conexão (senha errada, bloqueio, sem resposta...). */
    val linkDetail: StateFlow<String?> = _linkDetail.asStateFlow()

    private val _info = MutableStateFlow<TapoInfo?>(null)
    /** Modelo, nome e firmware informados pela câmera. */
    val info: StateFlow<TapoInfo?> = _info.asStateFlow()

    private val _auth = MutableStateFlow<TapoAuth?>(null)
    val auth: StateFlow<TapoAuth?> = _auth.asStateFlow()

    private val _cameraPresets = MutableStateFlow<List<TapoPreset>>(emptyList())
    /** Presets guardados na câmera. */
    val cameraPresets: StateFlow<List<TapoPreset>> = _cameraPresets.asStateFlow()

    private val _light = MutableStateFlow<LightState?>(null)
    /** Luz da câmera (nulo = sem luz ou ainda não descoberta). */
    val light: StateFlow<LightState?> = _light.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** Gravando um preset (a tela mostra que está trabalhando). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _gamepad = MutableStateFlow<String?>(null)
    /** Nome do controle físico conectado (nulo = nenhum). */
    val gamepad: StateFlow<String?> = _gamepad.asStateFlow()

    private val _pad = MutableStateFlow(PadState())
    /** Estado ao vivo do controle (para o desenho e a detecção). */
    val pad: StateFlow<PadState> = _pad.asStateFlow()

    private val _active = MutableStateFlow<Set<PadButton>>(emptySet())
    /** Botões, gatilhos e analógicos acionados agora (para acender no desenho). */
    val active: StateFlow<Set<PadButton>> = _active.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Avisos curtos para mostrar na tela. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _photoRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    /** Pedido de foto vindo do controle físico (a tela tira a foto do vídeo). */
    val photoRequests: SharedFlow<Unit> = _photoRequests.asSharedFlow()

    @Volatile private var foreground = true

    /** Rede Wi-Fi atual. As conexões são presas a ela para os comandos não saírem pelos dados móveis. */
    @Volatile private var wifiNetwork: Network? = null

    // Conexão com a câmera em uso
    @Volatile private var api: TapoApi? = null
    @Volatile private var apiKey: String? = null
    /** Fila única dos pedidos para a câmera (o movimento usa a sua própria thread). */
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "tapo-rede").apply { isDaemon = true } }
    private val healthRunning = AtomicBoolean(false)
    @Volatile private var loaded = false
    @Volatile private var lastMoveError = 0L

    private val motor = TapoMotor(
        move = { x, y ->
            // Com o login recusado, não tenta de novo a cada passo (a câmera bloquearia a conta).
            if (_link.value == LinkState.LOGIN_FAILED) throw TapoException(_linkDetail.value ?: "Login recusado")
            api?.move(x, y) ?: throw TapoException("Sem câmera")
        },
        stepMax = { _settings.value.moveStep },
        onError = { e -> onMoveError(e) },
    )
    val controller = PtzController(motor)
    private val mapping = MappingEngine(controller) { a -> main.post { runAction(a) } }

    private val loop = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ptz-ciclo").apply { isDaemon = true } }
    private var lastTickNs = System.nanoTime()
    private var lastHealthMs = 0L

    // Entrada do controle físico (só na thread principal)
    private val keysDown = mutableSetOf<PadButton>()
    private val hatDown = mutableSetOf<PadButton>()
    private var keyLt = 0.0
    private var keyRt = 0.0
    private var axisState = PadState()
    /** Cópia dos analógicos/gatilhos para o ciclo (outra thread) checar se o controle parou de mandar sinal. */
    @Volatile private var analogSnapshot = PadState()
    @Volatile private var lastGamepadEventMs = 0L
    /** Controle que está comandando (com dois controles ligados, um não atrapalha o outro). */
    private var activeDeviceId: Int? = null

    /** Na tela de mapeamento o controle não mexe a câmera; os botões só são mostrados no desenho. */
    @Volatile var mappingMode = false
        set(value) {
            field = value
            pushPad()
        }

    @Volatile private var detectListener: ((DetectEvent) -> Unit)? = null

    init {
        controller.config = _settings.value.motion
        controller.limits = com.ptzstudio.tapo.core.CameraLimits()
        motor.start()
        applyCamera()
        loop.scheduleAtFixedRate({ safeTick() }, 0, 20, TimeUnit.MILLISECONDS)
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        im.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshGamepad()
            override fun onInputDeviceRemoved(deviceId: Int) {
                refreshGamepad()
                if (activeDeviceId == null || activeDeviceId == deviceId) {
                    // O controle que estava comandando sumiu: para tudo até as entradas voltarem ao neutro.
                    clearPad()
                    controller.safetyStop(SystemClock.elapsedRealtime())
                }
            }
            override fun onInputDeviceChanged(deviceId: Int) = refreshGamepad()
        }, main)
        refreshGamepad()
        watchWifi()
    }

    // ---------------- Configurações guardadas ----------------

    private fun loadSettings(): AppSettings {
        val stored = prefs.getString(KEY_SETTINGS, null)
        val text = SecretStore.open(stored)
        if (stored != null && text == null) {
            // A chave do celular não abriu (ex.: dados restaurados de outro aparelho): começa do zero.
            prefs.edit().putString(KEY_SETTINGS_BACKUP, stored).apply()
            return AppSettings()
        }
        val parsed = SettingsCodec.tryParse(text)
        if (parsed == null) {
            // Arquivo estragado: guarda uma cópia antes que a próxima mudança grave por cima.
            prefs.edit().putString(KEY_SETTINGS_BACKUP, stored).apply()
            return AppSettings()
        }
        return parsed
    }

    private fun store(s: AppSettings) {
        val json = SettingsCodec.toJson(s)
        prefs.edit().putString(KEY_SETTINGS, SecretStore.seal(json) ?: json).apply()
    }

    // ---------------- Rede Wi-Fi ----------------

    private fun bindToWifi(s: DatagramSocket) {
        wifiNetwork?.bindSocket(s)
    }

    /**
     * Acompanha a rede Wi-Fi (mesmo sem internet). Quando ela aparece ou muda, a câmera é reconectada por ela.
     */
    private fun watchWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        if (wifiNetwork != network) {
                            wifiNetwork = network
                            applyCamera(force = true)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    main.post { if (wifiNetwork == network) wifiNetwork = null }
                }
            }, main)
        } catch (_: Exception) {
            // Sem acesso ao estado da rede: segue sem prender ao Wi-Fi.
        }
    }

    // ---------------- Ciclo ----------------

    private fun safeTick() {
        try {
            val nowNs = System.nanoTime()
            val dt = ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.1)
            lastTickNs = nowNs
            val nowMs = SystemClock.elapsedRealtime()
            controller.tick(dt, nowMs)
            checkInputWatchdog(nowMs)
            if (foreground && nowMs - lastHealthMs >= HEALTH_INTERVAL_MS) {
                lastHealthMs = nowMs
                health()
            }
        } catch (_: Throwable) {
            // O ciclo nunca pode parar por causa de um erro.
        }
    }

    /** Confere a câmera de tempos em tempos (na fila da rede). Na primeira vez, carrega presets e luz. */
    private fun health() {
        val a = api ?: return
        if (_link.value == LinkState.LOGIN_FAILED) return
        if (!healthRunning.compareAndSet(false, true)) return
        net.execute {
            try {
                val i = a.info()
                if (a !== api) return@execute
                _info.value = i
                _auth.value = a.client.auth
                if (!loaded) {
                    loaded = true
                    loadPresetsAndLight(a)
                }
                _link.value = LinkState.ONLINE
                _linkDetail.value = null
            } catch (e: TapoException) {
                if (a !== api) return@execute
                onCameraError(e)
            } catch (e: Exception) {
                if (a !== api) return@execute
                _link.value = LinkState.NO_REPLY
                _linkDetail.value = "A câmera não respondeu. Confira o IP e se o celular está no mesmo Wi-Fi."
            } finally {
                healthRunning.set(false)
            }
        }
    }

    private fun onCameraError(e: TapoException) {
        val loginProblem = e.code == -40411 || e.code == -40404 || (e.message ?: "").contains("Senha", true) ||
            (e.message ?: "").contains("dona da câmera")
        if (loginProblem) {
            // Para de tentar: cada senha errada conta para o bloqueio da câmera.
            _link.value = LinkState.LOGIN_FAILED
            controller.safetyStop(SystemClock.elapsedRealtime())
            motor.halt()
        } else if (_link.value != LinkState.ONLINE) {
            _link.value = LinkState.NO_REPLY
        }
        _linkDetail.value = e.message
    }

    private fun loadPresetsAndLight(a: TapoApi) {
        try {
            _cameraPresets.value = a.presets()
        } catch (e: TapoException) {
            if (e.code != null && e.code in TapoApi.NOT_SUPPORTED) _cameraPresets.value = emptyList() else throw e
        }
        _light.value = try {
            a.detectLight()?.let { LightState(it.first, it.second) }
        } catch (e: TapoException) {
            null
        }
    }

    private fun onMoveError(e: Exception) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMoveError < 3000) return
        lastMoveError = now
        if (e is TapoException) {
            if (e.code == -40411 || e.code == -40404) main.post { onCameraError(e) }
            say(e.message ?: "Erro ao mover")
        } else {
            say("A câmera não respondeu ao movimento")
        }
    }

    /**
     * Segurança para controle Bluetooth que trava sem se desconectar: se um analógico ou gatilho está fora do
     * centro e o controle não manda nenhum sinal por [INPUT_WATCHDOG_MS], a câmera para.
     */
    private fun checkInputWatchdog(nowMs: Long) {
        if (!_settings.value.motion.inputWatchdogEnabled) return
        val a = analogSnapshot
        val dz = _settings.value.motion.deadzonePercent / 100.0
        val held = hypot(a.lsX, a.lsY) > dz || hypot(a.rsX, a.rsY) > dz
        if (held && lastGamepadEventMs != 0L && nowMs - lastGamepadEventMs > INPUT_WATCHDOG_MS) {
            lastGamepadEventMs = nowMs
            analogSnapshot = PadState()
            controller.safetyStop(nowMs)
            main.post { clearPad() }
            say("Controle sem sinal: câmera parada")
        }
    }

    /** Monta a conexão com a câmera escolhida (de novo só se o IP ou a senha mudaram, ou se [force]). */
    private fun applyCamera(force: Boolean = false) {
        val cam = _settings.value.selectedCamera
        val key = cam?.let { "${it.id}|${it.ip.trim()}|${it.password}" }
        if (!force && key == apiKey) return
        controller.stopNow(SystemClock.elapsedRealtime())
        motor.halt()
        apiKey = key
        loaded = false
        _info.value = null
        _auth.value = null
        _cameraPresets.value = emptyList()
        _light.value = null
        _linkDetail.value = null
        if (cam == null || cam.ip.isBlank()) {
            api = null
            _link.value = LinkState.NO_CAMERA
            return
        }
        api = TapoApi(TapoClient(AndroidTapoHttp(cam.ip.trim(), { wifiNetwork }), cam.password))
        _link.value = LinkState.CONNECTING
        if (cam.password.isEmpty()) {
            _link.value = LinkState.LOGIN_FAILED
            _linkDetail.value = "Falta a senha da conta Tapo em Câmeras › Editar."
            return
        }
        lastHealthMs = 0L
    }

    /** Passos do último login com a câmera (para descobrir por que ela recusou). */
    fun loginTrace(): List<String> = api?.client?.loginTrace.orEmpty()

    /** Tenta conectar de novo (depois de corrigir a senha ou esperar o bloqueio passar). */
    fun reconnect() = applyCamera(force = true)

    // ---------------- Ciclo de vida ----------------

    fun onForeground() {
        foreground = true
        lastHealthMs = 0L
        refreshGamepad()
    }

    /** App saiu da tela: o Android não entrega mais o controle, então tudo para (e só volta a mover depois de soltar). */
    fun onBackground() {
        foreground = false
        clearPad()
        controller.safetyStop(SystemClock.elapsedRealtime())
        motor.halt()
    }

    /**
     * A janela do app perdeu o foco (cortina de notificações, aviso do sistema, tela dividida, janelas do app):
     * o "soltou" do controle iria para a outra janela, então para agora.
     */
    fun onFocusLost() {
        clearPad()
        controller.safetyStop(SystemClock.elapsedRealtime())
        motor.halt()
    }

    /** Usado quando o app vai fechar por erro: para de mandar movimento. */
    fun emergencyStop() {
        controller.stopNow(SystemClock.elapsedRealtime())
        motor.halt()
    }

    // ---------------- Configurações ----------------

    fun update(transform: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        store(new)
        controller.config = new.motion
        applyCamera()
    }

    fun setMotion(m: MotionConfig) = update { it.copy(motion = m.sanitized()) }

    fun setMoveStep(step: Int) = update { it.copy(moveStep = step.coerceIn(1, AppSettings.MAX_STEP)) }

    fun saveCamera(cam: CameraConfig) {
        val old = _settings.value.cameras.firstOrNull { it.id == cam.id }
        update { s ->
            val list = if (s.cameras.any { it.id == cam.id }) s.cameras.map { if (it.id == cam.id) cam else it } else s.cameras + cam
            s.copy(cameras = list, selectedCameraId = s.selectedCameraId ?: cam.id)
        }
        // Senha corrigida: tenta de novo mesmo se o IP é o mesmo.
        if (old != null && old.password != cam.password && cam.id == _settings.value.selectedCamera?.id) reconnect()
    }

    fun removeCamera(id: String) = update { s ->
        val list = s.cameras.filterNot { it.id == id }
        s.copy(cameras = list, selectedCameraId = if (s.selectedCameraId == id) list.firstOrNull()?.id else s.selectedCameraId)
    }

    fun selectCamera(id: String) = update { it.copy(selectedCameraId = id) }

    fun setMapping(button: PadButton, action: PadAction) = update { s ->
        s.copy(mapping = DefaultMapping.sanitize(s.mapping + (button to action)))
    }

    fun resetMapping() = update { it.copy(mapping = DefaultMapping.map) }

    fun learnKey(keyCode: Int, button: PadButton) = update { s ->
        s.copy(learnedKeys = s.learnedKeys.filterValues { it != button } + (keyCode to button))
    }

    fun renamePreset(n: Int, name: String) = update { s ->
        val names = s.presetNames.toMutableList()
        if (n in 1..names.size) names[n - 1] = name.trim().ifEmpty { "Preset $n" }
        s.copy(presetNames = names)
    }

    fun setVideoEnabled(on: Boolean) = update { it.copy(videoEnabled = on) }

    // ---------------- Comandos da tela ----------------

    /** Maior velocidade da barra (o passo de cada pedido é proporcional a ela). */
    val maxPanSpeed: Int get() = com.ptzstudio.tapo.core.CameraLimits().maxPan

    fun setVirtualStick(x: Double, y: Double) = controller.setVirtualStick(x, y)
    fun setScreenPanTilt(pan: Int, tilt: Int) = controller.setScreenPanTilt(pan, tilt)

    /** Preset da câmera em cada botão do app (1..8). */
    fun resolvedPresets(): Map<Int, TapoPreset> =
        PresetSlots.resolve(_settings.value.selectedCamera?.presetSlots.orEmpty(), _cameraPresets.value)

    /** Nome mostrado no botão: o nome dado no app, senão o nome do preset na câmera. */
    fun presetLabel(n: Int, resolved: Map<Int, TapoPreset> = resolvedPresets()): String {
        val custom = _settings.value.presetNames.getOrNull(n - 1)
        if (custom != null && custom != "Preset $n") return custom
        return resolved[n]?.name ?: "Preset $n"
    }

    private fun onNet(what: String, block: (TapoApi) -> Unit) {
        val a = api
        if (a == null) {
            say("Nenhuma câmera escolhida")
            return
        }
        if (_link.value == LinkState.LOGIN_FAILED) {
            say(_linkDetail.value ?: "Login recusado: confira a senha em Câmeras › Editar")
            return
        }
        net.execute {
            try {
                block(a)
            } catch (e: TapoException) {
                say(e.message ?: "$what: erro")
                if (e.code == -40411 || e.code == -40404) main.post { onCameraError(e) }
            } catch (e: Exception) {
                say("$what: a câmera não respondeu")
            }
        }
    }

    fun recallPreset(n: Int) {
        val p = resolvedPresets()[n]
        if (p == null) {
            say("${presetLabel(n)} está vazio: segure o botão para gravar a posição")
            return
        }
        controller.latchUntilNeutral()
        motor.halt()
        say(presetLabel(n))
        onNet("Preset") { it.gotoPreset(p.id) }
    }

    /**
     * Grava a posição atual no botão [n]. A Tapo não regrava um preset: o antigo do botão é apagado e um novo
     * é criado com o mesmo nome; o botão passa a apontar para o novo.
     */
    fun savePreset(n: Int) {
        val camId = _settings.value.selectedCamera?.id ?: return
        if (api == null) return say("Nenhuma câmera escolhida")
        val resolved = resolvedPresets()
        val name = presetLabel(n, resolved)
        val old = resolved[n]
        _busy.value = true
        onNet("Gravar preset") { a ->
            try {
                val before = a.presets()
                if (old != null && before.any { it.id == old.id }) a.deletePreset(old.id)
                a.addPreset(name)
                val after = a.presets()
                val created = PresetSlots.newPreset(before.map { it.id }.toSet(), old?.id, after)
                    ?: throw TapoException("A câmera não confirmou o preset novo")
                _cameraPresets.value = after
                main.post {
                    update { s ->
                        s.copy(cameras = s.cameras.map { c ->
                            if (c.id != camId) c else {
                                // Congela os botões como estão agora, para nenhum outro mudar de lugar.
                                val fixed = resolved.mapValues { it.value.id }.filterKeys { it != n } + (n to created.id)
                                c.copy(presetSlots = c.presetSlots + fixed)
                            }
                        })
                    }
                }
                say("Posição salva em $name")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Lê de novo os presets da câmera (feitos no app Tapo, por exemplo). */
    fun reloadPresets() = onNet("Presets") { a ->
        _cameraPresets.value = a.presets()
        say("Presets atualizados: ${_cameraPresets.value.size} na câmera")
    }

    fun toggleLight() {
        val l = _light.value
        if (l == null) {
            say(if (_link.value == LinkState.ONLINE) "Esta câmera não tem luz para acender" else "Câmera ainda não conectada")
            return
        }
        val want = l.on != true
        onNet("Luz") { a ->
            a.setLight(l.kind, want, l.on)
            val now = try { a.lightOn(l.kind) } catch (_: Exception) { want }
            _light.value = LightState(l.kind, now ?: want)
            say(if ((now ?: want)) "Luz acesa" else "Luz apagada")
        }
    }

    fun takePhoto() {
        _photoRequests.tryEmit(Unit)
    }

    fun stopCamera() {
        controller.stopNow(SystemClock.elapsedRealtime())
        motor.halt()
    }

    /** Sobe ou desce a velocidade do Pan/Tilt um passo. */
    fun changeSpeed(delta: Int) {
        val m = _settings.value.motion
        val max = maxPanSpeed
        val level = (m.speedLevel(max) + delta).coerceIn(1, max)
        setMotion(m.withSpeedLevel(level, max))
        say("Velocidade $level de $max")
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    private fun runAction(a: PadAction) {
        val p = a.presetNumber
        when {
            p != null -> recallPreset(p)
            a == PadAction.SPEED_UP -> changeSpeed(+1)
            a == PadAction.SPEED_DOWN -> changeSpeed(-1)
            a == PadAction.PHOTO -> takePhoto()
            a == PadAction.LIGHT -> toggleLight()
            a == PadAction.STOP -> { stopCamera(); say("Câmera parada") }
            else -> {}
        }
    }

    // ---------------- Câmeras na rede ----------------

    /** A câmera responde na porta 443? (sem login: não gasta tentativa de senha) */
    suspend fun probe(cam: CameraConfig): Boolean = withContext(Dispatchers.IO) {
        if (cam.ip.isBlank()) return@withContext false
        try {
            val s = java.net.Socket()
            wifiNetwork?.bindSocket(s)
            s.use { it.connect(InetSocketAddress(cam.ip.trim(), 443), 1500) }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun discover(): List<FoundCamera> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("tapo-descoberta")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            WsDiscovery.discover(3000, ::bindToWifi)
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    // ---------------- Controle físico ----------------

    fun startDetection(listener: (DetectEvent) -> Unit) {
        detectListener = listener
        pushPad()
    }

    fun stopDetection() {
        detectListener = null
        pushPad()
    }

    private fun isGamepad(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val s = device.sources
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun refreshGamepad() {
        val name = InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { isGamepad(it) }
            ?.name
        _gamepad.value = name
    }

    private fun clearPad() {
        keysDown.clear(); hatDown.clear(); keyLt = 0.0; keyRt = 0.0; axisState = PadState()
        analogSnapshot = PadState()
        activeDeviceId = null
        mapping.reset()
        pushPad()
    }

    /** O controle informa a pressão dos gatilhos como eixo (aí o "botão" L2/R2 é ignorado). */
    private fun hasAnalogTriggers(dev: InputDevice?): Boolean {
        if (dev == null) return false
        return listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            .any { dev.getMotionRange(it) != null }
    }

    private fun padIsNeutral(): Boolean =
        keysDown.isEmpty() && hatDown.isEmpty() && keyLt == 0.0 && keyRt == 0.0 &&
            hypot(axisState.lsX, axisState.lsY) < 0.2 && hypot(axisState.rsX, axisState.rsY) < 0.2 &&
            axisState.lt < 0.1 && axisState.rt < 0.1

    /** Com dois controles ligados, só um comanda por vez (o outro é ignorado enquanto o primeiro está em uso). */
    private fun acceptDevice(id: Int): Boolean {
        val cur = activeDeviceId
        if (cur != null && cur != id && !padIsNeutral()) return false
        activeDeviceId = id
        return true
    }

    /** Recebe teclas da Activity. Devolve true se era do controle (e foi tratada). */
    fun onKeyEvent(e: KeyEvent): Boolean {
        if (!isGamepad(e.device) && !KeyEvent.isGamepadButton(e.keyCode)) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return true
        if (down && e.repeatCount > 0) return true
        if (!acceptDevice(e.deviceId)) return true
        lastGamepadEventMs = SystemClock.elapsedRealtime()
        val learned = _settings.value.learnedKeys[e.keyCode]
        val button = learned ?: keyToButton(e.keyCode)
        if (button == null) {
            if (down) detectListener?.let { l -> l(DetectEvent.UnknownKey(e.keyCode)) }
            return true
        }
        val analogTriggers = hasAnalogTriggers(e.device)
        when (button) {
            // Se o controle manda a pressão do gatilho como eixo, o "botão" L2/R2 não pode passar por cima dela
            // (senão um toque leve viraria zoom no máximo).
            PadButton.LT -> if (!analogTriggers) keyLt = if (down) 1.0 else 0.0
            PadButton.RT -> if (!analogTriggers) keyRt = if (down) 1.0 else 0.0
            else -> if (down) keysDown += button else keysDown -= button
        }
        pushPad()
        return true
    }

    /** Recebe analógicos, gatilhos e direcional (eixos) da Activity. */
    fun onMotionEvent(e: MotionEvent): Boolean {
        if (!isGamepad(e.device)) return false
        if (e.action != MotionEvent.ACTION_MOVE) return true
        if (!acceptDevice(e.deviceId)) return true
        lastGamepadEventMs = SystemClock.elapsedRealtime()
        val dev = e.device
        fun axis(a: Int): Double = e.getAxisValue(a).toDouble()
        fun has(a: Int): Boolean = dev?.getMotionRange(a, e.source) != null
        // Gatilho num eixo que vai de -1 a 1 (repouso em -1) vira 0..1.
        fun trigger(a: Int): Double {
            val r = dev?.getMotionRange(a, e.source) ?: return 0.0
            val v = axis(a)
            return if (r.min < -0.5f) (v - r.min) / (r.max - r.min) else v
        }
        val dedicatedTriggers = has(MotionEvent.AXIS_LTRIGGER) || has(MotionEvent.AXIS_BRAKE) ||
            has(MotionEvent.AXIS_RTRIGGER) || has(MotionEvent.AXIS_GAS)
        val rx: Double
        val ry: Double
        val lt: Double
        val rt: Double
        if (dedicatedTriggers || !has(MotionEvent.AXIS_RX)) {
            // Padrão Xbox/Android (GameSir): analógico direito em Z/RZ, gatilhos em eixos próprios.
            rx = axis(MotionEvent.AXIS_Z); ry = -axis(MotionEvent.AXIS_RZ)
            lt = max(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
            rt = max(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
        } else {
            // Sem eixos próprios de gatilho: analógico direito em RX/RY e gatilhos em Z/RZ.
            rx = axis(MotionEvent.AXIS_RX); ry = -axis(MotionEvent.AXIS_RY)
            lt = if (has(MotionEvent.AXIS_Z)) trigger(MotionEvent.AXIS_Z) else 0.0
            rt = if (has(MotionEvent.AXIS_RZ)) trigger(MotionEvent.AXIS_RZ) else 0.0
        }
        axisState = PadState(
            lsX = axis(MotionEvent.AXIS_X), lsY = -axis(MotionEvent.AXIS_Y),
            rsX = rx, rsY = ry, lt = lt.coerceIn(0.0, 1.0), rt = rt.coerceIn(0.0, 1.0),
        )
        val hx = axis(MotionEvent.AXIS_HAT_X)
        val hy = axis(MotionEvent.AXIS_HAT_Y)
        hatDown.clear()
        if (hx < -0.5) hatDown += PadButton.DPAD_LEFT
        if (hx > 0.5) hatDown += PadButton.DPAD_RIGHT
        if (hy < -0.5) hatDown += PadButton.DPAD_UP
        if (hy > 0.5) hatDown += PadButton.DPAD_DOWN
        analogSnapshot = axisState
        pushPad()
        return true
    }

    private var lastActive: Set<PadButton> = emptySet()

    private fun pushPad() {
        val state = axisState.copy(
            pressed = keysDown + hatDown,
            lt = max(axisState.lt, keyLt),
            rt = max(axisState.rt, keyRt),
        )
        _pad.value = state
        val active = buildSet {
            addAll(state.pressed)
            if (hypot(state.lsX, state.lsY) > DETECT_STICK) add(PadButton.LS)
            if (hypot(state.rsX, state.rsY) > DETECT_STICK) add(PadButton.RS)
            if (state.lt > DETECT_TRIGGER) add(PadButton.LT)
            if (state.rt > DETECT_TRIGGER) add(PadButton.RT)
        }
        _active.value = active
        val listener = detectListener
        if (listener != null) {
            (active - lastActive).firstOrNull()?.let { listener(DetectEvent.Button(it)) }
        }
        lastActive = active
        // Na detecção ou no mapeamento, o controle não mexe a câmera.
        if (listener != null || mappingMode) {
            mapping.update(PadState(), _settings.value.mapping)
        } else {
            mapping.update(state, _settings.value.mapping)
        }
    }

    private fun keyToButton(code: Int): PadButton? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> PadButton.A
        KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
        KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
        KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.LB
        KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.RB
        KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.LT
        KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.RT
        KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> PadButton.VIEW
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadButton.MENU
        KeyEvent.KEYCODE_BUTTON_MODE -> PadButton.HOME
        KeyEvent.KEYCODE_DPAD_UP -> PadButton.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.DPAD_RIGHT
        else -> null
    }

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_SETTINGS_BACKUP = "settings_corrompido"
        private const val HEALTH_INTERVAL_MS = 4000L
        /** Analógico/gatilho fora do centro sem nenhum sinal do controle por este tempo = parada de segurança. */
        const val INPUT_WATCHDOG_MS = 5000L
        const val DETECT_STICK = 0.8
        const val DETECT_TRIGGER = 0.6
    }
}
