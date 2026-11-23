package ru.hl100.pult

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

/** Connection (QR, address, search), theme and updates. */
@Composable
fun SettingsScreen(state: UiState, p: Palette, controller: PultController, onScanQr: () -> Unit, onPickTheme: () -> Unit) {
    var address by remember { mutableStateOf(state.connection?.address ?: "") }

    Text("Подключение", fontSize = 25.sp, color = p.ink)
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Сканировать QR-код", p, !state.busy, onScanQr)
    Text("QR-код — в агенте на ПК: «Подключить телефон».", fontSize = 11.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("IP-адрес ПК") },
        placeholder = { Text("192.168.1.2:47591") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Spacer(Modifier.height(10.dp))
    PrimaryButton(if (state.busy) "Подожди…" else "Подключить по адресу", p, !state.busy) { controller.connectTo(address) }
    OutlinedButton(
        onClick = { controller.discover() },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        shape = RoundedCornerShape(p.radius.dp),
    ) { Text("Найти ПК в сети", color = p.ink) }
    state.found.forEach { pc ->
        TextButton(onClick = { controller.connectTo(pc.address) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(pc.name, color = p.ink)
                Text(pc.address, fontSize = 11.sp, color = p.sub)
            }
        }
    }
    if (state.connection != null) {
        TextButton(onClick = { controller.forget() }, enabled = !state.busy) { Text("Забыть компьютер", color = p.sub) }
    }

    Spacer(Modifier.height(25.dp))
    Text("Оформление", fontSize = 24.sp, color = p.ink)
    TextButton(onClick = onPickTheme, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Тема оформления", color = p.ink)
            Text(themeName(state.theme) + " ›", color = p.sub)
        }
    }
    Text("Виджеты используют эту же тему.", fontSize = 11.sp, color = p.sub)

    Spacer(Modifier.height(23.dp))
    Text("Обновления", fontSize = 24.sp, color = p.ink)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Проверять при запуске", color = p.ink, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = state.checkUpdates, onCheckedChange = { controller.setCheckUpdates(it) })
    }
    Text("Android ${BuildConfig.VERSION_NAME} · протокол ${PultApi.PROTOCOL}", fontSize = 12.sp, color = p.sub)
    OutlinedButton(
        onClick = { controller.checkUpdate() },
        enabled = !state.busy,
        shape = RoundedCornerShape(p.radius.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) { Text("Проверить обновления", color = p.ink) }
}
