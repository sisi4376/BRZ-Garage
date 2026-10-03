#!/usr/bin/env python3
"""Build the bundled Android UI font subset from Noto Sans SC Variable."""

from __future__ import annotations

import argparse
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont


TEXT_EXTENSIONS = {
    ".csv",
    ".html",
    ".java",
    ".json",
    ".kt",
    ".md",
    ".txt",
    ".xml",
}


def collect_codepoints(source_root: Path) -> set[int]:
    # Always retain printable ASCII, Latin-1, general/CJK punctuation and
    # full-width forms. Source scanning then adds every localized UI glyph.
    codepoints = set(range(0x20, 0x100))
    codepoints.update(range(0x2000, 0x2070))
    codepoints.update(range(0x3000, 0x3040))
    codepoints.update(range(0xFF00, 0xFFF0))

    for path in source_root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in TEXT_EXTENSIONS:
            continue
        if "assets/firmware" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        codepoints.update(map(ord, text))
    return codepoints


def rename_font(font: TTFont) -> None:
    replacements = {
        1: "BRZ UI Sans",
        2: "Regular",
        3: "BRZ UI Sans Regular",
        4: "BRZ UI Sans Regular",
        6: "BRZUISans-Regular",
        16: "BRZ UI Sans",
        17: "Regular",
        25: "BRZUISans",
    }
    for record in font["name"].names:
        value = replacements.get(record.nameID)
        if value is not None:
            record.string = value.encode(record.getEncoding())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_font", type=Path)
    parser.add_argument("output_font", type=Path)
    parser.add_argument(
        "--source-root",
        type=Path,
        default=Path("android_app/app/src/main"),
    )
    args = parser.parse_args()

    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_legacy = True
    options.name_languages = ["*"]
    options.notdef_glyph = True
    options.notdef_outline = True
    options.recommended_glyphs = True
    options.glyph_names = True
    options.legacy_cmap = True
    options.symbol_cmap = True

    font = subset.load_font(str(args.source_font), options, lazy=False)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=collect_codepoints(args.source_root))
    subsetter.subset(font)
    rename_font(font)

    args.output_font.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(args.output_font), options)


if __name__ == "__main__":
    main()
