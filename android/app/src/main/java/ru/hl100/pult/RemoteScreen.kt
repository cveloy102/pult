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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import kotlin.math.max

/** Card at the top of every tab: PC name and connection state. */
@Composable
fun DeviceCard(state: UiState, p: Palette) {
    Row(Modifier.fillMaxWidth().card(p).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol("monitor", p.ink, 28)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(state.status?.name ?: state.connection?.name ?: "Подключи компьютер", fontSize = 17.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val line = when {
                state.connection == null -> buildAnnotatedString { append("Одна домашняя сеть") }
                state.connected -> buildAnnotatedString { withStyle(SpanStyle(color = p.online)) { append("●") }; append(" На связи") }
                else -> buildAnnotatedString { append("■ ПК недоступен") }
            }
            Text(line, fontSize = 12.sp, color = p.sub)
        }
    }
}

/** Main tab: Wake-on-LAN, four action tiles, the running timer and sound. */
@Composable
fun RemoteScreen(state: UiState, p: Palette, controller: PultController, onConfirmPower: (String) -> Unit, onPickOutput: () -> Unit) {
    if (state.connection == null) {
        Spacer(Modifier.height(24.dp))
        ScreenTitle("Компьютер под рукой", p, size = 25)
        Spacer(Modifier.height(10.dp))
        Text("Выключай ПК, ставь таймер и управляй звуком с телефона.", fontSize = 13.sp, color = p.sub)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Подключить компьютер →", p) { controller.selectTab(TAB_SETTINGS) }
        return
    }
    val now = rememberNow()
    val timer = state.status?.timer
    SectionLabel("// Питание", p)
    if (state.canWake) {
        val sending = ACTION_WAKE in state.sending
        PrimaryButton(if (sending) "Отправляю сигнал…" else "Включить компьютер ↗", p, !sending) { controller.wake() }
        Text(
            "Wake-on-LAN: ПК должен быть подключён кабелем, а пробуждение по сети включено в BIOS и в настройках сетевой карты.",
            fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The accent goes to the wake button while the PC is off: one primary action per screen.
            ActionTile("Выключить ↗", "power", p, primary = state.connected, enabled = state.canControl, modifier = Modifier.weight(1f)) { onConfirmPower("shutdown") }
            ActionTile("Перезагрузить ↻", "restart", p, primary = false, enabled = state.canControl, modifier = Modifier.weight(1f)) { onConfirmPower("restart") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile("Заблокировать ↗", "lock", p, primary = false, enabled = state.canControl, modifier = Modifier.weight(1f)) { controller.send("lock") }
            val left = timer?.let { remainingSeconds(it, now) }
            ActionTile(
                if (left == null) "Таймер →" else timerActionName(timer.action), "timer", p, primary = false, enabled = true, modifier = Modifier.weight(1f),
                countdown = left?.let { formatRemaining(it) },
            ) { controller.selectTab(TAB_TIMER) }
        }
    }
    if (timer != null) {
        SectionLabel("// Таймер", p)
        ActiveTimer(state, p, controller)
    }
    SectionLabel("// Звук", p)
    Sound(state, p, controller, onPickOutput)
}

@Composable
private fun ActionTile(label: String, kind: String, p: Palette, primary: Boolean, enabled: Boolean, modifier: Modifier, countdown: String? = null, onClick: () -> Unit) {
    val ink = if (primary) p.accentInk else p.ink
    Column(
        modifier.height(96.dp).alpha(if (enabled) 1f else .45f)
            .background(if (primary) p.accent else p.surface, RoundedCornerShape(Radius.control))
            .border(1.dp, if (primary) p.accent else p.line, RoundedCornerShape(Radius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Symbol(kind, ink, 25)
        Column {
            if (countdown != null) Text(countdown, fontFamily = DisplayFont, fontWeight = FontWeight.Black, fontSize = 20.sp, color = ink, maxLines = 1)
            Text(label, fontSize = 12.sp, color = ink, maxLines = 1)
        }
    }
}

/** Current time, ticking once a second while on screen. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun remainingSeconds(timer: TimerStatus, now: Long): Long =
    runCatching { max(0, (OffsetDateTime.parse(timer.dueAt).toInstant().toEpochMilli() - now) / 1000) }.getOrDefault(0)

/** Countdown of the PC timer with cancel; shown on the remote tab while a timer runs and on the timer tab. */
@Composable
fun ActiveTimer(state: UiState, p: Palette, controller: PultController) {
    val now = rememberNow()
    val timer = state.status?.timer
    val remaining = timer?.let { remainingSeconds(it, now) }
    val caption = when {
        timer == null -> "Активных таймеров нет"
        !state.connected -> "Последний таймер · нет связи"
        timer.action == "lock" -> "Блокировка через"
        timer.action == "restart" -> "Перезагрузка через"
        else -> "Выключение через"
    }
    Row(
        Modifier.fillMaxWidth().card(p).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(caption, fontSize = 12.sp, color = p.sub, maxLines = 1)
            if (remaining != null) {
                Spacer(Modifier.height(6.dp))
                Text(formatRemaining(remaining), fontFamily = DisplayFont, fontWeight = FontWeight.Black, fontSize = 28.sp, color = p.ink, maxLines = 1)
            }
        }
        if (timer == null) TextAction("Задать →", p, state.canControl) { controller.selectTab(TAB_TIMER) }
        else SecondaryButton("Отменить ×", p, Modifier, state.canControl, height = 40.dp) { controller.send("cancelTimer") }
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
        Text(if (reported == null) "Недоступна" else "${volume.toInt()}%", fontSize = 13.sp, color = p.sub, modifier = Modifier.width(110.dp), textAlign = TextAlign.End)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Symbol("volume", p.sub, 22)
        Slider(
            value = volume,
            onValueChange = { dragging = true; volume = it },
            onValueChangeFinished = { dragging = false; controller.setVolume(volume.toInt()) },
            valueRange = 0f..100f,
            enabled = state.canControl && reported != null,
            colors = SliderDefaults.colors(
                thumbColor = p.ink, activeTrackColor = p.accent, inactiveTrackColor = p.switchOff,
                disabledThumbColor = p.sub, disabledActiveTrackColor = p.line, disabledInactiveTrackColor = p.switchOff,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Box(
            Modifier.size(52.dp).alpha(if (state.canControl) 1f else .45f).card(p, Radius.control)
                .clickable(enabled = state.canControl, role = Role.Button) { controller.send("playPause") }
                .semantics { contentDescription = "Плей / пауза" },
            contentAlignment = Alignment.Center,
        ) { Text("▶Ⅱ", fontSize = 15.sp, color = p.ink) }
    }
    Text("Плей / пауза — системная медиаклавиша", fontSize = 10.sp, color = p.sub)
    if (state.status?.outputSelection == true) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).alpha(if (state.connected) 1f else .45f).card(p, Radius.control)
                .clickable(enabled = state.connected, role = Role.Button, onClick = onPickOutput)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Устройство вывода", fontSize = 10.sp, color = p.sub, maxLines = 1)
                Text(state.status.outputs.firstOrNull { it.selected }?.name ?: "Выбрать устройство", fontSize = 13.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            if ("audioOutput" in state.sending) CircularProgressIndicator(Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
            else Text("→", fontSize = 16.sp, color = p.sub)
        }
    }
}
