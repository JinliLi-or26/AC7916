@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Gradle command was not found in PATH.
  echo Install Gradle 8.9 or use Android Studio to create an Empty Views Activity (Java) project first.
  pause
  exit /b 1
)
call gradle wrapper --gradle-version 8.9 --distribution-type bin
if errorlevel 1 exit /b 1
echo Gradle wrapper created. Re-open the project in Android Studio.
pause
