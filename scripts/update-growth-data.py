#!/usr/bin/env python3
"""Extract factual LMS parameters from WHO's published growth spreadsheets."""
import csv
import hashlib
import io
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = "https://cdn.who.int/media/docs/default-source/child-growth/child-growth-standards/indicators/"
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
rows = []
sources = []
for sex in ("boys", "girls"):
    files = [
        ("weight", "weeks", f"weight-for-age/wfa_{sex}_0-to-13-weeks_zscores.xlsx"),
        ("weight", "months", f"weight-for-age/wfa_{sex}_0-to-5-years_zscores.xlsx"),
        ("height", "weeks", f"length-height-for-age/lhfa_{sex}_0-to-13-weeks_zscores.xlsx"),
        ("height", "length", f"length-height-for-age/lhfa_{sex}_0-to-2-years_zscores.xlsx"),
        ("height", "standing", f"length-height-for-age/lhfa_{sex}_2-to-5-years_zscores.xlsx"),
        ("head", "weeks", f"head-circumference-for-age/hcfa-{sex}-0-13-zscores.xlsx"),
        ("head", "months", f"head-circumference-for-age/hcfa-{sex}-0-5-zscores.xlsx"),
    ]
    for measure, series, path in files:
        url = BASE + path
        content = subprocess.check_output(["curl", "--fail", "--silent", "--show-error", "--location", "--max-time", "60", url])
        sources.append(f"{hashlib.sha256(content).hexdigest()}  {url}")
        with zipfile.ZipFile(io.BytesIO(content)) as workbook:
            sheet = ET.fromstring(workbook.read("xl/worksheets/sheet1.xml"))
        values = []
        for row in sheet.findall("s:sheetData/s:row", NS)[1:]:
            cells = {c.attrib["r"].rstrip("0123456789"): c.findtext("s:v", namespaces=NS)
                     for c in row.findall("s:c", NS)}
            age, l, m, s = [float(cells[key]) for key in ("A", "B", "C", "D")]
            assert m > 0 and s > 0
            values.append([measure, sex, series, *(format(v, ".10g") for v in (age, l, m, s))])
        assert len(values) == (14 if series == "weeks" else 25 if series == "length" else 37 if series == "standing" else 61)
        rows.extend(values)
        print(f"Extracted {path}")

output = ROOT / "app/src/main/res/raw/who_growth.csv"
output.parent.mkdir(parents=True, exist_ok=True)
with output.open("w", newline="") as file:
    writer = csv.writer(file, lineterminator="\n")
    writer.writerow(["measure", "sex", "series", "age", "L", "M", "S"])
    writer.writerows(rows)
(ROOT / "docs/who-growth-sources.txt").write_text(
    "WHO Child Growth Standards source workbook SHA-256 and URL.\n"
    "Regenerate with python3 scripts/update-growth-data.py.\n\n" + "\n".join(sources) + "\n")
