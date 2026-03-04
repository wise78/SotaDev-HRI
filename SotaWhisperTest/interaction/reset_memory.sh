#!/bin/sh
# reset_memory.sh — Clear all user profiles AND camera face DB
# Run on robot before experiment to start fresh
#
# Usage: ./reset_memory.sh

DATA_DIR=/home/root/SotaWhisperTest/data
FACE_USER_DIR=/var/sota/userlist

echo "========================================"
echo "  Memory Reset"
echo "========================================"

# 1. Clear user profiles
if [ -f "$DATA_DIR/user_profiles.json" ]; then
    COUNT=$(grep -o '"userId"' "$DATA_DIR/user_profiles.json" 2>/dev/null | wc -l)
    echo "  Found $COUNT user profile(s)"
    rm -f "$DATA_DIR/user_profiles.json"
    echo "  Deleted: user_profiles.json"
else
    echo "  No user_profiles.json found (already clean)"
fi

# 2. Clear camera face user database
#    SDK stores registered faces at /var/sota/userlist/
#    Each user = .user (JSON) + .png (face photo)
#    Source: FaceDetectLib.FACEFEATURE_LIST_DIR in sotalib.jar
echo ""
if [ -d "$FACE_USER_DIR" ]; then
    USER_COUNT=$(ls "$FACE_USER_DIR"/*.user 2>/dev/null | wc -l)
    PHOTO_COUNT=$(ls "$FACE_USER_DIR"/*.png 2>/dev/null | wc -l)
    if [ "$USER_COUNT" -gt 0 ] || [ "$PHOTO_COUNT" -gt 0 ]; then
        echo "  Found $USER_COUNT .user file(s), $PHOTO_COUNT .png file(s)"
        rm -f "$FACE_USER_DIR"/*.user "$FACE_USER_DIR"/*.png
        echo "  Deleted all face user data"
    else
        echo "  No face user files found (already clean)"
    fi
    # Also list any remaining files for transparency
    REMAINING=$(ls "$FACE_USER_DIR"/ 2>/dev/null | wc -l)
    if [ "$REMAINING" -gt 0 ]; then
        echo "  Remaining files in userlist/:"
        ls "$FACE_USER_DIR"/
    fi
else
    echo "  Face user dir not found: $FACE_USER_DIR"
    echo "  (Will be created by SDK on first face registration)"
fi

echo ""
echo "  Memory cleared!"
echo "  Restart the interaction program for changes to take effect."
echo "========================================"
