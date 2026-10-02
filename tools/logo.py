"""Orbit logo: a bold 'O' drawn as an orbit, with a moon sitting in a gap in the ring."""
import math, os
from PIL import Image, ImageDraw, ImageFont

ROOT = r'C:\orbitline'
OUT = os.path.join(ROOT, 'branding')
os.makedirs(OUT, exist_ok=True)

# Geometry in a 64-unit square.
C = 32.0          # centre
R = 19.0          # ring radius (to stroke centre)
STROKE = 6.4      # ring thickness
GAP = 34.0        # half-angle of the gap, degrees
MOON_AT = -45.0   # moon position, degrees (screen coords: -45 = top-right)
MOON_R = 5.2

def pt(angle_deg, r=R, c=C):
    a = math.radians(angle_deg)
    return c + r * math.cos(a), c + r * math.sin(a)

def ring_path(scale=1.0, ox=0.0, oy=0.0):
    s0 = MOON_AT + GAP
    s1 = MOON_AT - GAP + 360
    x0, y0 = pt(s0)
    x1, y1 = pt(s1)
    f = lambda v: f"{v * scale:.3f}"
    return (f"M{f(x0 + ox)},{f(y0 + oy)} A{f(R)},{f(R)} 0 1 1 {f(x1 + ox)},{f(y1 + oy)}")

mx, my = pt(MOON_AT)

# ---------- SVG: mark ----------
mark_svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">
  <title>Orbit</title>
  <path d="{ring_path()}" fill="none" stroke="currentColor" stroke-width="{STROKE}" stroke-linecap="round"/>
  <circle cx="{mx:.3f}" cy="{my:.3f}" r="{MOON_R}" fill="currentColor"/>
</svg>
'''
open(os.path.join(OUT, 'orbit-mark.svg'), 'w').write(mark_svg)

# ---------- SVG: app icon ----------
icon_svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="512" height="512">
  <rect width="64" height="64" rx="14" fill="#0A0A0A"/>
  <path d="{ring_path()}" fill="none" stroke="#FFFFFF" stroke-width="{STROKE}" stroke-linecap="round"/>
  <circle cx="{mx:.3f}" cy="{my:.3f}" r="{MOON_R}" fill="#FFFFFF"/>
</svg>
'''
open(os.path.join(OUT, 'orbit-icon.svg'), 'w').write(icon_svg)

# ---------- SVG: horizontal logo ----------
logo_svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 150 64" width="300" height="128">
  <title>Orbit</title>
  <g fill="none" stroke="currentColor" stroke-width="{STROKE}" stroke-linecap="round">
    <path d="{ring_path()}"/>
  </g>
  <circle cx="{mx:.3f}" cy="{my:.3f}" r="{MOON_R}" fill="currentColor"/>
  <text x="64" y="44" font-family="Geist, Inter, system-ui, sans-serif" font-weight="600" font-size="34" letter-spacing="-1.2" fill="currentColor">Orbit</text>
</svg>
'''
open(os.path.join(OUT, 'orbit-logo.svg'), 'w').write(logo_svg)

# ---------- PNG renders (4x supersampled) ----------
def draw_mark(img, x, y, size, color):
    """Draw the mark into img with its 64-unit box at (x, y) scaled to `size` px (annulus + gap + caps + moon)."""
    k = size / 64.0
    w = STROKE * k
    mask = Image.new('L', img.size, 0)
    d = ImageDraw.Draw(mask)
    cx, cy = x + C * k, y + C * k
    ro, ri = R * k + w / 2, R * k - w / 2
    d.ellipse([cx - ro, cy - ro, cx + ro, cy + ro], fill=255)
    d.ellipse([cx - ri, cy - ri, cx + ri, cy + ri], fill=0)
    d.pieslice([cx - ro - 2, cy - ro - 2, cx + ro + 2, cy + ro + 2], MOON_AT - GAP, MOON_AT + GAP, fill=0)
    for a in (MOON_AT + GAP, MOON_AT - GAP):
        px, py = pt(a)
        px, py = x + px * k, y + py * k
        d.ellipse([px - w / 2, py - w / 2, px + w / 2, py + w / 2], fill=255)
    d.ellipse([x + (mx - MOON_R) * k, y + (my - MOON_R) * k, x + (mx + MOON_R) * k, y + (my + MOON_R) * k], fill=255)
    layer = Image.new('RGBA', img.size, color)
    img.paste(layer, (0, 0), mask)

def render(size, path, bg, fg, radius=None, mark_scale=1.0):
    S = 4
    img = Image.new('RGBA', (size * S, size * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if bg is not None:
        if radius is None:
            d.rectangle([0, 0, size * S, size * S], fill=bg)
        else:
            d.rounded_rectangle([0, 0, size * S - 1, size * S - 1], radius=radius * S, fill=bg)
    m = size * S * mark_scale
    off = (size * S - m) / 2
    draw_mark(img, off, off, m, fg)
    img.resize((size, size), Image.LANCZOS).save(path)

render(1024, os.path.join(OUT, 'orbit-icon-1024.png'), (10, 10, 10, 255), (255, 255, 255, 255), radius=224)
render(512, os.path.join(OUT, 'orbit-icon-512.png'), (10, 10, 10, 255), (255, 255, 255, 255), radius=112)
render(512, os.path.join(OUT, 'orbit-mark-black.png'), None, (10, 10, 10, 255))
render(512, os.path.join(OUT, 'orbit-mark-white.png'), None, (255, 255, 255, 255))

def render_logo(path, bg, fg, h=256):
    S = 4
    font = ImageFont.truetype(os.path.join(ROOT, r'app\src\main\res\font\geist.ttf'), int(h * 0.56 * S))
    try:
        font.set_variation_by_axes([600])
    except Exception:
        pass
    text = 'Orbit'
    tw = font.getbbox(text)[2]
    W = int(h * S * 1.0 + tw + h * S * 0.35)
    img = Image.new('RGBA', (W, h * S), bg)
    draw_mark(img, 0, 0, h * S, fg)
    d = ImageDraw.Draw(img)
    asc, desc = font.getmetrics()
    ty = (h * S - (asc + desc)) / 2 + h * S * 0.02
    d.text((h * S * 1.02, ty), text, font=font, fill=fg)
    img = img.resize((W // S, h), Image.LANCZOS)
    img.save(path)

render_logo(os.path.join(OUT, 'orbit-logo-light.png'), (255, 255, 255, 255), (10, 10, 10, 255))
render_logo(os.path.join(OUT, 'orbit-logo-dark.png'), (10, 10, 10, 255), (237, 237, 237, 255))

# ---------- Android adaptive icon foreground (108dp viewport, mark inside the 66dp safe zone) ----------
# Map 64-unit box onto 108 viewport: centre 54, scale so ring outer diameter ~ 50dp.
k = 1.30
ox = 54 - C * k
oy = 54 - C * k
def apt(a, r=R):
    x, y = pt(a, r)
    return ox + x * k, oy + y * k
s0 = MOON_AT + GAP; s1 = MOON_AT - GAP + 360
x0, y0 = apt(s0); x1, y1 = apt(s1)
amx, amy = apt(MOON_AT)
fg = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Orbit mark: an orbit ring forming an "O", with a moon in its gap. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path
        android:pathData="M{x0:.3f},{y0:.3f} A{R * k:.3f},{R * k:.3f} 0 1 1 {x1:.3f},{y1:.3f}"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{STROKE * k:.3f}"
        android:strokeLineCap="round"
        android:fillColor="#00000000" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M{amx:.3f},{amy:.3f}m-{MOON_R * k:.3f},0a{MOON_R * k:.3f},{MOON_R * k:.3f} 0,1 1,{2 * MOON_R * k:.3f},0a{MOON_R * k:.3f},{MOON_R * k:.3f} 0,1 1,-{2 * MOON_R * k:.3f},0" />
</vector>
'''
open(os.path.join(ROOT, r'app\src\main\res\drawable\ic_launcher_foreground.xml'), 'w').write(fg)

# Small in-app mark (24dp) for use in the UI.
k2 = 24 / 64
def bpt(a, r=R):
    x, y = pt(a, r)
    return x * k2, y * k2
bx0, by0 = bpt(s0); bx1, by1 = bpt(s1); bmx, bmy = bpt(MOON_AT)
mark = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M{bx0:.3f},{by0:.3f} A{R * k2:.3f},{R * k2:.3f} 0 1 1 {bx1:.3f},{by1:.3f}"
        android:strokeColor="#FF000000"
        android:strokeWidth="{STROKE * k2:.3f}"
        android:strokeLineCap="round"
        android:fillColor="#00000000" />
    <path
        android:fillColor="#FF000000"
        android:pathData="M{bmx:.3f},{bmy:.3f}m-{MOON_R * k2:.3f},0a{MOON_R * k2:.3f},{MOON_R * k2:.3f} 0,1 1,{2 * MOON_R * k2:.3f},0a{MOON_R * k2:.3f},{MOON_R * k2:.3f} 0,1 1,-{2 * MOON_R * k2:.3f},0" />
</vector>
'''
open(os.path.join(ROOT, r'app\src\main\res\drawable\orbit_mark.xml'), 'w').write(mark)
print('ok')
