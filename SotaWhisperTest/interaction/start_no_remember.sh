#!/bin/sh
# start_no_remember.sh — G2 Session 2: NO-REMEMBER + WOR (Without Reciprocity)
# Memory DISABLED: robot pretends to forget name but uses context (origin)
#
# Usage: ./start_no_remember.sh <laptop_ip> <participant_id> <name> [language]
# Example: ./start_no_remember.sh 192.168.11.32 P03 Rupak
# Example: ./start_no_remember.sh 192.168.11.32 P03 Rupak ja

OPENCV_LIB=/usr/local/share/OpenCV/java
JAR_DIR=/home/root/SotaWhisperTest
STATUS_PORT=5051

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
    echo "Usage: $0 <laptop_ip> <participant_id> <name> [language]"
    echo ""
    echo "  laptop_ip       Laptop IP address"
    echo "  participant_id   e.g., P01, P02..."
    echo "  name             User name from Session 1 (must match profile)"
    echo "  language         en or ja (default: en)"
    echo ""
    echo "Condition: NO-REMEMBER + WOR (G2 Session 2, pretend forget)"
    echo ""
    echo "Example: $0 192.168.11.32 P03 Rupak"
    echo "Example: $0 192.168.11.32 P03 Rupak ja"
    exit 1
fi

LAPTOP_IP="$1"
PID="$2"
NAME="$3"
LANG="${4:-en}"

echo "========================================"
echo "  NO-REMEMBER Session (G2-S2)"
echo "  Participant: $PID"
echo "  Target Name: $NAME"
echo "  Group: G2"
echo "  Condition: NO-REMEMBER + WOR"
echo "  Memory: DISABLED (pretend forget)"
echo "  Language: $LANG"
echo "========================================"
echo ""

cd "$JAR_DIR"
exec env LD_LIBRARY_PATH="$OPENCV_LIB:$LD_LIBRARY_PATH" \
    java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
    -jar whisperinteraction.jar "$LAPTOP_IP" \
    --status-port $STATUS_PORT \
    --no-memory \
    --participant-id "$PID" \
    --group G2 \
    --session 2 \
    --target-name "$NAME" \
    --language "$LANG"
