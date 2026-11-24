"""The words that don't come from the API: what each test is, and the questions people ask.

The test descriptions are the apps' own (the benchmark list on the Diagnostics screen), so the site and the apps say
the same thing. Change them there first.
"""
from __future__ import annotations

# key → what the section measures, and its tests as the apps list them: (tag, name, what it does)
CATALOG = {
    "cpu": {
        "blurb": "Raw processor work on one core, then on all of them.",
        "tests": [
            ("C1", "Math", "Matrix multiply, FFT, Mandelbrot, prime sieve, RSA-1024, n-body"),
            ("C2", "JSON & XML", "Working through documents in the two formats apps trade data in"),
            ("C3", "Compression & crypto", "zlib, AES-256-GCM, SHA-256"),
            ("C4", "Multi-core", "Matrix multiply, FFT, Mandelbrot, JSON, zlib and SHA-256 on every core"),
        ],
    },
    "mem": {
        "blurb": "How fast data moves: in RAM, on storage, and through a database.",
        "tests": [
            ("M1", "RAM", "Read, write and copy on every core, plus random-access latency"),
            ("M2", "Storage", "Sequential and random 4 KB reads and writes on a temp file, deleted after"),
            ("M3", "Database", "SQLite used like a messaging app: save, mark read, open chats"),
        ],
    },
    "gpu": {
        "blurb": "Three real-time 3D scenes, rendered off the leash.",
        "tests": [
            ("G1", "Lake Valley", "A forest lake in daylight: sun shadows, water reflections"),
            ("G2", "Neon City", "A rainy city at night: 800+ lights, fog, wet streets"),
            ("G3", "Neon City RT", "The same city with ray-traced reflections and shadows, on devices that support it"),
        ],
    },
    "ux": {
        "blurb": "The things you actually do all day, measured instead of guessed.",
        "tests": [
            ("U1", "Web browsing", "Page layout, Canvas, CSS and SVG animation, and JavaScript in the system WebView"),
            ("U2", "Web scrolling", "An endless social feed, built as it scrolls ever faster, with no dropped frames allowed"),
            ("U3", "Photo gallery", "12 MP JPEG, WebP and AVIF photos decoded and shown in a web page"),
            ("U4", "Photo editing", "GPU edits of a 12 MP photo: fisheye, blur, bokeh, sharpen, colour grade"),
            ("U5", "Video playback", "4K60 H.264, HEVC 10-bit, VP9, AV1 and 8K30 HEVC and AV1, decoded flat out"),
            ("U6", "Video export", "A 4K edit exported to H.264, HEVC and AV1 (plus 8K HEVC), with the quality checked"),
            ("U7", "QR scanning", "32 camera frames decoded with ZXing"),
        ],
    },
    "ai": {
        "blurb": "Six open models, each on the CPU and the GPU.",
        "tests": [
            ("A1", "Image classification", "MobileNet-v2 · 224×224"),
            ("A2", "Object detection", "YOLOX · 640×640"),
            ("A3", "Street segmentation", "FFNet-40S · Cityscapes · 2048×1024"),
            ("A4", "Super resolution", "Real-ESRGAN ×4 · 128 → 512"),
            ("A5", "Depth estimation", "MiDaS v2 · 256×256"),
            ("A6", "Sound recognition", "YAMNet · 0.96 s of audio"),
        ],
        "note": "The models are open and 8-bit. The CPU and the GPU count half each. The NPU isn't scored: no way to run "
                "models on it reaches every device, and a device shouldn't lose points because its maker's tools aren't public.",
    },
    "llm": {
        "blurb": "Google's Gemma 3 1B language model running on the device itself, on its CPU and its GPU.",
        "tests": [
            ("L1", "LLM on the CPU", "Gemma 3 1B, 4-bit, on llama.cpp: a 512-token prompt in, then a 32-token reply token by token"),
            ("L2", "LLM on the GPU", "The same model with every layer on the GPU"),
        ],
        "note": "Each engine is scored against the reference device's same engine and counts half; an engine that can't "
                "run the model scores 0 there. Nothing is downloaded. The NPU isn't scored yet: no way to run a language "
                "model on it reaches every chip, and a device shouldn't lose points because its maker's tools aren't "
                "public. Gemma is provided under and subject to the Gemma Terms of Use.",
    },
}

# What 6,000 points per section stood for, by scoring version (BenchmarkScoring.CurrentVersion on the server). A new
# version restarts the board; runs of an earlier one stay on their pages, measured against their own reference.
REFERENCES = {
    4: "a Snapdragon 8 Elite phone as it ships, in Balanced mode",
    5: "a Snapdragon 8 Elite phone (HONOR 600 Pro) in its Performance power mode",
}


def reference(version: int) -> str:
    """The reference of a scoring version, or of the latest one before it this file knows."""
    known = [v for v in REFERENCES if v <= version] or [min(REFERENCES)]
    return REFERENCES[max(known)]


# The stress test's phases, as the apps' timeline names them.
PHASES = [
    ("Pre-flight", "Checks the device is fit to test: not charging, not in a power-saving mode, not already hot."),
    ("Cooldown", "Waits until the device has shed the heat it arrived with."),
    ("Warm-up", "Brings the workers up to speed so the first samples aren't a cold start."),
    ("Calibrate", "Measures the baseline: what this device does at full speed, before heat is a factor."),
    ("Measured", "Holds full load for 5, 15 or 30 minutes and records throughput at least once a second."),
    ("Verdict", "Reports how much of the baseline you kept, when it dipped, and whether the run counts as clean."),
]


def faq(site) -> list[tuple[str, str]]:
    """Questions and answers for the home page (also given to search engines as structured data). Plain text only."""
    qa = [
        ("Is ThermoHammer free?",
         "Yes. It is free on Google Play, the App Store and the Microsoft Store, with no account and no ads."),
        ("Is a stress test safe for my phone?",
         "Yes. The test runs ordinary calculations and rendering at full load, the kind of work a long game or a video "
         "export does. The operating system stays in charge of every temperature limit; ThermoHammer only measures what "
         "the device does to protect itself. It will get warm, which is the point, and you can stop at any time."),
        ("What does the score mean?",
         f"Each of the six sections is scored against one reference, {reference(site.version)}: it gets 6,000 points "
         "per section and 36,000 in total. A section score of 12,000 means about twice that phone's measured speed in "
         "that section. The total is the six sections added up."),
        ("What is stability?",
         "The share of its full speed a device delivers over a sustained, full-load run. 95% means it barely slowed "
         "down. 50% means half of the speed was gone."),
        ("What is GIPS?",
         "Giga-instructions per second: billions of instructions the processor completes each second. It is the unit of "
         "the stress test's throughput curve."),
        ("Why isn't my result on the leaderboard?",
         "Only verified runs are listed: the device started cool, was unplugged, was not in a power-saving mode and had "
         "nothing else keeping it busy. A run that cannot be sent is saved on the device so you can submit it later. The "
         "board shows each device's best verified run."),
        ("Which devices does it run on?",
         "Android phones and tablets (Android 7 and later), iPhone and iPad (iOS 16 and later) and Windows 10 and 11 PCs "
         "run the same tests and share one leaderboard; Windows PCs are labelled Windows. On a PC, the graphics tests "
         "need a Direct3D 11 graphics card."),
        ("What data does it send?",
         "No name, no email, no account. Submitting a result sends the measurements, the device model, its chip and its "
         "OS version to the leaderboard, and that is what you see on this site."),
        ("Why does my phone score differently from the same model on the board?",
         "Power mode, temperature, an OS update or a busy background can each move a score, sometimes a lot. The board "
         "keeps the best verified run per model; in the app, Phone Health compares your runs with other units of the same "
         "model and with your own earlier runs."),
    ]
    if site.earlier_versions:       # the board restarted on a new scoring version: say why, next to what a score means
        qa.insert(3, ("Why did the leaderboard start over?",
                      f"Scoring version {site.version} measures every device against a new reference, "
                      f"{reference(site.version)}, so its points can't be compared with earlier ones and the board "
                      f"restarted with it. Runs scored before stay on their device's page, marked with their version. "
                      f"Run the benchmark again to put your device on today's board."))
    return qa

for _section in CATALOG.values():
    _section.setdefault("note", "")
