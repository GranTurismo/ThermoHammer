"""The site's charts, drawn as inline SVG on the server: no chart library, nothing to load, readable without JavaScript.

Every curve is a real run. The colours are the apps' Thermal Ramp: teal while a device holds its speed, through amber
and ember to white-hot as it loses it.
"""
from __future__ import annotations

import math
from html import escape

from .model import RAMP, REFERENCE, SECTIONS, THERMAL_NAMES, StressRun, ramp_hex

A_COLOR = "#00E6FF"     # the apps' comparison pair: A cyan, B amber
B_COLOR = "#FFAB00"
INK0, INK1, INK2 = "#EAF4F4", "#9FB4BC", "#7D919A"
GRID = "rgba(255,255,255,.07)"
Y_TOP = 108.0           # the curves' scale: 0 … 108 % of full speed, the same on every chart so they compare by eye


def _f(v: float) -> str:
    return f"{v:.1f}".rstrip("0").rstrip(".")


def clock(seconds: float) -> str:
    seconds = int(round(seconds))
    return f"{seconds // 60}:{seconds % 60:02d}"


def ramp_gradient(uid: str, y_full: float, y_empty: float) -> str:
    """A vertical gradient in the chart's own coordinates: 100 % is teal, 45 % and below is white-hot."""
    y45 = y_full + (y_empty - y_full) * 0.55
    stops = "".join(f'<stop offset="{p:.2f}" stop-color="{ramp_hex(p)}"/>' for p, _ in RAMP)
    return (f'<linearGradient id="{uid}" gradientUnits="userSpaceOnUse" x1="0" y1="{_f(y_full)}" x2="0" y2="{_f(y45)}">'
            f'{stops}</linearGradient>')


def _path(points: list[tuple[float, float]]) -> str:
    return "M" + " L".join(f"{_f(x)} {_f(y)}" for x, y in points)


def _ticks(seconds: float, narrow: bool = False) -> list[float]:
    minutes = seconds / 60
    step = 1 if minutes <= 6 else 5 if minutes <= 20 else 10
    if narrow and minutes > 6:
        step = 5 if minutes <= 16 else 10
    out, t = [], 0.0
    while t <= minutes + 0.06:
        out.append(t * 60)
        t += step
    return out


def ribbon(run: StressRun, uid: str, width: int = 760, height: int = 320, animate: bool = False, title: str = "") -> str:
    """One run's throughput over time, in two cuts: `width` wide for large screens and a narrow one for phones."""
    wide = _ribbon(run, uid, width, height, animate, title).replace('class="ribbon"', 'class="ribbon cut-wide"', 1)
    narrow = _ribbon(run, uid + "n", 430, 290, animate, title).replace('class="ribbon"', 'class="ribbon cut-narrow"', 1)
    return wide + narrow


def _ribbon(run: StressRun, uid: str, width: int, height: int, animate: bool, title: str) -> str:
    if not run.has_curve:
        return ""
    left, right, top, bottom = 52, 18, 24, 32
    pw, ph = width - left - right, height - top - bottom
    span = max(run.seconds, run.minutes * 60 * 0.98)

    def x(t: float) -> float:
        return left + pw * min(1.0, t / span)

    def y(p: float) -> float:
        return top + ph * (1 - min(Y_TOP, max(0.0, p)) / Y_TOP)

    pts = [(x(t), y(p)) for t, p, _ in run.series]
    line = _path(pts)
    area = line + f" L{_f(pts[-1][0])} {_f(y(0))} L{_f(pts[0][0])} {_f(y(0))} Z"
    out = [f'<svg class="ribbon" viewBox="0 0 {width} {height}" role="img" aria-label="{escape(title or "Throughput over time")}" '
           f'xmlns="http://www.w3.org/2000/svg">',
           f"<title>{escape(title or 'Throughput over time')}</title>",
           "<defs>", ramp_gradient(f"g{uid}", y(100), y(0)),
           f'<linearGradient id="a{uid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{INK0}" stop-opacity=".1"/>'
           f'<stop offset="1" stop-color="{INK0}" stop-opacity="0"/></linearGradient>', "</defs>"]
    for p in (25, 50, 75, 100):
        dash = ' stroke-dasharray="3 5"' if p == 100 else ""
        out.append(f'<line x1="{left}" x2="{width - right}" y1="{_f(y(p))}" y2="{_f(y(p))}" stroke="{GRID}"{dash}/>')
        out.append(f'<text x="{left - 8}" y="{_f(y(p) + 4)}" text-anchor="end" class="ax">{p}%</text>')
    for t in _ticks(span, width < 500):
        out.append(f'<text x="{_f(x(t))}" y="{height - 8}" text-anchor="middle" class="ax">{clock(t)}</text>')
        out.append(f'<line x1="{_f(x(t))}" x2="{_f(x(t))}" y1="{_f(y(0))}" y2="{_f(y(0) + 4)}" stroke="{INK2}"/>')
    out.append(f'<line x1="{left}" x2="{width - right}" y1="{_f(y(0))}" y2="{_f(y(0))}" stroke="rgba(255,255,255,.16)"/>')
    # thermal states the device reported, at the second each first appeared
    for level, t in sorted(run.thermal_at.items()):
        color = ramp_hex((0.05, 0.30, 0.62, 0.85)[min(level, 3)])
        tx = x(t)
        anchor, dx = ("start", 5) if tx < width - right - 70 else ("end", -5)
        out.append(f'<line x1="{_f(tx)}" x2="{_f(tx)}" y1="{top - 6}" y2="{_f(y(0))}" stroke="{color}" stroke-opacity=".55" stroke-dasharray="2 4"/>')
        out.append(f'<text x="{_f(tx + dx)}" y="{top + 4 + (level - 1) * 12}" text-anchor="{anchor}" class="flag" fill="{color}">'
                   f'{THERMAL_NAMES[min(level, 3)].upper()}</text>')
    draw = ' class="draw" pathLength="1"' if animate else ""
    fade = ' class="fade"' if animate else ""
    out.append(f'<path d="{area}" fill="url(#a{uid})"{fade}/>')
    out.append(f'<path d="{line}" fill="none" stroke="url(#g{uid})" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round"{draw}/>')
    ex, ey = pts[-1]
    out.append(f'<circle cx="{_f(ex)}" cy="{_f(ey)}" r="4" fill="{ramp_hex(min(1, max(0, (100 - run.series[-1][1]) / 55)))}"{fade}/>')
    out.append("</svg>")
    return "".join(out)


def spark_defs(uid: str = "spark", height: int = 34) -> str:
    """One gradient shared by every sparkline on a page."""
    return (f'<svg width="0" height="0" class="defs" aria-hidden="true" focusable="false"><defs>'
            f'{ramp_gradient(uid, 2, height - 2)}</defs></svg>')


def sparkline(run: StressRun, uid: str = "spark", width: int = 150, height: int = 34) -> str:
    """A run's curve at thumbnail size, on the same 0–100 % scale as every other."""
    if not run.has_curve:
        return ""
    step = max(1, len(run.series) // 60)
    series = run.series[::step]
    if series[-1] is not run.series[-1]:
        series.append(run.series[-1])
    span = max(run.seconds, 1.0)
    pts = [(1 + (width - 2) * min(1.0, t / span), 2 + (height - 4) * (1 - min(100.0, max(0.0, p)) / 100.0)) for t, p, _ in series]
    return (f'<svg class="spark" viewBox="0 0 {width} {height}" width="{width}" height="{height}" aria-hidden="true" focusable="false">'
            f'<path d="{_path(pts)}" fill="none" stroke="url(#{uid})" stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round"/></svg>')


def duo(a: StressRun, b: StressRun, name_a: str, name_b: str, width: int = 760, height: int = 320) -> str:
    """Two runs on one chart: A cyan, B amber. Two cuts, as for `ribbon`."""
    wide = _duo(a, b, name_a, name_b, width, height).replace('class="ribbon duo"', 'class="ribbon duo cut-wide"', 1)
    narrow = _duo(a, b, name_a, name_b, 430, 290).replace('class="ribbon duo"', 'class="ribbon duo cut-narrow"', 1)
    return wide + narrow


def _duo(a: StressRun, b: StressRun, name_a: str, name_b: str, width: int, height: int) -> str:
    if not (a.has_curve and b.has_curve):
        return ""
    left, right, top, bottom = 52, 18, 16, 32
    pw, ph = width - left - right, height - top - bottom
    span = max(a.seconds, b.seconds, 60.0)

    def x(t: float) -> float:
        return left + pw * min(1.0, t / span)

    def y(p: float) -> float:
        return top + ph * (1 - min(Y_TOP, max(0.0, p)) / Y_TOP)

    label = f"Throughput over time: {name_a} against {name_b}"
    out = [f'<svg class="ribbon duo" viewBox="0 0 {width} {height}" role="img" aria-label="{escape(label)}" xmlns="http://www.w3.org/2000/svg">',
           f"<title>{escape(label)}</title>"]
    for p in (25, 50, 75, 100):
        dash = ' stroke-dasharray="3 5"' if p == 100 else ""
        out.append(f'<line x1="{left}" x2="{width - right}" y1="{_f(y(p))}" y2="{_f(y(p))}" stroke="{GRID}"{dash}/>')
        out.append(f'<text x="{left - 8}" y="{_f(y(p) + 4)}" text-anchor="end" class="ax">{p}%</text>')
    for t in _ticks(span, width < 500):
        out.append(f'<text x="{_f(x(t))}" y="{height - 8}" text-anchor="middle" class="ax">{clock(t)}</text>')
    out.append(f'<line x1="{left}" x2="{width - right}" y1="{_f(y(0))}" y2="{_f(y(0))}" stroke="rgba(255,255,255,.16)"/>')
    for run, color in ((b, B_COLOR), (a, A_COLOR)):
        pts = [(x(t), y(p)) for t, p, _ in run.series]
        out.append(f'<path d="{_path(pts)}" fill="none" stroke="{color}" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>')
        out.append(f'<circle cx="{_f(pts[-1][0])}" cy="{_f(pts[-1][1])}" r="3.6" fill="{color}"/>')
    out.append("</svg>")
    return "".join(out)


def _hex_points(cx: float, cy: float, radii: list[float]) -> list[tuple[float, float]]:
    return [(cx + r * math.cos(math.radians(-90 + i * 60)), cy + r * math.sin(math.radians(-90 + i * 60)))
            for i, r in enumerate(radii)]


def _poly(points: list[tuple[float, float]]) -> str:
    return " ".join(f"{_f(x)},{_f(y)}" for x, y in points)


def radar(scores: dict[str, int], size: int = 380, title: str = "") -> str:
    """One device's six sections on a common scale, with the 6,000-point reference drawn as a dashed hexagon."""
    cx = cy = size / 2
    radius = size / 2 - 58
    values = [max(0, scores.get(k, 0)) for k, *_ in SECTIONS]
    top = max(max(values), REFERENCE) or 1
    label = title or "Section scores against the 6,000-point reference"
    out = [f'<svg class="radar" viewBox="0 0 {size} {size}" role="img" aria-label="{escape(label)}" xmlns="http://www.w3.org/2000/svg">',
           f"<title>{escape(label)}</title>"]
    for ring in (0.25, 0.5, 0.75, 1.0):
        out.append(f'<polygon points="{_poly(_hex_points(cx, cy, [radius * ring] * 6))}" fill="none" stroke="{GRID}"/>')
    for px, py in _hex_points(cx, cy, [radius] * 6):
        out.append(f'<line x1="{cx}" y1="{cy}" x2="{_f(px)}" y2="{_f(py)}" stroke="{GRID}"/>')
    ref = _hex_points(cx, cy, [radius * REFERENCE / top] * 6)
    out.append(f'<polygon points="{_poly(ref)}" fill="none" stroke="{INK1}" stroke-opacity=".7" stroke-dasharray="4 5" stroke-width="1.2"/>')
    shape = _hex_points(cx, cy, [radius * v / top for v in values])
    out.append(f'<polygon points="{_poly(shape)}" fill="rgba(61,232,194,.16)" stroke="#3DE8C2" stroke-width="2" stroke-linejoin="round"/>')
    for (px, py), (_, _, _, _, color) in zip(shape, SECTIONS):
        out.append(f'<circle cx="{_f(px)}" cy="{_f(py)}" r="3.6" fill="{color}"/>')
    for (lx, ly), (key, short, _, _, color), v in zip(_hex_points(cx, cy, [radius + 26] * 6), SECTIONS, values):
        anchor = "middle" if abs(lx - cx) < 4 else "start" if lx > cx else "end"
        out.append(f'<text x="{_f(lx)}" y="{_f(ly - 3)}" text-anchor="{anchor}" class="rk" fill="{color}">{short}</text>')
        out.append(f'<text x="{_f(lx)}" y="{_f(ly + 11)}" text-anchor="{anchor}" class="rv">{v:,}</text>')
    out.append("</svg>")
    return "".join(out)


def radar_duo(sections: list[dict], name_a: str, name_b: str, size: int = 400) -> str:
    """Two devices on one hexagon. Each axis is scaled to the better of the two, as in the apps' comparison."""
    cx = cy = size / 2
    radius = size / 2 - 60
    label = f"Section by section: {name_a} against {name_b}"
    out = [f'<svg class="radar" viewBox="0 0 {size} {size}" role="img" aria-label="{escape(label)}" xmlns="http://www.w3.org/2000/svg">',
           f"<title>{escape(label)}</title>"]
    for ring in (0.25, 0.5, 0.75, 1.0):
        out.append(f'<polygon points="{_poly(_hex_points(cx, cy, [radius * ring] * 6))}" fill="none" stroke="{GRID}"/>')
    for px, py in _hex_points(cx, cy, [radius] * 6):
        out.append(f'<line x1="{cx}" y1="{cy}" x2="{_f(px)}" y2="{_f(py)}" stroke="{GRID}"/>')
    secs = (sections + [{"share_a": 0, "share_b": 0, "title": "", "duel": None}] * 6)[:6]
    for share, color in (("share_b", B_COLOR), ("share_a", A_COLOR)):
        shape = _hex_points(cx, cy, [radius * max(0.02, s[share]) for s in secs])
        out.append(f'<polygon points="{_poly(shape)}" fill="{color}" fill-opacity=".13" stroke="{color}" stroke-width="2" stroke-linejoin="round"/>')
    short = {s[0]: s[1] for s in SECTIONS}
    for (lx, ly), s in zip(_hex_points(cx, cy, [radius + 24] * 6), secs):
        if not s.get("duel"):
            continue
        winner = s["duel"].winner
        color = A_COLOR if winner == "a" else B_COLOR if winner == "b" else INK1
        anchor = "middle" if abs(lx - cx) < 4 else "start" if lx > cx else "end"
        out.append(f'<text x="{_f(lx)}" y="{_f(ly - 2)}" text-anchor="{anchor}" class="rk" fill="{color}">{short.get(s["key"], s["title"])}</text>')
        if s["duel"].badge:
            out.append(f'<text x="{_f(lx)}" y="{_f(ly + 12)}" text-anchor="{anchor}" class="rv" fill="{color}">{s["duel"].badge}</text>')
        elif winner == "even":
            out.append(f'<text x="{_f(lx)}" y="{_f(ly + 12)}" text-anchor="{anchor}" class="rv">even</text>')
    out.append("</svg>")
    return "".join(out)
