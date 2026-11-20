"""Writes the Kiln lockups with the word set in Montserrat Medium (SIL OFL 1.1), converted to paths."""
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

FONT = sys.argv[2] if len(sys.argv) > 2 else r"C:WindowsFontsMontserrat-Medium.ttf"
OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/assets"

font = TTFont(FONT)
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()
hmtx = font["hmtx"]
cap = font["OS/2"].sCapHeight or 700

CAP_PX = 46.0
scale = CAP_PX / cap
tracking = 0.10 * font["head"].unitsPerEm  # letter spacing in font units
baseline = 50 + CAP_PX / 2
x = 118.0

parts = []
for ch in "KILN":
    name = cmap[ord(ch)]
    pen = SVGPathPen(glyphs)
    glyphs[name].draw(pen)
    parts.append(f'<path transform="translate({x:.2f} {baseline:.2f}) scale({scale:.5f} -{scale:.5f})" d="{pen.getCommands()}"/>')
    x += (hmtx[name][0] + tracking) * scale
width = int(x - tracking * scale + 14)

MARK = {
    "dark": ("#E8EAEC", "#FF7A1A", "#FFC24A"),
    "light": ("#1A1D21", "#E8620C", "#FFB020"),
}
for variant, (ink, ember, core) in MARK.items():
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="100" viewBox="0 0 {width} 100" role="img" aria-label="Kiln">
  <!-- for {variant} backgrounds; word: Montserrat Medium (SIL Open Font License 1.1), converted to paths -->
  <path d="M50 8 L86 29 L86 71 L50 92 L14 71 L14 29 Z" fill="none" stroke="{ink}" stroke-width="7" stroke-linejoin="round"/>
  <path d="M50 34 L64 42 L64 58 L50 66 L36 58 L36 42 Z" fill="{ember}"/>
  <path d="M50 42 L57 46 L57 54 L50 58 L43 54 L43 46 Z" fill="{core}"/>
  <g fill="{ink}">
    {chr(10).join("    " + p for p in parts).strip()}
  </g>
</svg>
'''
    with open(f"{OUT}/kiln-logo-{variant}.svg", "w", encoding="utf-8", newline="\n") as f:
        f.write(svg)
print("width", width)
