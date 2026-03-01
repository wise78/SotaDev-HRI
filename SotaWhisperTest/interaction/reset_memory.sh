#!/bin/sh
# reset_memory.sh — Clear all user profiles and face memory
# Run on robot before experiment to start fresh
#
# Usage: ./reset_memory.sh

DATA_DIR=/home/root/SotaWhisperTest/data

echo "========================================"
echo "  Memory Reset"
echo "========================================"

if [ -f "$DATA_DIR/user_profiles.json" ]; then
    COUNT=$(grep -o '"userId"' "$DATA_DIR/user_profiles.json" 2>/dev/null | wc -l)
    echo "  Found $COUNT user profile(s)"
    rm -f "$DATA_DIR/user_profiles.json"
    echo "  Deleted: user_profiles.json"
else
    echo "  No user_profiles.json found (already clean)"
fi

echo ""
echo "  Memory cleared!"
echo "  Note: Reboot robot to also clear camera face cache"
echo "========================================"
