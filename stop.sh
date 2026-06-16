#!/bin/bash
echo "Stopping backend..."
pkill -f "spring-boot:run" 2>/dev/null
pkill -f "MvnDaemonThread\|mvn" 2>/dev/null

echo "Stopping frontend..."
pkill -f "vite" 2>/dev/null

echo "Stopped."
