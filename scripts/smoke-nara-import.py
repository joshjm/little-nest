#!/usr/bin/env python3
"""Check the installed release's real document-picker import on an owned emulator."""
import csv
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
if not SERIAL.startswith("emulator-"):
    raise SystemExit("This test creates sample data. Use only a disposable emulator.")
ADB = str(Path(os.environ.get("ANDROID_HOME", Path.home() / ".local/share/baby-app-tools/android-sdk")) / "platform-tools/adb")
REMOTE = "/sdcard/Download/little-nest-nara-smoke.csv"
HIERARCHY = "/sdcard/little-nest-import-smoke.xml"


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args])


def nodes():
    result = adb("shell", "uiautomator", "dump", HIERARCHY)
    if b"dumped to" not in result:
        raise AssertionError("Android UI hierarchy unavailable")
    return list(ET.fromstring(adb("shell", "cat", HIERARCHY)).iter("node"))


def find(text, attr="text", contains=False):
    for node in nodes():
        value = node.get(attr, "")
        matches = text.casefold() in value.casefold() if contains else text.casefold() == value.casefold()
        if matches:
            return node
    return None


def tap(text, attr="text", contains=False):
    node = find(text, attr, contains)
    assert node is not None, f"Missing UI control: {text}"
    bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))


def wait(text, seconds=20, contains=False):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if find(text, contains=contains) is not None:
            return
        time.sleep(.3)
    raise AssertionError(f"Timed out waiting for: {text}")


def import_csv():
    tap("Family")
    for _ in range(12):
        if find("Import Nara Baby CSV") is not None:
            break
        adb("shell", "input", "swipe", "500", "1800", "500", "500", "250")
    tap("Import Nara Baby CSV")
    if find("Show roots", "content-desc") is not None:
        tap("Show roots", "content-desc")
        wait("Downloads")
        tap("Downloads")
    wait("little-nest-nara-smoke.csv")
    tap("little-nest-nara-smoke.csv")
    wait("Import Nara Baby CSV")
    checkbox = find("Continue 1 ongoing sleep timer", contains=True)
    assert checkbox is not None and checkbox.get("checked") == "true", "Recent ongoing sleep must be offered for continuation"
    tap("android:id/button1", "resource-id")
    wait("Import complete", 60)


now = int(time.time() * 1000)
family = "synthetic-" + str(uuid.uuid4())
rows = []


def event(kind, fields, unassigned=False):
    row = {"Type": kind, "Profile Name": "" if unassigned else "CSV Smoke Baby",
           "Start Date/time (Epoch)": str(now - 600_000), "Time Zone": "Australia/Brisbane",
           "Created By Caregiver": "Test caregiver", "Last Updated By Caregiver": "Test partner",
           "_familyKey": family, "_profileKey": "" if unassigned else "test-baby",
           "_activityKey": "test-" + kind, "Note": 'CSV smoke, with "quotes"\nand a new line  '}
    row.update(fields)
    rows.append(row)


event("Sleep", {})
event("Bottle Feed", {"[Bottle Feed] Type": "Breast Milk Formula", "[Bottle Feed] Formula Name": "Sample formula",
                     "[Bottle Feed] Breast Milk Volume": "25", "[Bottle Feed] Breast Milk Volume Unit": "ML",
                     "[Bottle Feed] Formula Volume": "75", "[Bottle Feed] Formula Volume Unit": "ML"})
event("Diaper", {"[Diaper] Type": "Dirty Wet", "[Diaper] Detail": "Blowout", "[Diaper] Dirty Color": "BLACK GREEN",
                "[Diaper] Dirty Texture": "MUSH PEBBLE"})
event("Breastfeed", {"[Breastfeed] Begin Side": "RIGHT", "[Breastfeed] End Side": "LEFT.nonTimer",
                    "[Breastfeed] Left Duration (Seconds)": "120", "[Breastfeed] Right Duration (Seconds)": "60"})
event("Pump", {"[Pump] Duration (Seconds)": "0", "[Pump] Left Volume": "1", "[Pump] Left Volume Unit": "FLOZ",
               "[Pump] Right Volume": "10", "[Pump] Right Volume Unit": "ML"}, unassigned=True)
event("Growth", {"[Growth] Weight": "5", "[Growth] Weight Unit": "KG", "[Growth] Head Size": "15", "[Growth] Head Size Unit": "IN"})
event("Baby First", {"[Baby First] Baby First": "Grabbing rattle"})
event("Profile", {"[Profile] Type": "CHILD", "[Profile] Sex": "FEMALE", "[Profile] Birth Date": "2025-01-01"})
columns = list(dict.fromkeys(key for row in rows for key in row))
buffer = io.StringIO(newline="")
writer = csv.DictWriter(buffer, columns)
writer.writeheader()
writer.writerows(rows)

try:
    with tempfile.TemporaryDirectory(prefix="little-nest-import-") as directory:
        file = Path(directory) / "little-nest-nara-smoke.csv"
        file.write_text(buffer.getvalue(), encoding="utf-8")
        adb("push", str(file), REMOTE)
    adb("shell", "am", "start", "-W", "-S", "-n", "app.littlenest/.MainActivity")
    if find("Show roots", "content-desc") is not None:
        adb("shell", "input", "keyevent", "BACK")
        adb("shell", "am", "start", "-W", "-n", "app.littlenest/.MainActivity")
    wait("Family")
    import_csv()
    assert find("9 new records imported", contains=True) is not None
    tap("android:id/button1", "resource-id")
    wait("Today")
    tap("Today")
    wait("Sleeping now")
    tap("Finish")
    wait("A day at a time")
    assert find("Finish") is None
    import_csv()
    assert find("0 new records imported", contains=True) is not None
    tap("android:id/button1", "resource-id")
    wait("Today")
    tap("Today")
    wait("A day at a time")
    assert find("Finish") is None, "Reimport must not restart a finished sleep"
    print("Signed release CSV smoke passed: document picker, mixed/side-unit data, ongoing sleep finish and duplicate-free repeat import.")
finally:
    adb("shell", "rm", "-f", REMOTE, HIERARCHY)
