#!/bin/sh
# start_novice.sh — Session 1 for ALL participants (G1 and G2)
# Condition: NOVICE + WOR (Without Reciprocity)
# Memory disabled, first meeting for everyone
#
# Usage: ./start_novice.sh <laptop_ip> <participant_id> <group>
# Example: ./start_novice.sh 192.168.11.32 P01 G1

OPENCV_LIB=/usr/local/share/OpenCV/java
JAR_DIR=/home/root/SotaWhisperTest
STATUS_PORT=5051

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
    echo "Usage: $0 <laptop_ip> <participant_id> <group>"
    echo ""
    echo "  laptop_ip       Laptop IP address"
    echo "  participant_id   e.g., P01, P02..."
    echo "  group            G1 or G2"
    echo ""
    echo "Condition: NOVICE + WOR (Session 1, no memory)"
    echo ""
    echo "Example: $0 192.168.11.32 P01 G1"
    exit 1
fi

LAPTOP_IP="$1"
PID="$2"
GROUP="$3"

echo "========================================"
echo "  NOVICE Session (S1)"
echo "  Participant: $PID"
echo "  Group: $GROUP"
echo "  Condition: NOVICE + WOR"
echo "  Memory: DISABLED"
echo "========================================"
echo ""

cd "$JAR_DIR"
exec env LD_LIBRARY_PATH="$OPENCV_LIB:$LD_LIBRARY_PATH" \
    java -jar whisperinteraction.jar "$LAPTOP_IP" \
    --status-port $STATUS_PORT \
    --no-memory \
    --participant-id "$PID" \
    --group "$GROUP" \
    --session 1
