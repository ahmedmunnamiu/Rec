@echo off
REM Advanced Auto Call Recorder - ADB setup for Windows.
REM Grants the permissions ADB is allowed to grant. It cannot grant signature/privileged permissions
REM (such as CAPTURE_AUDIO_OUTPUT) and does not bypass any Android security restriction.
REM Usage: setup_adb.bat [device-serial] [package]
setlocal EnableDelayedExpansion
set "SERIAL=%~1"
set "PKG=%~2"
if "%PKG%"=="" set "PKG=com.ahmedmunna.callrecorder"

where adb >nul 2>&1
if errorlevel 1 (
  echo adb not found. Install Android platform-tools and add it to PATH.
  exit /b 1
)

adb start-server >nul 2>&1
echo == Device detection ==
if "%SERIAL%"=="" (
  set COUNT=0
  for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
    if "%%b"=="device" ( set /a COUNT+=1 & set "SERIAL=%%a" )
    if "%%b"=="unauthorized" echo Unauthorized device %%a - accept the USB debugging prompt on the phone.
  )
  if !COUNT! EQU 0 ( echo No authorised device found. See adb\README.md. & exit /b 1 )
  if !COUNT! GTR 1 ( echo Several devices connected. Re-run: setup_adb.bat ^<serial^> & exit /b 1 )
)
echo Using device: %SERIAL%
set "A=adb -s %SERIAL%"

echo.
echo == Package detection ==
%A% shell pm list packages %PKG% | findstr /x /c:"package:%PKG%" >nul
if errorlevel 1 (
  echo %PKG% is not installed. Install first: adb -s %SERIAL% install -r AdvancedCallRecorder-debug.apk
  exit /b 1
)
echo   [ OK ] %PKG% is installed

set FAILS=0
echo.
echo == Runtime permissions ==
for %%P in (RECORD_AUDIO READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS POST_NOTIFICATIONS) do (
  %A% shell pm grant %PKG% android.permission.%%P >"%TEMP%\acr_out.txt" 2>&1
  set "OUT="
  for /f "usebackq delims=" %%L in ("%TEMP%\acr_out.txt") do set "OUT=%%L"
  if "!OUT!"=="" ( echo   [ OK ] granted %%P ) else ( echo   [INFO] %%P: !OUT! & set /a FAILS+=1 )
)
echo   (POST_NOTIFICATIONS only exists on Android 13+; an error there on older Android is normal.)

echo.
echo == AppOps ==
%A% shell appops set %PKG% RECORD_AUDIO allow
%A% shell appops set %PKG% RUN_ANY_IN_BACKGROUND allow
%A% shell appops set %PKG% SYSTEM_ALERT_WINDOW allow

echo.
echo == Battery optimisation ==
%A% shell dumpsys deviceidle whitelist +%PKG%

echo.
echo == Not grantable through ADB ==
echo   CAPTURE_AUDIO_OUTPUT is a signature/privileged permission. ADB cannot grant it.
echo   Run the Engine Test Center in the app to see what your phone really supports.
echo.
echo Done. Permissions reported as INFO above were not granted.
endlocal
exit /b 0
