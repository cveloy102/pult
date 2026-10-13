package ru.hl100.pult

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class Candidate(val address: String, val name: String, val fingerprint: String, val protocol: Int)
data class TimerStatus(val action: String, val dueAt: String)
data class AudioOutput(val id: String, val name: String, val selected: Boolean)
data class Status(val name: String, val volume: Int?, val muted: Boolean, val timer: TimerStatus?, val error: String?, val outputs: List<AudioOutput> = emptyList(), val outputSelection: Boolean = false)
class ApiException(val status: Int, message: String) : Exception(message)

object PultApi {
    const val PROTOCOL = 1
    private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
    fun normalizeAddress(input: String): String {
        val url = URI(if (input.trim().startsWith("https://")) input.trim() else "https://${input.trim()}")
        require(url.scheme == "https" && url.host != null && url.userInfo == null && url.rawQuery == null && url.rawFragment == null && (url.path.isNullOrEmpty() || url.path == "/")) { "Введи IP или адрес HTTPS без пути, например 192.168.1.2:47591." }
        // Local IP literals only: no remote hosts, DNS rebinding, or credential forwarding.
        val host = url.host.removePrefix("[").removeSuffix("]")
        require(host.matches(Regex("[0-9.]+")) || host.contains(":") && host.matches(Regex("[0-9a-fA-F:]+"))) { "Для подключения введи локальный IP-адрес ПК." }
        val ip = InetAddress.getByName(host)
        require(ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isLoopbackAddress || (ip.address.size == 16 && (ip.address[0].toInt() and 0xFE) == 0xFC)) { "Пульт работает только в локальной сети." }
        val port = if (url.port < 0) 47591 else url.port
        require(port in 1..65535) { "Неверный порт." }
        return "https://${if (host.contains(":")) "[$host]" else host}:$port"
    }
    private fun request(address: String, path: String, pin: String?, token: String?, body: JSONObject? = null): Pair<JSONObject, String> {
        require(pin != null || path == "/api/info" && token == null && body == null) { "Не подтверждена идентичность ПК." }
        var observed = ""
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Client certificates unsupported")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("No certificate")
                observed = fingerprint(chain[0])
                if (pin != null && !MessageDigest.isEqual(observed.toByteArray(), pin.uppercase().toByteArray())) throw java.security.cert.CertificateException("Сертификат ПК изменился. Привяжи компьютер заново.")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        val connection = URL(normalizeAddress(address) + path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        // Identity is verified by the out-of-band certificate pin, not its pult.local hostname.
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connection.connectTimeout = 4000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = try { JSONObject(text) } catch (_: Exception) { throw ApiException(code, "ПК вернул некорректный ответ.") }
            if (code !in 200..299) throw ApiException(code, json.optString("error", "Запрос не выполнен ($code)."))
            return json to observed
        } finally { connection.disconnect() }
    }
    fun probe(address: String): Candidate {
        val normalized = normalizeAddress(address); val (json, pin) = request(normalized, "/api/info", null, null)
        require(json.getInt("protocol") == PROTOCOL) { "Версии приложения и агента несовместимы. Обнови нужную часть." }
        require(json.getString("fingerprint") == pin) { "Некорректная идентичность агента." }
        return Candidate(normalized, json.getString("name"), pin, json.getInt("protocol"))
    }
    fun pair(candidate: Candidate, code: String, phoneName: String): Connection {
        require(candidate.fingerprint.matches(Regex("[A-F0-9]{64}")))
        val (json, _) = request(candidate.address, "/api/pair", candidate.fingerprint, null, JSONObject().put("code", code.replace(" ", "")).put("name", phoneName.take(80)))
        val agent = json.getJSONObject("agent")
        require(agent.getInt("protocol") == PROTOCOL && agent.getString("fingerprint") == candidate.fingerprint) { "Несовместимый агент." }
        return Connection(candidate.address, candidate.fingerprint, json.getString("token"), agent.getString("name"))
    }
    fun status(connection: Connection): Status {
        val json = request(connection.address, "/api/status", connection.fingerprint, connection.token).first
        require(json.getInt("protocol") == PROTOCOL) { "Версии протокола несовместимы." }
        val audio = json.optJSONObject("audio"); val timer = json.optJSONObject("timer")
        val outputs = json.optJSONArray("outputs")
        val devices = if (outputs == null) emptyList() else (0 until outputs.length()).map { index ->
            val device = outputs.getJSONObject(index)
            AudioOutput(device.getString("id"), device.getString("name"), device.optBoolean("selected"))
        }
        val capabilities = json.optJSONArray("capabilities")
        val outputSelection = capabilities != null && (0 until capabilities.length()).any { capabilities.optString(it) == "audioOutput" }
        return Status(json.getString("name"), audio?.getInt("volume"), audio?.optBoolean("muted") ?: false,
            timer?.let { TimerStatus(it.getString("action"), it.getString("dueAt")) }, json.optString("error").takeIf { it.isNotBlank() && it != "null" }, devices, outputSelection)
    }
    fun command(connection: Connection, action: String, value: Int? = null, timerAction: String? = null, seconds: Int? = null, replace: Boolean = false, requestId: String = UUID.randomUUID().toString(), deviceId: String? = null) {
        val body = JSONObject().put("requestId", requestId).put("action", action).put("replace", replace)
        value?.let { body.put("value", it) }; timerAction?.let { body.put("timerAction", it) }; seconds?.let { body.put("durationSeconds", it) }
        deviceId?.let { body.put("deviceId", it) }
        request(connection.address, "/api/command", connection.fingerprint, connection.token, body)
    }
    fun discover(): List<Candidate> {
        val nonce = UUID.randomUUID().toString(); val bytes = "PULT_DISCOVER_V1:$nonce".toByteArray()
        val found = linkedMapOf<String, Candidate>()
        DatagramSocket().use { socket ->
            socket.broadcast = true; socket.soTimeout = 500
            val targets = linkedSetOf(InetAddress.getByName("255.255.255.255"))
            java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach { targets.add(it) }
            targets.forEach { target -> try { socket.send(DatagramPacket(bytes, bytes.size, target, 47592)) } catch (_: Exception) { } }
            val deadline = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(ByteArray(2048), 2048); socket.receive(packet)
                    val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (json.optString("requestId") != nonce || json.optInt("protocol") != PROTOCOL) continue
                    val address = normalizeAddress("${packet.address.hostAddress}:${json.getInt("port")}")
                    val pin = json.getString("fingerprint"); if (!pin.matches(Regex("[A-F0-9]{64}"))) continue
                    found[address] = Candidate(address, json.getString("name").take(80), pin, PROTOCOL)
                } catch (_: java.net.SocketTimeoutException) { } catch (_: Exception) { }
            }
        }
        return found.values.toList()
    }
}
