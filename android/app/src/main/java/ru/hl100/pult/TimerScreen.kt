package ru.hl100.pult

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

    Text("Таймер", fontSize = 27.sp, color = p.ink)
    Spacer(Modifier.height(18.dp))
    SectionLabel("ДЕЙСТВИЕ", p)
    listOf("shutdown" to "Выключить", "restart" to "Перезагрузить", "lock" to "Заблокировать").forEach { (key, label) ->
        Row(Modifier.fillMaxWidth().clickable { action = key }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = action == key, onClick = { action = key })
            Text(label, color = p.ink)
        }
    }
    OutlinedTextField(
        value = minutes,
        onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
        label = { Text("Через сколько минут") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60).forEach { value ->
            OutlinedButton(onClick = { minutes = value.toString() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(p.radius.dp)) {
                Text(if (value == 60) "1 час" else "$value мин", fontSize = 12.sp, color = p.ink)
            }
        }
    }
    Spacer(Modifier.height(15.dp))
    PrimaryButton("Запустить таймер", p, state.canControl) {
        val value = minutes.toIntOrNull()
        if (value == null || value !in 1..1440) controller.showMessage("Укажи от 1 до 1440 минут.")
        else pending = TimerPlan(action, value * 60, replace = state.status?.timer != null)
    }
    Text(
        "Таймер работает на ПК. Приложение можно закрыть. После пропущенного срока во сне ПК команда отменяется.",
        fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(vertical = 18.dp),
    )
    ActiveTimer(state, p, controller)

    pending?.let { plan ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("${timerActionName(plan.action)} через ${plan.seconds / 60} мин?") },
            text = {
                Text(
                    if (plan.replace) "Текущий таймер будет отменён и заменён этим. Новый отсчёт начнётся на ПК."
                    else "Таймер будет работать на ПК. Его можно отменить с телефона, в окне агента или через трей."
                )
            },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    pending = null
                    controller.send("timer", timerAction = plan.action, seconds = plan.seconds, replace = plan.replace)
                }) { Text(if (plan.replace) "Заменить таймер" else "Запустить") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } },
        )
    }
}
