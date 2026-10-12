#ifndef AppVersion
  #define AppVersion "0.1.3"
#endif
#ifndef SourceDir
  #define SourceDir "..\artifacts\agent-0.1.3"
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

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Pult"; ValueData: """{app}\Pult.Agent.exe"" --tray"; Tasks: autostart; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "Pult"; Tasks: not autostart; Flags: deletevalue

[Run]
Filename: "{app}\Pult.Agent.exe"; Description: "Запустить Пульт"; Flags: nowait postinstall skipifsilent

[Code]
var
  AutostartRestored: Boolean;

procedure CurPageChanged(CurPageID: Integer);
begin
  { Preserve changes made in the app, including disabling autostart after install. }
  if (CurPageID = wpSelectTasks) and not AutostartRestored then
  begin
    AutostartRestored := True;
    if RegKeyExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{729A1FB9-7C28-4B2D-BAC0-0B306FC53662}_is1') then
    begin
      if RegValueExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Pult') then
        WizardSelectTasks('autostart')
      else
        WizardSelectTasks('!autostart');
    end;
  end;
end;
