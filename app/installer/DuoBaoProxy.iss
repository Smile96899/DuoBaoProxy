; Inno Setup script for the Windows installer. Built by package.ps1, which passes
; AppVersion (from CMakeLists.txt) and SrcDir (the windeployqt staging folder).

#ifndef AppVersion
  #define AppVersion "1.0"
#endif
#ifndef SrcDir
  #define SrcDir "stage"
#endif

[Setup]
AppId={{748CDDF6-6B8A-4DAB-AB61-0507FF7827B0}
AppName=多宝代理 DuoBao Proxy
AppVersion={#AppVersion}
AppVerName=多宝代理 DuoBao Proxy {#AppVersion}
AppPublisher=DuoBao
AppPublisherURL=https://github.com/Smile96899/DuoBaoProxy
AppSupportURL=https://github.com/Smile96899/DuoBaoProxy/issues
AppUpdatesURL=https://github.com/Smile96899/DuoBaoProxy/releases
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\DuoBaoProxy
DefaultGroupName=多宝代理
DisableProgramGroupPage=yes
; Per-user install by default (no UAC prompt); the dialog lets the user pick all users instead.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
SetupIconFile=..\res\logo.ico
UninstallDisplayIcon={app}\DuoBaoProxy.exe
UninstallDisplayName=多宝代理 DuoBao Proxy
OutputDir=output
OutputBaseFilename=DuoBaoProxy-{#AppVersion}-windows-x64-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=force
RestartApplications=no

[Languages]
Name: "chs"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SrcDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\多宝代理"; Filename: "{app}\DuoBaoProxy.exe"
Name: "{autodesktop}\多宝代理"; Filename: "{app}\DuoBaoProxy.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\DuoBaoProxy.exe"; Description: "{cm:LaunchProgram,多宝代理}"; Flags: nowait postinstall skipifsilent

[Code]
const
  InetKey = 'Software\Microsoft\Windows\CurrentVersion\Internet Settings';
  RunKey = 'Software\Microsoft\Windows\CurrentVersion\Run';

procedure KillApp();
var
  rc: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM DuoBaoProxy.exe', '', SW_HIDE, ewWaitUntilTerminated, rc);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM DuoBaoProxyCore.exe', '', SW_HIDE, ewWaitUntilTerminated, rc);
end;

// A killed app cannot turn the system proxy back off; do it here so the
// network keeps working. Only touches a proxy that points at this machine.
procedure ResetSystemProxy();
var
  enabled: Cardinal;
  server: String;
begin
  if RegQueryDWordValue(HKCU, InetKey, 'ProxyEnable', enabled) and (enabled = 1) and
     RegQueryStringValue(HKCU, InetKey, 'ProxyServer', server) and (Pos('127.0.0.1:', server) = 1) then
    RegWriteDWordValue(HKCU, InetKey, 'ProxyEnable', 0);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  KillApp();
  ResetSystemProxy();
  Result := '';
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    KillApp();
    ResetSystemProxy();
    RegDeleteValue(HKCU, RunKey, 'DuoBaoProxy');
  end;
  if CurUninstallStep = usPostUninstall then
  begin
    if not UninstallSilent and
       (MsgBox('是否同时删除节点、订阅和设置等用户数据？', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES) then
      DelTree(ExpandConstant('{userappdata}\DuoBao\DuoBaoProxy'), True, True, True);
  end;
end;
