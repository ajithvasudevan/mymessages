#!/usr/bin/env python3
"""Surgical patches on europa ~/mymessages: add RECORD_AUDIO perm + the 'speak' string."""
import os, sys, time, shutil, pathlib

ROOT = pathlib.Path(os.path.expanduser("~/mymessages"))
BAK = ROOT / ("app/.bak_speak_%d" % int(time.time()))
BAK.mkdir(parents=True, exist_ok=True)

def patch(rel, anchor, addition, present_marker):
    p = ROOT / rel
    t = p.read_text()
    if present_marker in t:
        print(f"SKIP {rel}: already has marker")
        return
    if anchor not in t:
        print(f"ERROR {rel}: anchor not found: {anchor!r}", file=sys.stderr)
        sys.exit(2)
    shutil.copy2(p, BAK / p.name)
    t = t.replace(anchor, anchor + addition, 1)
    p.write_text(t)
    print(f"OK   {rel}: patched")

# 1) RECORD_AUDIO after MODIFY_AUDIO_SETTINGS
patch(
    "app/src/main/AndroidManifest.xml",
    '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />',
    '\n    <!-- Two-way doorbell talk: capture the phone mic for the WebRTC backchannel. -->\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />',
    'android.permission.RECORD_AUDIO',
)

# 2) 'speak' string after the 'mute' string
patch(
    "app/src/main/res/values/strings.xml",
    '<string name="mute">Mute / unmute audio</string>',
    '\n    <string name="speak">Talk to door</string>',
    'name="speak"',
)

print("backup dir:", BAK)
