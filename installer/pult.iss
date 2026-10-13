#ifndef AppVersion
  #define AppVersion "0.1.5"
#endif
#ifndef SourceDir
  #define SourceDir "..\artifacts\agent-0.1.5"
#endif
#ifndef OutputPath
  #define OutputPath "..\artifacts"
#endif
[Setup]
AppId={{729A1FB9-7C28-4B2D-BAC0-0B306FC53662}
AppName=Пульт
AppVersion={#AppVersion}
AppPublisher=HL100
AppPublisherURL=https://github.com/cveloy102/pult
DefaultDirName={localappdata}\Programs\Pult
DisableDirPage=yes
DefaultGroupName=Пульт
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
AppMutex=Local\Pult.Agent
CloseApplications=no
RestartApplications=no
SetupIconFile=..\agent\Pult.Agent\Assets\pult.ico
UninstallDisplayIcon={app}\Pult.Agent.exe
OutputDir={#OutputPath}
OutputBaseFilename=pult-agent-{#AppVersion}-win-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UsePreviousTasks=yes

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "autostart"; Description: "Запускать Пульт при входе в Windows"
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Пульт"; Filename: "{app}\Pult.Agent.exe"; WorkingDir: "{app}"
Name: "{userdesktop}\Пульт"; Filename: "{app}\Pult.Agent.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\Pult.Agent.exe"; Description: "Запустить Пульт"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\Pult.Agent.exe"; Parameters: "--configure-startup off"; Flags: runhidden; RunOnceId: "RemovePultStartup"

[Code]
var
  AutostartRestored: Boolean;

function ExistingAutostartEnabled(): Boolean;
var
  Schedule, Root, Task: Variant;
begin
  Result := RegValueExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Pult');
  try
    Schedule := CreateOleObject('Schedule.Service');
    Schedule.Connect();
    Root := Schedule.GetFolder('\');
    Task := Root.GetTask('Pult Agent (' + GetUserNameString() + ')');
    Result := Task.Enabled;
  except
    { First upgrade from Run registration has no scheduled task yet. }
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ExitCode: Integer;
  Arguments: String;
begin
  if CurStep = ssPostInstall then
  begin
    if WizardIsTaskSelected('autostart') then Arguments := '--configure-startup on'
    else Arguments := '--configure-startup off';
    if not Exec(ExpandConstant('{app}\Pult.Agent.exe'), Arguments, ExpandConstant('{app}'), SW_HIDE, ewWaitUntilTerminated, ExitCode) then
      RaiseException('Не удалось настроить автозапуск Пульта.');
    if ExitCode <> 0 then
      RaiseException('Не удалось настроить автозапуск. Журнал: %LOCALAPPDATA%\Pult\startup-config-error.txt');
  end;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  { Preserve changes made in the app, including disabling autostart after install. }
  if (CurPageID = wpSelectTasks) and not AutostartRestored then
  begin
    AutostartRestored := True;
    if RegKeyExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{729A1FB9-7C28-4B2D-BAC0-0B306FC53662}_is1') then
    begin
      if ExistingAutostartEnabled() then
        WizardSelectTasks('autostart')
      else
        WizardSelectTasks('!autostart');
    end;
  end;
end;
