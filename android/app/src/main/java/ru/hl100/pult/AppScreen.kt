package ru.hl100.pult

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Whole app screen: header, current tab, bottom navigation, snackbar and dialogs. */
@Composable
fun AppScreen(state: UiState, controller: PultController, onScanQr: () -> Unit, onInstallUpdate: (AndroidRelease) -> Unit) {
    val p = palette(state.theme)
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    var themesOpen by rememberSaveable { mutableStateOf(false) }
    var outputsOpen by rememberSaveable { mutableStateOf(false) }

    PultTheme(state.theme) {
        val notices = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            val notice = state.message
            if (notice.isNotBlank()) {
                notices.showSnackbar(notice, withDismissAction = true, duration = SnackbarDuration.Short)
                controller.messageShown(notice)
            }
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(p.base).safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(22.dp, 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (state.theme == "signal") "Пульт_" else "Пульт", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, fontFamily = themeFont(state.theme), color = p.ink)
                    TextButton(onClick = { controller.selectTab(TAB_SETTINGS) }) { Symbol("settings", p.ink) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                    DeviceCard(state, p)
                    Spacer(Modifier.height(20.dp))
                    when (state.tab) {
                        TAB_REMOTE -> RemoteScreen(state, p, controller, onConfirmPower = { confirming = it }, onPickOutput = { outputsOpen = true })
                        TAB_TIMER -> TimerScreen(state, p, controller)
                        else -> SettingsScreen(state, p, controller, onScanQr, onPickTheme = { themesOpen = true })
                    }
                    Spacer(Modifier.height(24.dp))
                }
                HorizontalDivider(color = p.line)
                BottomNavigation(state.tab, p) { controller.selectTab(it) }
            }
            SnackbarHost(notices, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
                Snackbar(it, containerColor = p.surface, contentColor = p.ink, dismissActionContentColor = p.sub)
            }
        }

        confirming?.let { action ->
            PowerConfirmDialog(action, onConfirm = { confirming = null; controller.send(action) }, onDismiss = { confirming = null })
        }
        if (themesOpen) ThemeDialog(state, p, controller) { themesOpen = false }
        if (outputsOpen) OutputDialog(state, p, controller) { outputsOpen = false }
        state.candidate?.let { PairDialog(it, state, controller) }
        state.pairLink?.let { PairLinkDialog(it, state, controller) }
        state.update?.let { UpdateDialog(it, state, controller, onInstallUpdate) }
    }
}

@Composable
private fun BottomNavigation(tab: String, p: Palette, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp, 5.dp)) {
        listOf(TAB_REMOTE to "Пульт", TAB_TIMER to "Таймер", TAB_SETTINGS to "Настройки").forEach { (key, label) ->
            val color = if (tab == key) p.ink else p.sub
            TextButton(onClick = { onSelect(key) }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Symbol(if (key == TAB_REMOTE) "power" else key, color, 20)
                    Spacer(Modifier.height(5.dp))
                    Text(label, fontSize = 11.sp, color = color)
                }
            }
        }
    }
}
