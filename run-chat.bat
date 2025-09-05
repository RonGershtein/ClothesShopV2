@echo off
cd /d "%~dp0"
echo [Chat] starting on 6060...
java -cp out server.app.ChatServer 6060
