#!/bin/sh
# start_videocall.sh — Video call mode: no camera, auto-start conversation
# For remote participants via LINE/WhatsApp video call.
# Camera and face detection are DISABLED — all servos free for gestures.
#
# Session 1: ./start_videocall.sh <laptop_ip> <participant_id> <group> [language]
# Session 2: ./start_videocall.sh <laptop_ip> <participant_id> <name> [language]
#
# Session is auto-detected: if <group_or_name> is G1/G2, it's Session 1.
# Otherwise it's Session 2 (name-based profile lookup).

OPENCV_LIB=/usr/local/share/OpenCV/java
JAR_DIR=/home/root/SotaWhisperTest
STATUS_PORT=5051

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ]; then
    echo "Usage (Session 1 - first meeting):"
    echo "  $0 <laptop_ip> <participant_id> <G1|G2> [language]"
    echo "  Example: $0 192.168.11.32 P10 G1"
    echo "  Example: $0 192.168.11.32 P10 G2 en"
    echo ""
    echo "Usage (Session 2 - returning user):"
    echo "  $0 <laptop_ip> <participant_id> <name> [language]"
    echo "  Example: $0 192.168.11.32 P10 Andi"
    echo "  Example: $0 192.168.11.32 P10 Andi en"
    echo ""
    echo "Mode: VIDEO-CALL (no camera, auto-start, full gesture)"
    exit 1
fi

LAPTOP_IP="$1"
PID="$2"
GROUP_OR_NAME="$3"
LANG="${4:-en}"

# Auto-detect session: G1/G2 = Session 1, otherwise = Session 2 (name)
case "$GROUP_OR_NAME" in
    G1|g1|G2|g2)
        SESSION=1
        GROUP=$(echo "$GROUP_OR_NAME" | tr '[:lower:]' '[:upper:]')
        NAME=""
        echo "========================================"
        echo "  VIDEO CALL - Session 1 (NOVICE)"
        echo "  Participant: $PID"
        echo "  Group: $GROUP"
        echo "  Condition: NOVICE + WOR"
        echo "  Memory: DISABLED (first meeting)"
        echo "  Language: $LANG"
        echo "  Camera: DISABLED (video-call)"
        echo "  Gestures: FULL (all servos)"
        echo "========================================"
        echo ""

        cd "$JAR_DIR"
        exec env LD_LIBRARY_PATH="$OPENCV_LIB:$LD_LIBRARY_PATH" \
            java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
            -jar whisperinteraction.jar "$LAPTOP_IP" \
            --status-port $STATUS_PORT \
            --video-call \
            --no-memory \
            --participant-id "$PID" \
            --group "$GROUP" \
            --session 1 \
            --language "$LANG"
        ;;
    *)
        SESSION=2
        NAME="$GROUP_OR_NAME"
        # Determine group from participant profiles (G1=REMEMBER, G2=NO-REMEMBER)
        # Default to G1 (REMEMBER) — override with env var if needed: GROUP=G2 ./start_videocall.sh ...
        GROUP="${GROUP:-G1}"

        if [ "$GROUP" = "G2" ] || [ "$GROUP" = "g2" ]; then
            CONDITION="NO-REMEMBER + WOR"
            MEMORY_FLAG="--no-memory"
            MEMORY_LABEL="DISABLED (pretend forget)"
        else
            CONDITION="REMEMBER + WR"
            MEMORY_FLAG=""
            MEMORY_LABEL="ENABLED"
        fi

        echo "========================================"
        echo "  VIDEO CALL - Session 2"
        echo "  Participant: $PID"
        echo "  Target Name: $NAME"
        echo "  Group: $GROUP"
        echo "  Condition: $CONDITION"
        echo "  Memory: $MEMORY_LABEL"
        echo "  Language: $LANG"
        echo "  Camera: DISABLED (video-call)"
        echo "  Gestures: FULL (all servos)"
        echo "========================================"
        echo ""

        cd "$JAR_DIR"
        exec env LD_LIBRARY_PATH="$OPENCV_LIB:$LD_LIBRARY_PATH" \
            java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
            -jar whisperinteraction.jar "$LAPTOP_IP" \
            --status-port $STATUS_PORT \
            --video-call \
            $MEMORY_FLAG \
            --participant-id "$PID" \
            --group "$GROUP" \
            --session 2 \
            --target-name "$NAME" \
            --language "$LANG"
        ;;
esac
