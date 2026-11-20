"""Renders the GitHub social preview (1280x640) for Kiln."""
import math
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/assets/social-preview.png"
S = 2  # supersampling
W, H = 1280 * S, 640 * S
BG = (16, 18, 20)
INK = (232, 234, 236)
MUTED = (150, 158, 166)
EMBER = (255, 122, 26)
CORE = (255, 194, 74)
MED = "C:/Windows/Fonts/Montserrat-Medium.ttf"
REG = "C:/Windows/Fonts/Montserrat-Regular.ttf"

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)


def hexagon(cx, cy, r):
    return [(cx + r * math.cos(math.radians(90 + 60 * i)), cy + r * math.sin(math.radians(90 + 60 * i))) for i in range(6)]


# faint hex grid on the right
for row in range(-1, 9):
    for col in range(-1, 14):
        r = 46 * S
        cx = 700 * S + col * r * math.sqrt(3) + (row % 2) * r * math.sqrt(3) / 2
        cy = row * r * 1.5
        fade = max(0.0, min(1.0, (cx - 640 * S) / (500 * S)))
        if fade <= 0:
            continue
        c = tuple(int(BG[i] + (40 - BG[i] + 8) * fade * 0.5) for i in range(3))
        d.polygon(hexagon(cx, cy, r - 3 * S), outline=c, width=2 * S)

# glow behind the mark
glow = Image.new("RGB", (W, H), (0, 0, 0))
gd = ImageDraw.Draw(glow)
mx, my, mr = 250 * S, 320 * S, 150 * S
gd.ellipse([mx - 120 * S, my - 120 * S, mx + 120 * S, my + 120 * S], fill=(120, 50, 10))
glow = glow.filter(ImageFilter.GaussianBlur(70 * S))
img = Image.blend(img, Image.composite(glow, img, glow.convert("L")), 0.8)
d = ImageDraw.Draw(img)

# mark: hex ring, ember, core
d.polygon(hexagon(mx, my, mr), outline=INK, width=21 * S)
d.polygon(hexagon(mx, my, mr * 0.40), fill=EMBER)
d.polygon(hexagon(mx, my, mr * 0.20), fill=CORE)

# word mark with tracking
font = ImageFont.truetype(MED, 150 * S)
x, y = 470 * S, 205 * S
for ch in "KILN":
    d.text((x, y), ch, font=font, fill=INK)
    x += d.textlength(ch, font=font) + 16 * S

tag = ImageFont.truetype(MED, 38 * S)
sub = ImageFont.truetype(REG, 28 * S)
d.text((474 * S, 400 * S), "Server-driven client content for Paper", font=tag, fill=INK)
d.text((474 * S, 455 * S), "Native GUIs · Keybinds · Sandboxed Lua extensions", font=sub, fill=MUTED)
d.rectangle([474 * S, 518 * S, 534 * S, 522 * S], fill=EMBER)
d.text((474 * S, 540 * S), "github.com/SLNE-Development/kiln", font=sub, fill=MUTED)

img.resize((1280, 640), Image.LANCZOS).save(OUT, optimize=True)
print("saved", OUT)
