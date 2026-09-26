import json
import unittest
import math
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "android_app/app/src/main/assets/license_plate_vector"
ARTWORK_SOURCE = (
    ROOT
    / "android_app/app/src/main/java/com/brz/gauge/trips/LicensePlateArtwork.kt"
)


class LicensePlateAssetTests(unittest.TestCase):
    def test_all_supported_plate_characters_have_bundled_vector_glyphs(self):
        provinces = "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新"
        table = json.loads((ASSET_DIR / "glyphs.json").read_text(encoding="utf-8"))
        glyphs = table["glyphs"]
        self.assertEqual(1, table["version"])
        self.assertEqual(set(provinces + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"), set(glyphs))
        self.assertTrue(all(glyphs[character] for character in glyphs))
        self.assertTrue(all(layer["path"].startswith("M") for layers in glyphs.values() for layer in layers))

    def test_province_outlines_fill_the_standard_45_by_90_mm_character_size(self):
        provinces = "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新"
        glyphs = json.loads((ASSET_DIR / "glyphs.json").read_text(encoding="utf-8"))["glyphs"]
        for province in provinces:
            coordinates = []
            for layer in glyphs[province]:
                numbers = [
                    float(value)
                    for value in re.findall(r"[+-]?(?:[0-9]*[.])?[0-9]+", layer["path"])
                ]
                coordinates.extend(zip(numbers[0::2], numbers[1::2]))
            xs = [point[0] for point in coordinates]
            ys = [point[1] for point in coordinates]
            self.assertAlmostEqual(0.0, min(xs), places=3, msg=province)
            self.assertAlmostEqual(45.0, max(xs), places=3, msg=province)
            self.assertAlmostEqual(0.0, min(ys), places=3, msg=province)
            self.assertAlmostEqual(90.0, max(ys), places=3, msg=province)
        # These straight strokes are PDF rectangles rather than curves and
        # guard against the extraction regression that made the glyphs small.
        self.assertGreaterEqual(len(glyphs["川"]), 3)
        self.assertGreaterEqual(len(glyphs["云"]), 2)

    def test_preview_uses_bundled_vector_glyphs_and_standard_slots(self):
        source = ARTWORK_SOURCE.read_text(encoding="utf-8")
        self.assertIn('"license_plate_vector/glyphs.json"', source)
        self.assertIn("canvas.translate(leftMm, 25f)", source)
        self.assertIn("drawGlyph(context, canvas, value.province, 15f, paint)", source)
        self.assertIn("drawGlyph(context, canvas, value.authority, 72f, paint)", source)
        self.assertIn("151f", source)
        self.assertIn("canvas.drawPath(layer.path, paint)", source)
        self.assertNotIn('Typeface.create("sans-serif-condensed"', source)
        self.assertNotIn("BitmapFactory", source)

    def test_vector_paths_are_rasterized_at_four_pixels_per_mm(self):
        source = ARTWORK_SOURCE.read_text(encoding="utf-8")
        self.assertIn("Bitmap.createBitmap(1760, 560", source)
        self.assertIn("bitmap.setHasMipMap(true)", source)
        self.assertIn("bitmap.prepareToDraw()", source)
        self.assertIn("Paint(Paint.ANTI_ALIAS_FLAG)", source)
        self.assertIn("LruCache<Char, List<GlyphLayer>>", source)
        self.assertIn("parsePathData", source)
        self.assertIn("'C' -> path.cubicTo", source)
        self.assertNotIn("createScaledBitmap", source)

    def test_custom_plate_is_perspective_installed_on_each_vehicle(self):
        hero = (
            ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/VehicleHeroView.kt"
        ).read_text(encoding="utf-8")
        models = (
            ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/SupportedVehicleModel.kt"
        ).read_text(encoding="utf-8")
        activity = (
            ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/MainActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("LicensePlateArtwork.renderBitmap", hero)
        self.assertIn("Paint.DITHER_FLAG", hero)
        self.assertIn("setPolyToPoly(source, 0, destination, 0, 4)", hero)
        self.assertIn("drawPlateMount(canvas, destination)", hero)
        self.assertEqual(models.count("frontPlateQuad = floatArrayOf("), 2)
        self.assertIn('card(body, "我的车辆")', activity)
        self.assertIn('button("自定义车牌")', activity)
        self.assertNotIn('card(body, "车辆工具")', activity)

    def test_vehicle_plate_quads_preserve_realistic_projected_proportions(self):
        models = (
            ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/SupportedVehicleModel.kt"
        ).read_text(encoding="utf-8")
        quads = re.findall(r"frontPlateQuad = floatArrayOf\((.*?)\)", models, re.DOTALL)
        self.assertEqual(2, len(quads))
        for block in quads:
            values = [float(value) for value in re.findall(r"([0-9.]+)f", block)]
            self.assertEqual(8, len(values))
            tl, tr, br, bl = [values[index:index + 2] for index in range(0, 8, 2)]
            top = math.hypot(tr[0] - tl[0], tr[1] - tl[1]) * 1536
            bottom = math.hypot(br[0] - bl[0], br[1] - bl[1]) * 1536
            left = math.hypot(bl[0] - tl[0], bl[1] - tl[1]) * 1024
            right = math.hypot(br[0] - tr[0], br[1] - tr[1]) * 1024
            projected_ratio = ((top + bottom) / 2) / ((left + right) / 2)
            # A 440:140 plate viewed on the roughly 30-35 degree yawed bumper plane is
            # horizontally foreshortened from 3.14:1 to about 2.5-2.7:1 on this artwork.
            self.assertGreater(projected_ratio, 2.4)
            self.assertLess(projected_ratio, 2.8)
            # User-calibrated quads occupy roughly 12-13% of the 1536 px source image.
            self.assertGreater((top + bottom) / 2, 175)
            self.assertLess((top + bottom) / 2, 205)
            # Small hand-tuned corner differences are allowed; neither side may flare visibly.
            self.assertLessEqual(right, left * 1.02)

    def test_incomplete_plate_is_rejected_and_display_switch_is_persisted(self):
        generator = (
            ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/LicensePlateGenerator.kt"
        ).read_text(encoding="utf-8")
        state = (
            ROOT / "android_app/app/src/main/java/com/brz/gauge/trips/AppState.kt"
        ).read_text(encoding="utf-8")
        editor = (
            ROOT
            / "android_app/app/src/main/java/com/brz/gauge/trips/LicensePlateGeneratorActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("normalized.length != 7", generator)
        self.assertIn('"custom_license_plate"', state)
        self.assertIn('"show_custom_license_plate"', state)
        self.assertIn("将此车牌安装在首页车辆示意图上", editor)

    def test_vector_glyph_provenance_is_distributed_with_app(self):
        source_note = (ASSET_DIR / "SOURCE.md").read_text(encoding="utf-8")
        self.assertIn("67 outlined glyphs", source_note)
        self.assertIn("GA 36-2007", source_note)
        self.assertIn("does not grant additional", source_note)
        self.assertIn("rights to the source document", source_note)


if __name__ == "__main__":
    unittest.main()
