"""Renders the whole site into a folder of plain files: pages, share images, sitemap, the lot."""
from __future__ import annotations

import hashlib
import json
import os
import re
import statistics
from datetime import datetime, timezone
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, StrictUndefined, select_autoescape
from markupsafe import Markup

from . import charts, content, og
from .config import ROOT, Config
from .model import (REFERENCE, REFERENCE_TOTAL, SECTIONS, THERMAL_NAMES, Comparison, Device, Site, StressRun,
                    capacity_hex, percent_text, ramp_hex)

TEXT_TYPES = {".css", ".js", ".svg", ".json", ".txt", ".xml", ".html", ".webmanifest"}


def num(v) -> str:
    try:
        return f"{int(round(float(v))):,}"
    except (TypeError, ValueError):
        return "–"


def pct(v, digits: int = 1) -> str:
    try:
        return f"{float(v):.{digits}f}%"
    except (TypeError, ValueError):
        return "–"


def times(v) -> str:
    """A ratio as people say it: 2.1×, 12×."""
    try:
        v = float(v)
    except (TypeError, ValueError):
        return "–"
    return f"{v:.0f}×" if v >= 9.95 else f"{v:.1f}×" if v >= 1.995 else f"{v:.2f}×"


def day(d: datetime | None) -> str:
    return f"{d.day} {d.strftime('%b %Y')}" if d else ""


def gips(v) -> str:
    return f"{float(v):.2f}"


def jsonld(data) -> Markup:
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    return Markup(f'<script type="application/ld+json">{text}</script>')


class Builder:
    def __init__(self, cfg: Config, log=print):
        self.cfg = cfg
        self.log = log
        self.files: dict[str, bytes | Path] = {}
        self.assets: dict[str, str] = {}
        self.sitemap: list[tuple[str, str, str]] = []
        self.env = Environment(
            loader=FileSystemLoader(ROOT / "templates"), autoescape=select_autoescape(["html", "j2", "xml"]),
            undefined=StrictUndefined, trim_blocks=True, lstrip_blocks=True)
        self.env.filters.update(num=num, pct=pct, times=times, day=day, gips=gips, clock=charts.clock)

    # ── files ────────────────────────────────────────────────────────────────────────────────────────────────────────

    def put(self, path: str, data: bytes | str | Path) -> None:
        self.files[path.lstrip("/")] = data.encode("utf-8") if isinstance(data, str) else data

    def _collect_assets(self) -> None:
        """Copies static/ under /assets/ with a content hash in every name, so they can be cached forever."""
        static = ROOT / "static"
        plain = sorted(p for p in static.rglob("*") if p.is_file() and p.suffix != ".css" and p.name != ".DS_Store")
        for p in plain:
            rel = p.relative_to(static).as_posix()
            data = p.read_bytes()
            digest = hashlib.sha1(data).hexdigest()[:10]
            target = f"assets/{p.parent.relative_to(static).as_posix()}/{p.stem}.{digest}{p.suffix}".replace("/./", "/")
            self.assets[rel] = target
            self.put(target, data)
        for p in sorted(static.rglob("*.css")):
            rel = p.relative_to(static).as_posix()
            css = re.sub(r"@@([\w./-]+)", lambda m: self.cfg.url("/" + self.assets[m.group(1)]), p.read_text(encoding="utf-8"))
            css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
            css = re.sub(r"\s*\n\s*", "\n", css).strip()
            digest = hashlib.sha1(css.encode()).hexdigest()[:10]
            target = f"assets/css/{p.stem}.{digest}.css"
            self.assets[rel] = target
            self.put(target, css)
        # the files browsers ask for at the root
        for name in ("favicon.ico",):
            self.put(name, (static / "img" / name).read_bytes())

    def asset(self, rel: str) -> str:
        return self.cfg.url("/" + self.assets[rel])

    # ── pages ────────────────────────────────────────────────────────────────────────────────────────────────────────

    def page(self, path: str, template: str, *, title: str, description: str, og_image: str | None = None,
             og_alt: str = "", index: bool = True, priority: str = "0.6", jsonld_blocks: list | None = None,
             crumbs: list[tuple[str, str]] | None = None, **ctx) -> None:
        cfg = self.cfg
        canonical = cfg.abs_url(path)
        blocks = list(jsonld_blocks or [])
        if crumbs:
            blocks.append({"@context": "https://schema.org", "@type": "BreadcrumbList", "itemListElement": [
                {"@type": "ListItem", "position": i, "name": name, "item": cfg.abs_url(p)}
                for i, (name, p) in enumerate([("ThermoHammer", "/")] + crumbs, start=1)]})
        html = self.env.get_template(template).render(
            page_title=title, page_description=description, canonical=canonical, path=path,
            og_image=cfg.abs_url("/" + (og_image or self.default_og)), og_alt=og_alt or title,
            robots="noindex, nofollow" if (cfg.noindex or not index) else "index, follow, max-image-preview:large",
            jsonld_blocks=[jsonld(b) for b in blocks], crumbs=crumbs or [], **ctx)
        html = re.sub(r"\n\s*\n+", "\n", html)
        out = path.lstrip("/")
        self.put(out + "index.html" if out == "" or out.endswith("/") else out, html)
        if index and not cfg.noindex:
            self.sitemap.append((canonical, self.changed.strftime("%Y-%m-%d"), priority))

    # ── the build ────────────────────────────────────────────────────────────────────────────────────────────────────

    def build(self, site: Site) -> dict:
        cfg = self.cfg
        self.files.clear()
        self.sitemap.clear()
        self.assets.clear()
        self.site = site
        self.changed = self._changed_at(site)
        self._collect_assets()
        cards = og.Cards(cfg.cache / "og", ROOT / "assets-src")
        self.cards = cards
        self.default_og = self._og("home", cards.home(site))

        hero = self._hero_runs(site)
        self.env.globals.update(
            cfg=cfg, site=site, url=cfg.url, abs_url=cfg.abs_url, asset=self.asset, charts=charts, SECTIONS=SECTIONS,
            REFERENCE=REFERENCE, REFERENCE_TOTAL=REFERENCE_TOTAL, THERMAL_NAMES=THERMAL_NAMES, CATALOG=content.CATALOG,
            PHASES=content.PHASES, TEST_COUNT=sum(len(c["tests"]) for c in content.CATALOG.values()), capacity_hex=capacity_hex, ramp_hex=ramp_hex, percent_text=percent_text,
            built=site.built, changed=self.changed, year=site.built.year, A_COLOR=charts.A_COLOR, B_COLOR=charts.B_COLOR,
            section_meta={s[0]: {"short": s[1], "label": s[2], "color": s[4]} for s in SECTIONS})

        self._home(site, hero)
        self._leaderboards(site)
        self._devices(site)
        self._compare(site)
        self._how(site)
        self._notes(site)
        self._misc(site)
        return {"pages": sum(1 for f in self.files if f.endswith(".html")), "files": len(self.files)}

    def _changed_at(self, site: Site) -> datetime:
        """When the leaderboard data last changed, remembered between builds (the sitemap's lastmod)."""
        state_file = self.cfg.cache / "state.json"
        state = {}
        if state_file.exists():
            try:
                state = json.loads(state_file.read_text())
            except ValueError:
                state = {}
        if state.get("fingerprint") != site.fingerprint:
            state = {"fingerprint": site.fingerprint, "changed": site.built.isoformat()}
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps(state))
        return datetime.fromisoformat(state["changed"])

    def _og(self, name: str, image_file: Path) -> str:
        digest = image_file.stem.split("-")[-1][:10]
        target = f"og/{name}.{digest}.png"
        self.put(target, image_file)
        return target

    @staticmethod
    def _hero_runs(site: Site) -> list[StressRun]:
        """Three real runs for the home page: the steadiest, a typical one, and the one that lost the most."""
        pool = [d.headline for d in site.stress_board
                if d.headline.standard and d.headline.steady and d.headline.samples >= 200 and d.headline.has_curve]
        if not pool:
            return []
        pool.sort(key=lambda r: r.stability, reverse=True)
        median = statistics.median(r.stability for r in pool)
        middle = min(pool, key=lambda r: abs(r.stability - median))
        picks = []
        for r in (pool[0], middle, pool[-1]):
            if r not in picks:
                picks.append(r)
        return picks

    # ── home ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    def _home(self, site: Site, hero: list[StressRun]) -> None:
        cfg = self.cfg
        faq = content.faq(site)
        featured = self._featured_pair(site)
        note = self._note_data(site)
        blocks = [
            {"@context": "https://schema.org", "@type": "WebSite", "name": "ThermoHammer", "url": cfg.abs_url("/"),
             "description": "Phone and PC benchmark with a thermal throttling stress test and a public leaderboard.",
             "publisher": {"@type": "Organization", "name": cfg.publisher}},
            {"@context": "https://schema.org", "@type": "SoftwareApplication", "name": "ThermoHammer",
             "applicationCategory": "UtilitiesApplication", "operatingSystem": "Android, iOS, iPadOS",
             "description": "A complete device benchmark (CPU, memory, GPU, everyday apps, AI and an on-device LLM) and a "
                            "controlled stress test that shows when a device throttles and how much speed it keeps.",
             "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
             "installUrl": [cfg.play_url, cfg.appstore_url], "url": cfg.abs_url("/"),
             "image": cfg.abs_url("/" + self.assets["img/icon-512.png"]),
             "author": {"@type": "Organization", "name": cfg.publisher}},
            {"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
                {"@type": "Question", "name": q, "acceptedAnswer": {"@type": "Answer", "text": a}} for q, a in faq]},
        ]
        self.page(
            "/", "home.html.j2", priority="1.0",
            title="ThermoHammer: phone benchmark and thermal throttling test",
            description=f"Benchmark your whole phone or PC in six sections, then stress it to see when it throttles and how "
                        f"much speed it keeps. {site.stats['devices']} devices and {site.stats['runs']} verified runs on the "
                        f"public leaderboard. Free for Android and iPhone.",
            jsonld_blocks=blocks, hero=hero, faq=faq, featured=featured, note=note,
            steady=hero[0] if hero else None, fader=hero[-1] if len(hero) > 1 else None)

    @staticmethod
    def _featured_pair(site: Site) -> Comparison | None:
        """The matchup shown on the home page: two phones on the benchmark board with the most sections in play."""
        phones = [p for p in site.pairs if p.has_bench and p.a.os_id != 4 and p.b.os_id != 4]
        pool = phones or [p for p in site.pairs if p.has_bench]
        if not pool:
            return None
        return min(pool, key=lambda p: (-(p.a.run_count + p.b.run_count), abs(p.a.total - p.b.total)))

    # ── leaderboards ─────────────────────────────────────────────────────────────────────────────────────────────────

    def _leaderboards(self, site: Site) -> None:
        cfg = self.cfg
        top = site.board[0] if site.board else None
        item_list = {"@context": "https://schema.org", "@type": "ItemList", "name": "ThermoHammer benchmark leaderboard",
                     "numberOfItems": len(site.board), "itemListElement": [
                         {"@type": "ListItem", "position": d.rank, "name": d.title, "url": cfg.abs_url(f"/devices/{d.slug}/")}
                         for d in site.board[:100]]}
        dataset = {"@context": "https://schema.org", "@type": "Dataset", "name": "ThermoHammer benchmark and throttling results",
                   "description": "Verified benchmark scores and sustained-load stability measurements of phones, tablets and "
                                  "PCs, submitted from the ThermoHammer apps and scored on the server.",
                   "url": cfg.abs_url("/leaderboard/"), "creator": {"@type": "Organization", "name": cfg.publisher},
                   "isAccessibleForFree": True, "dateModified": self.changed.strftime("%Y-%m-%d"),
                   "keywords": ["phone benchmark", "CPU throttling", "thermal throttling", "sustained performance"]}
        self.page(
            "/leaderboard/", "leaderboard.html.j2", priority="0.9",
            title="Benchmark leaderboard: phones and PCs ranked | ThermoHammer",
            description=(f"{len(site.board)} devices ranked by total benchmark score across CPU, memory, GPU, everyday UX, "
                         f"AI and LLM." + (f" Top: {top.title} with {num(top.total)} points." if top else "")
                         + " Verified runs only, scored on the server."),
            og_image=self._og("leaderboard", self.cards.board(site)), og_alt="The ThermoHammer benchmark leaderboard",
            crumbs=[("Leaderboard", "/leaderboard/")], jsonld_blocks=[item_list, dataset])

        best = site.stress_board[0] if site.stress_board else None
        stress_list = {"@context": "https://schema.org", "@type": "ItemList", "name": "ThermoHammer throttling leaderboard",
                       "numberOfItems": len(site.stress_board), "itemListElement": [
                           {"@type": "ListItem", "position": i, "name": d.title, "url": cfg.abs_url(f"/devices/{d.slug}/")}
                           for i, d in enumerate(site.stress_board[:100], start=1)]}
        self.page(
            "/leaderboard/throttling/", "throttling.html.j2", priority="0.9",
            title="Throttling leaderboard: which phones hold their speed | ThermoHammer",
            description=(f"{site.stats['stress_runs']} sustained-load stress runs on {site.stats['stress_devices']} devices, "
                         f"ranked by how much speed each one keeps." + (f" Steadiest: {best.title} at {pct(best.headline.stability)}."
                                                                         if best else "") + " Every row shows its real curve."),
            og_image=self._og("throttling", self.cards.throttling(site)), og_alt="The ThermoHammer throttling leaderboard",
            crumbs=[("Leaderboard", "/leaderboard/"), ("Throttling", "/leaderboard/throttling/")], jsonld_blocks=[stress_list])

    # ── devices ──────────────────────────────────────────────────────────────────────────────────────────────────────

    def _devices(self, site: Site) -> None:
        groups: dict[str, list[Device]] = {}
        for d in site.devices:
            brand = "Apple" if d.manufacturer.lower() == "apple" else ("Windows PCs" if d.os_id == 4 else d.manufacturer or "Other")
            groups.setdefault(brand, []).append(d)
        ordered = sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0].lower()))
        self.page(
            "/devices/", "devices.html.j2", priority="0.7",
            title="All tested devices: benchmark scores and throttling | ThermoHammer",
            description=f"Every device with a verified ThermoHammer run: {site.stats['devices']} phones, tablets and PCs with "
                        f"their benchmark scores and sustained-load stability.",
            crumbs=[("Devices", "/devices/")], groups=ordered)
        for d in site.devices:
            self._device(site, d)

    def _device(self, site: Site, d: Device) -> None:
        bits = []
        if d.has_bench:
            title = f"{d.title} benchmark: {num(d.total)} points | ThermoHammer"
            bits.append(f"{d.title} scores {num(d.total)} in the ThermoHammer benchmark"
                        + (f" (#{d.rank} of {len(site.board)})" if d.rank else "") + ": "
                        + ", ".join(f"{s['short']} {num(s['points'])}" for s in d.section_list) + ".")
        else:
            title = f"{d.title} throttling test: holds {pct(d.headline.stability)} | ThermoHammer"
        if d.has_stress:
            r = d.headline
            bits.append(f"Under a {r.minutes}-minute stress test it holds {pct(r.stability)} of its speed.")
        bits.append("Verified runs, full test numbers and comparisons.")
        neighbours = site.neighbours(d)
        self.page(
            f"/devices/{d.slug}/", "device.html.j2", priority="0.8" if d.has_bench else "0.6",
            title=title, description=" ".join(bits),
            og_image=self._og(f"device-{d.slug}", self.cards.device(d, site)), og_alt=f"{d.title} on ThermoHammer",
            crumbs=[("Devices", "/devices/"), (d.title, f"/devices/{d.slug}/")],
            d=d, neighbours=neighbours, uid=re.sub(r"[^a-z0-9]", "", d.slug)[:24])

    # ── comparisons ──────────────────────────────────────────────────────────────────────────────────────────────────

    def _compare(self, site: Site) -> None:
        bench_pairs = sorted((p for p in site.pairs if p.has_bench), key=lambda p: abs(p.a.total - p.b.total))
        stress_only = [p for p in site.pairs if not p.has_bench and p.same_test and p.a.headline.steady and p.b.headline.steady]
        stress_only.sort(key=lambda p: -abs(p.a.headline.stability - p.b.headline.stability))
        picker = [{"s": d.slug, "n": d.title, "p": d.platform, "b": int(d.has_bench), "t": int(d.has_stress)} for d in site.devices]
        self.page(
            "/compare/", "compare_index.html.j2", priority="0.8",
            title="Compare phones head to head: benchmark and throttling | ThermoHammer",
            description=f"Pick any two of {site.stats['devices']} tested devices and see them side by side: total score, six "
                        f"sections, every test, and how each holds its speed under load.",
            crumbs=[("Compare", "/compare/")], bench_pairs=bench_pairs[:12], stress_pairs=stress_only[:9],
            picker_json=Markup(json.dumps(picker, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")))
        for p in site.pairs:
            self._pair(site, p)

    def _pair(self, site: Site, p: Comparison) -> None:
        a, b = p.a, p.b
        if p.has_bench:
            desc = (f"{p.headline}: {num(a.total)} against {num(b.total)} points. {p.sections_line}. "
                    f"CPU, memory, GPU, everyday UX, AI and LLM compared test by test.")
        else:
            desc = (f"{a.title} holds {pct(a.headline.stability)} of its speed under sustained load, {b.title} "
                    f"{pct(b.headline.stability)}. Both throttling curves on one chart.")
        more = [q for q in site.neighbours(a, 4) + site.neighbours(b, 4) if q.slug != p.slug]
        seen, others = set(), []
        for q in more:
            if q.slug not in seen:
                seen.add(q.slug)
                others.append(q)
        self.page(
            p.path, "compare.html.j2", priority="0.7" if p.has_bench else "0.4",
            title=f"{a.title} vs {b.title}: {'benchmark' if p.has_bench else 'throttling'} comparison | ThermoHammer",
            description=desc, og_image=self._og(f"vs-{p.slug}", self.cards.pair(p)), og_alt=f"{a.title} against {b.title}",
            crumbs=[("Compare", "/compare/"), (f"{a.title} vs {b.title}", p.path)], p=p, a=a, b=b, others=others[:6])

    # ── how it works, notes ──────────────────────────────────────────────────────────────────────────────────────────

    def _how(self, site: Site) -> None:
        self.page(
            "/how-it-works/", "how.html.j2", priority="0.8",
            title="How ThermoHammer tests and scores a device",
            description="What each of the six benchmark sections measures, how points are calculated against the 6,000-point "
                        "reference, what counts as a verified run, and how the stress test measures throttling.",
            crumbs=[("How it works", "/how-it-works/")],
            jsonld_blocks=[{"@context": "https://schema.org", "@type": "TechArticle",
                            "headline": "How ThermoHammer tests and scores a device", "url": self.cfg.abs_url("/how-it-works/"),
                            "author": {"@type": "Organization", "name": self.cfg.publisher},
                            "dateModified": self.changed.strftime("%Y-%m-%d")}])

    def _note_data(self, site: Site) -> dict | None:
        """The numbers behind the power-mode note, read from the two runs themselves. None if they aren't there."""
        honor = next((d for d in site.board if d.name == "HONOR 600 Pro"), None)
        if not honor:
            return None
        balanced, performance = site.all_bench_runs.get(4), site.all_bench_runs.get(5)
        if not balanced or not performance or balanced not in honor.bench_runs or performance not in honor.bench_runs:
            return None
        if performance.scores.get("cpu", 0) < balanced.scores.get("cpu", 0) * 1.5:
            return None
        rows = []
        for key, short, long_, _, color in SECTIONS:
            x, y = balanced.scores.get(key, 0), performance.scores.get(key, 0)
            rows.append({"key": key, "label": long_, "color": color, "a": x, "b": y, "ratio": y / x if x else 0})

        def row_value(run, section, group, label):
            for s in run.sections:
                if s["key"] == section:
                    for g in s["groups"]:
                        if g["title"] == group:
                            for r in g["rows"]:
                                if r["label"] == label:
                                    return r["value"]
            return None

        gap = (performance.created - balanced.created).total_seconds() / 60 if balanced.created and performance.created else None
        rival = next((d for d in site.board if d.name == "Samsung Galaxy S25 FE"), None)
        trace = site.all_stress_runs.get(1039)
        first = site.all_bench_runs.get(2)
        trace_early = trace_after = None
        if trace and trace.has_curve and trace.device is honor:
            early = [p for t, p, _ in trace.series if t <= 3.5]
            after = [p for t, p, _ in trace.series if 8 <= t <= 40]
            if early and after and statistics.fmean(early) > statistics.fmean(after) + 15:
                trace_early, trace_after = statistics.fmean(early), statistics.fmean(after)
            else:
                trace = None        # the run doesn't show the drop the note describes: leave the figure out
        else:
            trace = None
        return {"device": honor, "balanced": balanced, "performance": performance, "rows": rows,
                "cpu_ratio": performance.scores["cpu"] / balanced.scores["cpu"], "gap_min": gap,
                "total_ratio": performance.total / balanced.total,
                "db_before": row_value(balanced, "mem", "Database", "Update"),
                "db_after": row_value(performance, "mem", "Database", "Update"),
                "rival": rival, "trace": trace, "trace_early": trace_early, "trace_after": trace_after, "first": first,
                "path": "/notes/power-mode-doubles-cpu-score/"}

    def _notes(self, site: Site) -> None:
        note = self._note_data(site)
        notes = []
        if note:
            title = "One setting doubled this phone's CPU score"
            description = (f"The same HONOR 600 Pro, the same app, {round(note['gap_min'] or 0)} minutes apart: CPU "
                           f"{num(note['balanced'].scores['cpu'])} in Balanced mode, {num(note['performance'].scores['cpu'])} in "
                           f"Performance. What a power mode does to a benchmark, with the runs to check.")
            published = note["performance"].created or site.built
            notes.append({"path": note["path"], "title": title, "description": description, "date": published})
            self.page(
                note["path"], "note_power_mode.html.j2", priority="0.7", title=f"{title} | ThermoHammer", description=description,
                og_image=self._og("note-power-mode", self.cards.note(title, "Lab note", [
                    ("BALANCED", num(note["balanced"].scores["cpu"])), ("PERFORMANCE", num(note["performance"].scores["cpu"]))])),
                crumbs=[("Lab notes", "/notes/"), (title, note["path"])], note=note, published=published,
                jsonld_blocks=[{"@context": "https://schema.org", "@type": "Article", "headline": title,
                                "description": description, "datePublished": published.strftime("%Y-%m-%d"),
                                "dateModified": self.changed.strftime("%Y-%m-%d"), "mainEntityOfPage": self.cfg.abs_url(note["path"]),
                                "author": {"@type": "Organization", "name": "ThermoHammer"},
                                "publisher": {"@type": "Organization", "name": self.cfg.publisher}}])
        self.page(
            "/notes/", "notes.html.j2", priority="0.5", title="Lab notes | ThermoHammer",
            description="Things the leaderboard taught us: short write-ups with the runs to check them against.",
            crumbs=[("Lab notes", "/notes/")], notes=notes)

    # ── everything else ──────────────────────────────────────────────────────────────────────────────────────────────

    def _misc(self, site: Site) -> None:
        cfg = self.cfg
        self.page("/404.html", "404.html.j2", index=False, title="Page not found | ThermoHammer",
                  description="That page isn't here.")
        self.put("site.webmanifest", json.dumps({
            "name": "ThermoHammer", "short_name": "ThermoHammer", "start_url": cfg.url("/"), "display": "browser",
            "background_color": "#06090B", "theme_color": "#06090B",
            "icons": [{"src": self.asset("img/icon-192.png"), "sizes": "192x192", "type": "image/png"},
                      {"src": self.asset("img/icon-512.png"), "sizes": "512x512", "type": "image/png"}]}, indent=1))
        if cfg.noindex:
            self.put("robots.txt", "User-agent: *\nDisallow: /\n")
        else:
            self.put("robots.txt", f"User-agent: *\nAllow: /\n\nSitemap: {cfg.abs_url('/sitemap.xml')}\n")
            urls = "".join(f"<url><loc>{loc}</loc><lastmod>{mod}</lastmod><priority>{pr}</priority></url>\n"
                           for loc, mod, pr in sorted(self.sitemap, key=lambda u: (-float(u[2]), u[0])))
            self.put("sitemap.xml", '<?xml version="1.0" encoding="UTF-8"?>\n'
                                    f'<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n{urls}</urlset>\n')
        self.put("llms.txt", self.env.get_template("llms.txt.j2").render())
        # the data behind the boards, for anyone who wants to chart it themselves
        self.put("data/leaderboard.json", json.dumps({
            "updated": self.changed.isoformat(), "source": cfg.abs_url("/leaderboard/"),
            "benchmark": [{"rank": d.rank, "device": d.name, "platform": d.platform, "chip": d.chip, "gpu": d.gpu,
                           "os": d.os_text, "total": d.total, "sections": d.best.scores, "runs": d.run_count,
                           "url": cfg.abs_url(f"/devices/{d.slug}/")} for d in site.board],
            "throttling": [{"device": r.device.name, "platform": r.device.platform, "minutes": r.minutes,
                            "threads": "multi" if r.multi else "single", "stability": round(r.stability, 2),
                            "peak_gips": round(r.peak_gips, 3), "sustained_gips": round(r.sustained_gips, 3),
                            "url": cfg.abs_url(f"/devices/{r.device.slug}/")} for r in site.stress_runs],
        }, ensure_ascii=False, separators=(",", ":")))

    # ── writing to disk ──────────────────────────────────────────────────────────────────────────────────────────────

    def publish(self) -> dict:
        """Makes the output folder match the build: new and changed files are swapped in whole, stale ones removed."""
        out = self.cfg.out
        out.mkdir(parents=True, exist_ok=True)
        written = removed = 0
        keep = set()
        for rel, data in self.files.items():
            target = out / rel
            keep.add(target)
            payload = data.read_bytes() if isinstance(data, Path) else data
            if target.exists() and target.stat().st_size == len(payload) and target.read_bytes() == payload:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_name(target.name + ".tmp~")
            tmp.write_bytes(payload)
            os.replace(tmp, target)
            written += 1
        for existing in sorted(out.rglob("*"), reverse=True):
            if existing.is_file() and existing not in keep:
                existing.unlink()
                removed += 1
            elif existing.is_dir() and not any(existing.iterdir()):
                existing.rmdir()
        return {"written": written, "removed": removed, "total": len(self.files)}
