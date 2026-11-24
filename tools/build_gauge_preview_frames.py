"""Generate the Android editor's default-theme frames using the built native LVGL simulator."""
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android_app/app/src/main/assets/gauge-pages"
SIDE = 466
SOURCES = 13
# Cuts lie between LVGL cells. Validate mixed frames against native renders before saving.
TEMP_CELLS = [(0, 0, 466, 203), (0, 203, 466, 273), (0, 273, 466, 466)]
INFO_CELLS = [(0, 0, 233, 197), (233, 0, 466, 197), (0, 197, 233, 301),
              (233, 197, 466, 301), (0, 301, 466, 466)]


def generate():
    with tempfile.TemporaryDirectory() as temp:
        frames = Path(temp)
        subprocess.run([ROOT / "simulator/build/brz_lvgl_sim.exe", "--export-editor-frames"],
                       cwd=frames, check=True, timeout=60)

        def frame(page, source):
            with Image.open(frames / f"page-{page}-{source}.bmp") as bmp:
                assert bmp.size == (SIDE, SIDE)
                return bmp.convert("RGB")

        for page, cells, sources in [(0, TEMP_CELLS, [5, 2, 8]), (1, INFO_CELLS, [7, 5, 11, 6, 12])]:
            composite = Image.new("RGB", (SIDE, SIDE))
            for box, source in zip(cells, sources):
                composite.paste(frame(page, source).crop(box), box)
            difference = ImageChops.difference(composite, frame(page, SOURCES)).getbbox()
            if difference:
                raise ValueError(f"Page {page} differs from LVGL in rectangle {difference}")

        for page in range(4):
            atlas = Image.new("RGB", (4 * SIDE, 4 * SIDE))
            for source in range(SOURCES):
                atlas.paste(frame(page, source), (source % 4 * SIDE, source // 4 * SIDE))
            atlas.save(ASSETS / f"lvgl-{page}.png", optimize=True)


if __name__ == "__main__":
    generate()
