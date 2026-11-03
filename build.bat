@echo off
rem Build helper: uses a local JDK 21 + Gradle when JAVA_HOME/gradle are not on PATH.
setlocal

if "%JAVA_HOME%"=="" (
  if exist "D:\Chat\.build-tools\jdk21\jdk-21.0.13+9\bin\javac.exe" (
    set "JAVA_HOME=D:\Chat\.build-tools\jdk21\jdk-21.0.13+9"
  ) else (
    echo [ERROR] JAVA_HOME is not set and no bundled JDK 21 was found.
    exit /b 1
  )
)

set "GRADLE=gradle"
if exist "D:\Chat\.build-tools\gradle-8.10.2\bin\gradle.bat" set "GRADLE=D:\Chat\.build-tools\gradle-8.10.2\bin\gradle.bat"

set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using JAVA_HOME=%JAVA_HOME%
echo Using GRADLE=%GRADLE%
echo.

call "%GRADLE%" build --console=plain
if errorlevel 1 (
  echo.
  echo [FAILED] see the output above. If downloads stall, check the proxy settings in gradle.properties.
  exit /b 1
)

echo.
echo [OK] Jar: build\libs\deepseek-companion-1.0.0.jar
