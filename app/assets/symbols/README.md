# Monochrome SVG symbols

The 34 SVG files are the user-approved vector reconstructions of the PNG assets
from stable commit `8736d60104e5f4f6b5911ed26d9bd807fc199322`. Names, canvas sizes,
transparent margins and original white opacity are preserved. These are traced
contours, not the original designers' vector masters; small edge differences remain.

`SvgSymbol` supports the exact bundled subset: a `viewBox`, absolute M/L/C/Z paths,
white fill, even-odd holes, and per-path opacity. Paths are parsed once and cached
by `Symbols`; unsupported/missing SVGs fall back to the existing PNG. Keep all new
SVGs compatible with this subset and run `tests/test_svg_symbols.py` when changing
them. Color logos, SVG text, images, external resources, transforms, strokes and
general SVG filters are intentionally outside this monochrome asset format.

Drawing preserves uniform caller tint alpha, including overlap between opacity
layers. HDMI and speaker use tiny bounded composition layers only when their
caller tint is translucent. Other symbols draw paths directly.

The three screensavers share a cached weather badge. Its icon alpha mask is drawn
from SVG at the actual requested glyph size before baking the existing shadow.
