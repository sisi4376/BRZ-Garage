#!/usr/bin/env python3
"""Export the shared plate contours as ArkUI SVGs without losing winding holes."""
import argparse
import json
from pathlib import Path
from xml.sax.saxutils import escape


def export(source: Path, destination: Path) -> None:
    table = json.loads(source.read_text(encoding="utf-8"))
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "glyphs.json").write_text(
        json.dumps(table, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    for character, layers in table["glyphs"].items():
        paths = "".join(
            '<path fill="{}" fill-rule="nonzero" d="{}"/>'.format(
                "#0052A8" if layer["cutout"] else "#FFFFFF", escape(layer["path"], {'"': '&quot;'}))
            for layer in layers)
        (destination / f"{ord(character)}.svg").write_text(
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 45 90">' + paths + '</svg>\n',
            encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    export(args.source, args.destination)
