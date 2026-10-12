package ru.hl100.pult

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import kotlin.math.max
import androidx.compose.ui.text.style.TextAlign

data class Palette(val base: Color, val surface: Color, val ink: Color, val sub: Color, val line: Color, val accent: Color, val accentInk: Color, val radius: Int)
data class TimerPlan(val action: String, val seconds: Int, val replace: Boolean)
fun timerActionName(action: String) = when(action) { "shutdown" -> "Выключение"; "restart" -> "Перезагрузка"; "lock" -> "Блокировка"; else -> "Неизвестное действие" }
fun palette(theme: String): Palette = when (theme) {
    "quiet" -> Palette(Color(0xFFF6F3EC), Color(0xFFEBE7DF), Color(0xFF272C29), Color(0xFF626B63), Color(0xFFD8DCD3), Color(0xFF425749), Color(0xFFFFFDF6), 22)
    "orbit" -> Palette(Color(0xFF121724), Color(0xFF1C2433), Color(0xFFF1F4FB), Color(0xFFA7B4CC), Color(0xFF354259), Color(0xFFB5CEFF), Color(0xFF172642), 18)
    else -> Palette(Color(0xFF101011), Color(0xFF19191A), Color(0xFFEEEBDF), Color(0xFFA4A29A), Color(0xFF343433), Color(0xFFF04E48), Color(0xFF111111), 3)
}

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var theme by mutableStateOf("signal")
    private var connection by mutableStateOf<Connection?>(null)
    private var status by mutableStateOf<Status?>(null)
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var sending by mutableStateOf(emptySet<String>())
    private var volumeWrites by mutableIntStateOf(0)
    private var message by mutableStateOf("")
    private var tab by mutableStateOf("remote")
    private var confirming by mutableStateOf<String?>(null)
    private var themesOpen by mutableStateOf(false)
    private var candidate by mutableStateOf<Candidate?>(null)
    private var found by mutableStateOf<List<Candidate>>(emptyList())
    private var update by mutableStateOf<AndroidRelease?>(null)
    private var pendingApk: java.io.File? = null
    private val commandMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this); theme = settings.theme; connection = settings.connection
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { refreshStatus(); delay(3000) }
            }
        }
        setContent { AppScreen() }
        if(settings.checkUpdates){
            val prefs=getSharedPreferences("updates",MODE_PRIVATE)
            if(System.currentTimeMillis()-prefs.getLong("lastCheck",0)>24*60*60*1000L){
                lifecycleScope.launch {try{val release=withContext(Dispatchers.IO){AndroidUpdates.find()};prefs.edit().putLong("lastCheck",System.currentTimeMillis()).apply();if(release!=null)message="Доступен Пульт ${release.version}. Открой настройки → обновления."}catch(_:Exception){}}
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized && pendingApk != null && packageManager.canRequestPackageInstalls()) {
            val apk = pendingApk!!; pendingApk = null
            try { AndroidUpdates.install(this, apk) } catch (ex: Exception) { message = readable(ex) }
        }
    }
    private suspend fun refreshStatus() {
        val selected = connection ?: return
        try {
            commandMutex.withLock {
                val result = withContext(Dispatchers.IO) { PultApi.status(selected) }
                if (connection == selected) applyStatus(result)
            }
        } catch (ex: Exception) {
            if (connection != selected) return
            connected = false; settings.widgetMessage = if (ex is ApiException && ex.status == 401) "Доступ отозван" else "Нет связи с ПК"; Widgets.refresh(this)
        }
    }
    private fun applyStatus(result: Status) {
        if (result.error != null && result.error != status?.error) message = result.error
        status = result; connected = true
        result.volume?.let { settings.lastVolume = it }
        settings.widgetMessage = "Последний ответ: ПК доступен"; Widgets.refresh(this)
    }
    private fun setVolume(value: Int) {
        val selected = connection ?: return
        volumeWrites++
        lifecycleScope.launch {
            try {
                commandMutex.withLock {
                    withContext(Dispatchers.IO) { PultApi.command(selected, "volume", value) }
                    val result = withContext(Dispatchers.IO) { PultApi.status(selected) }
                    if(connection == selected) applyStatus(result)
                }
            } catch(ex: Exception) { message = readable(ex) }
            finally { volumeWrites-- }
        }
    }
    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch { try { block() } catch (ex: Exception) { message = readable(ex) } finally { busy = false } }
    }
    private fun readable(ex: Exception): String = when(ex) {
        is ApiException -> ex.message ?: "Команда не выполнена."
        is javax.net.ssl.SSLException -> "Сертификат ПК не совпадает. Проверь агент и привяжи ПК заново."
        is java.net.SocketTimeoutException -> "ПК не ответил. Команда могла быть принята — проверь состояние перед повтором."
        is java.net.ConnectException -> "Не удалось подключиться. Проверь Wi-Fi, агент и брандмауэр Windows."
        else -> ex.message?.take(180) ?: "Не удалось выполнить действие."
    }
    private fun send(action: String, value: Int? = null, timerAction: String? = null, seconds: Int? = null, replace: Boolean = false) {
        val selected = connection ?: return
        if (action in sending) return
        sending = sending + action
        lifecycleScope.launch {
            try {
            commandMutex.withLock {
                withContext(Dispatchers.IO) { PultApi.command(selected, action, value, timerAction, seconds, replace) }
                // Power commands can disconnect the PC immediately after acceptance.
                if (action !in setOf("shutdown", "restart")) {
                    val result = withContext(Dispatchers.IO) { PultApi.status(selected) }
                    if (connection == selected) applyStatus(result)
                }
            }
            if (action != "playPause") message = when(action) { "timer" -> "${timerActionName(timerAction ?: "")} через ${(seconds ?: 0)/60} мин. Таймер запущен на ПК."; "cancelTimer" -> "Таймер отменён."; "lock" -> "Команда блокировки принята."; "shutdown", "restart" -> "Команда принята агентом."; else -> "Громкость изменена." }
            } catch (ex: Exception) { message = readable(ex) }
            finally { sending = sending - action }
        }
    }
    @Composable private fun AppScreen() {
        val p = palette(theme)
        SideEffect { WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = theme == "quiet"; WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = theme == "quiet" }
        val colorScheme = if(theme == "quiet") lightColorScheme(primary=p.accent,onPrimary=p.accentInk,background=p.base,onBackground=p.ink,surface=p.surface,onSurface=p.ink,outline=p.line) else darkColorScheme(primary=p.accent,onPrimary=p.accentInk,background=p.base,onBackground=p.ink,surface=p.surface,onSurface=p.ink,outline=p.line)
        val family = if(theme == "signal") FontFamily.Monospace else FontFamily.SansSerif
        MaterialTheme(colorScheme=colorScheme, typography=Typography(bodyLarge=LocalTextStyle.current.copy(fontFamily=family,fontSize=15.sp),bodyMedium=LocalTextStyle.current.copy(fontFamily=family,fontSize=13.sp),titleLarge=LocalTextStyle.current.copy(fontFamily=family,fontSize=24.sp,fontWeight=FontWeight.Medium))) {
            val notices = remember { SnackbarHostState() }
            LaunchedEffect(message) {
                val notice = message
                if (notice.isNotBlank()) {
                    notices.showSnackbar(notice, withDismissAction = true, duration = SnackbarDuration.Short)
                    if (message == notice) message = ""
                }
            }
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(p.base).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(22.dp,18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(if(theme=="signal") "Пульт_" else "Пульт",fontSize=32.sp,fontWeight=FontWeight.SemiBold,fontFamily=family,color=p.ink)
                    TextButton(onClick={tab="settings"}) { Symbol("settings",p.ink) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp)) {
                    DeviceCard(p)
                    Spacer(Modifier.height(20.dp))
                    when(tab) { "remote" -> RemoteScreen(p); "timer" -> TimerScreen(p); else -> SettingsScreen(p) }
                    Spacer(Modifier.height(24.dp))
                }
                HorizontalDivider(color=p.line)
                Row(Modifier.fillMaxWidth().padding(10.dp,5.dp)) {
                    listOf("remote" to "Пульт", "timer" to "Таймер", "settings" to "Настройки").forEach { (key,label) ->
                        TextButton(onClick={tab=key},modifier=Modifier.weight(1f)) { Column(horizontalAlignment=Alignment.CenterHorizontally) { Symbol(if(key=="remote")"power" else key,if(tab==key)p.ink else p.sub,20); Spacer(Modifier.height(5.dp)); Text(label,fontSize=11.sp,color=if(tab==key)p.ink else p.sub) } }
                    }
                }
            }
            SnackbarHost(notices, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start=16.dp,end=16.dp,bottom=88.dp)) {
                Snackbar(it, containerColor=p.surface, contentColor=p.ink, dismissActionContentColor=p.sub)
            }
            }
            if(confirming != null) {
                val action=confirming!!
                AlertDialog(onDismissRequest={confirming=null},title={Text(if(action=="restart")"Перезагрузить ПК?" else "Выключить ПК?")},text={Text("Сохрани открытые документы. Windows может попросить подтвердить их закрытие.")},confirmButton={TextButton(onClick={confirming=null;send(action)}) { Text(if(action=="restart")"Перезагрузить" else "Выключить") }},dismissButton={TextButton(onClick={confirming=null}) { Text("Отмена") }})
            }
            if(themesOpen) ThemeDialog(p)
            if(candidate != null) PairDialog(p)
            update?.let { release ->
                AlertDialog(onDismissRequest={update=null},title={Text("Пульт ${release.version}")},text={Column { Text("Доступна новая версия Android-приложения."); Spacer(Modifier.height(10.dp)); Text(release.notes.take(600),fontSize=12.sp) }},confirmButton={TextButton(enabled=!busy,onClick={task { val apk=withContext(Dispatchers.IO){AndroidUpdates.download(this@MainActivity,release)}; update=null; pendingApk=apk; if(packageManager.canRequestPackageInstalls()){pendingApk=null;AndroidUpdates.install(this@MainActivity,apk)} else startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:$packageName"))) }}){Text("Скачать и установить")}},dismissButton={TextButton(onClick={update=null}){Text("Позже")}})
            }
        }
    }
    @Composable private fun DeviceCard(p: Palette) {
        Surface(color=p.surface,shape=RoundedCornerShape(p.radius.dp),modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Symbol("monitor",p.ink,28); Spacer(Modifier.width(13.dp))
                Column { Text(status?.name ?: connection?.name ?: "Подключи компьютер",fontSize=17.sp,fontWeight=FontWeight.Medium,color=p.ink); Spacer(Modifier.height(5.dp)); Text(if(connection==null)"Одна домашняя сеть" else if(connected)"● Подключён · Wi-Fi" else "○ ПК недоступен",fontSize=11.sp,color=if(connected)p.accent else p.sub) }
            }
        }
    }
    @Composable private fun RemoteScreen(p: Palette) {
        if(connection==null) { Text("Компьютер под рукой",fontSize=25.sp,color=p.ink); Spacer(Modifier.height(12.dp)); Text("Выключай ПК, ставь таймер и управляй звуком с телефона.",color=p.sub); Spacer(Modifier.height(18.dp)); Primary("Подключить компьютер",p){tab="settings"}; return }
        Label("ПИТАНИЕ / 01",p)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            ActionTile("Выключить","power",p,true,Modifier.weight(1f)){confirming="shutdown"}
            ActionTile("Заблокировать","lock",p,false,Modifier.weight(1f)){send("lock")}
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick={confirming="restart"},enabled=connected&&!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(p.radius.dp)) { Symbol("restart",p.ink,19); Spacer(Modifier.width(10.dp)); Text("Перезагрузить",fontSize=13.sp,color=p.ink) }
        Spacer(Modifier.height(24.dp)); Label("ТАЙМЕР / 02",p); ActiveTimer(p)
        Spacer(Modifier.height(24.dp)); Sound(p)
    }
    @Composable private fun ActionTile(label:String,kind:String,p:Palette,primary:Boolean,modifier:Modifier,click:()->Unit) {
        Button(onClick=click,enabled=connected&&!busy,modifier=modifier.height(110.dp),shape=RoundedCornerShape(p.radius.dp),contentPadding=PaddingValues(16.dp),colors=ButtonDefaults.buttonColors(containerColor=if(primary)p.accent else p.surface,contentColor=if(primary)p.accentInk else p.ink)) {
            Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.SpaceBetween) { Symbol(kind,if(primary)p.accentInk else p.ink,25); Text(label,fontSize=12.sp) }
        }
    }
    @Composable private fun ActiveTimer(p: Palette) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while(true){delay(1000);now=System.currentTimeMillis()} }
        val timer=status?.timer
        val remaining=timer?.let { runCatching { max(0,(OffsetDateTime.parse(it.dueAt).toInstant().toEpochMilli()-now)/1000) }.getOrDefault(0) }
        Row(Modifier.fillMaxWidth().border(1.dp,p.line,RoundedCornerShape(p.radius.dp)).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(if(timer==null)"Активных таймеров нет" else if(!connected)"Последний таймер · нет связи" else when(timer.action){"lock"->"Блокировка через";"restart"->"Перезагрузка через";else->"Выключение через"},fontSize=11.sp,color=p.sub,maxLines=1); Spacer(Modifier.height(7.dp)); Text(if(remaining==null)"—" else "%02d:%02d".format(remaining/60,remaining%60),fontSize=29.sp,color=p.ink,maxLines=1) }
            TextButton(enabled=connected&&!busy,onClick={if(timer==null)tab="timer" else send("cancelTimer")}){Text(if(timer==null)"Задать" else "Отменить",color=p.ink,fontSize=12.sp)}
        }
    }
    @Composable private fun Sound(p:Palette) {
        var volume by remember { mutableFloatStateOf((status?.volume ?: settings.lastVolume).toFloat()) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(status?.volume, volumeWrites) { if(!dragging && volumeWrites==0)volume=(status?.volume ?: settings.lastVolume).toFloat() }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Громкость",fontSize=13.sp,color=p.ink);Text(if(status?.volume==null)"Недоступна" else "${volume.toInt()}%",fontSize=13.sp,color=p.sub,modifier=Modifier.width(90.dp),textAlign=TextAlign.End)}
        Row(verticalAlignment=Alignment.CenterVertically){Symbol("volume",p.sub,22);Slider(value=volume,onValueChange={dragging=true;volume=it},onValueChangeFinished={dragging=false;setVolume(volume.toInt())},valueRange=0f..100f,enabled=connected&&!busy&&status?.volume!=null,modifier=Modifier.weight(1f).padding(horizontal=12.dp));OutlinedButton(onClick={send("playPause")},enabled=connected&&!busy,contentPadding=PaddingValues(12.dp),shape=RoundedCornerShape(p.radius.dp),modifier=Modifier.size(52.dp)){Text("▶Ⅱ",fontSize=16.sp,color=p.ink)}}
        Text("Плей / пауза — системная медиаклавиша",fontSize=10.sp,color=p.sub)
    }
    @Composable private fun TimerScreen(p:Palette) {
        var minutes by rememberSaveable { mutableStateOf("30") }
        var action by rememberSaveable { mutableStateOf("shutdown") }
        var pending by remember { mutableStateOf<TimerPlan?>(null) }
        Text("Таймер",fontSize=27.sp,color=p.ink); Spacer(Modifier.height(18.dp)); Label("ДЕЙСТВИЕ",p)
        listOf("shutdown" to "Выключить", "restart" to "Перезагрузить", "lock" to "Заблокировать").forEach { (key,label) -> Row(Modifier.fillMaxWidth().clickable { action=key }.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=action==key,onClick={action=key});Text(label,color=p.ink)} }
        OutlinedTextField(value=minutes,onValueChange={minutes=it.filter(Char::isDigit).take(4)},label={Text("Через сколько минут")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth().padding(vertical=15.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(15,30,60).forEach{value->OutlinedButton(onClick={minutes=value.toString()},modifier=Modifier.weight(1f),shape=RoundedCornerShape(p.radius.dp)){Text(if(value==60)"1 час" else "$value мин",fontSize=12.sp,color=p.ink)}}}
        Spacer(Modifier.height(15.dp));Primary("Запустить таймер",p,connected&&!busy){val value=minutes.toIntOrNull();if(value==null||value !in 1..1440)message="Укажи от 1 до 1440 минут." else pending=TimerPlan(action,value*60,status?.timer!=null)}
        Text("Таймер работает на ПК. Приложение можно закрыть. После пропущенного срока во сне ПК команда отменяется.",fontSize=12.sp,color=p.sub,modifier=Modifier.padding(vertical=18.dp));ActiveTimer(p)
        pending?.let { plan -> AlertDialog(onDismissRequest={pending=null},title={Text("${timerActionName(plan.action)} через ${plan.seconds/60} мин?")},text={Text(if(plan.replace)"Текущий таймер будет отменён и заменён этим. Новый отсчёт начнётся на ПК." else "Таймер будет работать на ПК. Его можно отменить с телефона, в окне агента или через трей.")},confirmButton={TextButton(enabled=!busy,onClick={pending=null;send("timer",timerAction=plan.action,seconds=plan.seconds,replace=plan.replace)}){Text(if(plan.replace)"Заменить таймер" else "Запустить")}},dismissButton={TextButton(onClick={pending=null}){Text("Отмена")}}) }
    }
    @Composable private fun SettingsScreen(p:Palette) {
        var address by remember { mutableStateOf(connection?.address ?: "") }
        Text("Подключение",fontSize=25.sp,color=p.ink);Spacer(Modifier.height(16.dp))
        OutlinedTextField(value=address,onValueChange={address=it},label={Text("IP-адрес ПК")},placeholder={Text("192.168.1.2:47591")},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
        Spacer(Modifier.height(10.dp));Primary(if(busy)"Подожди…" else "Подключить по адресу",p,!busy){task{candidate=withContext(Dispatchers.IO){PultApi.probe(address)}}}
        OutlinedButton(onClick={task{found=withContext(Dispatchers.IO){PultApi.discover()};if(found.isEmpty())message="ПК не найден. Запусти агент или введи адрес вручную."}},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(top=9.dp),shape=RoundedCornerShape(p.radius.dp)){Text("Найти ПК в сети",color=p.ink)}
        found.forEach{pc->TextButton(onClick={task{candidate=withContext(Dispatchers.IO){PultApi.probe(pc.address)}}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth()){Text(pc.name,color=p.ink);Text(pc.address,fontSize=11.sp,color=p.sub)}}}
        if(connection!=null)TextButton(onClick={connection=null;settings.connection=null;status=null;connected=false;settings.widgetMessage="Подключи ПК";Widgets.refresh(this@MainActivity)},enabled=!busy){Text("Забыть компьютер",color=p.sub)}
        Spacer(Modifier.height(25.dp));Text("Оформление",fontSize=24.sp,color=p.ink)
        TextButton(onClick={themesOpen=true},modifier=Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Тема оформления",color=p.ink);Text(themeName(theme)+" ›",color=p.sub)}}
        Text("Виджеты используют эту же тему.",fontSize=11.sp,color=p.sub)
        Spacer(Modifier.height(23.dp));Text("Обновления",fontSize=24.sp,color=p.ink)
        var checks by remember { mutableStateOf(settings.checkUpdates) }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Проверять при запуске",color=p.ink,modifier=Modifier.weight(1f),fontSize=13.sp);Switch(checked=checks,onCheckedChange={checks=it;settings.checkUpdates=it})}
        Text("Android ${BuildConfig.VERSION_NAME} · протокол 1",fontSize=12.sp,color=p.sub)
        OutlinedButton(onClick={checkUpdate()},enabled=!busy,shape=RoundedCornerShape(p.radius.dp),modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Text("Проверить обновления",color=p.ink)}
    }
    @Composable private fun ThemeDialog(p:Palette) {
        fun choose(key:String) { theme=key; settings.theme=key; Widgets.refresh(this@MainActivity) }
        AlertDialog(
            onDismissRequest={themesOpen=false},
            title={Text("Тема оформления")},
            text={
                Column {
                    listOf("signal","quiet","orbit").forEach { key ->
                        val colors=palette(key)
                        Row(Modifier.fillMaxWidth().clickable {choose(key)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=theme==key,onClick={choose(key)})
                            Text(themeName(key),modifier=Modifier.weight(1f))
                            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                listOf(colors.base,colors.surface,colors.accent).forEach { color ->
                                    Box(Modifier.size(20.dp).background(color,RoundedCornerShape(4.dp)).border(1.dp,p.line,RoundedCornerShape(4.dp)))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton={TextButton(onClick={themesOpen=false}){Text("Готово")}}
        )
    }
    @Composable private fun PairDialog(p:Palette) {
        val pc=candidate ?: return
        var verified by remember(pc){mutableStateOf(false)};var code by remember(pc){mutableStateOf("")}
        AlertDialog(onDismissRequest={if(!busy)candidate=null},title={Text(pc.name)},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("Сверь отпечаток с окном «Подключить телефон» на ПК.",fontSize=13.sp);Text(pc.fingerprint.chunked(4).joinToString(" "),fontFamily=FontFamily.Monospace,fontSize=12.sp,modifier=Modifier.padding(vertical=15.dp));Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=verified,onCheckedChange={verified=it});Text("Отпечаток совпадает",fontSize=12.sp)};OutlinedTextField(value=code,onValueChange={code=it.filter(Char::isDigit).take(6)},label={Text("Код с экрана ПК")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())}},confirmButton={TextButton(enabled=verified&&code.length==6&&!busy,onClick={task{val result=withContext(Dispatchers.IO){PultApi.pair(pc,code,Build.MODEL)};settings.connection=result;connection=result;candidate=null;tab="remote";message="Компьютер привязан.";refreshStatus()}}){Text("Привязать")}},dismissButton={TextButton(enabled=!busy,onClick={candidate=null}){Text("Отмена")}})
    }
    private fun checkUpdate() { task { val release=withContext(Dispatchers.IO){AndroidUpdates.find()};if(release==null)message="Установлена актуальная версия Android-приложения." else update=release } }
    @Composable private fun Primary(label:String,p:Palette,enabled:Boolean=true,action:()->Unit){Button(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(p.radius.dp),colors=ButtonDefaults.buttonColors(containerColor=p.accent,contentColor=p.accentInk)){Text(label,fontSize=14.sp)}}
    @Composable private fun Label(text:String,p:Palette){Text(text,fontSize=10.sp,color=p.sub,letterSpacing=1.sp,modifier=Modifier.padding(bottom=12.dp))}
}

fun themeName(theme:String)=when(theme){"quiet"->"Тихий";"orbit"->"Орбита";else->"Сигнал"}
@Composable fun Symbol(kind:String,color:Color,size:Int=24){
    Canvas(Modifier.size(size.dp)){
        val w=this.size.width;val h=this.size.height;val stroke=w*.075f
        fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(w*x1,h*y1),Offset(w*x2,h*y2),stroke,StrokeCap.Round)
        when(kind){
            "power"->{drawArc(color,-50f,280f,false,Offset(w*.16f,h*.16f),Size(w*.68f,h*.68f),style=Stroke(stroke,cap=StrokeCap.Round));line(.5f,.06f,.5f,.48f)}
            "lock"->{drawRoundRect(color,Offset(w*.2f,h*.44f),Size(w*.6f,h*.46f),CornerRadius(w*.08f),style=Stroke(stroke));drawArc(color,180f,180f,false,Offset(w*.31f,h*.1f),Size(w*.38f,h*.58f),style=Stroke(stroke));line(.5f,.63f,.5f,.73f)}
            "monitor"->{drawRoundRect(color,Offset(w*.08f,h*.12f),Size(w*.84f,h*.6f),CornerRadius(w*.04f),style=Stroke(stroke));line(.5f,.73f,.5f,.92f);line(.3f,.93f,.7f,.93f)}
            "restart"->{drawArc(color,-90f,290f,false,Offset(w*.17f,h*.17f),Size(w*.66f,h*.66f),style=Stroke(stroke,cap=StrokeCap.Round));line(.5f,.04f,.5f,.29f);line(.5f,.29f,.71f,.16f)}
            "volume"->{line(.1f,.39f,.3f,.39f);line(.1f,.39f,.1f,.64f);line(.1f,.64f,.3f,.64f);line(.3f,.39f,.5f,.22f);line(.5f,.22f,.5f,.8f);line(.5f,.8f,.3f,.64f);drawArc(color,-55f,110f,false,Offset(w*.44f,h*.2f),Size(w*.5f,h*.61f),style=Stroke(stroke))}
            "timer"->{drawCircle(color,w*.33f,Offset(w*.5f,h*.57f),style=Stroke(stroke));line(.4f,.08f,.6f,.08f);line(.5f,.3f,.5f,.58f);line(.5f,.58f,.65f,.66f)}
            else->{line(.13f,.25f,.87f,.25f);line(.13f,.5f,.87f,.5f);line(.13f,.75f,.87f,.75f);drawCircle(color,w*.075f,Offset(w*.35f,h*.25f));drawCircle(color,w*.075f,Offset(w*.65f,h*.5f));drawCircle(color,w*.075f,Offset(w*.4f,h*.75f))}
        }
    }
}
