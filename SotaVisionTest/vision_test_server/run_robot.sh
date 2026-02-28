#!/bin/bash
# ============================================================
#  VisionTestServer — Run on Sota Robot
#
#  Usage: ./run_robot.sh [port]
#  Default port: 8889
#
#  Jalankan dari folder vision_test_server/ di robot Sota.
# ============================================================

cd "$(dirname "$0")"

# Classpath (relative to vision_test_server/)
LIB_DIR="../../lib"
ROBOT_LIB="/home/vstone/lib"
BIN_DIR="../bin"
CLASSPATH="${BIN_DIR}:${LIB_DIR}/sotalib.jar:${LIB_DIR}/SRClientHelper.jar:${LIB_DIR}/opencv-310.jar:${LIB_DIR}/jna-4.1.0.jar:${ROBOT_LIB}/*:/home/vstone/vstonemagic/*:/usr/local/share/OpenCV/java/opencv-310.jar"

# OpenCV native library path
export LD_LIBRARY_PATH="/usr/local/share/OpenCV/java:${LD_LIBRARY_PATH}"

PORT="${1:-8889}"

# Compile if needed
if [ ! -f "${BIN_DIR}/jp/vstone/sotavisiontest/VisionTestServer.class" ]; then
    echo "[INFO] Compiling VisionTestServer..."
    mkdir -p "${BIN_DIR}"
    javac -source 8 -target 8 -cp "${CLASSPATH}" -d "${BIN_DIR}" \
        ../src/jp/vstone/sotavisiontest/VisionTestServer.java
    if [ $? -ne 0 ]; then
        echo "[ERROR] Compilation failed."
        exit 1
    fi
    echo "[OK] Compiled."
fi

echo ""
echo "============================================================"
echo "  Sota Vision Test Server"
echo "  Port: ${PORT}"
echo "  Press Ctrl+C to stop"
echo "============================================================"
echo ""

java -cp "${CLASSPATH}" -Dfile.encoding=UTF-8 \
    jp.vstone.sotavisiontest.VisionTestServer "${PORT}"
