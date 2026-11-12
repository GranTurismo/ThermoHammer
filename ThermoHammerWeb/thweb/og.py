"""Share images (1200×630): what a link to a page looks like when it is pasted into a chat or a post.

Each card is drawn from the same numbers as its page and kept on disk under a hash of them, so it is only redrawn
when something on it changes.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

from .model import SECTIONS, Comparison, Device, Site, StressRun, capacity_t, ramp_rgb

VERSION = 7             # bump to redraw every card
W, H, S = 1200, 630, 2  # drawn at twice the size, then scaled down, so lines and type come out smooth

BG = (6, 9, 11)
SURFACE = (11, 17, 23)
HAIR = (26, 34, 41)
INK0 = (234, 244, 244)
INK1 = (159, 180, 188)
INK2 = (125, 145, 154)
TEAL = (61, 232, 194)
A_COLOR = (0, 230, 255)
B_COLOR = (255, 171, 0)


def _hex(color: str) -> tuple[int, int, int]:
    color = color.lstrip("#")
    return tuple(int(color[i:i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


class Cards:
    def __init__(self, cache: Path, assets_src: Path):
        self.cache = cache
        self.cache.mkdir(parents=True, exist_ok=True)
        fonts = assets_src / "fonts"
        self.paths = {"sans": fonts / "spacegrotesk_700.ttf", "sans5": fonts / "spacegrotesk_500.ttf",
                      "mono": fonts / "jetbrainsmono_medium.ttf", "monob": fonts / "jetbrainsmono_extrabold.ttf"}
        self.glyphs = {k: set(TTFont(p, lazy=True).getBestCmap().keys()) for k, p in self.paths.items()}
        self._fonts: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}
        self.mark = Image.open(assets_src / "icon_1024.png").convert("RGB").crop((100, 100, 924, 924))
        self.made = 0

    # ── drawing helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    def font(self, face: str, size: int) -> ImageFont.FreeTypeFont:
        key = (face, size)
        if key not in self._fonts:
            self._fonts[key] = ImageFont.truetype(str(self.paths[face]), size * S)
        return self._fonts[key]

    def clean(self, face: str, text: str) -> str:
        """Drops what the typeface can't draw (a device name in another script), so no empty boxes appear."""
        kept = "".join(ch for ch in text if ord(ch) in self.glyphs[face] or ch == " ")
        return " ".join(kept.split())

    def text(self, d: ImageDraw.ImageDraw, xy, text: str, face: str, size: int, fill, anchor: str = "la", spacing: float = 0.0) -> float:
        """Draws text and returns its width in card pixels. `spacing` is extra space between letters, in ems."""
        text = self.clean(face, text)
        f = self.font(face, size)
        x, y = xy[0] * S, xy[1] * S
        if not spacing:
            d.text((x, y), text, font=f, fill=fill, anchor=anchor)
            return d.textlength(text, font=f) / S
        gap = spacing * size * S
        width = sum(d.textlength(ch, font=f) for ch in text) + gap * max(0, len(text) - 1)
        if anchor[0] == "r":
            x -= width
        elif anchor[0] == "m":
            x -= width / 2
        for ch in text:
            d.text((x, y), ch, font=f, fill=fill, anchor="l" + anchor[1])
            x += d.textlength(ch, font=f) + gap
        return width / S

    def width(self, d: ImageDraw.ImageDraw, text: str, face: str, size: int) -> float:
        return d.textlength(self.clean(face, text), font=self.font(face, size)) / S

    def fit(self, d: ImageDraw.ImageDraw, text: str, face: str, max_width: float, big: int, small: int) -> tuple[str, int]:
        """The largest size at which the text fits; shortened with an ellipsis if even the smallest is too wide."""
        text = self.clean(face, text)
        for size in range(big, small - 1, -2):
            if self.width(d, text, face, size) <= max_width:
                return text, size
        while len(text) > 4 and self.width(d, text + "…", face, small) > max_width:
            text = text[:-1].rstrip()
        return text + "…", small

    @staticmethod
    def rect(d: ImageDraw.ImageDraw, box, fill=None, outline=None, radius: int = 0, width: int = 1) -> None:
        x0, y0, x1, y1 = (v * S for v in box)
        d.rounded_rectangle((x0, y0, x1, y1), radius=radius * S, fill=fill, outline=outline, width=width * S)

    def canvas(self, tag: str) -> tuple[Image.Image, ImageDraw.ImageDraw]:
        img = Image.new("RGB", (W * S, H * S), BG)
        d = ImageDraw.Draw(img)
        for gx in range(0, W, 60):
            d.line((gx * S, 0, gx * S, H * S), fill=(10, 14, 17), width=S)
        for gy in range(0, H, 60):
            d.line((0, gy * S, W * S, gy * S), fill=(10, 14, 17), width=S)
        # the Thermal Ramp along the bottom edge
        for px in range(W * S):
            d.line((px, (H - 8) * S, px, H * S), fill=ramp_rgb(px / (W * S)))
        mark = self.mark.resize((56 * S, 56 * S), Image.LANCZOS)
        mask = Image.new("L", mark.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, mark.width, mark.height), radius=13 * S, fill=255)
        img.paste(mark, (60 * S, 48 * S), mask)
        self.text(d, (132, 76), "THERMOHAMMER", "monob", 26, INK0, "lm", spacing=0.08)
        self.text(d, (W - 60, 76), tag.upper(), "mono", 18, INK2, "rm", spacing=0.14)
        return img, d

    def curve(self, d: ImageDraw.ImageDraw, run: StressRun, box, color=None, grid: bool = True, weight: int = 5) -> None:
        """A run's curve inside `box` on a 0–100 % scale: ramp-coloured, or one colour for comparisons."""
        x0, y0, x1, y1 = box
        if grid:
            for p in (25, 50, 75, 100):
                gy = y1 - (y1 - y0) * p / 100
                d.line((x0 * S, gy * S, x1 * S, gy * S), fill=HAIR, width=S)
        span = max(run.seconds, 1.0)
        pts = [(x0 + (x1 - x0) * min(1.0, t / span), y1 - (y1 - y0) * min(100.0, max(0.0, p)) / 100) for t, p, _ in run.series]
        for (ax, ay), (bx, by), (_, p, _) in zip(pts, pts[1:], run.series[1:]):
            fill = color or ramp_rgb(capacity_t(p))
            d.line((ax * S, ay * S, bx * S, by * S), fill=fill, width=weight * S)
        for (px, py), (_, p, _) in zip(pts, run.series):
            r = weight * S / 2
            d.ellipse((px * S - r, py * S - r, px * S + r, py * S + r), fill=color or ramp_rgb(capacity_t(p)))

    def _save(self, name: str, key, draw) -> Path:
        digest = hashlib.sha1(json.dumps([VERSION, key], sort_keys=True, default=str).encode()).hexdigest()[:12]
        target = self.cache / f"{name}-{digest}.png"
        if target.exists():
            return target
        img = draw()
        img = img.resize((W, H), Image.LANCZOS).quantize(colors=160, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        tmp = target.with_suffix(".tmp")
        img.save(tmp, format="PNG", optimize=True)
        tmp.replace(target)
        for old in self.cache.glob(f"{name}-*.png"):
            if old != target:
                old.unlink(missing_ok=True)
        self.made += 1
        return target

    # ── the cards ────────────────────────────────────────────────────────────────────────────────────────────────────

    def device(self, dev: Device, site: Site) -> Path:
        r = dev.headline
        key = [dev.title, dev.chip, dev.gpu, dev.os_text, dev.total, dev.rank, len(site.board),
               [s["points"] for s in dev.section_list], (r.id, round(r.stability, 1)) if r else None]

        def draw() -> Image.Image:
            img, d = self.canvas("Benchmark" if dev.has_bench else "Throttling test")
            name, size = self.fit(d, dev.title, "sans", 1080, 68, 40)
            self.text(d, (60, 148), name, "sans", size, INK0)
            sub = " · ".join(x for x in (dev.chip if dev.os_id != 4 else "", dev.gpu, dev.os_text) if x) or dev.platform
            sub, sub_size = self.fit(d, sub, "mono", 1080, 22, 16)
            self.text(d, (60, 148 + size + 16), sub, "mono", sub_size, INK1)
            if dev.has_bench:
                self.text(d, (60, 318), "TOTAL SCORE", "mono", 18, INK2, spacing=0.14)
                self.text(d, (56, 344), f"{dev.total:,}", "monob", 118, INK0)
                if dev.rank:
                    self.text(d, (60, 490), f"#{dev.rank} OF {len(site.board)} ON THE BOARD", "mono", 20, TEAL, spacing=0.1)
                if r:
                    self.text(d, (60, 528), f"HOLDS {r.stability:.1f}% UNDER LOAD", "mono", 20, ramp_rgb(capacity_t(r.stability)), spacing=0.1)
                top = max((s["points"] for s in dev.section_list), default=1) or 1
                y = 318
                for s in dev.section_list:
                    color = _hex(s["color"])
                    self.text(d, (660, y + 12), s["short"], "monob", 20, color, "lm")
                    self.rect(d, (730, y + 4, 1010, y + 20), fill=(17, 26, 33), radius=8)
                    self.rect(d, (730, y + 4, 730 + max(10, 280 * s["points"] / top), y + 20), fill=color, radius=8)
                    self.text(d, (1140, y + 12), f"{s['points']:,}", "mono", 20, INK0, "rm")
                    y += 38
            elif r:
                self.text(d, (60, 318), "HOLDS", "mono", 18, INK2, spacing=0.14)
                self.text(d, (56, 344), f"{r.stability:.1f}%", "monob", 118, ramp_rgb(capacity_t(r.stability)))
                self.text(d, (60, 490), "OF ITS SPEED UNDER SUSTAINED LOAD", "mono", 20, INK1, spacing=0.1)
                self.text(d, (60, 528), r.label.upper(), "mono", 20, INK2, spacing=0.1)
                if r.has_curve:
                    self.rect(d, (640, 300, 1140, 560), outline=HAIR, radius=14)
                    self.curve(d, r, (664, 322, 1116, 538))
            return img

        return self._save(f"device-{dev.slug}", key, draw)

    def pair(self, p: Comparison) -> Path:
        a, b = p.a, p.b
        key = [a.title, b.title, a.total, b.total, p.headline, p.sections_line,
               (a.headline.id, round(a.headline.stability, 1)) if a.headline else None,
               (b.headline.id, round(b.headline.stability, 1)) if b.headline else None, p.has_bench]

        def draw() -> Image.Image:
            img, d = self.canvas("Head to head")
            for dev, x, anchor, color in ((a, 60, "l", A_COLOR), (b, W - 60, "r", B_COLOR)):
                name, size = self.fit(d, dev.title, "sans", 500, 46, 26)
                self.text(d, (x, 170), name, "sans", size, color, anchor + "a")
                if p.has_bench:
                    self.text(d, (x, 250), f"{dev.total:,}", "monob", 84, INK0, anchor + "a")
                else:
                    self.text(d, (x, 250), f"{dev.headline.stability:.1f}%", "monob", 84,
                              ramp_rgb(capacity_t(dev.headline.stability)), anchor + "a")
            self.text(d, (W / 2, 290), "VS", "mono", 22, INK2, "mm", spacing=0.2)
            if p.has_bench:
                split = 60 + (W - 120) * p.total_share_a
                self.rect(d, (60, 392, split - 3, 416), fill=A_COLOR, radius=6)
                self.rect(d, (split + 3, 392, W - 60, 416), fill=B_COLOR, radius=6)
                line = p.sections_line
            else:
                self.rect(d, (60, 372, W - 60, 478), outline=HAIR, radius=12)
                self.curve(d, b.headline, (76, 384, W - 76, 468), color=B_COLOR, grid=False, weight=4)
                self.curve(d, a.headline, (76, 384, W - 76, 468), color=A_COLOR, grid=False, weight=4)
                line = "Speed held under sustained load"
            head, size = self.fit(d, p.headline, "sans", 1080, 36, 24)
            self.text(d, (60, 500 if p.has_bench else 508), head, "sans", size, INK0)
            if p.has_bench:
                self.text(d, (60, 556), line.upper(), "mono", 18, INK2, spacing=0.1)
            return img

        return self._save(f"vs-{p.slug}", key, draw)

    def home(self, site: Site) -> Path:
        pool = [d.headline for d in site.stress_board if d.headline.standard and d.headline.steady and d.headline.has_curve]
        fader = min(pool, key=lambda r: r.stability) if pool else None
        key = ["home", site.stats["devices"], site.stats["runs"], fader.id if fader else None]

        def draw() -> Image.Image:
            img, d = self.canvas("Benchmark · stress test")
            self.text(d, (60, 150), "Every phone is fast", "sans", 84, INK0)
            self.text(d, (60, 244), "for a minute.", "sans", 84, ramp_rgb(0.6))
            self.text(d, (60, 372), f"{site.stats['devices']} DEVICES · {site.stats['runs']} VERIFIED RUNS · ANDROID · iPHONE · WINDOWS",
                      "mono", 20, INK1, spacing=0.08)
            if fader:
                self.curve(d, fader, (60, 430, W - 60, 596), grid=False, weight=5)
            return img

        return self._save("home", key, draw)

    def board(self, site: Site) -> Path:
        rows = [(d.rank, d.title, d.total) for d in site.board[:5]]
        key = ["board", rows]

        def draw() -> Image.Image:
            img, d = self.canvas("Leaderboard")
            self.text(d, (60, 132), "Benchmark leaderboard", "sans", 52, INK0)
            top = rows[0][2] if rows else 1
            y = 226
            for rank, name, total in rows:
                self.text(d, (60, y + 22), f"#{rank}", "monob", 24, TEAL if rank == 1 else INK2, "lm")
                label, size = self.fit(d, name, "sans5", 560, 28, 20)
                self.text(d, (124, y + 22), label, "sans5", size, INK0, "lm")
                self.rect(d, (720, y + 14, 980, y + 30), fill=(17, 26, 33), radius=8)
                self.rect(d, (720, y + 14, 720 + max(10, 260 * total / top), y + 30), fill=ramp_rgb(0.78 - 0.5 * total / top), radius=8)
                self.text(d, (W - 60, y + 22), f"{total:,}", "monob", 26, INK0, "rm")
                y += 64
            return img

        return self._save("board", key, draw)

    def throttling(self, site: Site) -> Path:
        rows = [(d.title, round(d.headline.stability, 1), d.headline.id) for d in site.stress_board[:5]]
        key = ["throttling", rows]

        def draw() -> Image.Image:
            img, d = self.canvas("Throttling leaderboard")
            self.text(d, (60, 132), "Which phones hold their speed", "sans", 52, INK0)
            y = 226
            for dev in site.stress_board[:5]:
                r = dev.headline
                label, size = self.fit(d, dev.title, "sans5", 520, 28, 20)
                self.text(d, (60, y + 22), label, "sans5", size, INK0, "lm")
                if r.has_curve:
                    self.curve(d, r, (620, y + 4, 960, y + 40), grid=False, weight=3)
                self.text(d, (W - 60, y + 22), f"{r.stability:.1f}%", "monob", 26, ramp_rgb(capacity_t(r.stability)), "rm")
                y += 64
            return img

        return self._save("throttling", key, draw)

    def note(self, title: str, tag: str, figures: list[tuple[str, str]]) -> Path:
        key = ["note", title, figures]

        def draw() -> Image.Image:
            img, d = self.canvas(tag)
            words, lines, line = title.split(), [], ""
            for w in words:
                if self.width(d, (line + " " + w).strip(), "sans", 60) > 1080:
                    lines.append(line)
                    line = w
                else:
                    line = (line + " " + w).strip()
            lines.append(line)
            y = 140
            for ln in lines[:3]:
                self.text(d, (60, y), ln, "sans", 60, INK0)
                y += 72
            x = 60
            for i, (label, value) in enumerate(figures[:2]):
                self.text(d, (x, 420), label, "mono", 18, INK2, spacing=0.14)
                self.text(d, (x - 3, 446), value, "monob", 92, INK1 if i == 0 else TEAL)
                x += 520
            return img

        return self._save("note", key, draw)
