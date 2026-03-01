#!/bin/sh
# start_remember.sh — G1 Session 2: REMEMBER + WR (With Reciprocity)
# Memory ENABLED: robot recognizes user, references past interaction
#
# Usage: ./start_remember.sh <laptop_ip> <participant_id> [language]
# Example: ./start_remember.sh 192.168.11.32 P01
# Example: ./start_remember.sh 192.168.11.32 P01 ja

OPENCV_LIB=/usr/local/share/OpenCV/java
JAR_DIR=/home/root/SotaWhisperTest
STATUS_PORT=5051

if [ -z "$1" ] || [ -z "$2" ]; then
    echo "Usage: $0 <laptop_ip> <participant_id> [language]"
    echo ""
    echo "  laptop_ip       Laptop IP address"
    echo "  participant_id   e.g., P01, P02..."
    echo "  language         en or ja (default: en)"
    echo ""
    echo "Condition: REMEMBER + WR (G1 Session 2, memory enabled)"
    echo ""
    echo "Example: $0 192.168.11.32 P01"
    echo "Example: $0 192.168.11.32 P01 ja"
    exit 1
fi

LAPTOP_IP="$1"
PID="$2"
LANG="${3:-en}"

echo "========================================"
echo "  REMEMBER Session (G1-S2)"
echo "  Participant: $PID"
echo "  Group: G1"
echo "  Condition: REMEMBER + WR"
echo "  Memory: ENABLED"
echo "  Language: $LANG"
echo "========================================"
echo ""

cd "$JAR_DIR"
exec env LD_LIBRARY_PATH="$OPENCV_LIB:$LD_LIBRARY_PATH" \
    java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
    -jar whisperinteraction.jar "$LAPTOP_IP" \
    --status-port $STATUS_PORT \
    --participant-id "$PID" \
    --group G1 \
    --session 2 \
    --language "$LANG"
