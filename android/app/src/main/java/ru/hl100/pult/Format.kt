package ru.hl100.pult

/** Countdown text: MM:SS under an hour, H:MM:SS from an hour. */
fun formatRemaining(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)

fun timerActionName(action: String) = when (action) {
    "shutdown" -> "Выключение"
    "restart" -> "Перезагрузка"
    "lock" -> "Блокировка"
    else -> "Неизвестное действие"
}

const val THEME_SYSTEM = "system"
const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"

/** Maps any stored value to a current choice. Before 0.3 the themes were "signal", "quiet" (light) and "orbit"; a new install follows the system. */
fun normalizeTheme(theme: String?) = when (theme) {
    THEME_LIGHT, "quiet" -> THEME_LIGHT
    THEME_DARK, "signal", "orbit" -> THEME_DARK
    else -> THEME_SYSTEM
}

/** The theme actually drawn: "system" follows the phone's dark mode. */
fun resolveTheme(theme: String, systemDark: Boolean) = when (normalizeTheme(theme)) {
    THEME_SYSTEM -> if (systemDark) THEME_DARK else THEME_LIGHT
    else -> normalizeTheme(theme)
}

fun themeName(theme: String) = when (theme) {
    THEME_LIGHT -> "Светлая"
    THEME_DARK -> "Тёмная"
    else -> "Как в системе"
}

/** Snackbar text after a command was accepted; null for play/pause, whose result is audible. */
fun commandResultText(action: String, timerAction: String?, seconds: Int?): String? = when (action) {
    "playPause" -> null
    "audioOutput" -> "Устройство вывода переключено."
    "timer" -> "${timerActionName(timerAction ?: "")} через ${(seconds ?: 0) / 60} мин. Таймер запущен на ПК."
    "cancelTimer" -> "Таймер отменён."
    "lock" -> "Команда блокировки принята."
    "shutdown", "restart" -> "Команда принята агентом."
    else -> "Громкость изменена."
}

/** Error text for the user. A timeout never claims the command failed: it may have been accepted. */
fun errorText(ex: Throwable): String = when (ex) {
    is ApiException -> ex.message ?: "Команда не выполнена."
    is javax.net.ssl.SSLException ->
        if (generateSequence<Throwable>(ex) { it.cause }.any { it is java.security.cert.CertificateException })
            "Сертификат ПК не совпадает. Проверь агент и привяжи ПК заново."
        else "Не удалось установить защищённое соединение с ПК. Проверь сеть и агент."
    is java.net.SocketTimeoutException -> "ПК не ответил. Команда могла быть принята — проверь состояние перед повтором."
    is java.net.ConnectException -> "Не удалось подключиться. Проверь Wi-Fi, агент и брандмауэр Windows."
    else -> ex.message?.take(180) ?: "Не удалось выполнить действие."
}
