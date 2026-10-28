; ThermoHammer for Windows — the installer (Inno Setup 6). Built on a Mac by installer/build.sh, which publishes the app
; and passes the paths:  ISCC /DArch=x64|arm64 /DPublishDir=... /DRedist=... /DOutDir=... ThermoHammer.iss

#ifndef Arch
  #define Arch "x64"
#endif
#define AppName "ThermoHammer"
#define AppVersion "2.0.0"

[Setup]
AppId={{6D1F0C57-7A0B-4B54-9F8E-3C2D3A6B7E21}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=GranTurismo
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir={#OutDir}
OutputBaseFilename=ThermoHammer-{#AppVersion}-{#Arch}-Setup
SetupIconFile={#SourcePath}\..\Assets\AppIcon.ico
UninstallDisplayIcon={app}\ThermoHammer.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequiredOverridesAllowed=dialog
#if Arch == "arm64"
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
MinVersion=10.0.19041

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; ONNX Runtime needs the Visual C++ runtime; Windows doesn't always have it
Source: "{#Redist}"; DestDir: "{tmp}"; DestName: "vc_redist.exe"; Flags: deleteafterinstall; Check: NeedsVcRuntime

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\ThermoHammer.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\ThermoHammer.exe"; Tasks: desktopicon

[Run]
Filename: "{tmp}\vc_redist.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "Installing the Visual C++ runtime..."; Flags: waituntilterminated; Check: NeedsVcRuntime
Filename: "{app}\ThermoHammer.exe"; Description: "Start {#AppName}"; Flags: nowait postinstall skipifsilent

[Code]
function NeedsVcRuntime: Boolean;
var
  Installed: Cardinal;
  Key: String;
begin
#if Arch == "arm64"
  Key := 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\arm64';
#else
  Key := 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64';
#endif
  Result := not (RegQueryDWordValue(HKLM, Key, 'Installed', Installed) and (Installed = 1));
end;
