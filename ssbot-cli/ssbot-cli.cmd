@echo off
if not exist "%~dp0target\ssbot-cli.jar" (
  echo Build first: cd /d "%~dp0" ^&^& mvnw.cmd package
  exit /b 1
)
java -Dfile.encoding=UTF-8 -jar "%~dp0target\ssbot-cli.jar" %*
