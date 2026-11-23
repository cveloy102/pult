package ru.hl100.pult

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ScreenTitle("Пульт", p, size = 32)
                    Box(
                        Modifier.size(48.dp).clickable(role = Role.Button) { controller.selectTab(TAB_SETTINGS) }.semantics { contentDescription = "Настройки" },
                        contentAlignment = Alignment.Center,
                    ) { Symbol("settings", p.ink, 22) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                    DeviceCard(state, p)
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
                Snackbar(it, containerColor = p.surface, contentColor = p.ink, dismissActionContentColor = p.sub, shape = RoundedCornerShape(Radius.control))
            }
        }

        confirming?.let { action ->
            PowerConfirmDialog(action, onConfirm = { confirming = null; controller.send(action) }, onDismiss = { confirming = null })
        }
        if (themesOpen) ThemeDialog(state, p, controller) { themesOpen = false }
        if (outputsOpen) OutputDialog(state, p, controller) { outputsOpen = false }
        state.candidate?.let { PairDialog(it, state, p, controller) }
        state.pairLink?.let { PairLinkDialog(it, state, controller) }
        state.update?.let { UpdateDialog(it, state, controller, onInstallUpdate) }
    }
}

/** Three tabs; the selected one sits on a plate with an accent icon. */
@Composable
private fun BottomNavigation(tab: String, p: Palette, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(p.base).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(TAB_REMOTE to "Пульт", TAB_TIMER to "Таймер", TAB_SETTINGS to "Настройки").forEach { (key, label) ->
            val selected = tab == key
            Column(
                Modifier.weight(1f).height(58.dp)
                    .background(if (selected) p.plate else p.base, RoundedCornerShape(Radius.control))
                    .clickable(role = Role.Tab) { onSelect(key) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Symbol(if (key == TAB_REMOTE) "power" else key, if (selected) p.accent else p.sub, 22)
                Spacer(Modifier.height(3.dp))
                Text(label, fontSize = 12.sp, fontFamily = SansFont, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) p.ink else p.sub)
            }
        }
    }
}
