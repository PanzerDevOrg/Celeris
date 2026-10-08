"""Charts for docs/benchmarks.md from benchmarks/results (python3 charts.py <results> <out dir>)."""
import csv, json, sys
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

B = Path(sys.argv[1]); OUT = Path(sys.argv[2])
INK, MUTED, GRID = "#1f2430", "#6b7280", "#e5e7eb"
CEL, CEL2 = "#7c3aed", "#c4b5fd"
OTHERS = ["#0ea5e9", "#f59e0b", "#94a3b8", "#10b981"]
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11, "axes.edgecolor": GRID,
                     "axes.labelcolor": INK, "xtick.color": INK, "ytick.color": INK,
                     "axes.spines.top": False, "axes.spines.right": False})

def jmh(name):
    out = {}
    for r in json.load(open(B / name)):
        key = r["benchmark"].split(".")[-1]
        out[(key, tuple(sorted(r["params"].items())))] = r["primaryMetric"]["score"]
    return out

def finish(fig, ax, title, sub, path):
    ax.set_title(title, loc="left", fontsize=15, fontweight="bold", color=INK, pad=26)
    ax.text(0, 1.02, sub, transform=ax.transAxes, fontsize=10, color=MUTED)
    ax.grid(axis="x", color=GRID); ax.set_axisbelow(True)
    fig.tight_layout(); fig.savefig(OUT / path, dpi=160, facecolor="white"); plt.close(fig)

# ---- compression: MB/s for a 38 KiB chunk-like payload (bigger = faster)
c = jmh("compress-jni.json")
size = 38912
def mbs(bench): return size / c[(bench, (("size", str(size)),))] / 1.048576  # bytes/us -> MiB/s
rows = [("Celeris zstd", "celerisZstdCompress", "celerisZstdDecompress", CEL),
        ("zstd-jni 1.5.7", "zstdJniCompress", "zstdJniDecompress", OTHERS[0]),
        ("LZ4 (lz4-java)", "lz4Compress", "lz4Decompress", OTHERS[3]),
        ("Java Deflater lvl 1", "deflate1Compress", "inflateDecompress", OTHERS[2]),
        ("Java Deflater lvl 6", "deflate6Compress", "inflateDecompress", OTHERS[1])]
ratio = {"Celeris zstd": 5.51, "zstd-jni 1.5.7": 5.51, "LZ4 (lz4-java)": 3.88, "Java Deflater lvl 1": 5.07, "Java Deflater lvl 6": 5.83}
fig, axes = plt.subplots(1, 2, figsize=(11, 4.4), sharey=True)
for ax, idx, label in ((axes[0], 1, "Compress"), (axes[1], 2, "Decompress")):
    names = [r[0] for r in rows][::-1]; vals = [mbs(r[idx]) for r in rows][::-1]; cols = [r[3] for r in rows][::-1]
    bars = ax.barh(names, vals, color=cols, height=0.62)
    for b, v in zip(bars, vals):
        ax.text(b.get_width() + max(vals) * 0.015, b.get_y() + b.get_height() / 2, f"{v:,.0f}", va="center", fontsize=10, color=INK)
    ax.set_xlim(0, max(vals) * 1.18); ax.set_xlabel("MiB/s (higher is faster)")
    ax.set_title(label, loc="left", fontsize=12, color=INK)
    ax.grid(axis="x", color=GRID); ax.set_axisbelow(True)
axes[0].set_yticks(range(len(rows))); axes[0].set_yticklabels([f"{r[0]}  ·  {ratio[r[0]]:.1f}×" for r in rows][::-1])
fig.suptitle("Compression: 38 KiB chunk-like data, one thread", x=0.01, ha="left", fontsize=15, fontweight="bold", color=INK)
fig.text(0.01, 0.885, "Label: compression ratio (bigger = smaller output). JMH, JDK 21, 4 vCPU Xeon 2.1 GHz.", fontsize=10, color=MUTED)
fig.tight_layout(rect=(0, 0, 1, 0.88)); fig.savefig(OUT / "compression.png", dpi=160, facecolor="white"); plt.close(fig)

# ---- physics: microseconds per tick
p1 = jmh("physics-1t.json"); pm = jmh("physics-mt.json")
def ph(d, items, kernel): return d[("tick", (("items", str(items)), ("kernel", kernel)))]
labels = ["1,000 items", "10,000 items", "32,768 items", "32,768 items\n3 threads"]
java = [ph(p1, 1000, "java"), ph(p1, 10000, "java"), ph(p1, 32768, "java"), ph(pm, 32768, "java")]
nat = [ph(p1, 1000, "native"), ph(p1, 10000, "native"), ph(p1, 32768, "native"), ph(pm, 32768, "native")]
fig, ax = plt.subplots(figsize=(10, 4.4))
import numpy as np
y = np.arange(len(labels))[::-1]
ax.barh(y + 0.2, [v / 1000 for v in java], 0.38, color=OTHERS[2], label="Java, one item at a time (vanilla's rules)")
ax.barh(y - 0.2, [v / 1000 for v in nat], 0.38, color=CEL, label="Celeris native kernel (AVX2)")
for yy, j, n in zip(y, java, nat):
    ax.text(j / 1000 + 0.04, yy + 0.2, f"{j/1000:.2f} ms", va="center", fontsize=10, color=MUTED)
    ax.text(n / 1000 + 0.04, yy - 0.2, f"{n/1000:.2f} ms  ({j/n:.1f}× faster)", va="center", fontsize=10, color=INK, fontweight="bold")
ax.set_yticks(y); ax.set_yticklabels(labels); ax.set_xlabel("milliseconds per game tick (lower is faster; a tick is 50 ms)")
ax.set_xlim(0, max(java) / 1000 * 1.45); ax.legend(loc="lower right", frameon=False, fontsize=10)
finish(fig, ax, "Batch physics: dropped items per tick", "Gravity, drag, block collisions and ground friction in a random 48×32×48 world. JMH, JDK 21.", "physics.png")

# ---- queues: median million messages / s
def q(path):
    out = {}
    for r in csv.DictReader(open(B / path)):
        out[(r["queue"], int(r["producers"]))] = float(r["median_mops"])
    return out
after, before = q("queues-after.csv"), q("queues-before.csv")
series = [("Celeris MpscRingBuffer (0.2.3)", after, "Celeris MpscRingBuffer", CEL),
          ("Celeris MpscRingBuffer (0.2.2)", before, "Celeris MpscRingBuffer", CEL2),
          ("JCTools MpscArrayQueue", after, "JCTools MpscArrayQueue", OTHERS[0]),
          ("ArrayBlockingQueue (JDK)", after, "ArrayBlockingQueue", OTHERS[1]),
          ("ConcurrentLinkedQueue (JDK)", after, "ConcurrentLinkedQueue", OTHERS[2])]
fig, ax = plt.subplots(figsize=(10, 4.6))
x = np.arange(3); w = 0.16
for i, (label, src, key, col) in enumerate(series):
    vals = [src[(key, p)] for p in (1, 2, 3)]
    bars = ax.bar(x + (i - 2) * w, vals, w, color=col, label=label)
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v + 0.6, f"{v:.0f}" if v >= 10 else f"{v:.1f}", ha="center", fontsize=8.5, color=INK)
ax.set_xticks(x); ax.set_xticklabels(["1 worker thread", "2 worker threads", "3 worker threads"])
ax.set_ylabel("million results / s (median, higher is faster)"); ax.legend(frameon=False, fontsize=9.5)
ax.grid(axis="y", color=GRID); ax.set_axisbelow(True)
ax.set_title("Handing results to the game thread", loc="left", fontsize=15, fontweight="bold", color=INK, pad=26)
ax.text(0, 1.02, "Workers send 8-byte results to one consumer; 20 M per round, median of 9. JDK 21, 4 vCPU.", transform=ax.transAxes, fontsize=10, color=MUTED)
fig.tight_layout(); fig.savefig(OUT / "queues.png", dpi=160, facecolor="white"); plt.close(fig)
# ---- broadphase on item piles: before (full count) vs now (stop at capacity), log scale
bp = jmh("broadphase.json")
def bpv(items, kernel, scan): return bp[("broadphase", (("items", str(items)), ("kernel", kernel), ("scan", scan)))]
groups = [(4096, "java"), (4096, "native"), (16384, "java"), (16384, "native")]
labels = [f"{n:,} items · {'Java' if k == 'java' else 'native'}" for n, k in groups]
full = [bpv(n, k, "full") / 1000 for n, k in groups]
stop = [bpv(n, k, "stop") / 1000 for n, k in groups]
fig, ax = plt.subplots(figsize=(10, 4.9))
y = np.arange(len(groups))[::-1]
ax.barh(y + 0.2, full, 0.38, color=OTHERS[2], label="Before: every overlapping pair counted")
ax.barh(y - 0.2, stop, 0.38, color=CEL, label="0.2.3: stops once the pair buffer is full")
ax.set_xscale("log")
for yy, f_, s_ in zip(y, full, stop):
    ax.text(f_ * 1.12, yy + 0.2, f"{f_:,.1f} ms", va="center", fontsize=10, color=MUTED)
    ax.text(s_ * 1.12, yy - 0.2, f"{s_:.2f} ms  ({f_ / s_:,.0f}× faster)", va="center", fontsize=10, color=INK, fontweight="bold")
ax.set_yticks(y); ax.set_yticklabels(labels); ax.set_xlabel("milliseconds per call, log scale (lower is faster)")
ax.set_xlim(min(stop) / 2, max(full) * 8)
ax.legend(loc="upper left", bbox_to_anchor=(0, -0.2), ncol=2, frameon=False, fontsize=10)
finish(fig, ax, "Finding touching items in a pile", "n items dropped into one block (~n²/2 overlapping pairs), pair buffer of n. JMH, JDK 21.", "broadphase.png")
# ---- in-game: server tick time with N dropped items (panzer-build-logic perf-test, mspt-<mc>.json)
games = sorted(B.glob("mspt-*.json"))
if games:
    fig, axes = plt.subplots(1, len(games), figsize=(5.4 * len(games), 4.6), squeeze=False)
    for ax, path in zip(axes[0], games):
        data = json.load(open(path))
        res = {r["config"]: {run["items"]: run for run in r.get("runs", [])} for r in data["results"]}
        vanilla = next(k for k in res if "no mods" in k)
        velox = next(k for k in res if "native" in k)
        counts = sorted(res[vanilla])
        x = np.arange(len(counts)); w = 0.38
        van = [res[vanilla][n]["resting_ms"] for n in counts]
        vel = [res[velox][n]["resting_ms"] for n in counts]
        ax.bar(x - w / 2, van, w, color=OTHERS[2], label="Vanilla (NeoForge, no mods)")
        ax.bar(x + w / 2, vel, w, color=CEL, label="Velox + Celeris native kernel")
        for xx, a, b in zip(x, van, vel):
            ax.text(xx - w / 2, a * 1.02, f"{a:.1f}", ha="center", fontsize=9, color=MUTED)
            ax.text(xx + w / 2, b * 1.02, f"{b:.1f}\n{a / b:.1f}×", ha="center", fontsize=9, color=INK, fontweight="bold")
        ax.axhline(50, color="#ef4444", lw=1, ls="--"); ax.text(len(counts) - 0.5, 51, "50 ms = 20 TPS", ha="right", fontsize=8.5, color="#ef4444")
        ax.set_xticks(x); ax.set_xticklabels([f"{n:,} items" for n in counts])
        ax.set_title(f"Minecraft {data['minecraft']}", loc="left", fontsize=12, color=INK)
        ax.set_ylabel("ms per server tick (lower is faster)"); ax.grid(axis="y", color=GRID); ax.set_axisbelow(True)
    axes[0][0].legend(frameon=False, fontsize=9.5, loc="upper left")
    fig.suptitle("Real server: tick time with items resting on the ground", x=0.01, ha="left", fontsize=15, fontweight="bold", color=INK)
    fig.text(0.01, 0.885, "Dedicated NeoForge server, vanilla tick sprint, GitHub Actions runner (4 vCPU).", fontsize=10, color=MUTED)
    fig.tight_layout(rect=(0, 0, 1, 0.88)); fig.savefig(OUT / "ingame.png", dpi=160, facecolor="white"); plt.close(fig)
print("ok")
