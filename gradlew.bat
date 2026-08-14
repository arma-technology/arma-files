@echo off
setlocal
set DIRNAME=%~dp0
set JAR=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
set URL=https://github.com/gradle/gradle/raw/refs/tags/v8.11.1/gradle/wrapper/gradle-wrapper.jar
set SHA=2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046
if not exist "%JAR%" (
  echo Gradle wrapper JAR not found; downloading verified Gradle 8.11.1 wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='%JAR%'; Invoke-WebRequest -UseBasicParsing '%URL%' -OutFile $p; $h=(Get-FileHash -Algorithm SHA256 $p).Hash.ToLower(); if($h -ne '%SHA%'){Remove-Item $p; exit 2}"
  if errorlevel 1 exit /b 1
)
java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
