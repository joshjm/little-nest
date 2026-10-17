#!/usr/bin/env python3
"""Exercise the signed APK on an explicitly selected, disposable test emulator."""
import os
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial", nargs="?", default="emulator-5554")
mode = parser.add_mutually_exclusive_group()
mode.add_argument("--reset", action="store_true")
mode.add_argument("--existing", action="store_true")
parser.add_argument("--apk", type=Path, default=ROOT / "artifacts/LittleNest.apk")
parser.add_argument("--care-plan", action="store_true", help="Check version 1.3 planning and real notification delivery")
parser.add_argument("--planner", action="store_true", help="Check the Plan tab, optional goals and source links")
args = parser.parse_args()
SERIAL = args.serial
if not SERIAL.startswith("emulator-"):
    raise SystemExit("Use only a disposable emulator; this smoke test creates sample data.")
ADB = str(Path(os.environ.get("ANDROID_HOME", Path.home() / ".local/share/baby-app-tools/android-sdk")) / "platform-tools/adb")


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args])


def nodes():
    for attempt in range(3):
        result = adb("shell", "uiautomator", "dump", "/sdcard/little-nest-smoke.xml")
        if b"dumped to" in result:
            return ET.fromstring(adb("shell", "cat", "/sdcard/little-nest-smoke.xml")).iter("node")
        print(f"UI hierarchy not ready, attempt {attempt + 1}/3", file=sys.stderr)
        time.sleep(1)
    raise AssertionError("Android did not provide a UI hierarchy after three attempts")


def find(value, attr="text", prefix=False, contains=False, class_name=None):
    def match():
        for node in nodes():
            if class_name and node.attrib.get("class") != class_name:
                continue
            actual = node.attrib.get(attr, "").casefold()
            if (value.casefold() in actual if contains else actual.startswith(value.casefold()) if prefix else actual == value.casefold()):
                return node
        return None
    found = match()
    if found is not None:
        return found
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size").decode())[0])
    for forward in (True, False):
        for _ in range(7):
            start, end = (0.73, 0.3) if forward else (0.3, 0.73)
            adb("shell", "input", "swipe", str(width // 2), str(int(height * start)),
                str(width // 2), str(int(height * end)), "700")
            found = match()
            if found is not None:
                return found
    raise AssertionError(f"UI control not found: {value}")


def tap(value, attr="text", prefix=False, class_name=None):
    node = find(value, attr, prefix, class_name=class_name)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.3)


def screenshot(name):
    nodes()
    (ROOT / "artifacts" / name).write_bytes(adb("exec-out", "screencap", "-p"))


adb("install", "-r", str(args.apk))
if args.reset:
    adb("shell", "pm", "clear", "app.littlenest")
adb("shell", "am", "start", "-W", "-S", "-n", "app.littlenest/.MainActivity")
time.sleep(1)
if args.existing:
    tap("Today", class_name="android.widget.Button")
    find("A day at a time")
    tap("History")
    assert any("120" in node.attrib.get("text", "") for node in nodes()), "Existing bottle was lost during update"
    tap("Today", class_name="android.widget.Button")
else:
    find("Create a profile")
    screenshot("welcome.png")
    tap("Create a profile")
    tap("Name", "content-desc")
    adb("shell", "input", "text", "Baby")
    adb("shell", "input", "keyevent", "BACK")
    tap("android:id/button1", "resource-id")
find("A day at a time")
tap("Bottle")
tap("Amount", "content-desc")
adb("shell", "input", "text", "120")
adb("shell", "input", "keyevent", "BACK")
screenshot("bottle-form.png")
tap("android:id/button1", "resource-id")
find("A day at a time")
tap("Breastfeeding")
tap("android:id/button3", "resource-id")
screenshot("active-timer.png")
tap("Pause")
tap("Switch side")
tap("Resume")
tap("Finish")
find("Last breast side: Right")
screenshot("today.png")
tap("History")
find("The story so far")
assert any("120" in node.attrib.get("text", "") for node in nodes()), "Saved bottle amount missing"
screenshot("history.png")
tap("Trends")
find("Find your rhythm")
screenshot("trends.png")
tap("Family")
find("Your people. Your data.")
screenshot("family.png")
if args.care_plan:
    adb("shell", "pm", "grant", "app.littlenest", "android.permission.POST_NOTIFICATIONS")
    tap("Today", class_name="android.widget.Button")
    tap("Add sleep", "content-desc")
    tap("android:id/button3", "resource-id")
    tap("Finish")
    tap("Care plan & advance alerts")
    tap("Advance milk-feed alerts")

    def fill(label, value):
        tap(label, "content-desc")
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * 6))
        adb("shell", "input", "text", value)
        adb("shell", "input", "keyevent", "BACK")

    fill("Manual milk-feed interval (minutes)", "30")
    fill("Manual wake interval (minutes)", "120")
    fill("Quiet hours start (0–23)", "0")
    fill("Quiet hours end (0–23)", "0")
    tap("android:id/button1", "resource-id")
    find("Next milk feed ·", prefix=True)
    deadline = time.monotonic() + 45
    while True:
        notifications = adb("shell", "cmd", "notification", "list").decode()
        if re.search(r"care:[^\s|]+\.feed", notifications):
            break
        if time.monotonic() >= deadline:
            raise AssertionError("Suggested-care notification did not arrive")
        time.sleep(1)
    screenshot("care-plan.png")
    tap("Next milk feed ·", prefix=True)
    find("Your manual interval: 30 min", contains=True)
    screenshot("care-details.png")
    tap("Dismiss this cycle")
    deadline = time.monotonic() + 15
    while re.search(r"care:[^\s|]+\.feed", adb("shell", "cmd", "notification", "list").decode()):
        if time.monotonic() >= deadline:
            raise AssertionError("Dismissed care notification was not removed")
        time.sleep(0.3)
    tap("Care plan & advance alerts")
    tap("Advance milk-feed alerts")
    tap("android:id/button1", "resource-id")
    find("Next milk feed ·", prefix=True)
    print("Signed care-plan smoke passed: manual windows, real alarm/notification, details and dismiss.")
if args.planner:
    tap("Plan")
    find("Room for a rhythm")
    screenshot("planner.png")
    tap("Edit daily goals")
    for label, value in (
        ("Total sleep goal (hours, e.g. 14.5)", "14.5"),
        ("Daytime nap goal (count, 1–12)", "3"),
        ("Milk-feed session goal (count, 1–30)", "8"),
    ):
        tap(label, "content-desc")
        adb("shell", "input", "text", value)
        adb("shell", "input", "keyevent", "BACK")
    tap("android:id/button1", "resource-id")
    find("/ 14h 30m goal", contains=True)
    screenshot("planner-goals.png")
    adb("shell", "am", "start", "-W", "-S", "-n", "app.littlenest/.MainActivity")
    tap("Plan")
    find("/ 14h 30m goal", contains=True)
    find("Still learning bedtime")
    tap("Sources & how planning works")
    find("CDC · sleep by age · opens browser")
    screenshot("planner-sources.png")
    tap("android:id/button2", "resource-id")
    print("Signed planner smoke passed: Plan, persisted goals, bedtime learning and source links.")
adb("shell", "rm", "/sdcard/little-nest-smoke.xml")
print("Signed release smoke test passed: profile, bottle, timer, history, trends, family.")
