package ru.hl100.pult

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A timer the user is about to start; [replace] means an active timer will be replaced. */
data class TimerPlan(val action: String, val seconds: Int, val replace: Boolean)

@Composable
fun TimerScreen(state: UiState, p: Palette, controller: PultController) {
    var minutes by rememberSaveable { mutableStateOf("30") }
    var action by rememberSaveable { mutableStateOf("shutdown") }
    var pending by remember { mutableStateOf<TimerPlan?>(null) }

    Spacer(Modifier.height(24.dp))
    ScreenTitle("Таймер", p)
    if (state.status?.timer != null) {
        Spacer(Modifier.height(16.dp))
        ActiveTimer(state, p, controller)
    }
    SectionLabel("// Действие", p)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("shutdown" to "Выключить", "restart" to "Перезагрузить", "lock" to "Заблокировать").forEach { (key, label) ->
            OptionRow(label, action == key, p) { action = key }
        }
    }
    SectionLabel("// Через сколько минут", p)
    OutlinedTextField(
        value = minutes,
        onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
        placeholder = { Text("30") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        colors = fieldColors(p),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(15, 30, 60).forEach { value ->
            SecondaryButton(if (value == 60) "1 час" else "$value мин", p, Modifier.weight(1f), selected = minutes == value.toString(), height = 40.dp) { minutes = value.toString() }
        }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(if (state.status?.timer != null) "Заменить таймер ↗" else "Запустить таймер ↗", p, state.canControl) {
        val value = minutes.toIntOrNull()
        if (value == null || value !in 1..1440) controller.showMessage("Укажи от 1 до 1440 минут.")
        else pending = TimerPlan(action, value * 60, replace = state.status?.timer != null)
    }
    Text(
        "Таймер работает на ПК. Приложение можно закрыть. После пропущенного срока во сне ПК команда отменяется.",
        fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 14.dp),
    )

    pending?.let { plan ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = p.surface,
            title = { Text("${timerActionName(plan.action)} через ${plan.seconds / 60} мин?") },
            text = {
                Text(
                    if (plan.replace) "Текущий таймер будет отменён и заменён этим. Новый отсчёт начнётся на ПК."
                    else "Таймер будет работать на ПК. Его можно отменить с телефона, в окне агента или через трей.",
                    color = p.sub,
                )
            },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    pending = null
                    controller.send("timer", timerAction = plan.action, seconds = plan.seconds, replace = plan.replace)
                }) { Text(if (plan.replace) "Заменить таймер" else "Запустить") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена", color = p.sub) } },
        )
    }
}
