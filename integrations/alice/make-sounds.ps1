$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$pultSpeech = New-Object System.Speech.Synthesis.SpeechSynthesizer
$pultSpeech.SelectVoice('Microsoft Irina Desktop')
$pultSpeech.Rate = 1
$pultPrompts = @{
 ask_shutdown = 'Выключить компьютер? Для подтверждения скажите: Алиса, подтверди выключение ПК. Для отмены скажите: Алиса, отмена.'
 ask_restart = 'Перезагрузить компьютер? Для подтверждения скажите: Алиса, подтверди перезагрузку ПК. Для отмены скажите: Алиса, отмена.'
 ask_timer_shutdown = 'Поставить таймер выключения компьютера? Для подтверждения скажите: Алиса, подтверди таймер выключения ПК.'
 ask_timer_restart = 'Поставить таймер перезагрузки компьютера? Для подтверждения скажите: Алиса, подтверди таймер перезагрузки ПК.'
 done = 'Пульт принял команду.'
 error = 'Не удалось выполнить команду. Проверьте Пульт на компьютере. Возможно, уже установлен таймер.'
 cancelled = 'Команда компьютера отменена.'
 no_confirmation = 'Нет команды для подтверждения. Повторите команду компьютера.'
}
foreach ($pultItem in $pultPrompts.GetEnumerator()) {
 $pultSpeech.SetOutputToWaveFile((Join-Path $PSScriptRoot ($pultItem.Key + '.wav')))
 $pultSpeech.Speak($pultItem.Value)
 $pultSpeech.SetOutputToNull()
}
$pultSpeech.Dispose()

