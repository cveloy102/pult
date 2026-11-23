package ru.hl100.pult

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Network access to the PC agent and GitHub. Replaced by a fake in unit tests. */
interface PcClient {
    suspend fun status(connection: Connection): Status
    suspend fun command(connection: Connection, action: String, value: Int? = null, timerAction: String? = null, seconds: Int? = null, replace: Boolean = false, deviceId: String? = null)
    suspend fun probe(address: String): Candidate
    suspend fun pair(candidate: Candidate, code: String, phoneName: String): Connection
    suspend fun discover(): List<Candidate>
    suspend fun wake(targets: List<WakeTarget>): Int
    suspend fun findUpdate(): AndroidRelease?
}

/** Real implementation: blocking calls, always invoked by the controller on the IO dispatcher. */
object NetworkClient : PcClient {
    override suspend fun status(connection: Connection) = PultApi.status(connection)
    override suspend fun command(connection: Connection, action: String, value: Int?, timerAction: String?, seconds: Int?, replace: Boolean, deviceId: String?) =
        PultApi.command(connection, action, value, timerAction, seconds, replace, deviceId = deviceId)
    override suspend fun probe(address: String) = PultApi.probe(address)
    override suspend fun pair(candidate: Candidate, code: String, phoneName: String) = PultApi.pair(candidate, code, phoneName)
    override suspend fun discover() = PultApi.discover()
    override suspend fun wake(targets: List<WakeTarget>) = Wake.send(targets)
    override suspend fun findUpdate() = AndroidUpdates.find()
}

/** Persistent app settings. Implemented by [Settings]; replaced by a fake in unit tests. */
interface PultStore {
    var theme: String
    var checkUpdates: Boolean
    var widgetMessage: String
    var lastVolume: Int
    var wake: List<WakeTarget>
    var connection: Connection?
}

/** Everything the screens show. Dialog visibility that has no logic stays in the UI. */
data class UiState(
    val theme: String = THEME_DARK,
    val connection: Connection? = null,
    val status: Status? = null,
    val connected: Boolean = false,
    /** A long single action (pairing, search, update) is running; most buttons are disabled. */
    val busy: Boolean = false,
    /** Commands in flight, by action; a second tap on the same action is ignored. */
    val sending: Set<String> = emptySet(),
    val volumeWrites: Int = 0,
    val lastVolume: Int = 0,
    val message: String = "",
    val tab: String = TAB_REMOTE,
    val candidate: Candidate? = null,
    val found: List<Candidate> = emptyList(),
    val pairLink: PairLink? = null,
    val wakeTargets: List<WakeTarget> = emptyList(),
    val checkUpdates: Boolean = true,
    val update: AndroidRelease? = null,
) {
    val canControl get() = connected && !busy
    val canWake get() = connection != null && !connected && wakeTargets.isNotEmpty()
}

const val TAB_REMOTE = "remote"
const val TAB_TIMER = "timer"
const val TAB_SETTINGS = "settings"
const val ACTION_WAKE = "wake"
private val POWER_ACTIONS = setOf("shutdown", "restart")
private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * App logic without Android UI: status polling, commands, pairing, Wake-on-LAN and updates.
 * All public methods are called on the main thread; network work runs on [io].
 */
class PultController(
    private val scope: CoroutineScope,
    private val store: PultStore,
    private val client: PcClient,
    private val phoneName: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val refreshWidgets: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(
        UiState(
            theme = store.theme,
            connection = store.connection,
            wakeTargets = store.wake,
            checkUpdates = store.checkUpdates,
            lastVolume = store.lastVolume,
        )
    )
    val state: StateFlow<UiState> = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private val commandMutex = Mutex()

    // Incremented by every command; a status poll started earlier must not overwrite newer state.
    private var commandEpoch = 0

    private fun set(change: UiState.() -> UiState) = mutableState.update { it.change() }

    fun showMessage(text: String) = set { copy(message = text) }

    /** Called by the UI after the snackbar for [text] was shown. */
    fun messageShown(text: String) = set { if (message == text) copy(message = "") else this }

    fun selectTab(tab: String) = set { copy(tab = tab) }

    // ----- Status -----

    /** One status poll. Polling never holds the command lock, so buttons are not delayed by a slow request. */
    suspend fun refreshStatus() {
        val selected = current.connection ?: return
        if (commandMutex.isLocked) return
        val epoch = commandEpoch
        try {
            val result = withContext(io) { client.status(selected) }
            if (current.connection == selected && epoch == commandEpoch && !commandMutex.isLocked) applyStatus(result)
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            if (current.connection != selected || epoch != commandEpoch) return
            set { copy(connected = false) }
            updateWidget(if (ex is ApiException && ex.status == 401) "Доступ отозван" else "Нет связи с ПК")
        }
    }

    private fun applyStatus(result: Status) {
        val before = current
        val volume = result.volume
        if (volume != null && volume != store.lastVolume) store.lastVolume = volume
        val wake = if (result.wake.isNotEmpty() && result.wake != before.wakeTargets) result.wake.also { store.wake = it } else before.wakeTargets
        val error = result.error
        set {
            copy(
                status = result,
                connected = true,
                wakeTargets = wake,
                lastVolume = volume ?: lastVolume,
                message = if (error != null && error != before.status?.error) error else message,
            )
        }
        // Only on reconnect: keeps widget command results visible and avoids redraws every poll.
        if (!before.connected) updateWidget("Последний ответ: ПК доступен")
    }

    private fun updateWidget(text: String) {
        if (store.widgetMessage != text) {
            store.widgetMessage = text
            refreshWidgets()
        }
    }

    // ----- Commands -----

    fun setVolume(value: Int) {
        val selected = current.connection ?: return
        set { copy(volumeWrites = volumeWrites + 1) }
        scope.launch {
            try {
                commandMutex.withLock {
                    commandEpoch++
                    withContext(io) { client.command(selected, "volume", value) }
                    val result = withContext(io) { client.status(selected) }
                    if (current.connection == selected) applyStatus(result)
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                showMessage(errorText(ex))
            } finally {
                set { copy(volumeWrites = volumeWrites - 1) }
            }
        }
    }

    fun send(action: String, value: Int? = null, timerAction: String? = null, seconds: Int? = null, replace: Boolean = false, deviceId: String? = null) {
        val selected = current.connection ?: return
        if (action in current.sending) return
        set { copy(sending = sending + action) }
        scope.launch {
            try {
                commandMutex.withLock {
                    commandEpoch++
                    withContext(io) { client.command(selected, action, value, timerAction, seconds, replace, deviceId) }
                    // Power commands can disconnect the PC immediately after acceptance.
                    if (action !in POWER_ACTIONS) {
                        val result = withContext(io) { client.status(selected) }
                        if (current.connection == selected) applyStatus(result)
                    }
                }
                commandResultText(action, timerAction, seconds)?.let(::showMessage)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                showMessage(errorText(ex))
            } finally {
                set { copy(sending = sending - action) }
            }
        }
    }

    /** Wake-on-LAN to the last address the agent reported while the PC was on. */
    fun wake() {
        val targets = current.wakeTargets
        if (targets.isEmpty() || ACTION_WAKE in current.sending) return
        set { copy(sending = sending + ACTION_WAKE) }
        scope.launch {
            try {
                val sent = withContext(io) { client.wake(targets) }
                showMessage(if (sent > 0) "Сигнал включения отправлен. ПК появится в сети через 30–90 секунд." else "Не удалось отправить сигнал. Проверь Wi-Fi.")
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                showMessage(errorText(ex))
            } finally {
                set { copy(sending = sending - ACTION_WAKE) }
            }
        }
    }

    /** Runs one long action at a time and reports its error as a message. */
    private fun task(block: suspend () -> Unit) {
        if (current.busy) return
        set { copy(busy = true) }
        scope.launch {
            try {
                block()
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                showMessage(errorText(ex))
            } finally {
                set { copy(busy = false) }
            }
        }
    }

    // ----- Pairing -----

    fun connectTo(address: String) = task {
        val pc = withContext(io) { client.probe(address) }
        set { copy(candidate = pc) }
    }

    fun discover() = task {
        val pcs = withContext(io) { client.discover() }
        set { copy(found = pcs) }
        if (pcs.isEmpty()) showMessage("ПК не найден. Запусти агент или введи адрес вручную.")
    }

    fun dismissCandidate() = set { copy(candidate = null) }

    /** Manual pairing after the user compared the fingerprint and typed the code. */
    fun pair(candidate: Candidate, code: String) = task {
        val result = withContext(io) { client.pair(candidate, code, phoneName) }
        adopt(result, "Компьютер привязан.")
    }

    /** A pult://pair link from the system camera ([scanned] = false) or the in-app scanner. */
    fun receiveLink(text: String?, scanned: Boolean) {
        val link = text?.let { PairLink.parse(it) }
        when {
            link != null -> set { copy(pairLink = link, tab = if (scanned) tab else TAB_SETTINGS) }
            scanned -> showMessage("Это не QR-код Пульта.")
            else -> showMessage("QR-код Пульта не распознан. Создай новый код на ПК.")
        }
    }

    fun dismissPairLink() = set { copy(pairLink = null) }

    /** Pairs using a QR link: tries each address; the certificate must match the QR code exactly. */
    fun pairByLink(link: PairLink) = task {
        var failure: Exception? = null
        for (address in link.addresses) {
            val pc = try {
                withContext(io) { client.probe(address) }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                failure = ex
                continue
            }
            // The QR code is the trusted channel: the certificate must match it exactly.
            if (pc.fingerprint != link.fingerprint) {
                failure = Exception("Сертификат ПК не совпадает с QR-кодом. Создай новый код на ПК.")
                continue
            }
            val result = withContext(io) { client.pair(pc, link.code, phoneName) }
            adopt(result, "Компьютер привязан по QR-коду.")
            return@task
        }
        set { copy(pairLink = null) }
        throw failure ?: Exception("ПК не найден.")
    }

    private suspend fun adopt(result: Connection, text: String) {
        store.wake = emptyList()
        store.connection = result
        set {
            copy(
                connection = result, status = null, connected = false, wakeTargets = emptyList(),
                candidate = null, pairLink = null, tab = TAB_REMOTE, message = text,
            )
        }
        refreshStatus()
    }

    fun forget() {
        store.connection = null
        store.wake = emptyList()
        store.widgetMessage = "Подключи ПК"
        set { copy(connection = null, status = null, connected = false, wakeTargets = emptyList()) }
        refreshWidgets()
    }

    // ----- Settings and updates -----

    fun chooseTheme(key: String) {
        val theme = normalizeTheme(key)
        store.theme = theme
        set { copy(theme = theme) }
        refreshWidgets()
    }

    fun setCheckUpdates(enabled: Boolean) {
        store.checkUpdates = enabled
        set { copy(checkUpdates = enabled) }
    }

    fun checkUpdate() = task {
        val release = withContext(io) { client.findUpdate() }
        if (release == null) showMessage("Установлена актуальная версия Android-приложения.") else set { copy(update = release) }
    }

    /** Background check at most once a day; errors are silent so LAN control is never affected. */
    fun checkUpdateOnStart(lastCheck: Long, now: Long, saveCheck: (Long) -> Unit) {
        if (!store.checkUpdates || now - lastCheck <= DAY_MS) return
        scope.launch {
            try {
                val release = withContext(io) { client.findUpdate() }
                saveCheck(now)
                if (release != null) showMessage("Доступен Пульт ${release.version}. Открой настройки → обновления.")
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
            }
        }
    }

    fun dismissUpdate() = set { copy(update = null) }

    /** [install] downloads and opens the system installer; it needs an Activity, so the UI provides it. */
    fun installUpdate(release: AndroidRelease, install: suspend (AndroidRelease) -> Unit) = task {
        install(release)
        set { copy(update = null) }
    }
}
