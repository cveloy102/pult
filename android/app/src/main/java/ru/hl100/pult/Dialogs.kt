package ru.hl100.pult

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shutdown/restart confirmation. [action] is "shutdown" or "restart". */
@Composable
fun PowerConfirmDialog(action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val restart = action == "restart"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (restart) "Перезагрузить ПК?" else "Выключить ПК?") },
        text = { Text("Сохрани открытые документы. Windows может попросить подтвердить их закрытие.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (restart) "Перезагрузить" else "Выключить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ThemeDialog(state: UiState, p: Palette, controller: PultController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Тема оформления") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(THEME_DARK, THEME_LIGHT).forEach { key ->
                    val colors = palette(key)
                    OptionRow(themeName(key), state.theme == key, p, trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(colors.base, colors.surface, colors.accent).forEach { color ->
                                Box(Modifier.size(18.dp).background(color, RoundedCornerShape(4.dp)).border(1.dp, p.line, RoundedCornerShape(4.dp)))
                            }
                        }
                    }) { controller.chooseTheme(key) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

/** Windows playback devices; choosing one switches the system output. */
@Composable
fun OutputDialog(state: UiState, p: Palette, controller: PultController, onDismiss: () -> Unit) {
    val devices = state.status?.outputs.orEmpty()
    val switching = "audioOutput" in state.sending
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        tonalElevation = 0.dp,
        titleContentColor = p.ink,
        textContentColor = p.ink,
        title = { Text("Устройство вывода") },
        text = {
            Column {
                Text("Системный звук Windows. Программы со своим выбором устройства могут использовать его.", fontSize = 12.sp, color = p.sub)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                    if (devices.isEmpty()) Text("Подключённых устройств вывода нет.", color = p.sub)
                    devices.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = state.connected && !switching) { onDismiss(); controller.send("audioOutput", deviceId = device.id) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = device.selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(device.name, fontSize = 13.sp, color = p.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

/** Manual pairing: compare the fingerprint with the PC window and type the one-time code. */
@Composable
fun PairDialog(pc: Candidate, state: UiState, p: Palette, controller: PultController) {
    var verified by remember(pc) { mutableStateOf(false) }
    var code by remember(pc) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.busy) controller.dismissCandidate() },
        title = { Text(pc.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Сверь отпечаток с окном «Подключить телефон» на ПК.", fontSize = 13.sp)
                Text(
                    pc.fingerprint.chunked(4).joinToString(" "), fontSize = 12.sp, lineHeight = 20.sp, color = p.ink,
                    modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth().card(p, Radius.control).padding(12.dp),
                )
                OptionRow("Отпечаток совпадает", verified, p) { verified = !verified }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Код с экрана ПК") },
                    singleLine = true,
                    colors = fieldColors(p),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = verified && code.length == 6 && !state.busy, onClick = { controller.pair(pc, code) }) { Text("Привязать") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = { controller.dismissCandidate() }) { Text("Отмена") } },
    )
}

/** Confirmation before pairing from a QR link; the link already carries the fingerprint and code. */
@Composable
fun PairLinkDialog(link: PairLink, state: UiState, controller: PultController) {
    val replaced = state.connection?.let { "\n\nТекущий ПК «${it.name}» будет заменён." } ?: ""
    AlertDialog(
        onDismissRequest = { if (!state.busy) controller.dismissPairLink() },
        title = { Text("Подключить «${link.name}»?") },
        text = { Text("Отпечаток и код взяты из QR-кода на экране ПК.\nАдрес: ${link.addresses.joinToString(", ")}$replaced") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { controller.pairByLink(link) }) { Text(if (state.busy) "Подключаю…" else "Подключить") } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = { controller.dismissPairLink() }) { Text("Отмена") } },
    )
}

@Composable
fun UpdateDialog(release: AndroidRelease, state: UiState, controller: PultController, onInstall: (AndroidRelease) -> Unit) {
    AlertDialog(
        onDismissRequest = { controller.dismissUpdate() },
        title = { Text("Пульт ${release.version}") },
        text = {
            Column {
                Text("Доступна новая версия Android-приложения.")
                Spacer(Modifier.height(10.dp))
                Text(release.notes.take(600), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { onInstall(release) }) { Text("Скачать и установить") } },
        dismissButton = { TextButton(onClick = { controller.dismissUpdate() }) { Text("Позже") } },
    )
}
