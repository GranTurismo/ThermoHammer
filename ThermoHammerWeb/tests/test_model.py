"""Unit tests for the site model's derived numbers. Run: .venv/bin/python -m unittest discover -s tests"""
import unittest
from datetime import datetime, timezone

from thweb.model import BenchRun, Device


def run(run_id, total, os_version, day, build=""):
    return BenchRun(id=run_id, created=datetime(2026, 11, day, tzinfo=timezone.utc), app_version="2.1.0",
                    os_version=os_version, os_build=build, total=total,
                    scores={"cpu": total // 6, "mem": total // 6}, sections=[])


def device(runs, stats=None, os_id=2):
    d = Device(key="2:test", slug="test", name="Test Phone", alias="", manufacturer="Test", os_id=os_id)
    d.bench_runs = runs
    d.stats = stats
    for r in runs:
        r.device = d
    return d


class HealthTest(unittest.TestCase):
    def stats(self, median, runs=5):
        return {"runs": runs, "total": {"median": median}, "sections": {"cpu": 4000, "mem": 0}}

    def test_thresholds_follow_the_apps(self):
        r = run(1, 30000, "16", 1)
        for median, verdict in ((28000, "faster"), (30500, "on-par"), (33000, "slower"), (40000, "much-slower")):
            h = device([r], self.stats(median)).health(r)
            self.assertEqual(verdict, h["verdict"], median)
        self.assertAlmostEqual(75.0, device([r], self.stats(40000)).health(r)["percent"])

    def test_too_few_runs_says_nothing(self):
        r = run(1, 30000, "16", 1)
        self.assertIsNone(device([r], self.stats(30000, runs=2)).health(r))
        self.assertIsNone(device([r], None).health(r))

    def test_section_against_its_median_and_missing_median(self):
        r = run(1, 24000, "16", 1)
        h = device([r], self.stats(24000)).health(r)
        cpu = next(s for s in h["sections"] if s["key"] == "cpu")
        mem = next(s for s in h["sections"] if s["key"] == "mem")
        self.assertAlmostEqual(1.0, cpu["vs"])
        self.assertIsNone(mem["vs"])           # a median of 0 is "unknown", not a division


class OsHistoryTest(unittest.TestCase):
    def test_one_os_version_has_no_history(self):
        self.assertEqual([], device([run(1, 30000, "16", 1), run(2, 31000, "16", 2)]).os_history)

    def test_change_between_versions_oldest_first(self):
        runs = [run(4, 27000, "17", 9, "CP1"), run(3, 27600, "17", 8, "CP1"),
                run(2, 30000, "16", 2, "BP2"), run(1, 30400, "16", 1, "BP1")]
        hist = device(runs).os_history
        self.assertEqual(["16", "17"], [g["os_version"] for g in hist])
        self.assertEqual(30200, hist[0]["median"])
        self.assertAlmostEqual(27300 / 30200 - 1, hist[1]["change"])
        self.assertTrue(hist[1]["solid"])
        self.assertEqual(["BP1", "BP2"], hist[0]["builds"])
        self.assertNotIn("change", hist[0])

    def test_one_run_on_a_side_is_only_a_hint(self):
        hist = device([run(2, 27000, "17", 9), run(1, 30000, "16", 1), run(3, 30200, "16", 2)]).os_history
        self.assertFalse(hist[1]["solid"])


class NounTest(unittest.TestCase):
    def test_noun_by_platform(self):
        self.assertEqual("phone", device([], os_id=2).noun)
        self.assertEqual("phone", device([], os_id=1).noun)
        self.assertEqual("Mac", device([], os_id=3).noun)
        self.assertEqual("PC", device([], os_id=4).noun)


if __name__ == "__main__":
    unittest.main()
