import unittest
import math
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "android_app/app/src/main/assets/license_plate_font"
ARTWORK_SOURCE = (
    ROOT
    / "android_app/app/src/main/java/com/brz/gauge/trips/LicensePlateArtwork.kt"
)


class LicensePlateAssetTests(unittest.TestCase):
    def test_all_supported_plate_characters_have_bundled_glyphs(self):
        provinces = "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新"
        serial_characters = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        missing = [
            character
            for character in provinces + serial_characters
            if not (ASSET_DIR / f"140_{character}.jpg").is_file()
        ]
        self.assertEqual([], missing)

    def test_preview_uses_bundled_glyphs_and_standard_slots(self):
        source = ARTWORK_SOURCE.read_text(encoding="utf-8")
        self.assertIn('"license_plate_font/140_${assetCharacter}.jpg"', source)
        self.assertIn("RectF(leftMm, 25f, leftMm + 45f, 115f)", source)
        self.assertIn("drawGlyph(context, canvas, value.province, 15f", source)
        self.assertIn("drawGlyph(context, canvas, value.authority, 72f", source)
        self.assertIn("151f", source)
        self.assertNotIn('Typeface.create("sans-serif-condensed"', source)

    def test_low_resolution_templates_are_supersampled_before_drawing(self):
        source = ARTWORK_SOURCE.read_text(encoding="utf-8")
        self.assertIn("GLYPH_MASK_WIDTH = 360", source)
        self.assertIn("GLYPH_MASK_HEIGHT = 720", source)
        self.assertIn("buildHighResolutionMask(source)", source)
        self.assertIn("LruCache<Char, Bitmap>", source)
        self.assertIn("Paint.DITHER_FLAG", source)

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

    def test_glyph_source_and_license_are_distributed_with_app(self):
        self.assertTrue((ASSET_DIR / "LICENSE.txt").is_file())
        source_note = (ASSET_DIR / "SOURCE.md").read_text(encoding="utf-8")
        self.assertIn("chinese_license_plate_generator", source_note)
        self.assertIn("not the nationwide anti-counterfeit production dies", source_note)


if __name__ == "__main__":
    unittest.main()
