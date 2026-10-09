"""Regression checks for counters in the actual bundled plate contours."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GLYPHS = json.loads((ROOT / "android_app/app/src/main/assets/license_plate_vector/glyphs.json")
                    .read_text(encoding="utf-8"))["glyphs"]


def contours(path):
    tokens = iter(re.findall(r"[MLCZ]|[-+]?(?:\d*\.)?\d+", path))
    result, points, cursor = [], [], (0, 0)
    def point():
        return float(next(tokens)), float(next(tokens))
    for command in tokens:
        if command == "M":
            if points:
                result.append(points)
            cursor = point()
            points = [cursor]
        elif command == "L":
            cursor = point()
            points.append(cursor)
        elif command == "C":
            a, b, end = point(), point(), point()
            start = cursor
            for step in range(1, 65):
                t = step / 64
                points.append(tuple((1-t)**3*start[i] + 3*(1-t)**2*t*a[i]
                                    + 3*(1-t)*t*t*b[i] + t**3*end[i] for i in range(2)))
            cursor = end
        elif command == "Z":
            if points:
                result.append(points)
            points = []
        else:
            raise AssertionError(command)
    if points:
        result.append(points)
    return result


def ink(character, x, y):
    filled = False
    for layer in GLYPHS[character]:
        winding = 0
        for polygon in contours(layer["path"]):
            for (ax, ay), (bx, by) in zip(polygon, polygon[1:]+polygon[:1]):
                side = (bx-ax)*(y-ay) - (by-ay)*(x-ax)
                if ay <= y < by and side > 0:
                    winding += 1
                elif by <= y < ay and side < 0:
                    winding -= 1
        if winding:
            filled = not layer["cutout"]
    return filled


class LicensePlateCounterTests(unittest.TestCase):
    def test_enclosed_regions_show_plate_background(self):
        # These points lie inside counters in the original Appendix B outlines.
        holes = {"京": [(22, 38)], "晋": [(23, 18), (22, 57), (22, 74)],
                 "鲁": [(16, 25), (30, 25), (16, 37), (30, 37), (22, 67), (22, 78)],
                 "皖": [(8, 31), (8, 55)], "闽": [(26, 38)], "粤": [(12, 17)]}
        for character, samples in holes.items():
            for point in samples:
                self.assertFalse(ink(character, *point), (character, point))

    def test_strokes_and_nested_islands_are_preserved(self):
        strokes = {"京": [(8, 35), (22, 50), (10, 12)], "晋": [(18, 18), (22, 64)],
                   "鲁": [(22, 25), (22, 72)], "粤": [(22, 30)], "皖": [(30, 31)]}
        for character, samples in strokes.items():
            for point in samples:
                self.assertTrue(ink(character, *point), (character, point))

    def test_letter_and_digit_counters_still_show_background(self):
        for character, point in [("A", (22, 50)), ("B", (20, 25)), ("8", (22, 24)), ("0", (22, 44))]:
            self.assertFalse(ink(character, *point), (character, point))


if __name__ == "__main__":
    unittest.main()
