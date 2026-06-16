@echo off
setlocal
set "MAVEN_HOME=%~dp0.mvn\apache-maven-3.9.8"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo ERROR: Local Maven distribution not found.
  echo Expected: %MAVEN_HOME%\bin\mvn.cmd
  exit /b 1
)
"%MAVEN_HOME%\bin\mvn.cmd" %*
