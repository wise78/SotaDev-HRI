#!/bin/sh
# run_interaction.sh — Launch WhisperInteraction with OpenCV native library path
# Usage: ./run_interaction.sh <laptop_ip> [options]
#
# OpenCV native lib (libopencv_java310.so) lives at /usr/local/share/OpenCV/java/
# Java cannot find it without LD_LIBRARY_PATH.
#
# StatusServer runs on port 5051 by default (for GUI monitoring).

OPENCV_LIB=/usr/local/share/OpenCV/java
JAR_DIR=/home/root/SotaWhisperTest
STATUS_PORT=5051

if [ -z "$1" ]; then
    echo "Usage: $0 <laptop_ip> [options]"
    echo ""
    echo "Options:"
    echo "  --ollama-port <port>   Ollama port (default: 11434)"
    echo "  --model <name>         Ollama model (default: llama3.2:3b)"
    echo "  --status-port <port>   Status server port for GUI (default: 5051)"
    echo ""
    echo "Example: $0 192.168.11.32"
    echo "Monitor: Open GUI on laptop -> connect to robot IP:$STATUS_PORT"
    exit 1
fi

echo "[run_interaction] LD_LIBRARY_PATH=$OPENCV_LIB"
echo "[run_interaction] JAR: $JAR_DIR/whisperinteraction.jar"
echo "[run_interaction] Status server: port $STATUS_PORT"
echo "[run_interaction] Args: $@"
echo ""

cd "$JAR_DIR"
exec env LD_LIBRARY_PATH="$OPENCV_LIB:$LD_LIBRARY_PATH" \
    java -jar whisperinteraction.jar "$@" --status-port $STATUS_PORT
