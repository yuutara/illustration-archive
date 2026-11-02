; Packs a finished app image into a per-user installer. Run it through scripts\build-installer.ps1, which checks the
; inputs and hands them over; nothing is picked up from the working directory.
; The data directory belongs to the app: this script never creates, reads, migrates or removes it.

#ifndef AppVersion
  #error Pass /DAppVersion. scripts\build-installer.ps1 does this.
#endif
#ifndef ImageDir
  #error Pass /DImageDir. scripts\build-installer.ps1 does this.
#endif
#ifndef StageDir
  #error Pass /DStageDir. scripts\build-installer.ps1 does this.
#endif

; scripts\test-installer.ps1 overrides the next four: its runs stay apart from a real install, and it can make the
; process query fail. Never change the real AppGuid: it is how Windows and later installers recognise the installed app.
#ifndef AppGuid
  #define AppGuid "A4E73DBE-917A-4832-8CE6-0A9F0753A3CF"
#endif
#ifndef AppDisplayName
  #define AppDisplayName "插画档案"
#endif
; The Evergreen Runtime's client ID, from Microsoft's distribution guide.
#ifndef WebView2Key
  #define WebView2Key "Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
#endif
#ifndef ProcessClass
  #define ProcessClass "Win32_Process"
#endif

#define AppIdKey "{" + AppGuid + "}"

[Setup]
AppId={#"{" + AppIdKey}
AppName={#AppDisplayName}
AppVersion={#AppVersion}
AppPublisher=yuutara
VersionInfoVersion={#AppVersion}
UninstallDisplayName={#AppDisplayName}
UninstallDisplayIcon={app}\IllustrationArchive.exe
SetupIconFile=..\icon\app.ico
OutputBaseFilename=IllustrationArchive-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ShowLanguageDialog=no
SetupLogging=yes
; Per-user: %LOCALAPPDATA%\Programs, registered under HKCU, no elevation offered.
PrivilegesRequired=lowest
DefaultDirName={autopf}\IllustrationArchive
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
MinVersion=10.0
; The app has no window Setup could close and no named mutex: PrepareToInstall and InitializeUninstall below stop
; while it runs, and nothing is forced closed or left to replace at the next restart.
CloseApplications=no
RestartApplications=no
; The CDS archive remembers each jar's UTC timestamp, so a local-time conversion would invalidate it.
TimeStampsInUTC=yes

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[CustomMessages]
AppRunning=插画档案正在运行。%n%n请在通知区域（托盘）的图标上右键选择“退出”，窗口和图标都消失后再重试。
AppStateUnknown=无法确认插画档案是否正在运行，为避免损坏程序文件，已停止操作。%n%n请确认它已退出后重试；仍然失败时，请重新启动电脑后再试。
OlderThanInstalled=当前已安装 %1，不能用较低的版本 %2 覆盖。%n%n如需回退，请先卸载当前版本。图库数据不会被删除，但旧版程序可能打不开已升级的图库。
InstalledVersionUnknown=无法识别已安装的版本（%1）。%n%n请先卸载现有版本，再运行安装程序。图库数据不会被删除。
DirOverlapsData=不能安装到这个位置，它与图库数据目录重叠：%n%n%1%n%n请选择其他文件夹。
WebView2Needed=插画档案的窗口需要 Microsoft Edge WebView2 运行环境，这台电脑还没有安装。%n%n接下来会联网从微软下载并安装。是否继续？
WebView2Declined=没有安装 Microsoft Edge WebView2 运行环境，插画档案的程序文件未作改动。
WebView2Installing=正在下载并安装 Microsoft Edge WebView2 运行环境…
WebView2Failed=Microsoft Edge WebView2 运行环境没有装好（代码 %1），插画档案的程序文件未作改动。%n%n这一步需要联网，请检查网络后重试。
FilesNotReplaced=有 %1 个程序文件没有写入成功，安装没有完成。%n%n第一个是：%2%n%n请确认插画档案已退出，然后重新运行安装程序。图库数据不受影响。
StaleFilesLeft=有 %1 个旧版程序文件没能删除。它们不影响使用，卸载时会一并清理。
FinishedFailedHeading=安装没有完成
FinishedFailedText=部分程序文件没有写入成功。请确认插画档案已退出，然后重新运行安装程序。图库数据不受影响。

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; ignoreversion: the jars carry one fixed timestamp and the runtime's files can keep their version between builds, so
; Setup's "keep the newer file" comparison must not decide.
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Unpacked on demand, see EnsureWebView2 and RecordInstalledFiles.
Source: "{#StageDir}\MicrosoftEdgeWebview2Setup.exe"; Flags: dontcopy
Source: "{#StageDir}\install-manifest.txt"; Flags: dontcopy

[Icons]
; The ID is the one DesktopLauncher gives its window, so a pinned shortcut and the running app share a taskbar button.
Name: "{autoprograms}\{#AppDisplayName}"; Filename: "{app}\IllustrationArchive.exe"; AppUserModelID: "IllustrationArchive"
Name: "{autodesktop}\{#AppDisplayName}"; Filename: "{app}\IllustrationArchive.exe"; AppUserModelID: "IllustrationArchive"; Tasks: desktopicon

[Run]
Filename: "{app}\IllustrationArchive.exe"; Description: "{cm:LaunchProgram,{#AppDisplayName}}"; Flags: nowait postinstall skipifsilent unchecked; Check: FilesInstalled

[UninstallDelete]
; Written by the [Code] below, so the uninstall log does not know it.
Type: files; Name: "{app}\install-manifest.txt"

[Code]
const
  ProcessName = 'IllustrationArchive.exe';
  ManifestName = 'install-manifest.txt';
  BootstrapperName = 'MicrosoftEdgeWebview2Setup.exe';
  UninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{#AppIdKey}_is1';
  { A manifest line is a SHA-256 in hex, two spaces, and a path relative to the install folder. }
  ManifestDigestLength = 64;
  ManifestPathStart = 67;
  FilesNotInstalledExitCode = 9;

var
  { Stays False until every installed file has been checked, so no path can end in a false success. }
  InstallVerified: Boolean;

function IsSilent: Boolean;
begin
  if IsUninstaller then
    Result := UninstallSilent
  else
    Result := WizardSilent;
end;

{ Returns False when it cannot tell, which must never read as "not running". This looks at the process, so a window
  hidden in the tray counts the same as a visible one, and the same app in another folder does not count. }
function FindRunningApp(const ExePath: String; var Running: Boolean): Boolean;
var
  Locator, Service, Processes, Process, Path: Variant;
  I: Integer;
  Value: String;
begin
  Result := False;
  Running := False;
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Service := Locator.ConnectServer('.', 'root\CIMV2');
    Processes := Service.ExecQuery('SELECT ExecutablePath FROM {#ProcessClass} WHERE Name = ''' + ProcessName + '''');
    for I := 0 to Processes.Count - 1 do begin
      Process := Processes.ItemIndex(I);
      Path := Process.ExecutablePath;
      { Windows hides the path of another user's process; that one could be running from this folder. }
      if VarIsNull(Path) then
        Exit;
      Value := Path;
      if Value = '' then
        Exit;
      if CompareText(Value, ExePath) = 0 then
        Running := True;
    end;
    Result := True;
  except
    Log('Could not list processes: ' + GetExceptionMessage);
  end;
end;

{ Returns what stops the operation, or an empty string once nothing does. }
function WaitForAppExit(const InstallDir: String): String;
var
  Running: Boolean;
begin
  repeat
    if not FindRunningApp(AddBackslash(InstallDir) + ProcessName, Running) then
      Result := CustomMessage('AppStateUnknown')
    else if Running then
      Result := CustomMessage('AppRunning')
    else
      Result := '';
  until (Result = '') or IsSilent or (MsgBox(Result, mbError, MB_RETRYCANCEL) <> IDRETRY);
end;

{ Installs are recognised by HKCU alone because this installer never installs for all users.
  StrToVersion compares each part as a number, so 1.10 is newer than 1.9. }
function InitializeSetup: Boolean;
var
  Installed: String;
  InstalledVersion, ThisVersion: Int64;
begin
  Result := True;
  if not RegQueryStringValue(HKCU, UninstallKey, 'DisplayVersion', Installed) then
    Exit;
  if not (StrToVersion(Installed, InstalledVersion) and StrToVersion('{#AppVersion}', ThisVersion)) then begin
    SuppressibleMsgBox(FmtMessage(CustomMessage('InstalledVersionUnknown'), [Installed]), mbCriticalError, MB_OK, IDOK);
    Result := False;
  end else if ComparePackedVersion(ThisVersion, InstalledVersion) < 0 then begin
    SuppressibleMsgBox(FmtMessage(CustomMessage('OlderThanInstalled'), [Installed, '{#AppVersion}']), mbCriticalError, MB_OK, IDOK);
    Result := False;
  end;
end;

function DirKey(const Dir: String): String;
begin
  Result := AnsiLowerCase(AddBackslash(ExpandFileName(Dir)));
end;

{ The folders DesktopServer.defaultHome() can resolve to. Equal, inside or around one of them all count: the app's
  files and the library must be able to go away independently of each other. }
function OverlappingDataDir(const InstallDir: String): String;
var
  DataDirs: array[0..2] of String;
  I: Integer;
  Target, Data: String;
begin
  Result := '';
  DataDirs[0] := ExpandConstant('{localappdata}\IllustrationArchive');
  DataDirs[1] := ExpandConstant('{localappdata}\IllustrationArchivePoC');
  DataDirs[2] := GetEnv('ILLUSTRATION_ARCHIVE_DESKTOP_HOME');
  Target := DirKey(InstallDir);
  for I := 0 to 2 do begin
    if DataDirs[I] = '' then
      Continue;
    Data := DirKey(DataDirs[I]);
    if (Pos(Data, Target) = 1) or (Pos(Target, Data) = 1) then begin
      Result := DataDirs[I];
      Exit;
    end;
  end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Data: String;
begin
  Result := True;
  if (CurPageID = wpSelectDir) and not WizardSilent then begin
    Data := OverlappingDataDir(WizardDirValue);
    if Data <> '' then begin
      MsgBox(FmtMessage(CustomMessage('DirOverlapsData'), [Data]), mbError, MB_OK);
      Result := False;
    end;
  end;
end;

{ An installed Edge browser does not count, and neither does a pv of 0.0.0.0, which Microsoft's guide says to treat as
  not installed. Machine-wide runtimes register in the 32-bit view even on 64-bit Windows. }
function RuntimeRegisteredIn(const RootKey: Integer): Boolean;
var
  Value: String;
  Version: Int64;
begin
  Result := RegQueryStringValue(RootKey, '{#WebView2Key}', 'pv', Value)
    and StrToVersion(Value, Version) and (Version <> 0);
end;

function HasWebView2: Boolean;
begin
  Result := RuntimeRegisteredIn(HKLM32) or RuntimeRegisteredIn(HKCU);
end;

{ Runs before any app file changes, so a failure leaves an existing install exactly as it was. The registry decides the
  outcome, not the exit code: the system-wide updater may finish the job for the bootstrapper. }
function EnsureWebView2: String;
var
  Bootstrapper: String;
  Code: Integer;
begin
  Result := '';
  if HasWebView2 then
    Exit;
  if not WizardSilent and (MsgBox(CustomMessage('WebView2Needed'), mbConfirmation, MB_OKCANCEL) <> IDOK) then begin
    Result := CustomMessage('WebView2Declined');
    Exit;
  end;
  WizardForm.PreparingLabel.Caption := CustomMessage('WebView2Installing');
  WizardForm.PreparingLabel.Visible := True;
  ExtractTemporaryFile(BootstrapperName);
  Bootstrapper := ExpandConstant('{tmp}\') + BootstrapperName;
  repeat
    Exec(Bootstrapper, '/silent /install', '', SW_HIDE, ewWaitUntilTerminated, Code);
    Log('WebView2 bootstrapper finished with code ' + IntToStr(Code));
    if HasWebView2 then begin
      Result := '';
      Exit;
    end;
    Result := FmtMessage(CustomMessage('WebView2Failed'), [IntToStr(Code)]);
  until WizardSilent or (MsgBox(Result, mbError, MB_RETRYCANCEL) <> IDRETRY);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  InstallDir, Data: String;
begin
  InstallDir := ExpandConstant('{app}');
  Data := OverlappingDataDir(InstallDir);
  if Data <> '' then begin
    Result := FmtMessage(CustomMessage('DirOverlapsData'), [Data]);
    Exit;
  end;
  Result := WaitForAppExit(InstallDir);
  if Result = '' then
    Result := EnsureWebView2;
  { Installing the runtime can take minutes, plenty of time to open the app again. }
  if Result = '' then
    Result := WaitForAppExit(InstallDir);
end;

function ManifestPath(const Line: String): String;
begin
  Result := Copy(Line, ManifestPathStart, Length(Line));
end;

{ Setup's dialog for a file it cannot replace offers to skip it and then still reports success, so what decides is the
  bytes that landed. Exceptions from this event are not fatal to Setup, which is why the outcome is a value. }
function CheckInstalledFiles(const InstallDir: String; var Manifest: TArrayOfString; var FirstBad: String): Integer;
var
  I: Integer;
  Digest: String;
begin
  Result := 0;
  for I := 0 to GetArrayLength(Manifest) - 1 do begin
    try
      Digest := GetSHA256OfFile(InstallDir + ManifestPath(Manifest[I]));
    except
      Digest := '';
    end;
    if CompareText(Digest, Copy(Manifest[I], 1, ManifestDigestLength)) <> 0 then begin
      if Result = 0 then
        FirstBad := ManifestPath(Manifest[I]);
      Log('Not installed as built: ' + ManifestPath(Manifest[I]));
      Result := Result + 1;
    end;
  end;
end;

{ The manifest in the install folder is the user's to edit, so a line reaching outside that folder is ignored. }
function IsInsideInstallDir(const Path: String): Boolean;
begin
  Result := (Path <> '') and (Path[1] <> '\') and (Path[1] <> '/') and (Pos(':', Path) = 0) and (Pos('..', Path) = 0);
end;

{ Only files an earlier installer recorded go, never a folder's whole contents. The uninstall log keeps these entries
  too, so a file that cannot be deleted now still goes on uninstall. }
procedure RemoveStaleFiles(const InstallDir: String; var Previous, Current: TArrayOfString);
var
  Keep: TStringList;
  I, Left: Integer;
  Path, Dir: String;
begin
  Left := 0;
  Keep := TStringList.Create;
  try
    for I := 0 to GetArrayLength(Current) - 1 do
      Keep.Add(ManifestPath(Current[I]));
    for I := 0 to GetArrayLength(Previous) - 1 do begin
      Path := ManifestPath(Previous[I]);
      if (Keep.IndexOf(Path) >= 0) or not IsInsideInstallDir(Path) or not FileExists(InstallDir + Path) then
        Continue;
      if DeleteFile(InstallDir + Path) then begin
        Log('Removed old file: ' + Path);
        Dir := ExtractFileDir(InstallDir + Path);
        while (CompareText(AddBackslash(Dir), InstallDir) <> 0) and RemoveDir(Dir) do
          Dir := ExtractFileDir(Dir);
      end else begin
        Log('Could not remove old file: ' + Path);
        Left := Left + 1;
      end;
    end;
  finally
    Keep.Free;
  end;
  if Left > 0 then
    SuppressibleMsgBox(FmtMessage(CustomMessage('StaleFilesLeft'), [IntToStr(Left)]), mbInformation, MB_OK, IDOK);
end;

{ The manifest in the install folder describes the last install that fully landed. A failed upgrade leaves the older
  one in place, so running the installer again still knows which old files to remove. }
function RecordInstalledFiles: Boolean;
var
  InstallDir, Built, Recorded, FirstBad: String;
  Current, Previous: TArrayOfString;
  BadCount: Integer;
begin
  InstallDir := AddBackslash(ExpandConstant('{app}'));
  Recorded := InstallDir + ManifestName;
  ExtractTemporaryFile(ManifestName);
  Built := ExpandConstant('{tmp}\') + ManifestName;
  Result := LoadStringsFromFile(Built, Current) and (GetArrayLength(Current) > 0);
  if not Result then begin
    Log('The embedded manifest could not be read.');
    Exit;
  end;
  BadCount := CheckInstalledFiles(InstallDir, Current, FirstBad);
  if BadCount > 0 then begin
    SuppressibleMsgBox(FmtMessage(CustomMessage('FilesNotReplaced'), [IntToStr(BadCount), FirstBad]), mbCriticalError, MB_OK, IDOK);
    Result := False;
    Exit;
  end;
  if FileExists(Recorded) and LoadStringsFromFile(Recorded, Previous) then
    RemoveStaleFiles(InstallDir, Previous, Current);
  Result := CopyFile(Built, Recorded, False);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    InstallVerified := RecordInstalledFiles;
end;

function FilesInstalled: Boolean;
begin
  Result := InstallVerified;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if (CurPageID = wpFinished) and not InstallVerified then begin
    WizardForm.FinishedHeadingLabel.Caption := CustomMessage('FinishedFailedHeading');
    WizardForm.FinishedLabel.Caption := CustomMessage('FinishedFailedText');
  end;
end;

{ Only called once Setup itself believes everything went well. }
function GetCustomSetupExitCode: Integer;
begin
  Result := 0;
  if not InstallVerified then
    Result := FilesNotInstalledExitCode;
end;

function InitializeUninstall: Boolean;
var
  Problem: String;
begin
  Problem := WaitForAppExit(ExpandConstant('{app}'));
  Result := Problem = '';
  if not Result then
    Log('Uninstall stopped: ' + Problem);
end;
