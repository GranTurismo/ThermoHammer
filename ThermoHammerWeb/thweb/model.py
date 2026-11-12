"""Shapes the API's answers into what the pages show: devices, their runs, rankings and head-to-head comparisons.

Nothing here invents a number. Scores, names and stability come from the API as stored; what is derived from a stress
run's samples (peak, sustained speed, the moment it dips) is computed here and labelled as such on the pages.
"""
from __future__ import annotations

import hashlib
import re
import statistics
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .api import Api

REFERENCE = 6000          # points per section on the reference phone
REFERENCE_TOTAL = REFERENCE * 6

PLATFORMS = {1: ("iOS", "ios"), 2: ("Android", "android"), 3: ("macOS", "macos"), 4: ("Windows", "windows")}

# key, short label, long label, the board's field, accent (the apps' own section colours)
SECTIONS = [
    ("cpu", "CPU", "CPU", "cpuScore", "#E8C547"),
    ("mem", "MEM", "Memory", "memScore", "#F2A65A"),
    ("gpu", "GPU", "GPU", "score", "#9D7BFF"),
    ("ux", "UX", "Everyday UX", "uxScore", "#3DE8C2"),
    ("ai", "AI", "AI", "aiScore", "#C792EA"),
    ("llm", "LLM", "LLM", "llmScore", "#B5E853"),
]
SECTION_KEYS = [s[0] for s in SECTIONS]

DURATION_MIN = {0: 5, 1: 15, 2: 30}
THERMAL_NAMES = ["Nominal", "Fair", "Serious", "Critical"]

# ── the Thermal Ramp of the apps (ForgeTheme): 0 = cool teal … 1 = white-hot ────────────────────────────────────────

RAMP = [(0.00, (0x3D, 0xE8, 0xC2)), (0.25, (0x9E, 0xE8, 0x6A)), (0.50, (0xFF, 0xB5, 0x45)),
        (0.68, (0xFF, 0x6A, 0x3D)), (0.85, (0xFF, 0x3D, 0x5E)), (1.00, (0xFF, 0xE9, 0xD6))]


def ramp_rgb(t: float) -> tuple[int, int, int]:
    t = min(1.0, max(0.0, t))
    for (p0, c0), (p1, c1) in zip(RAMP, RAMP[1:]):
        if t <= p1:
            f = (t - p0) / (p1 - p0) if p1 > p0 else 0.0
            return tuple(round(a + (b - a) * f) for a, b in zip(c0, c1))  # type: ignore[return-value]
    return RAMP[-1][1]


def ramp_hex(t: float) -> str:
    return "#%02X%02X%02X" % ramp_rgb(t)


def capacity_t(pct: float) -> float:
    """Delivered capacity in percent (100 = lost nothing) → ramp position, as Ramp.forCapacity in the apps."""
    return min(1.0, max(0.0, (100.0 - pct) / 55.0))


def capacity_hex(pct: float) -> str:
    return ramp_hex(capacity_t(pct))


# ── names ────────────────────────────────────────────────────────────────────────────────────────────────────────────

def display_name(manufacturer: str, model: str) -> str:
    """The apps' rule (manufacturer in front unless the model starts with it), except that Apple devices go by model."""
    m, model = (manufacturer or "").strip(), (model or "").strip()
    if not m or m.lower() == "apple" or model.lower().startswith(m.lower()):
        return model
    return f"{m} {model}".strip()


def slugify(text: str) -> str:
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_text.lower()).strip("-")


def clean_gpu(name: str | None) -> str:
    if not name:
        return ""
    n = name.strip()
    m = re.match(r"ANGLE \(\((.+?)\) on .+\)$", n) or re.match(r"ANGLE \((.+?)\)$", n)
    if m:
        n = m.group(1)
    n = re.sub(r"\s*\((TM|R)\)", "", n, flags=re.I)
    return re.sub(r"\s+", " ", n).strip()


def load_table(path: Path) -> dict[str, str]:
    """A two-column tab-separated file: key, value. Lines starting with # are comments."""
    table: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#") or "\t" not in line:
                continue
            key, value = line.split("\t", 1)
            table[key.strip().lower()] = value.strip()
    return table


def os_label(os_id: int, version: str) -> str:
    return f"{PLATFORMS.get(os_id, ('Unknown', 'unknown'))[0]} {version}".strip()


def parse_time(text: str | None) -> datetime | None:
    if not text:
        return None
    try:
        m = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)", text)
        return datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc) if m else None
    except ValueError:
        return None


# ── stress runs ──────────────────────────────────────────────────────────────────────────────────────────────────────

@dataclass
class StressRun:
    id: int
    minutes: int               # 5, 15 or 30
    multi: bool                # every core, or one thread
    os_version: str
    stability: float           # the API's number: share of the baseline speed delivered
    series: list[tuple[float, float, int]] = field(default_factory=list)   # (seconds, % of full speed, thermal state)
    seconds: float = 0.0
    samples: int = 0
    basis: str = "peak"             # what 100 % is: the run's "peak" sample (older app versions) or its cold "baseline"
    peak_gips: float = 0.0          # the fastest sample
    sustained_gips: float = 0.0     # mean of the second half
    end_pct: float = 0.0            # mean of the last tenth, % of full speed
    low_pct: float = 0.0            # lowest 5-second average, % of full speed
    dip_s: float | None = None      # first moment the 5-second average is under 90 % of full speed
    steady: bool = False            # the curve and the stability number tell the same story (no one-sample spike)
    thermal_max: int = 0
    thermal_at: dict[int, float] = field(default_factory=dict)   # state → first second it was reported
    device: "Device | None" = None

    @property
    def standard(self) -> bool:
        return self.minutes == 5 and self.multi

    @property
    def has_curve(self) -> bool:
        return len(self.series) >= 8

    @property
    def color(self) -> str:
        return capacity_hex(self.stability)

    @property
    def label(self) -> str:
        return f"{self.minutes} min · {'multi-thread' if self.multi else '1 thread'}"


def _rolling_mean(values: list[float], n: int) -> list[float]:
    out, acc = [], 0.0
    for i, v in enumerate(values):
        acc += v
        if i >= n:
            acc -= values[i - n]
        out.append(acc / min(i + 1, n))
    return out


def shape_stress(run: StressRun, stamps: list[dict], buckets: int = 200) -> None:
    """Fills in a run's curve and what can be read off it."""
    pts = sorted((s["elapsedMs"] / 1000.0, float(s["score"]), int(s.get("thermalState") or 0))
                 for s in stamps if s.get("score", 0) > 0)
    run.samples = len(pts)
    if len(pts) < 8:
        return
    times = [p[0] for p in pts]
    scores = [p[1] for p in pts]
    states = [p[2] for p in pts]
    step = statistics.median(b - a for a, b in zip(times, times[1:])) or 0.25
    per5s = max(1, round(5.0 / step))
    top = max(scores)
    half = len(scores) // 2
    second_half = statistics.fmean(scores[half:])
    # 100 % is what the stability number was measured against. Older app versions sent no baseline, and the server
    # used "mean of the second half ÷ fastest sample". Newer ones send delivered capacity: work done ÷ what the cold
    # baseline would have done, so the baseline is the mean ÷ that share.
    if abs(second_half / top * 100.0 - run.stability) < 0.05 or run.stability <= 0:
        run.basis, full = "peak", top
    else:
        run.basis, full = "baseline", statistics.fmean(scores) / (run.stability / 100.0)
    pct = [min(112.0, s / full * 100.0) for s in scores]
    smooth = _rolling_mean(pct, per5s)
    run.seconds = times[-1]
    run.peak_gips = top / 1e9
    run.sustained_gips = second_half / 1e9
    one_second = max(1, round(1.0 / step))
    calm_peak = max(_rolling_mean(scores, one_second)[one_second - 1:] or scores)
    run.steady = abs(second_half / calm_peak * 100.0 - run.stability) < 8.0
    tail = max(1, len(pct) // 10)
    run.end_pct = statistics.fmean(pct[-tail:])
    run.low_pct = min(smooth[per5s - 1:] or smooth)
    run.dip_s = next((times[i] for i in range(per5s - 1, len(smooth)) if smooth[i] < 90.0), None)
    run.thermal_max = max(states)
    for t, st in zip(times, states):
        for level in range(1, st + 1):
            run.thermal_at.setdefault(level, t)
    n = min(buckets, len(pts))
    series = []
    for b in range(n):
        lo, hi = b * len(pts) // n, max((b + 1) * len(pts) // n, b * len(pts) // n + 1)
        series.append((statistics.fmean(times[lo:hi]), statistics.fmean(pct[lo:hi]), max(states[lo:hi])))
    run.series = series


# ── benchmark runs ───────────────────────────────────────────────────────────────────────────────────────────────────

@dataclass
class BenchRun:
    id: int
    created: datetime | None
    app_version: str
    os_version: str
    os_build: str
    total: int
    scores: dict[str, int]                 # section key → points
    sections: list[dict]                   # the API's detail: key, title, score, groups[title, points, rows[label, value]]

    def ratio(self, key: str) -> float:
        return self.scores.get(key, 0) / REFERENCE


def bench_run_from_detail(d: dict) -> BenchRun:
    scores = {s["key"]: int(s.get("score") or 0) for s in d.get("sections", [])}
    return BenchRun(
        id=d["id"], created=parse_time(d.get("createdAt")), app_version=d.get("appVersion") or "",
        os_version=d.get("osVersion") or "", os_build=d.get("osBuild") or "", total=int(d.get("totalScore") or 0),
        scores=scores, sections=d.get("sections", []))


# ── devices ──────────────────────────────────────────────────────────────────────────────────────────────────────────

@dataclass
class Device:
    key: str
    slug: str
    name: str                      # as the leaderboard stores it (Apple devices without the "Apple")
    alias: str                     # a friendlier name when the stored one is a code, else ""
    manufacturer: str
    os_id: int
    soc: str = ""
    soc_name: str = ""             # e.g. "Snapdragon 8 Elite" for SM8750, when known
    gpu: str = ""
    os_versions: list[str] = field(default_factory=list)
    # benchmark
    board: dict | None = None      # this device's row on the benchmark board (its best verified run)
    best: BenchRun | None = None
    bench_runs: list[BenchRun] = field(default_factory=list)
    stats: dict | None = None      # the API's statistics over this model's runs
    rank: int | None = None
    platform_rank: int | None = None
    # stress
    stress_runs: list[StressRun] = field(default_factory=list)
    headline: StressRun | None = None      # best standard run (5 min, every core), else the best of what there is
    stress_rank: int | None = None

    @property
    def platform(self) -> str:
        return PLATFORMS.get(self.os_id, ("Unknown", "unknown"))[0]

    @property
    def platform_key(self) -> str:
        return PLATFORMS.get(self.os_id, ("Unknown", "unknown"))[1]

    @property
    def title(self) -> str:
        return self.alias or self.name

    @property
    def has_bench(self) -> bool:
        return self.best is not None

    @property
    def has_stress(self) -> bool:
        return self.headline is not None

    @property
    def total(self) -> int:
        return self.best.total if self.best else 0

    @property
    def chip(self) -> str:
        if self.soc_name and self.soc:
            return f"{self.soc_name} ({self.soc})" if self.soc_name.lower() != self.soc.lower() else self.soc
        return self.soc_name or self.soc

    @property
    def os_text(self) -> str:
        versions = self.os_versions[:1]
        return os_label(self.os_id, versions[0]) if versions else self.platform

    @property
    def run_count(self) -> int:
        return (self.board or {}).get("runs", len(self.bench_runs)) if self.has_bench else 0

    @property
    def section_list(self) -> list[dict]:
        """The six sections of the best run, with how they stand against the 6,000-point reference."""
        if not self.best:
            return []
        out = []
        for key, short, long_, _, color in SECTIONS:
            pts = self.best.scores.get(key, 0)
            out.append({"key": key, "short": short, "label": long_, "points": pts, "ratio": pts / REFERENCE, "color": color,
                        "share": pts / self.best.total if self.best.total else 0})
        return out

    @property
    def strongest(self) -> dict | None:
        return max(self.section_list, key=lambda s: s["points"], default=None)

    @property
    def weakest(self) -> dict | None:
        return min((s for s in self.section_list if s["points"] > 0), key=lambda s: s["points"], default=None)


# ── comparisons (the apps' rules: BenchmarkComparison.kt) ────────────────────────────────────────────────────────────

EVEN_WITHIN = 0.01


@dataclass
class Duel:
    winner: str                 # "a", "b", "even" or "none"
    advantage: float | None = None      # percent the better result beats the other by

    @property
    def badge(self) -> str:
        return "+" + percent_text(self.advantage) if self.advantage is not None and self.winner in ("a", "b") else ""


def percent_text(pct: float) -> str:
    return f"{pct:.0f}%" if pct >= 9.95 else f"{pct:.1f}%"


def duel(a: float | None, b: float | None, better: str | None = "higher") -> Duel:
    if better not in ("higher", "lower"):
        return Duel("none")
    lower = better == "lower"

    def has(v):
        return v is not None and v == v and (v >= 0 if lower else v > 0)

    has_a, has_b = has(a), has(b)
    if not has_a and not has_b:
        return Duel("none")
    if not has_b:
        return Duel("a")
    if not has_a:
        return Duel("b")
    if a == b:
        return Duel("even", 0.0)
    a_ahead = a < b if lower else a > b
    hi, lo = max(a, b), min(a, b)
    if lo <= 0:
        return Duel("a" if a_ahead else "b")
    pct = (hi / lo - 1) * 100
    if hi / lo - 1 < EVEN_WITHIN:
        return Duel("even", pct)
    return Duel("a" if a_ahead else "b", pct)


def _union(a: list, b: list, key):
    by_b = {key(x): x for x in b}
    seen, out = set(), []
    for x in a:
        k = key(x)
        if k not in seen:
            seen.add(k)
            out.append((x, by_b.get(k)))
    for y in b:
        k = key(y)
        if k not in seen:
            seen.add(k)
            out.append((None, y))
    return out


def _share(x, y) -> float:
    top = max(x or 0, y or 0)
    return 0.0 if top <= 0 else max(x or 0, 0) / top


@dataclass
class Comparison:
    a: Device
    b: Device
    slug: str
    # benchmark, when both have a verified run
    total: Duel | None = None
    sections: list[dict] = field(default_factory=list)
    wins_a: int = 0
    wins_b: int = 0
    even: int = 0
    # stress, when both have a run
    stress: Duel | None = None
    same_test: bool = True

    @property
    def has_bench(self) -> bool:
        return self.total is not None

    @property
    def has_stress(self) -> bool:
        return self.stress is not None

    @property
    def path(self) -> str:
        return f"/compare/{self.slug}/"

    @property
    def total_share_a(self) -> float:
        both = self.a.total + self.b.total
        return 0.5 if both == 0 else self.a.total / both

    @property
    def leader(self) -> Device | None:
        d = self.total or self.stress
        return self.a if d and d.winner == "a" else self.b if d and d.winner == "b" else None

    @property
    def headline(self) -> str:
        """One line on the overall result, worded as in the apps."""
        if self.total:
            t = self.total
            if t.winner == "even":
                return "Dead heat: within 1% of each other overall"
            if t.winner in ("a", "b"):
                name = self.a.title if t.winner == "a" else self.b.title
                return f"{name} scores {percent_text(t.advantage)} higher overall" if t.advantage is not None \
                    else f"{name} wins: the other has no total"
            return "Neither run has a total score"
        if self.stress:
            s = self.stress
            if s.winner == "even":
                return "Dead heat: both hold their speed equally well"
            name = self.a.title if s.winner == "a" else self.b.title
            return f"{name} holds its speed better under sustained load"
        return ""

    @property
    def sections_line(self) -> str:
        if not self.sections:
            return ""
        lead = max(self.wins_a, self.wins_b)
        counted = sum(1 for s in self.sections if s["duel"].winner != "none")
        base = f"Sections split {self.wins_a}–{self.wins_b}" if self.wins_a == self.wins_b else f"Ahead in {lead} of {counted} sections"
        return f"{base} · {self.even} even" if self.even else base


def compare(a: Device, b: Device) -> Comparison | None:
    """Two devices side by side, or None when they have nothing in common to compare."""
    first, second = sorted((a, b), key=lambda d: d.slug)
    c = Comparison(a=first, b=second, slug=f"{first.slug}-vs-{second.slug}")
    if first.best and second.best:
        accent = {s[0]: s[4] for s in SECTIONS}
        for sa, sb in _union(first.best.sections, second.best.sections, lambda s: s["key"]):
            groups = []
            for ga, gb in _union((sa or {}).get("groups", []), (sb or {}).get("groups", []), lambda g: g["title"]):
                rows = []
                for ra, rb in _union((ga or {}).get("rows", []), (gb or {}).get("rows", []), lambda r: r["label"]):
                    better = (ra or {}).get("better") or (rb or {}).get("better")
                    rows.append({"label": (ra or rb)["label"], "a": (ra or {}).get("value"), "b": (rb or {}).get("value"),
                                 "duel": duel((ra or {}).get("number"), (rb or {}).get("number"), better)})
                pa, pb = (ga or {}).get("points"), (gb or {}).get("points")
                groups.append({"title": (ga or gb)["title"], "a": pa, "b": pb, "duel": duel(pa, pb), "rows": rows,
                               "share_a": _share(pa, pb), "share_b": _share(pb, pa)})
            s = sa or sb
            xa, xb = (sa or {}).get("score"), (sb or {}).get("score")
            c.sections.append({"key": s["key"], "title": s["title"], "a": xa, "b": xb, "duel": duel(xa, xb), "groups": groups,
                               "share_a": _share(xa, xb), "share_b": _share(xb, xa), "color": accent.get(s["key"], "#9FB4BC")})
        c.total = duel(first.best.total, second.best.total)
        c.wins_a = sum(1 for s in c.sections if s["duel"].winner == "a")
        c.wins_b = sum(1 for s in c.sections if s["duel"].winner == "b")
        c.even = sum(1 for s in c.sections if s["duel"].winner == "even")
    if first.headline and second.headline:
        c.stress = duel(first.headline.stability, second.headline.stability)
        c.same_test = (first.headline.minutes, first.headline.multi) == (second.headline.minutes, second.headline.multi)
    return c if (c.total or c.stress) else None


# ── the whole picture ────────────────────────────────────────────────────────────────────────────────────────────────

@dataclass
class Site:
    devices: list[Device]
    board: list[Device]                 # benchmark order
    stress_board: list[Device]          # by headline stability
    stress_runs: list[StressRun]        # every run, best first
    pairs: list[Comparison]
    built: datetime
    stats: dict
    by_slug: dict[str, Device] = field(default_factory=dict)
    all_bench_runs: dict[int, BenchRun] = field(default_factory=dict)
    all_stress_runs: dict[int, StressRun] = field(default_factory=dict)
    section_leaders: list[dict] = field(default_factory=list)
    fingerprint: str = ""

    def pair(self, a: Device, b: Device) -> Comparison | None:
        first, second = sorted((a, b), key=lambda d: d.slug)
        return self._pair_index.get(f"{first.slug}-vs-{second.slug}")

    def __post_init__(self):
        self._pair_index = {p.slug: p for p in self.pairs}

    def neighbours(self, device: Device, limit: int = 6) -> list[Comparison]:
        """Comparisons worth offering from a device's page: the closest on each board."""
        out: list[Comparison] = []
        seen: set[str] = set()

        def add(other: Device):
            p = self.pair(device, other)
            if p and p.slug not in seen:
                seen.add(p.slug)
                out.append(p)

        if device.has_bench:
            others = sorted((d for d in self.board if d is not device), key=lambda d: abs(d.total - device.total))
            for d in others[:limit]:
                add(d)
        if device.has_stress:
            others = sorted((d for d in self.stress_board if d is not device and d.headline.standard == device.headline.standard),
                            key=lambda d: abs(d.headline.stability - device.headline.stability))
            for d in others:
                if len(out) >= limit + (3 if device.has_bench else 0):
                    break
                add(d)
        return out


def load_site(api: Api, data_dir: Path, max_pairs: int = 5000, log=print) -> Site:
    aliases = load_table(data_dir / "device_aliases.tsv")
    soc_names = load_table(data_dir / "soc_names.tsv")
    board_rows = api.benchmark_board()
    stress_rows = api.stress_board()
    fingerprint = hashlib.sha1(repr((board_rows, stress_rows)).encode()).hexdigest()

    devices: dict[str, Device] = {}

    def device_for(manufacturer: str, model: str, os_id: int) -> Device:
        name = display_name(manufacturer, model)
        key = f"{os_id}:{name.lower()}"
        if key not in devices:
            devices[key] = Device(key=key, slug="", name=name, alias=aliases.get(name.lower(), ""),
                                  manufacturer=(manufacturer or "").strip(), os_id=os_id)
        return devices[key]

    # ── benchmark: the board, then every verified run by id (ids are dense; the board only names each model's best) ──
    runs: dict[int, BenchRun] = {}
    details: dict[int, dict] = {}
    top_id = max((r["id"] for r in board_rows), default=0)
    misses = 0
    run_id = 1
    while board_rows and misses < 6:
        known_gap = run_id < top_id
        d = api.benchmark_run(run_id, ttl=6 * 3600 if known_gap or run_id == top_id else 0,
                              keep_missing=known_gap)
        if d is None:
            misses = misses + 1 if run_id > top_id else misses
        else:
            misses = 0
            details[run_id] = d
            runs[run_id] = bench_run_from_detail(d)
        run_id += 1
    for rank, row in enumerate(board_rows, start=1):
        dev = device_for(row["deviceManufacturer"], row["deviceModel"], row["os"])
        dev.board, dev.rank = row, rank
        dev.soc = (row.get("socModel") or "").strip()
        dev.soc_name = soc_names.get(dev.soc.lower(), "")
        dev.gpu = clean_gpu(row.get("gpuName"))
        dev.best = runs.get(row["id"])
        if dev.best is None:                       # the board is ahead of the cache: ask for it directly
            d = api.benchmark_run(row["id"], ttl=0)
            if d:
                dev.best = runs[row["id"]] = bench_run_from_detail(d)
                details[row["id"]] = d
        if row.get("osVersion") and row["osVersion"] not in dev.os_versions:
            dev.os_versions.append(row["osVersion"])
        try:
            dev.stats = api.model_stats(row["deviceModel"], row["os"], row["deviceManufacturer"])
        except Exception as e:          # statistics are an extra: a page without them is still right
            log(f"  no model statistics for {dev.name}: {e}")
    for run_id, d in details.items():
        dev = devices.get(f"{d['os']}:{display_name(d['deviceManufacturer'], d['deviceModel']).lower()}")
        if dev:
            dev.bench_runs.append(runs[run_id])
    for dev in devices.values():
        dev.bench_runs.sort(key=lambda r: (r.created or datetime.min.replace(tzinfo=timezone.utc)), reverse=True)

    # ── stress runs ──────────────────────────────────────────────────────────────────────────────────────────────────
    all_stress: dict[int, StressRun] = {}
    for row in stress_rows:
        dev = device_for(row["deviceManufacturer"], row["deviceModel"], row["os"])
        run = StressRun(id=row["id"], minutes=DURATION_MIN.get(row.get("type", 0), 5), multi=row.get("testThreadingType", 1) == 1,
                        os_version=row.get("osVersion") or "", stability=float(row.get("stabilityPercentage") or 0), device=dev)
        try:
            shape_stress(run, api.stamps(run.id))
        except Exception as e:
            log(f"  no curve for stress run {run.id}: {e}")
        dev.stress_runs.append(run)
        all_stress[run.id] = run
        if run.os_version and run.os_version not in dev.os_versions:
            dev.os_versions.append(run.os_version)
    for dev in devices.values():
        dev.stress_runs.sort(key=lambda r: r.stability, reverse=True)
        standard = [r for r in dev.stress_runs if r.standard]
        dev.headline = (standard or dev.stress_runs or [None])[0]

    # ── slugs: stable, readable, unique ──────────────────────────────────────────────────────────────────────────────
    taken: dict[str, Device] = {}
    for dev in sorted(devices.values(), key=lambda d: (not d.has_bench, d.key)):
        base = slugify(dev.title) or slugify(dev.name) or "device"
        slug = base
        if slug in taken:
            slug = f"{base}-{dev.platform_key}"
        if slug in taken:
            slug = f"{base}-{hashlib.sha1(dev.key.encode()).hexdigest()[:5]}"
        dev.slug = slug
        taken[slug] = dev

    board = sorted((d for d in devices.values() if d.has_bench), key=lambda d: d.rank or 10 ** 9)
    seen_platform: dict[int, int] = {}
    for d in board:
        seen_platform[d.os_id] = seen_platform.get(d.os_id, 0) + 1
        d.platform_rank = seen_platform[d.os_id]
    stress_board = sorted((d for d in devices.values() if d.has_stress and d.headline.standard),
                          key=lambda d: d.headline.stability, reverse=True)
    for i, d in enumerate(stress_board, start=1):
        d.stress_rank = i
    stress_board += sorted((d for d in devices.values() if d.has_stress and not d.headline.standard),
                           key=lambda d: d.headline.stability, reverse=True)

    # ── comparisons ──────────────────────────────────────────────────────────────────────────────────────────────────
    ordered = sorted(devices.values(), key=lambda d: d.slug)
    pairs: list[Comparison] = []
    for i, a in enumerate(ordered):
        for b in ordered[i + 1:]:
            if (a.has_bench and b.has_bench) or (a.has_stress and b.has_stress):
                c = compare(a, b)
                if c:
                    pairs.append(c)
    if len(pairs) > max_pairs:      # keep the benchmark pairs and the closest stress matches
        pairs.sort(key=lambda p: (not p.has_bench, abs(p.a.headline.stability - p.b.headline.stability) if p.has_stress else 0))
        pairs = pairs[:max_pairs]
        pairs.sort(key=lambda p: p.slug)

    leaders = []
    for key, short, long_, _, color in SECTIONS:
        top = max(board, key=lambda d: d.best.scores.get(key, 0), default=None)
        if top:
            leaders.append({"key": key, "short": short, "label": long_, "color": color, "device": top,
                            "points": top.best.scores.get(key, 0)})

    stress_sorted = sorted(all_stress.values(), key=lambda r: r.stability, reverse=True)
    stats = {
        "devices": len(devices),
        "bench_devices": len(board),
        "bench_runs": len(runs),
        "stress_devices": sum(1 for d in devices.values() if d.has_stress),
        "stress_runs": len(all_stress),
        "runs": len(runs) + len(all_stress),
        "platforms": sorted({d.platform for d in devices.values()}),
        "pairs": len(pairs),
        "api_requests": api.requests,
    }
    return Site(devices=sorted(devices.values(), key=lambda d: d.title.lower()), board=board, stress_board=stress_board,
                stress_runs=stress_sorted, pairs=pairs, built=datetime.now(timezone.utc), stats=stats,
                by_slug={d.slug: d for d in devices.values()}, all_bench_runs=runs, all_stress_runs=all_stress,
                section_leaders=leaders, fingerprint=fingerprint)
