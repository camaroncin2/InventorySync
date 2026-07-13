@echo off
setlocal

cd /d "%~dp0"

echo Compilando Cretania (neoforge + velocity + client)...
call gradlew.bat assemble --console=plain
if errorlevel 1 (
    echo.
    echo BUILD FALLIDO.
    pause
    exit /b 1
)

echo.
echo BUILD OK. Jars generados:
for %%D in (cretania-neoforge cretania-velocity cretania-client) do (
    for %%F in ("%%D\build\libs\%%D-*.jar") do echo   %%F
)

pause
