# License-plate vector glyphs

`glyphs.json` contains 67 outlined glyphs used by the app's conventional
seven-character blue-plate renderer: 31 province abbreviations, A-Z and 0-9.

The paths were mechanically extracted from the outlined character samples in
Appendix B of a PDF-compatible Illustrator document titled "Chinese
license-plate vector chart" (marked `GA 36-2007`, Illustrator metadata dated
2022-04-02). The original Illustrator document is intentionally not included
in this repository. Run `tools/extract_license_plate_vectors.py` with a local
copy of that document to regenerate this asset and its visual preview.

For the conventional 440 mm x 140 mm layout used here, the province outlines
are fitted to the 45 mm x 90 mm character dimensions specified by GA 36-2018.
The extraction includes both free-form curves and rectangular strokes; the
latter are required for complete glyphs such as `川` and `云`.

The source file contains no explicit copyright or redistribution license in
its metadata. Consequently, this provenance note does not grant additional
rights to the source document or extracted artwork under the repository's
software license.
