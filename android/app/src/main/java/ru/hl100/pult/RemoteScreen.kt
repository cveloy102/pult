package ru.hl100.pult

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import kotlin.math.max

/** Card at the top of every tab: PC name and connection state. */
@Composable
fun DeviceCard(state: UiState, p: Palette) {
    Surface(color = p.surface, shape = RoundedCornerShape(p.radius.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol("monitor", p.ink, 28)
            Spacer(Modifier.width(13.dp))
            Column {
                Text(state.status?.name ?: state.connection?.name ?: "Подключи компьютер", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = p.ink)
                Spacer(Modifier.height(5.dp))
                val line = when {
                    state.connection == null -> "Одна домашняя сеть"
                    state.connected -> "● Подключён · Wi-Fi"
                    else -> "○ ПК недоступен"
                }
                Text(line, fontSize = 11.sp, color = if (state.connected) p.accent else p.sub)
            }
        }
    }
}

/** Main tab: power, Wake-on-LAN, timer and sound. */
@Composable
fun RemoteScreen(state: UiState, p: Palette, controller: PultController, onConfirmPower: (String) -> Unit, onPickOutput: () -> Unit) {
    if (state.connection == null) {
        Text("Компьютер под рукой", fontSize = 25.sp, color = p.ink)
        Spacer(Modifier.height(12.dp))
        Text("Выключай ПК, ставь таймер и управляй звуком с телефона.", color = p.sub)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Подключить компьютер", p) { controller.selectTab(TAB_SETTINGS) }
        return
    }
    SectionLabel("ПИТАНИЕ / 01", p)
    if (state.canWake) {
        val sending = ACTION_WAKE in state.sending
        PrimaryButton(if (sending) "Отправляю сигнал…" else "Включить компьютер", p, !sending) { controller.wake() }
        Text(
            "Wake-on-LAN: ПК должен быть подключён кабелем, а пробуждение по сети включено в BIOS и в настройках сетевой карты.",
            fontSize = 11.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile("Выключить", "power", p, primary = true, enabled = state.canControl, modifier = Modifier.weight(1f)) { onConfirmPower("shutdown") }
        ActionTile("Заблокировать", "lock", p, primary = false, enabled = state.canControl, modifier = Modifier.weight(1f)) { controller.send("lock") }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = { onConfirmPower("restart") },
        enabled = state.canControl,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(p.radius.dp),
    ) {
        Symbol("restart", p.ink, 19)
        Spacer(Modifier.width(10.dp))
        Text("Перезагрузить", fontSize = 13.sp, color = p.ink)
    }
    Spacer(Modifier.height(24.dp))
    SectionLabel("ТАЙМЕР / 02", p)
    ActiveTimer(state, p, controller)
    Spacer(Modifier.height(24.dp))
    Sound(state, p, controller, onPickOutput)
}

@Composable
private fun ActionTile(label: String, kind: String, p: Palette, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(110.dp),
        shape = RoundedCornerShape(p.radius.dp),
        contentPadding = PaddingValues(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) p.accent else p.surface, contentColor = if (primary) p.accentInk else p.ink),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Symbol(kind, if (primary) p.accentInk else p.ink, 25)
            Text(label, fontSize = 12.sp)
        }
    }
}

/** Countdown of the PC timer with cancel; shown on the remote and timer tabs. */
@Composable
fun ActiveTimer(state: UiState, p: Palette, controller: PultController) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val timer = state.status?.timer
    val remaining = timer?.let { runCatching { max(0, (OffsetDateTime.parse(it.dueAt).toInstant().toEpochMilli() - now) / 1000) }.getOrDefault(0) }
    val caption = when {
        timer == null -> "Активных таймеров нет"
        !state.connected -> "Последний таймер · нет связи"
        timer.action == "lock" -> "Блокировка через"
        timer.action == "restart" -> "Перезагрузка через"
        else -> "Выключение через"
    }
    Row(
        Modifier.fillMaxWidth().border(1.dp, p.line, RoundedCornerShape(p.radius.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(caption, fontSize = 11.sp, color = p.sub, maxLines = 1)
            Spacer(Modifier.height(7.dp))
            Text(if (remaining == null) "—" else formatRemaining(remaining), fontSize = 29.sp, color = p.ink, maxLines = 1)
        }
        TextButton(
            enabled = state.canControl,
            onClick = { if (timer == null) controller.selectTab(TAB_TIMER) else controller.send("cancelTimer") },
        ) { Text(if (timer == null) "Задать" else "Отменить", color = p.ink, fontSize = 12.sp) }
    }
}

@Composable
private fun Sound(state: UiState, p: Palette, controller: PultController, onPickOutput: () -> Unit) {
    val reported = state.status?.volume
    var volume by remember { mutableFloatStateOf((reported ?: state.lastVolume).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // Follow the PC value, but not while the user drags or a volume command is in flight.
    LaunchedEffect(reported, state.volumeWrites) {
        if (!dragging && state.volumeWrites == 0) volume = (reported ?: state.lastVolume).toFloat()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Громкость", fontSize = 13.sp, color = p.ink)
        Text(if (reported == null) "Недоступна" else "${volume.toInt()}%", fontSize = 13.sp, color = p.sub, modifier = Modifier.width(90.dp), textAlign = TextAlign.End)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Symbol("volume", p.sub, 22)
        Slider(
            value = volume,
            onValueChange = { dragging = true; volume = it },
            onValueChangeFinished = { dragging = false; controller.setVolume(volume.toInt()) },
            valueRange = 0f..100f,
            enabled = state.canControl && reported != null,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        OutlinedButton(
            onClick = { controller.send("playPause") },
            enabled = state.canControl,
            contentPadding = PaddingValues(12.dp),
            shape = RoundedCornerShape(p.radius.dp),
            modifier = Modifier.size(52.dp),
        ) { Text("▶Ⅱ", fontSize = 16.sp, color = p.ink) }
    }
    Text("Плей / пауза — системная медиаклавиша", fontSize = 10.sp, color = p.sub)
    if (state.status?.outputSelection == true) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickOutput,
            enabled = state.connected,
            shape = RoundedCornerShape(p.radius.dp),
            modifier = Modifier.fillMaxWidth().height(62.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Устройство вывода", fontSize = 10.sp, color = p.sub, maxLines = 1)
                Text(state.status.outputs.firstOrNull { it.selected }?.name ?: "Выбрать устройство", fontSize = 13.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            if ("audioOutput" in state.sending) CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
            else Text("›", fontSize = 24.sp, color = p.sub, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
        }
    }
}
