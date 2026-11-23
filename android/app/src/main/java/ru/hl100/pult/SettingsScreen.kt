package ru.hl100.pult

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Connection (QR, address, search), theme and updates. */
@Composable
fun SettingsScreen(state: UiState, p: Palette, controller: PultController, onScanQr: () -> Unit, onPickTheme: () -> Unit) {
    var address by remember { mutableStateOf(state.connection?.address ?: "") }

    Spacer(Modifier.height(24.dp))
    ScreenTitle("Настройки", p)
    SectionLabel("// Подключение", p)
    PrimaryButton("Сканировать QR-код ↗", p, !state.busy, onScanQr)
    Text("QR-код — в агенте на ПК: «Подключить телефон».", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("IP-адрес ПК") },
        placeholder = { Text("192.168.1.2:47591") },
        singleLine = true,
        colors = fieldColors(p),
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(if (state.busy) "Подожди…" else "Подключить по адресу →", p, enabled = !state.busy) { controller.connectTo(address) }
        SecondaryButton("Найти ПК в сети →", p, enabled = !state.busy) { controller.discover() }
    }
    if (state.found.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().card(p).padding(horizontal = 16.dp)) {
            state.found.forEachIndexed { index, pc ->
                if (index > 0) HorizontalDivider(color = p.line)
                InfoRow(pc.address, pc.name + " →", p) { if (!state.busy) controller.connectTo(pc.address) }
            }
        }
    }
    if (state.connection != null) TextAction("Забыть компьютер ×", p, !state.busy) { controller.forget() }

    SectionLabel("// Оформление", p)
    Column(Modifier.fillMaxWidth().card(p).padding(horizontal = 16.dp)) {
        InfoRow("Тема оформления", themeName(state.theme) + " →", p, onPickTheme)
    }
    Text("Виджеты используют эту же тему.", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp))

    SectionLabel("// Обновления", p)
    Column(Modifier.fillMaxWidth().card(p).padding(horizontal = 16.dp)) {
        SwitchRow("Проверять при запуске", state.checkUpdates, p) { controller.setCheckUpdates(it) }
    }
    Text("Android ${BuildConfig.VERSION_NAME} · протокол ${PultApi.PROTOCOL}", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
    SecondaryButton("Проверить обновления →", p, enabled = !state.busy) { controller.checkUpdate() }
}
