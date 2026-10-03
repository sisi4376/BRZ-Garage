# BRZ UI Sans

Historical experiment: removed from APK resources in App 3.12.3 when the original system typography was restored. The attribution below is retained for earlier builds.

`android_app/app/src/main/res/font/brz_ui_sans_variable.ttf` is an
application-specific subset of Noto Sans SC Variable. It is bundled so the
Android UI remains legible and consistent when the phone has a third-party
theme font installed.

- Upstream: [Noto Sans CJK](https://github.com/notofonts/noto-cjk)
- Source binary: `Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf`, version 2.004
- Source SHA-256: `763146584CF0710223441356B4395E279021B0806C196614377A7A0174AE074A`
- License: SIL Open Font License 1.1

The subset retains the upstream copyright, license and license URL in its
OpenType metadata. Its family name is changed to `BRZ UI Sans` to distinguish
the modified subset from the upstream font. Regenerate it with:

```powershell
python tools/build_app_font_subset.py `
  C:\Windows\Fonts\NotoSansSC-VF.ttf `
  android_app/app/src/main/res/font/brz_ui_sans_variable.ttf
```
