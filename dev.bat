@echo off
for /f "tokens=1,2 delims==" %%A in (.env) do set %%A=%%B
gradlew.bat bootRun --args="--spring.profiles.active=dev"
