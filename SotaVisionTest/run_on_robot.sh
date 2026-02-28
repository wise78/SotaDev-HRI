#!/bin/bash
# ============================================================
#  SotaVisionTest - Run on Sota Robot
#  Deploy and run the Social Interaction System on the robot
#  Usage: ./run_on_robot.sh [ollama_url] [language]
#  Example: ./run_on_robot.sh http://192.168.11.5:11434 en
# ============================================================

# Classpath for robot environment
LIB_DIR="../lib"
ROBOT_LIB="/home/vstone/lib"
CLASSPATH="bin:${LIB_DIR}/sotalib.jar:${LIB_DIR}/SRClientHelper.jar:${LIB_DIR}/opencv-310.jar:${LIB_DIR}/jna-4.1.0.jar:${ROBOT_LIB}/*:/home/vstone/vstonemagic/*:/usr/local/share/OpenCV/java/opencv-310.jar"

# OpenCV native library path
export LD_LIBRARY_PATH="/usr/local/share/OpenCV/java:${LD_LIBRARY_PATH}"

# Default: Ollama running on laptop
OLLAMA_URL="${1:-http://192.168.11.5:11434}"

# Language: ja (Japanese, default) or en (English)
LANGUAGE="${2:-ja}"

echo "============================================================"
echo "  SotaVisionTest - Social Interaction System (Robot)"
echo "  Ollama: ${OLLAMA_URL}"
echo "  Language: ${LANGUAGE}"
echo "============================================================"
echo ""

# Compile if needed
if [ ! -f "bin/jp/vstone/sotavisiontest/MainController.class" ]; then
    echo "[INFO] Compiling..."
    mkdir -p bin
    javac -source 8 -target 8 -cp "${CLASSPATH}" -d bin src/jp/vstone/sotavisiontest/*.java
    if [ $? -ne 0 ]; then
        echo "[ERROR] Compilation failed."
        exit 1
    fi
    echo "[OK] Compiled."
fi

echo "Starting..."
java -cp "${CLASSPATH}" -Dfile.encoding=UTF-8 jp.vstone.sotavisiontest.MainController "${OLLAMA_URL}" "${LANGUAGE}"
