"""Scoring versions: the site before, at and after a board restart, built from made-up API answers (no network).

Run: .venv/bin/python -m unittest discover -s tests
"""
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path

from thweb.config import ROOT, Config
from thweb.model import current_version, load_site
from thweb.site import Builder


# ── made-up API answers, shaped like ThermoHammer.Api's ─────────────────────────────────────────────────────────────

def _row(label, value, number=None, better="higher"):
    return {"label": label, "value": value, "number": number, "better": better if number is not None else None}


def _group(title, points, *rows):
    return {"title": title, "points": points, "rows": list(rows)}


def _sections(scores: dict, version: int) -> list[dict]:
    cpu, mem, gpu, ux, ai = (scores[k] for k in ("cpu", "mem", "gpu", "ux", "ai"))
    sections = [
        {"key": "cpu", "title": "CPU", "score": cpu, "groups": [_group("Math", cpu, _row("Throughput", f"{cpu * 10:,} ops/s", cpu * 10.0))]},
        {"key": "mem", "title": "MEMORY", "score": mem, "groups": [_group(
            "Database", mem, _row("Insert", f"{mem * 2:,} tx/s", mem * 2.0), _row("Update", f"{mem:,} tx/s", float(mem)),
            _row("Query", f"{mem * 3:,} /s", mem * 3.0))]},
        {"key": "gpu", "title": "GPU", "score": gpu, "groups": [_group("Lake Valley", gpu, _row("Average", f"{gpu / 100:.1f} fps", gpu / 100))]},
        {"key": "ux", "title": "EVERYDAY UX", "score": ux, "groups": [_group("Web browsing", ux, _row("Layout", f"{ux:,}", float(ux)))]},
        {"key": "ai", "title": "AI", "score": ai, "groups": [_group("CPU", ai, _row("Classify", "300 inferences/s", 300.0))]},
    ]
    if version < 5:            # BenchmarkDetail.Llm: version 4 had an LLM test, version 5 has none
        llm = scores["llm"]
        sections.append({"key": "llm", "title": "LLM", "score": llm, "groups": [
            _group("Prompt (prefill)", llm, _row("CPU", "120 tok/s", 120.0), _row("GPU", "300 tok/s", 300.0)),
            _group("Reply (decode)", llm, _row("CPU", "20.0 tok/s", 20.0), _row("GPU", "15.0 tok/s", 15.0)),
            _group("Run", None, _row("GPU used", "Adreno 830"), _row("Free memory at start", "6.1 GB"))]})
    return sections


def detail(run_id, maker, model, version, when, cpu=6000, mem=6000, gpu=6000, ux=6000, ai=6000, llm=6000,
           os_version="16", soc="SM8750"):
    scores = {"cpu": cpu, "mem": mem, "gpu": gpu, "ux": ux, "ai": ai}
    if version < 5:
        scores["llm"] = llm
    return {"id": run_id, "createdAt": when, "benchmarkVersion": version, "deviceManufacturer": maker,
            "deviceModel": model, "socModel": soc, "gpuName": "Adreno 830", "os": 2, "osVersion": os_version,
            "osBuild": "", "appVersion": "2.2.0" if version >= 5 else "2.1.7", "totalScore": sum(scores.values()),
            "sections": _sections(scores, version)}


class FakeApi:
    """The leaderboard API as the server serves it on `server_version`: the board and the model statistics list only
    that version, every verified run of any version is readable by id."""

    def __init__(self, details, server_version, stress=(), stamps=None):
        self.details = {d["id"]: d for d in details}
        self.server_version = server_version
        self.stress = list(stress)
        self.stamps_by_id = stamps or {}
        self.memo: dict = {}
        self.asked: list[tuple[int, float]] = []
        self.requests = 0

    def _current(self):
        return [d for d in self.details.values() if d["benchmarkVersion"] == self.server_version]

    def benchmark_board(self):
        best: dict[str, dict] = {}
        for d in sorted(self._current(), key=lambda d: (-d["totalScore"], d["id"])):
            best.setdefault(d["deviceModel"].lower(), d)
        counts = {m: sum(1 for d in self._current() if d["deviceModel"].lower() == m) for m in best}
        return [{"id": d["id"], "deviceManufacturer": d["deviceManufacturer"], "deviceModel": d["deviceModel"],
                 "socModel": d["socModel"], "gpuName": d["gpuName"], "os": d["os"], "osVersion": d["osVersion"],
                 "totalScore": d["totalScore"], "runs": counts[m]}
                for m, d in sorted(best.items(), key=lambda kv: -kv[1]["totalScore"])]

    def stress_board(self):
        return self.stress

    def benchmark_run(self, run_id, ttl=6 * 3600, keep_missing=False):
        self.requests += 1
        self.asked.append((run_id, ttl))
        return self.details.get(run_id)

    def stamps(self, run_id):
        return self.stamps_by_id.get(run_id, [])

    def model_stats(self, model, os_id, manufacturer, ttl=0.0):
        runs = [d for d in self._current() if d["deviceModel"] == model and d["os"] == os_id]
        totals = sorted(d["totalScore"] for d in runs)
        return {"deviceModel": model, "runs": len(runs),
                "total": {"median": totals[len(totals) // 2]} if totals else None,
                "sections": {s["key"]: s["score"] for s in runs[0]["sections"]} if runs else {}}

    def recall(self, name):
        return self.memo.get(name)

    def remember(self, name, value):
        self.memo[name] = json.loads(json.dumps(value))


# version 4: the HONOR runs the lab note is about (#4 Balanced, #5 Performance), the rival, one more phone
V4 = [
    detail(1, "Vivo", "Vivo X300 Pro", 4, "2026-11-08T10:00:00Z", cpu=11000, gpu=8000),
    detail(2, "HONOR", "HONOR 600 Pro", 4, "2026-11-09T13:48:36Z", cpu=5900),
    detail(3, "Samsung", "Samsung Galaxy S25 FE", 4, "2026-11-10T06:00:04Z", cpu=7200),
    detail(4, "HONOR", "HONOR 600 Pro", 4, "2026-11-10T14:17:46Z", cpu=6000, mem=6000),
    detail(5, "HONOR", "HONOR 600 Pro", 4, "2026-11-10T14:30:06Z", cpu=12600, mem=8900),
]
# version 5: the HONOR and the Vivo again, and a phone that only ever ran version 5 (id 7 was never verified)
V5 = [
    detail(6, "HONOR", "HONOR 600 Pro", 5, "2026-11-25T09:00:00Z", cpu=6000),
    detail(8, "Vivo", "Vivo X300 Pro", 5, "2026-11-25T10:00:00Z", cpu=5400, gpu=6500),
    detail(9, "Google", "Pixel 10 Pro", 5, "2026-11-26T10:00:00Z", cpu=4800, os_version="17", soc="Tensor G5"),
    detail(10, "HONOR", "HONOR 600 Pro", 5, "2026-11-26T11:00:00Z", cpu=5800),
]
STRESS = [{"id": 1039, "deviceManufacturer": "Samsung", "deviceModel": "Galaxy A55", "os": 2, "type": 0,
           "testThreadingType": 1, "osVersion": "16", "stabilityPercentage": 71.0, "stressMode": 0}]
STAMPS = {1039: [{"elapsedMs": i * 1000, "score": 4e9 * (1.0 if i < 60 else 0.65), "thermalState": 0} for i in range(300)]}


def build(api: FakeApi, tmp: Path):
    site = load_site(api, ROOT / "data", log=lambda *a: None)
    cfg = Config(origin="https://example.test", base_path="", api="", out=tmp / "dist", cache=tmp / "cache",
                 noindex=False, max_pairs=5000)
    builder = Builder(cfg, log=lambda *a: None)
    builder.build(site)
    return site, builder


def text(builder: Builder, path: str) -> str:
    data = builder.files[path.lstrip("/") + ("index.html" if path.endswith("/") else "")]
    return (data.read_bytes() if isinstance(data, Path) else data).decode("utf-8")


class WorldTest(unittest.TestCase):
    details: list = []
    server_version = 4

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="thweb-test-"))
        cls.api = FakeApi(cls.details, cls.server_version, STRESS, STAMPS)
        cls.site, cls.builder = build(cls.api, cls.tmp)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def page(self, path: str) -> str:
        return text(self.builder, path)

    def device(self, name: str):
        return next(d for d in self.site.devices if d.name == name)


class CurrentVersionTest(unittest.TestCase):
    def test_the_board_decides(self):
        self.assertEqual(4, current_version([4, 4], [4, 4, 4]))
        self.assertEqual(5, current_version([5], [4, 4, 5]))

    def test_an_empty_board_with_runs_on_record_has_moved_on(self):
        self.assertEqual(5, current_version([], [4, 4]))

    def test_nothing_at_all(self):
        self.assertEqual(0, current_version([], []))


class WalkTest(unittest.TestCase):
    def test_runs_are_found_without_a_board_and_the_walk_ends(self):
        details = [detail(i, "HONOR", "HONOR 600 Pro", 4, f"2026-11-{i:02d}T10:00:00Z") for i in (1, 2, 3, 5, 8)]
        api = FakeApi(details, server_version=5)
        site = load_site(api, ROOT / "data", log=lambda *a: None)
        self.assertEqual([1, 2, 3, 5, 8], sorted(site.all_bench_runs))
        self.assertEqual(8 + 6, max(i for i, _ in api.asked))       # six misses in a row past the last run
        self.assertEqual(8, api.memo["benchmark-top"])

    def test_known_ids_are_cached_new_ones_asked_fresh(self):
        details = [detail(i, "HONOR", "HONOR 600 Pro", 4, f"2026-11-{i:02d}T10:00:00Z") for i in (1, 2, 3)]
        api = FakeApi(details, server_version=5)
        api.memo["benchmark-top"] = 3
        load_site(api, ROOT / "data", log=lambda *a: None)
        ttl = dict(api.asked)
        self.assertTrue(all(ttl[i] > 0 for i in (1, 2, 3)))
        self.assertTrue(all(ttl[i] == 0 for i in range(4, 10)))


class TodayTest(WorldTest):
    """Version 4 on the board, nothing earlier: the site as it is now."""
    details = V4
    server_version = 4

    def test_version_and_board(self):
        self.assertEqual(4, self.site.version)
        self.assertEqual([], self.site.earlier_versions)
        self.assertEqual(["HONOR 600 Pro", "Vivo X300 Pro", "Samsung Galaxy S25 FE"], [d.name for d in self.site.board])
        self.assertTrue(all(r.current for r in self.site.all_bench_runs.values()))

    def test_no_version_notes(self):
        self.assertNotIn("previous scoring", self.page("/run/4/"))
        self.assertNotIn("Earlier runs", self.page("/devices/honor-600-pro/"))
        self.assertNotIn("Why did the leaderboard start over?", self.page("/"))

    def test_the_note_still_names_the_board_total(self):
        note = self.page("/notes/power-mode-doubles-cpu-score/")
        self.assertIn("now stands at 45,500, #1 on the board: that is the Performance run", note)
        self.assertIn("5,900 points against 7,200", note)

    def test_version_4_counts_its_six_sections(self):
        how = self.page("/how-it-works/")
        self.assertIn("The benchmark: six sections", how)
        self.assertIn("<b>36,000</b>", how)
        self.assertIn('data-llm="6000"', self.page("/leaderboard/"))


class JustSwitchedTest(WorldTest):
    """The server has moved to version 5 and nobody has submitted yet: an empty board, version 4 runs only."""
    details = V4
    server_version = 5

    def test_nothing_ranks(self):
        self.assertEqual(5, self.site.version)
        self.assertEqual([4], self.site.earlier_versions)
        self.assertEqual([], self.site.board)
        self.assertEqual([], self.site.section_leaders)
        self.assertFalse(any(p.has_bench for p in self.site.pairs))
        self.assertFalse(any(r.current for r in self.site.all_bench_runs.values()))

    def test_every_run_and_device_keeps_its_page(self):
        for run_id in range(1, 6):
            for prefix in ("/run/", "/cert/", "/embed/run/"):
                self.assertIn(f"{prefix}{run_id}/index.html".lstrip("/"), self.builder.files)
        for slug in ("honor-600-pro", "samsung-galaxy-s25-fe", "vivo-x300-pro", "samsung-galaxy-a55"):
            self.assertIn(f"devices/{slug}/index.html", self.builder.files)

    def test_the_slugs_do_not_move(self):
        today = load_site(FakeApi(V4, 4, STRESS, STAMPS), ROOT / "data", log=lambda *a: None)
        self.assertEqual(sorted(d.slug for d in today.devices), sorted(d.slug for d in self.site.devices))

    def test_a_device_off_the_board_keeps_its_slug_against_a_namesake(self):
        # the same name on another platform, with a stress run only: the benchmarked one keeps the plain slug
        stress = [{**STRESS[0], "deviceManufacturer": "HONOR", "deviceModel": "HONOR 600 Pro", "os": 1}]
        for server_version in (4, 5):
            site = load_site(FakeApi(V4, server_version, stress, STAMPS), ROOT / "data", log=lambda *a: None)
            slugs = {d.key: d.slug for d in site.devices}
            self.assertEqual("honor-600-pro", slugs["2:honor 600 pro"], server_version)
            self.assertEqual("honor-600-pro-ios", slugs["1:honor 600 pro"], server_version)

    def test_home_and_leaderboard_say_the_board_restarted(self):
        since = self.site.version_since
        line = f"The board restarted with scoring version 5 on {since.day} {since.strftime('%b %Y')}."
        self.assertIn(line, self.page("/"))
        board = self.page("/leaderboard/")
        self.assertIn(line, board)
        self.assertNotIn('class="lb-row"', board)
        self.assertIn("Why did the leaderboard start over?", self.page("/"))
        self.assertTrue(any(f.startswith("og/leaderboard.") for f in self.builder.files))

    def test_run_and_certificate_say_which_scoring(self):
        run = self.page("/run/5/")
        self.assertIn("Scored with the previous scoring, version 4.", run)
        self.assertIn("a Snapdragon 8 Elite phone as it ships, in Balanced mode", run)
        self.assertNotIn("Can your phone beat", run)
        cert = self.page("/cert/5/")
        self.assertIn("scoring version 4", cert)
        self.assertNotIn("Fewer than three verified runs", cert)

    def test_off_board_device(self):
        page = self.page("/devices/samsung-galaxy-s25-fe/")
        self.assertIn("Not on the current board yet.", page)
        self.assertIn("Earlier runs, scored with version 4", page)
        self.assertIn('href="/run/3/"', page)
        self.assertNotIn('value="samsung-galaxy-s25-fe"', self.page("/compare/"))
        self.assertIn('value="samsung-galaxy-a55"', self.page("/compare/"))
        self.assertNotIn("Not on the current board yet", self.page("/devices/samsung-galaxy-a55/"))

    def test_the_note_still_builds_without_the_board_clause(self):
        note = self.page("/notes/power-mode-doubles-cpu-score/")
        self.assertIn("5,900 points against 7,200", note)
        self.assertNotIn("now stands at", note)
        self.assertIn("One setting doubled this phone's CPU score", self.page("/"))


class MixedTest(WorldTest):
    """Version 5 runs on the board, version 4 runs kept beside them."""
    details = V4 + V5
    server_version = 5

    def test_the_board_is_version_5_only(self):
        self.assertEqual(5, self.site.version)
        self.assertEqual(["HONOR 600 Pro", "Vivo X300 Pro", "Google Pixel 10 Pro"], [d.name for d in self.site.board])
        for d in self.site.board:
            self.assertEqual(5, d.best.benchmark_version)
            self.assertTrue(all(r.benchmark_version == 5 for r in d.bench_runs))
        honor = self.device("HONOR 600 Pro")
        self.assertEqual([10, 6], [r.id for r in honor.bench_runs])
        self.assertEqual([5, 4, 2], [r.id for r in honor.earlier_runs])
        self.assertIsNone(honor.health(self.site.all_bench_runs[4]))

    def test_comparisons_and_leaders_use_version_5(self):
        self.assertTrue(self.site.pairs)
        for p in self.site.pairs:
            self.assertTrue(p.a.has_bench and p.b.has_bench)
            self.assertEqual({5}, {p.a.best.benchmark_version, p.b.best.benchmark_version})
        cpu = next(l for l in self.site.section_leaders if l["key"] == "cpu")
        self.assertEqual(("HONOR 600 Pro", 6000), (cpu["device"].name, cpu["points"]))   # not run #5's 12,600
        board = self.page("/leaderboard/")
        self.assertNotIn("12,600", board)
        self.assertIn("Runs scored with version 4 stay on each device's page", board)

    def test_device_page_keeps_earlier_runs_apart(self):
        page = self.page("/devices/honor-600-pro/")
        current, _, earlier = page.partition("Earlier runs, scored with version 4")
        self.assertTrue(earlier)
        self.assertIn('href="/run/6/"', current)
        self.assertIn('href="/run/10/"', current)
        for run_id in (2, 4, 5):
            self.assertNotIn(f'href="/run/{run_id}/"', current)
            self.assertIn(f'href="/run/{run_id}/"', earlier)
        self.assertNotIn("Earlier runs", self.page("/devices/google-pixel-10-pro/"))

    def test_version_5_has_no_llm_section(self):
        run = self.page("/run/6/")
        self.assertIn("Five sections", run)
        self.assertNotIn("<span>LLM</span>", run)
        self.assertNotIn("tok/s", run)
        compare = self.page("/compare/honor-600-pro-vs-vivo-x300-pro/")
        self.assertNotIn("LLM", compare)
        board = self.page("/leaderboard/")
        self.assertNotIn("data-llm", board)
        self.assertNotIn('data-value="llm"', board)
        how = self.page("/how-it-works/")
        self.assertIn("The benchmark: five sections", how)
        # five sections × 7,200 = the 36,000 six sections × 6,000 gave
        self.assertIn("<b>7,200</b>", how)
        self.assertIn("<b>36,000</b>", how)
        self.assertIn("is worth 14,400", how)
        self.assertNotIn("LLM", how)
        # each run against its own version's reference: 7,200 for version 5, 6,000 for version 4
        self.assertIn("7,200 points:", run)
        self.assertIn("dashed: the 7,200 reference", run)
        self.assertIn("6,000 points:", self.page("/run/4/"))
        self.assertIn("Back then, 6,000 points per section", self.page("/run/4/"))
        self.assertNotIn("LLM", self.page("/"))
        self.assertNotIn("LLM", text(self.builder, "llms.txt"))

    def test_a_version_4_run_keeps_its_llm_section(self):
        run = self.page("/run/5/")
        self.assertIn("Six sections", run)
        llm = run[run.index("<span>LLM</span>"):]
        for title in ("Prompt (prefill)", "Reply (decode)", "Run"):
            self.assertIn(f"<h3><span>{title}</span>", llm)
        earlier = self.page("/devices/honor-600-pro/").partition("Earlier runs, scored with version 4")[2]
        self.assertIn(">LLM</th>", earlier)
        note = self.page("/notes/power-mode-doubles-cpu-score/")
        self.assertIn("<td>LLM</td>", note)

    def test_the_note_drops_the_board_clause_once_the_best_run_is_another(self):
        note = self.page("/notes/power-mode-doubles-cpu-score/")
        self.assertIn("5,900 points against 7,200", note)
        self.assertNotIn("now stands at", note)

    def test_terms_page(self):
        terms = self.page("/terms/")
        self.assertIn("https://ai.google.dev/gemma/terms", terms)
        self.assertIn("https://ai.google.dev/gemma/prohibited_use_policy", terms)
        self.assertIn("provided as is", terms)
        self.assertIn('href="/terms/"', self.page("/"))
        self.assertIn("https://example.test/terms/", text(self.builder, "sitemap.xml"))


if __name__ == "__main__":
    unittest.main()
