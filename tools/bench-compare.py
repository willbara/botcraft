#!/usr/bin/env python3
"""Summarise bench runs (run/bench/<name>/t*/bench.json) side by side.

    tools/bench-compare.py <name> [<other name> ...]

For each milestone: how many trials reached it and the median time to reach it, then the
medians of what happened along the way (stuck events, failed searches, nodes, failed jobs,
deaths). Times are minutes:seconds of game time from the start.
"""
import json
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "run" / "bench"
ORDER = ["log", "table", "wooden_pickaxe", "stone", "stone_pickaxe", "coal", "furnace", "raw_iron", "iron_ingot",
         "iron_pickaxe", "deep", "diamond", "diamond_pickaxe"]
TOTALS = ["stuck", "navFails", "searches", "escapeSearches", "nodes", "jobsFailed", "deaths", "interrupts", "walked"]


def load(name):
    runs = []
    for f in sorted((ROOT / name).glob("t*/bench.json")):
        runs.append(json.loads(f.read_text()))
    return runs


def clock(ticks):
    if ticks is None:
        return "-"
    s = int(ticks) // 20
    return f"{s // 60}:{s % 60:02d}"


def median(values):
    return statistics.median(values) if values else None


def main(names):
    data = {n: load(n) for n in names}
    for n, runs in data.items():
        if not runs:
            print(f"no bench.json under run/bench/{n}/", file=sys.stderr)
    w = 16
    print("milestone".ljust(w) + "".join(f" | {n[:22]:>22}" for n in names))
    keys = [k for k in ORDER if any(k in r["reached"] for runs in data.values() for r in runs)]
    for k in keys:
        cells = []
        for n in names:
            runs = data[n]
            times = [r["reached"][k] for r in runs if k in r["reached"]]
            cells.append(f"{len(times)}/{len(runs)} {clock(median(times)):>7}")
        print(k.ljust(w) + "".join(f" | {c:>22}" for c in cells))
    print()
    print("median per trial".ljust(w) + "".join(f" | {n[:22]:>22}" for n in names))
    for t in TOTALS + ["reorders"]:
        cells = []
        for n in names:
            vals = [r["totals"].get(t, 0) if t != "reorders" else r.get("reorders", 0) for r in data[n]]
            m = median(vals)
            cells.append("-" if m is None else f"{m:g}")
        print(t.ljust(w) + "".join(f" | {c:>22}" for c in cells))
    print()
    for n in names:
        for r in data[n]:
            status = "done" if r["done"] else "NOT done"
            last = max(r["reached"], key=lambda k: r["reached"][k]) if r["reached"] else "nothing"
            print(f"{n} t{r['trial']}: {status} in {clock(r['ticks'])}, last milestone {last}, start {r['start']}, "
                  f"deaths {r['totals']['deaths']}, failures {sum(r['failReasons'].values()) if r['failReasons'] else 0}")


if __name__ == "__main__":
    main(sys.argv[1:] or sorted(p.name for p in ROOT.iterdir() if p.is_dir()))
