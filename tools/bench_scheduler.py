import argparse
import json
import statistics
import os
import signal
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_scheduler import CLIENT, MODEL, PY, ROOT, SCHEDULER, WORKER, Proc  # noqa: E402

# workers x threads setups that share the 4 cores in different ways
CONFIGS = [(1, 4), (1, 6), (2, 2), (2, 3), (3, 2), (4, 1)]
# one or two pairs of glasses at the same time
LOADS = [1, 2]
SCHED_PORT, BASE_PORT = 50651, 50661
OUT = os.path.join(ROOT, "bench")


# nearest rank percentile
def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    return s[max(0, min(len(s) - 1, -(-p * len(s) // 100) - 1))]


# each setup runs a few times and the median is kept
def run_config(workers, threads, seconds):
    sched = Proc([SCHEDULER, "--port", str(SCHED_PORT), "--deadline-ms", "1000", "--report-seconds", "0",
                  "--session-max-in-flight", "2", "--redis", "none"], "scheduler")
    if not sched.wait_for("READY"):
        sys.exit("scheduler did not start")
    procs = []
    for i in range(1, workers + 1):
        w = Proc([WORKER, "--model", MODEL, "--port", str(BASE_PORT + i), "--worker-id", f"worker-{i}",
                  "--threads", str(threads), "--scheduler", f"127.0.0.1:{SCHED_PORT}"], f"worker-{i}")
        if not w.wait_for("READY"):
            sys.exit(f"worker-{i} did not start")
        procs.append(w)
    for i in range(1, workers + 1):
        sched.wait_for(f"worker registered: worker-{i}", 10)

    def clients(n, secs, raw=True):
        cmd = [PY, CLIENT, "--target", f"127.0.0.1:{SCHED_PORT}", "video", "--seconds", str(secs),
               "--fps", "8", "--max-in-flight", "2", "--json"] + (["--raw"] if raw else [])
        ps = [subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for _ in range(n)]
        return [json.loads(p.communicate(timeout=secs + 120)[0]) for p in ps]

    # short warmup run that is not measured
    clients(1, 3, raw=False)

    rows = []
    for load in LOADS:
        outs = clients(load, seconds)
        lat = [v for o in outs for v in o["ok_latencies_ms"]]
        answered = sum(o["answered"] for o in outs)
        ok = sum(o["statuses"].get("OK", 0) for o in outs)
        elapsed = max(o["seconds"] for o in outs)
        rows.append({
            "workers": workers, "threads": threads, "config": f"{workers}x{threads}", "sessions": load,
            "ok_fps": round(ok / elapsed, 2), "answered": answered,
            "dropped_on_client": sum(o["dropped_on_client"] for o in outs),
            "p50": pct(lat, 50), "p95": pct(lat, 95), "p99": pct(lat, 99),
            # share of frames that made the 300 ms deadline
            "under_300": round(100 * sum(1 for v in lat if v <= 300) / max(1, answered), 1),
            "under_400": round(100 * sum(1 for v in lat if v <= 400) / max(1, answered), 1),
            "statuses": {k: sum(o["statuses"].get(k, 0) for o in outs)
                         for k in {k for o in outs for k in o["statuses"]}},
        })
    for w in procs:
        w.stop(signal.SIGINT)
    sched.stop(signal.SIGTERM)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=15)
    ap.add_argument("--repeats", type=int, default=2)
    ap.add_argument("--cooldown", type=float, default=5)
    ap.add_argument("--summarize-only", action="store_true", help="rebuild summary + graph from bench/results.json")
    args = ap.parse_args()
    os.chdir(ROOT)
    os.makedirs(OUT, exist_ok=True)

    all_rows = []
    if args.summarize_only:
        with open(os.path.join(OUT, "results.json")) as f:
            all_rows = json.load(f)
    for rep in range(0 if args.summarize_only else args.repeats):
        # reverse the order every other repeat so heat and throttling hit all setups the same
        order = CONFIGS if rep % 2 == 0 else list(reversed(CONFIGS))
        for workers, threads in order:
            t0 = time.time()
            rows = run_config(workers, threads, args.seconds)
            for r in rows:
                r["repeat"] = rep + 1
                print(f"  repeat {rep + 1}  {r['config']:>4}  {r['sessions']} session(s): {r['ok_fps']:5.2f} fps OK | "
                      f"p50 {r['p50']} p95 {r['p95']} p99 {r['p99']} ms | <=300 ms: {r['under_300']}% "
                      f"| <=400 ms: {r['under_400']}%   ({time.time() - t0:.0f} s)", flush=True)
            all_rows.extend(rows)
            # let the cpu cool down between two setups
            time.sleep(args.cooldown)

    if not args.summarize_only:
        with open(os.path.join(OUT, "results.json"), "w") as f:
            json.dump(all_rows, f, indent=1)

    def med(vals):
        vals = [v for v in vals if v is not None]
        return round(statistics.median(vals), 1) if vals else None

    summary = []
    for workers, threads in CONFIGS:
        for load in LOADS:
            rs = [r for r in all_rows if r["workers"] == workers and r["threads"] == threads and r["sessions"] == load]
            summary.append({"config": f"{workers}x{threads}", "sessions": load,
                            **{k: med([r[k] for r in rs]) for k in ("ok_fps", "p50", "p95", "p99", "under_300", "under_400")},
                            "ok_fps_range": f"{min(r['ok_fps'] for r in rs)}-{max(r['ok_fps'] for r in rs)}",
                            "p95_range": f"{min(r['p95'] for r in rs)}-{max(r['p95'] for r in rs)}"})

    lines = ["| workers x threads | sessions | OK fps (median, range) | p50 | p95 (median, range) | p99 | frames <= 300 ms | <= 400 ms |",
             "|---|---|---|---|---|---|---|---|"]
    for s in summary:
        lines.append(f"| {s['config']} | {s['sessions']} | {s['ok_fps']} ({s['ok_fps_range']}) | {s['p50']} | "
                     f"{s['p95']} ({s['p95_range']}) | {s['p99']} | {s['under_300']}% | {s['under_400']}% |")
    table = "\n".join(lines)
    with open(os.path.join(OUT, "summary.md"), "w") as f:
        f.write("# scheduler benchmark (median of repeats)\n\n" + table + "\n")
    print("\n" + table)

    import matplotlib
    # draws straight to a file without any window
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    labels = [f"{w}x{t}" for w, t in CONFIGS]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 4.5))
    for load, marker in zip(LOADS, ("o", "s")):
        rows = [s for s in summary if s["sessions"] == load]
        ax1.plot(labels, [r["ok_fps"] for r in rows], marker=marker, label=f"{load} session(s)")
        ax2.plot(labels, [r["p95"] for r in rows], marker=marker, label=f"p95, {load} session(s)")
        ax2.plot(labels, [r["p50"] for r in rows], marker=marker, linestyle="--", alpha=0.6,
                 label=f"p50, {load} session(s)")
    ax2.axhline(300, color="red", linewidth=1, label="300 ms deadline")
    ax1.set_title("OK throughput (frames/s)")
    ax2.set_title("Latency on the client (ms)")
    for ax in (ax1, ax2):
        ax.set_xlabel("workers x threads per worker")
        ax.grid(alpha=0.3)
        ax.legend(fontsize=8)
    fig.suptitle("i7-8550U (4 cores / 8 threads), YOLOv8n 640, 8 fps per session, max 2 in flight")
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "throughput.png"), dpi=110)
    print(f"\nwrote {OUT}/results.json, summary.md, throughput.png")


if __name__ == "__main__":
    main()
