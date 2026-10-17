#!/usr/bin/env python3
"""Check the installed release's real document-picker import on an owned emulator."""
import csv
from datetime import datetime, timezone
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
GROWTH = "--growth" in sys.argv[2:]
if not SERIAL.startswith("emulator-"):
    raise SystemExit("This test creates sample data. Use only a disposable emulator.")
ADB = str(Path(os.environ.get("ANDROID_HOME", Path.home() / ".local/share/baby-app-tools/android-sdk")) / "platform-tools/adb")
REMOTE = "/sdcard/Download/little-nest-csv-smoke.csv"
HIERARCHY = "/sdcard/little-nest-import-smoke.xml"


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args])


def nodes():
    for attempt in range(3):
        result = adb("shell", "uiautomator", "dump", HIERARCHY)
        if b"dumped to" in result:
            return list(ET.fromstring(adb("shell", "cat", HIERARCHY)).iter("node"))
        print(f"UI hierarchy not ready, attempt {attempt + 1}/3", file=sys.stderr)
        time.sleep(1)
    raise AssertionError("Android UI hierarchy unavailable after three attempts")


def find(text, attr="text", contains=False, resource_id=None):
    for node in nodes():
        value = node.get(attr, "")
        matches = text.casefold() in value.casefold() if contains else text.casefold() == value.casefold()
        if matches and (resource_id is None or node.get("resource-id") == resource_id):
            return node
    return None


def tap(text, attr="text", contains=False, resource_id=None):
    node = find(text, attr, contains, resource_id)
    assert node is not None, f"Missing UI control: {text}"
    bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
    time.sleep(.5)


def wait(text, seconds=20, contains=False, attr="text", resource_id=None):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if find(text, attr=attr, contains=contains, resource_id=resource_id) is not None:
            return
        time.sleep(.3)
    raise AssertionError(f"Timed out waiting for: {text}")


def import_csv():
    tap("Family")
    for _ in range(12):
        if find("Import activity CSV") is not None:
            break
        adb("shell", "input", "swipe", "500", "1800", "500", "500", "250")
    tap("Import activity CSV")
    wait("Show roots", attr="content-desc")
    tap("Show roots", "content-desc")
    wait("Downloads", resource_id="android:id/title")
    tap("Downloads", resource_id="android:id/title")
    wait("little-nest-csv-smoke.csv")
    tap("little-nest-csv-smoke.csv")
    wait("Import activity CSV")
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
if GROWTH:
    rows[-1].update({"[Growth] Weight": "11", "[Growth] Height": "82", "[Growth] Height Unit": "CM",
                     "[Growth] Head Size": "47", "[Growth] Head Size Unit": "CM"})
    for index, date, weight, length, head in ((1, "2025-01-01", "3.2", "49", "34"), (2, "2025-04-01", "5.6", "59", "39")):
        event("Growth", {"[Growth] Weight": weight, "[Growth] Weight Unit": "KG",
                         "[Growth] Height": length, "[Growth] Height Unit": "CM",
                         "[Growth] Head Size": head, "[Growth] Head Size Unit": "CM",
                         "_activityKey": "growth-history-" + str(index),
                         "Start Date/time (Epoch)": str(int(datetime.fromisoformat(date).replace(tzinfo=timezone.utc).timestamp() * 1000))})
event("Baby First", {"[Baby First] Baby First": "Grabbing rattle"})
event("Profile", {"[Profile] Type": "CHILD", "[Profile] Sex": "FEMALE", "[Profile] Birth Date": "2025-01-01"})
columns = list(dict.fromkeys(key for row in rows for key in row))
buffer = io.StringIO(newline="")
writer = csv.DictWriter(buffer, columns)
writer.writeheader()
writer.writerows(rows)

try:
    with tempfile.TemporaryDirectory(prefix="little-nest-import-") as directory:
        file = Path(directory) / "little-nest-csv-smoke.csv"
        file.write_text(buffer.getvalue(), encoding="utf-8")
        adb("push", str(file), REMOTE)
    adb("shell", "am", "start", "-W", "-S", "-f", "0x10008000", "-n", "app.littlenest/.MainActivity")
    wait("Family")
    import_csv()
    assert find(f"{11 if GROWTH else 9} new records imported", contains=True) is not None
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
    if GROWTH:
        tap("Trends")
        for title, filename in (("Weight", "weight"), ("Length / height", "length"), ("Head circumference", "head")):
            description = title + " growth chart in"
            for _ in range(18):
                if find(description, attr="content-desc", contains=True) is not None:
                    break
                adb("shell", "input", "swipe", "500", "1800", "500", "900", "250")
            chart = find(description, attr="content-desc", contains=True)
            assert chart is not None, f"Missing growth chart: {title}"
            assert "WHO percentiles 3, 15, 50, 85, 97" in chart.get("content-desc", "")
            assert "2025-01-01" in chart.get("content-desc", "") and "2025-04-01" in chart.get("content-desc", "")
            assert "Dashed line follows" in chart.get("content-desc", "")
            screenshot = Path(__file__).resolve().parent.parent / "artifacts" / f"growth-{filename}.png"
            screenshot.write_bytes(adb("exec-out", "screencap", "-p"))
        for _ in range(5):
            if find("View 3 measurements") is not None:
                break
            adb("shell", "input", "swipe", "500", "1800", "500", "1200", "250")
        tap("View 3 measurements")
        assert sum("Approx. percentile" in node.get("text", "") for node in nodes()) == 3
        tap("android:id/button1", "resource-id")
        print("Signed release growth smoke passed: all three WHO charts, first-entry guides, imported history and text percentiles.")
    print("Signed release CSV smoke passed: document picker, mixed/side-unit data, ongoing sleep finish and duplicate-free repeat import.")
finally:
    adb("shell", "rm", "-f", REMOTE, HIERARCHY)
