#!/usr/bin/env python3
"""Extract the GA 36 plate glyph outlines from a PDF-compatible Illustrator file.

The source document is not copied into the repository.  This script reads the
outlined glyph tables on pages 29 and 30 and writes a compact Android asset
whose paths are normalized to the app's 45 mm x 90 mm character slots.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import pdfplumber
from PIL import Image, ImageDraw


TARGET_WIDTH = 45.0
TARGET_HEIGHT = 90.0
PLATE_BLUE = (0, 82, 168, 255)
PROVINCE_CHARACTERS = "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新"


@dataclass(frozen=True)
class Cell:
    page_index: int
    character: str
    left: float
    top: float
    width: float
    height: float


def source_cells() -> list[Cell]:
    cells: list[Cell] = []

    province_rows = (
        "京津冀晋蒙辽吉黑沪",
        "苏浙皖闽赣鲁豫鄂湘",
        "粤桂琼渝川贵云藏陕",
        "甘青宁新",
    )
    province_lefts = (89.0, 136.5, 184.5, 232.0, 275.5, 321.5, 369.0, 416.0, 463.5)
    for row_index, characters in enumerate(province_rows):
        for column_index, character in enumerate(characters):
            cells.append(
                Cell(
                    page_index=0,
                    character=character,
                    left=province_lefts[column_index],
                    top=(177.0, 266.0, 355.0, 441.0)[row_index],
                    width=45.0,
                    height=85.0,
                )
            )

    letter_rows = ("ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ")
    letter_lefts = (
        (89.5, 149.5, 209.5, 273.5, 336.0, 394.5, 454.5),
        (89.5, 149.5, 209.5, 273.5, 332.0, 392.5, 455.0),
        (89.5, 153.0, 213.0, 274.0, 335.5, 394.0, 460.5),
        (89.5, 149.5, 211.5, 274.0, 339.0),
    )
    letter_tops = (101.5, 201.5, 302.5, 404.5)
    for row_index, characters in enumerate(letter_rows):
        for column_index, character in enumerate(characters):
            cells.append(
                Cell(
                    page_index=1,
                    character=character,
                    left=letter_lefts[row_index][column_index],
                    top=letter_tops[row_index],
                    width=45.0,
                    height=90.0,
                )
            )

    number_rows = (
        ("12345", (168.5, 226.5, 280.5, 332.5, 382.5), 544.5),
        ("67890", (168.0, 226.5, 280.5, 335.0, 382.0), 648.5),
    )
    for characters, lefts, top in number_rows:
        for character, left in zip(characters, lefts):
            cells.append(Cell(1, character, left, top, 45.0, 90.0))

    return cells


def scalar_luminance(value: object) -> float | None:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, tuple) and value:
        components = [float(component) for component in value]
        return sum(components) / len(components)
    return None


def transform_path(path: Iterable[tuple], cell: Cell) -> list[tuple]:
    sx = TARGET_WIDTH / cell.width
    sy = TARGET_HEIGHT / cell.height

    def point(value: tuple[float, float]) -> tuple[float, float]:
        return ((value[0] - cell.left) * sx, (value[1] - cell.top) * sy)

    transformed: list[tuple] = []
    for operation in path:
        command = operation[0]
        if command in ("m", "l"):
            transformed.append((command, point(operation[1])))
        elif command == "c":
            transformed.append(
                (command, point(operation[1]), point(operation[2]), point(operation[3]))
            )
        elif command == "h":
            transformed.append((command,))
        else:
            raise ValueError(f"Unsupported path command: {command}")
    return transformed


def geometry_key(path: list[tuple]) -> tuple:
    values: list[object] = []
    for operation in path:
        values.append(operation[0])
        for point in operation[1:]:
            values.extend((round(point[0], 2), round(point[1], 2)))
    return tuple(values)


def format_number(value: float) -> str:
    result = f"{value:.3f}".rstrip("0").rstrip(".")
    return "0" if result in ("-0", "") else result


def android_path_data(path: list[tuple]) -> str:
    parts: list[str] = []
    for operation in path:
        command = operation[0]
        if command == "m":
            x, y = operation[1]
            parts.append(f"M{format_number(x)},{format_number(y)}")
        elif command == "l":
            x, y = operation[1]
            parts.append(f"L{format_number(x)},{format_number(y)}")
        elif command == "c":
            points = [coordinate for point in operation[1:] for coordinate in point]
            parts.append("C" + ",".join(format_number(value) for value in points))
        elif command == "h":
            parts.append("Z")
    return "".join(parts)


def fit_paths_to_character_size(paths: list[list[tuple]]) -> list[list[tuple]]:
    """Fit outlined ink to the GA 36-2018 45 mm x 90 mm character size."""
    points = [
        point
        for path in paths
        for operation in path
        for point in operation[1:]
    ]
    min_x = min(point[0] for point in points)
    max_x = max(point[0] for point in points)
    min_y = min(point[1] for point in points)
    max_y = max(point[1] for point in points)
    scale_x = TARGET_WIDTH / (max_x - min_x)
    scale_y = TARGET_HEIGHT / (max_y - min_y)

    def transform_point(point: tuple[float, float]) -> tuple[float, float]:
        return ((point[0] - min_x) * scale_x, (point[1] - min_y) * scale_y)

    fitted: list[list[tuple]] = []
    for path in paths:
        fitted_path: list[tuple] = []
        for operation in path:
            if operation[0] in ("m", "l"):
                fitted_path.append((operation[0], transform_point(operation[1])))
            elif operation[0] == "c":
                fitted_path.append(
                    (
                        "c",
                        transform_point(operation[1]),
                        transform_point(operation[2]),
                        transform_point(operation[3]),
                    )
                )
            else:
                fitted_path.append(operation)
        fitted.append(fitted_path)
    return fitted


def extract_cell(page: object, cell: Cell) -> list[dict[str, object]]:
    tolerance = 0.8
    candidates: list[tuple[int, list[tuple], bool]] = []
    # pdfplumber exposes closed free-form outlines as curves, but simple
    # rectangular strokes (for example the top bar of 云 and two stems of 川)
    # as rects.  Both are part of the normative outlined glyph and must be kept.
    # Rectangular positive strokes precede free-form curves so any later
    # negative/cutout curves remain visible (notably inside 粤).
    shapes = [*page.rects, *page.curves]
    for index, shape in enumerate(shapes):
        luminance = scalar_luminance(shape.get("non_stroking_color"))
        if not shape.get("fill") or luminance is None:
            continue
        if not (
            shape["x0"] >= cell.left - tolerance
            and shape["x1"] <= cell.left + cell.width + tolerance
            and shape["top"] >= cell.top - tolerance
            and shape["bottom"] <= cell.top + cell.height + tolerance
        ):
            continue
        path = transform_path(shape["path"], cell)
        candidates.append((index, path, luminance >= 0.5))

    # The PDF contains alternate black/white artwork in optional-content
    # layers.  Keeping the final occurrence of identical geometry reproduces
    # the visible page while removing redundant hidden-layer paths.
    final_by_geometry: dict[tuple, tuple[int, list[tuple], bool]] = {}
    for item in candidates:
        final_by_geometry[geometry_key(item[1])] = item
    layers = sorted(final_by_geometry.values(), key=lambda item: item[0])
    if not layers:
        codepoint = f"U+{ord(cell.character):04X}"
        raise ValueError(f"No vector paths found for {cell.character!r} ({codepoint}) in {cell}")
    if cell.character in PROVINCE_CHARACTERS:
        fitted_paths = fit_paths_to_character_size([path for _, path, _ in layers])
        layers = [
            (index, fitted_path, cutout)
            for (index, _, cutout), fitted_path in zip(layers, fitted_paths)
        ]
    return [
        {"cutout": cutout, "path": android_path_data(path)}
        for _, path, cutout in layers
    ]


def extract(source: Path) -> dict[str, list[dict[str, object]]]:
    result: dict[str, list[dict[str, object]]] = {}
    with pdfplumber.open(source) as document:
        pages = document.pages
        for cell in source_cells():
            result[cell.character] = extract_cell(pages[cell.page_index], cell)
    return result


def cubic_points(
    start: tuple[float, float],
    control1: tuple[float, float],
    control2: tuple[float, float],
    end: tuple[float, float],
    steps: int = 12,
) -> list[tuple[float, float]]:
    points: list[tuple[float, float]] = []
    for index in range(1, steps + 1):
        t = index / steps
        one_minus_t = 1.0 - t
        x = (
            one_minus_t**3 * start[0]
            + 3 * one_minus_t**2 * t * control1[0]
            + 3 * one_minus_t * t**2 * control2[0]
            + t**3 * end[0]
        )
        y = (
            one_minus_t**3 * start[1]
            + 3 * one_minus_t**2 * t * control1[1]
            + 3 * one_minus_t * t**2 * control2[1]
            + t**3 * end[1]
        )
        points.append((x, y))
    return points


def flattened_polygons(path: list[tuple]) -> list[list[tuple[float, float]]]:
    polygons: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    cursor = (0.0, 0.0)
    for operation in path:
        command = operation[0]
        if command == "m":
            if current:
                polygons.append(current)
            cursor = operation[1]
            current = [cursor]
        elif command == "l":
            cursor = operation[1]
            current.append(cursor)
        elif command == "c":
            current.extend(cubic_points(cursor, operation[1], operation[2], operation[3]))
            cursor = operation[3]
        elif command == "h":
            if current:
                polygons.append(current)
                current = []
    if current:
        polygons.append(current)
    return polygons


def make_preview(source: Path, destination: Path) -> None:
    scale = 4
    plate_width = 440 * scale
    plate_height = 140 * scale
    samples = ("京A12345", "沪B8RZ86", "粤I00001", "云O9K7M2")
    image = Image.new("RGBA", (plate_width, plate_height * len(samples)), (238, 242, 247, 255))

    with pdfplumber.open(source) as document:
        cells = {cell.character: cell for cell in source_cells()}
        paths = {
            character: [
                (layer["cutout"], transformed)
                for layer, transformed in _preview_layers(document, cell)
            ]
            for character, cell in cells.items()
        }

    draw = ImageDraw.Draw(image)
    for sample_index, sample in enumerate(samples):
        top = sample_index * plate_height
        draw.rounded_rectangle(
            (0, top, plate_width - 1, top + plate_height - 1),
            radius=10 * scale,
            fill=PLATE_BLUE,
        )
        draw.rounded_rectangle(
            (5 * scale, top + 5 * scale, 435 * scale, top + 135 * scale),
            radius=6 * scale,
            outline=(255, 255, 255, 255),
            width=max(1, round(2.4 * scale)),
        )
        for x in (115, 325):
            for y in (12.5, 127.5):
                draw.rounded_rectangle(
                    (
                        (x - 7.5) * scale,
                        top + (y - 4) * scale,
                        (x + 7.5) * scale,
                        top + (y + 4) * scale,
                    ),
                    radius=4 * scale,
                    fill=(202, 211, 221, 255),
                )
        positions = (15, 72, 151, 208, 265, 322, 379)
        for character, left in zip(sample, positions):
            for cutout, polygons in paths[character]:
                fill = PLATE_BLUE if cutout else (255, 255, 255, 255)
                for polygon in polygons:
                    points = [
                        ((left + x) * scale, top + (25 + y) * scale)
                        for x, y in polygon
                    ]
                    if len(points) >= 3:
                        draw.polygon(points, fill=fill)
        draw.ellipse(
            ((134 - 5) * scale, top + (70 - 5) * scale, (134 + 5) * scale, top + (70 + 5) * scale),
            fill=(255, 255, 255, 255),
        )

    destination.parent.mkdir(parents=True, exist_ok=True)
    image.save(destination)


def _preview_layers(document: object, cell: Cell):
    tolerance = 0.8
    candidates: list[tuple[int, list[tuple], bool]] = []
    page = document.pages[cell.page_index]
    shapes = [*page.rects, *page.curves]
    for index, shape in enumerate(shapes):
        luminance = scalar_luminance(shape.get("non_stroking_color"))
        if not shape.get("fill") or luminance is None:
            continue
        if (
            shape["x0"] >= cell.left - tolerance
            and shape["x1"] <= cell.left + cell.width + tolerance
            and shape["top"] >= cell.top - tolerance
            and shape["bottom"] <= cell.top + cell.height + tolerance
        ):
            path = transform_path(shape["path"], cell)
            candidates.append((index, path, luminance >= 0.5))
    final_by_geometry = {geometry_key(path): (index, path, cutout) for index, path, cutout in candidates}
    layers = sorted(final_by_geometry.values(), key=lambda item: item[0])
    if cell.character in PROVINCE_CHARACTERS:
        fitted_paths = fit_paths_to_character_size([path for _, path, _ in layers])
        layers = [
            (index, fitted_path, cutout)
            for (index, _, cutout), fitted_path in zip(layers, fitted_paths)
        ]
    for _, path, cutout in layers:
        yield {"cutout": cutout}, flattened_polygons(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()

    glyphs = extract(args.source)
    expected = set(PROVINCE_CHARACTERS)
    expected.update("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
    if set(glyphs) != expected:
        raise ValueError("Extracted glyph set does not match the app's supported characters")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps({"version": 1, "glyphs": glyphs}, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    if args.preview:
        make_preview(args.source, args.preview)

    print(f"Wrote {len(glyphs)} glyphs to {args.output}")
    print("Layer counts:", " ".join(f"{key}:{len(value)}" for key, value in glyphs.items()))


if __name__ == "__main__":
    main()
