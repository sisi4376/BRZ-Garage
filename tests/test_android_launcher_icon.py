from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android_app/app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class AndroidLauncherIconTests(unittest.TestCase):
    def test_adaptive_foreground_matches_85_percent_visible_preview(self):
        foreground = ET.parse(RES / "drawable/ic_launcher_foreground.xml").getroot()
        self.assertEqual(foreground.tag, "inset")
        self.assertEqual(foreground.get(ANDROID + "drawable"), "@drawable/app_icon_master")
        inset = float(foreground.get(ANDROID + "inset").rstrip("%")) / 100
        self.assertAlmostEqual((1 - 2 * inset) * 108 / 72, 0.85, places=6)
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            icon = ET.parse(RES / "mipmap-anydpi-v26" / name).getroot()
            self.assertEqual(icon.find("foreground").get(ANDROID + "drawable"),
                             "@drawable/ic_launcher_foreground")
            self.assertEqual(icon.find("background").get(ANDROID + "drawable"),
                             "@color/launcher_black")

    def test_legacy_icon_uses_same_proportion_without_adaptive_compensation(self):
        legacy = ET.parse(RES / "drawable/ic_launcher_legacy.xml").getroot()
        inset = legacy.find("item/inset")
        self.assertIsNotNone(inset)
        self.assertEqual(inset.get(ANDROID + "drawable"), "@drawable/app_icon_master")
        ratio = float(inset.get(ANDROID + "inset").rstrip("%")) / 100
        self.assertAlmostEqual(1 - 2 * ratio, 0.85)


if __name__ == "__main__":
    unittest.main()
