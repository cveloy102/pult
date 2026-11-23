package ru.hl100.pult

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

private val PIN = "A".repeat(64)
private val PC = Connection("https://192.168.1.5:47591", PIN, "token", "HL100")
private val WAKE = listOf(WakeTarget("34:5A:60:CE:DA:08", "192.168.1.255"))

private fun status(volume: Int? = 30, error: String? = null, wake: List<WakeTarget> = emptyList()) =
    Status("HL100", volume, false, null, error, wake = wake)

private class FakeStore(override var connection: Connection? = PC) : PultStore {
    override var theme = "signal"
    override var checkUpdates = true
    override var widgetMessage = "Открыть Пульт"
    override var lastVolume = 0
    override var wake: List<WakeTarget> = emptyList()
}

private class FakeClient : PcClient {
    /** Answers for the next status calls, in order; afterwards [defaultStatus] or [statusError]. */
    val statusAnswers = ArrayDeque<suspend () -> Status>()
    var defaultStatus = status()
    var statusError: Exception? = null
    var statusCalls = 0
    val commands = mutableListOf<String>()
    var commandGate: CompletableDeferred<Unit>? = null
    val candidates = mutableMapOf<String, Candidate>()
    val pairedWith = mutableListOf<String>()
    val woken = mutableListOf<List<WakeTarget>>()

    override suspend fun status(connection: Connection): Status {
        statusCalls++
        statusAnswers.removeFirstOrNull()?.let { return it() }
        statusError?.let { throw it }
        return defaultStatus
    }
    override suspend fun command(connection: Connection, action: String, value: Int?, timerAction: String?, seconds: Int?, replace: Boolean, deviceId: String?) {
        commands += action
        commandGate?.await()
    }
    override suspend fun probe(address: String) = candidates[address] ?: throw ConnectException("unreachable")
    override suspend fun pair(candidate: Candidate, code: String, phoneName: String): Connection {
        pairedWith += candidate.address
        return Connection(candidate.address, candidate.fingerprint, "new-token", candidate.name)
    }
    override suspend fun discover() = emptyList<Candidate>()
    override suspend fun wake(targets: List<WakeTarget>): Int { woken += targets; return targets.size * 12 }
    override suspend fun findUpdate(): AndroidRelease? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class PultControllerTest {
    private val store = FakeStore()
    private val client = FakeClient()
    private var widgetRefreshes = 0

    private fun TestScope.controller(connection: Connection? = PC): PultController {
        store.connection = connection
        return PultController(this, store, client, "Test phone", StandardTestDispatcher(testScheduler)) { widgetRefreshes++ }
    }

    @Test fun stalePollNeverOverwritesNewerCommandResult() = runTest {
        val c = controller()
        val slowPoll = CompletableDeferred<Status>()
        client.statusAnswers += { slowPoll.await() }   // poll started before the command
        client.statusAnswers += { status(volume = 70) } // status read right after the command
        launch { c.refreshStatus() }
        runCurrent()
        c.setVolume(70)
        advanceUntilIdle()
        assertEquals(70, c.state.value.status?.volume)
        slowPoll.complete(status(volume = 20))
        advanceUntilIdle()
        assertEquals("the old poll result must be dropped", 70, c.state.value.status?.volume)
    }

    @Test fun unreachablePcAndRevokedAccessUpdateWidget() = runTest {
        val c = controller()
        client.statusError = ConnectException("down")
        c.refreshStatus(); advanceUntilIdle()
        assertFalse(c.state.value.connected)
        assertEquals("Нет связи с ПК", store.widgetMessage)
        client.statusError = ApiException(401, "revoked")
        c.refreshStatus(); advanceUntilIdle()
        assertEquals("Доступ отозван", store.widgetMessage)
    }

    @Test fun widgetsRedrawOnlyOnReconnect() = runTest {
        val c = controller()
        repeat(3) { c.refreshStatus(); advanceUntilIdle() }
        assertTrue(c.state.value.connected)
        assertEquals(1, widgetRefreshes)
        assertEquals("Последний ответ: ПК доступен", store.widgetMessage)
    }

    @Test fun agentErrorShownOnceAsMessage() = runTest {
        val c = controller()
        client.defaultStatus = status(error = "Пропущенный таймер отменён.")
        c.refreshStatus(); advanceUntilIdle()
        assertEquals("Пропущенный таймер отменён.", c.state.value.message)
        c.messageShown("Пропущенный таймер отменён.")
        c.refreshStatus(); advanceUntilIdle()
        assertEquals("", c.state.value.message)
    }

    @Test fun wakeOfferedOnlyWhileOfflineWithKnownAddress() = runTest {
        val c = controller()
        assertFalse(c.state.value.canWake)
        client.defaultStatus = status(wake = WAKE)
        c.refreshStatus(); advanceUntilIdle()
        assertEquals(WAKE, store.wake)
        assertFalse("PC is on: nothing to wake", c.state.value.canWake)
        client.statusError = ConnectException("off")
        c.refreshStatus(); advanceUntilIdle()
        assertTrue(c.state.value.canWake)
        c.wake(); c.wake(); advanceUntilIdle()
        assertEquals("double tap sends one burst", listOf(WAKE), client.woken)
        assertTrue(c.state.value.message.startsWith("Сигнал включения отправлен"))
    }

    @Test fun wakeAddressSurvivesRestart() = runTest {
        store.wake = WAKE
        val c = controller()
        client.statusError = ConnectException("off")
        c.refreshStatus(); advanceUntilIdle()
        assertTrue(c.state.value.canWake)
    }

    @Test fun repeatedTapSendsCommandOnce() = runTest {
        val c = controller()
        client.commandGate = CompletableDeferred()
        c.send("playPause"); runCurrent()
        c.send("playPause"); runCurrent()
        client.commandGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("playPause"), client.commands)
        assertTrue(c.state.value.sending.isEmpty())
    }

    @Test fun pollSkippedWhileCommandRuns() = runTest {
        val c = controller()
        client.commandGate = CompletableDeferred()
        c.send("lock"); runCurrent()
        val before = client.statusCalls
        c.refreshStatus(); runCurrent()
        assertEquals(before, client.statusCalls)
        client.commandGate!!.complete(Unit); advanceUntilIdle()
        assertEquals("Команда блокировки принята.", c.state.value.message)
    }

    @Test fun powerCommandDoesNotWaitForStatus() = runTest {
        val c = controller()
        c.send("shutdown"); advanceUntilIdle()
        assertEquals(listOf("shutdown"), client.commands)
        assertEquals(0, client.statusCalls)
        assertEquals("Команда принята агентом.", c.state.value.message)
    }

    @Test fun commandErrorBecomesReadableMessage() = runTest {
        val c = controller()
        client.statusError = SSLHandshakeException("bad").apply { initCause(CertificateException("pin")) }
        c.send("volumeStep", 5); advanceUntilIdle()
        assertEquals("Сертификат ПК не совпадает. Проверь агент и привяжи ПК заново.", c.state.value.message)
    }

    @Test fun qrPairingRequiresMatchingCertificate() = runTest {
        val c = controller()
        client.candidates["192.168.1.5:47591"] = Candidate("https://192.168.1.5:47591", "HL100", "B".repeat(64), 1)
        c.pairByLink(PairLink(listOf("192.168.1.5:47591"), PIN, "123456", "HL100")); advanceUntilIdle()
        assertTrue(client.pairedWith.isEmpty())
        assertEquals(PC, store.connection)
        assertTrue(c.state.value.message.contains("не совпадает с QR-кодом"))
        assertNull(c.state.value.pairLink)
    }

    @Test fun qrPairingTriesNextAddressAndReplacesPc() = runTest {
        store.wake = WAKE
        val c = controller()
        client.candidates["10.0.0.2:47591"] = Candidate("https://10.0.0.2:47591", "Игровой", PIN, 1)
        c.receiveLink("pult://pair?v=1&a=192.168.9.9%3A47591%2C10.0.0.2%3A47591&f=$PIN&c=123456&n=x", scanned = false)
        assertEquals(TAB_SETTINGS, c.state.value.tab)
        c.pairByLink(c.state.value.pairLink!!); advanceUntilIdle()
        assertEquals(listOf("https://10.0.0.2:47591"), client.pairedWith)
        assertEquals("new-token", store.connection?.token)
        assertEquals("old wake address belongs to the old PC", emptyList<WakeTarget>(), store.wake)
        assertEquals(TAB_REMOTE, c.state.value.tab)
        assertEquals("Компьютер привязан по QR-коду.", c.state.value.message)
    }

    @Test fun invalidLinksExplainWhatHappened() = runTest {
        val c = controller()
        c.receiveLink("https://example.com", scanned = true)
        assertEquals("Это не QR-код Пульта.", c.state.value.message)
        c.receiveLink("pult://pair?v=1", scanned = false)
        assertEquals("QR-код Пульта не распознан. Создай новый код на ПК.", c.state.value.message)
        assertNull(c.state.value.pairLink)
    }

    @Test fun forgetClearsPairingAndWake() = runTest {
        store.wake = WAKE
        val c = controller()
        c.forget()
        assertNull(store.connection)
        assertTrue(store.wake.isEmpty())
        assertEquals("Подключи ПК", store.widgetMessage)
        assertFalse(c.state.value.canWake)
        assertEquals(1, widgetRefreshes)
    }

    @Test fun longActionsDoNotOverlap() = runTest {
        val c = controller(connection = null)
        c.connectTo("192.168.1.5"); c.discover()
        assertTrue(c.state.value.busy)
        advanceUntilIdle()
        assertFalse(c.state.value.busy)
        assertEquals("discover was ignored while busy", "Не удалось подключиться. Проверь Wi-Fi, агент и брандмауэр Windows.", c.state.value.message)
    }

    @Test fun errorTextNeverClaimsTimeoutFailed() {
        assertTrue(errorText(java.net.SocketTimeoutException()).contains("могла быть принята"))
        assertEquals("Не удалось установить защищённое соединение с ПК. Проверь сеть и агент.", errorText(SSLHandshakeException("reset")))
        assertEquals("Код неверный", errorText(ApiException(401, "Код неверный")))
        assertNull(commandResultText("playPause", null, null))
        assertEquals("Блокировка через 90 мин. Таймер запущен на ПК.", commandResultText("timer", "lock", 5400))
    }
}
