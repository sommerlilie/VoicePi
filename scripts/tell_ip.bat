@echo off
for /f "delims=[] tokens=2" %%a in ('ping -4 -n 1 %ComputerName% ^| findstr [') do set ThisIP=%%a
echo %ThisIP%  