#!/usr/bin/env python3
"""Summarise navigation bench runs (run/navbench/<name>/w*/navbench.json) side by side.

    tools/navbench-compare.py <name> [<other name> ...]

Per run: trips arrived, by tag; median ticks and median walked/straight ratio over trips that
arrived in every run compared (so the medians cover the same trips); median search nodes and
search ticks per trip; stuck events and jumps per trip; AI ms per tick (mean over trips, and the
worst tick); server tick ms (mean, and the worst tick).
"""
import json
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "run" / "navbench"


def load(name):
    trips = {}
    for f in sorted((ROOT / name).glob("w*/navbench.json")):
        for t in json.loads(f.read_text())["trips"]:
            trips[t["index"]] = t
    return trips


def med(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else float("nan")


def mean(values):
    values = [v for v in values if v is not None]
    return statistics.fmean(values) if values else float("nan")


def top(values):
    values = [v for v in values if v is not None]
    return max(values) if values else float("nan")


def main(names):
    runs = {n: load(n) for n in names}
    for n, trips in runs.items():
        if not trips:
            print(f"no navbench.json under run/navbench/{n}/", file=sys.stderr)
    common = set.intersection(*(set(t.keys()) for t in runs.values())) if runs else set()
    arrived_all = {i for i in common if all(runs[n][i]["outcome"] == "arrived" for n in names)}
    w = 34
    print("metric".ljust(w) + "".join(f" | {n[:20]:>20}" for n in names))
    rows = []
    rows.append(("trips scored", [f"{len(runs[n])}" for n in names]))
    rows.append(("arrived", [f"{sum(1 for t in runs[n].values() if t['outcome'] == 'arrived')}/{len(runs[n])}" for n in names]))
    for tag in ("surface", "down", "up", "cave"):
        rows.append((f"  arrived ({tag})", [
            f"{sum(1 for t in runs[n].values() if t['outcome'] == 'arrived' and t['pair']['tag'] == tag)}/"
            f"{sum(1 for t in runs[n].values() if t['pair']['tag'] == tag)}" for n in names]))
    rows.append((f"median ticks (arrived in all, n={len(arrived_all)})",
                 [f"{med([runs[n][i]['ticks'] for i in arrived_all]):.0f}" for n in names]))
    rows.append(("median walked/straight (same trips)",
                 [f"{med([runs[n][i]['walked'] / max(1, runs[n][i]['straight']) for i in arrived_all]):.2f}" for n in names]))
    rows.append(("median search nodes per trip", [f"{med([t['nodes'] for t in runs[n].values()]):.0f}" for n in names]))
    rows.append(("median ticks waiting on searches", [f"{med([t['searchTicks'] for t in runs[n].values()]):.0f}" for n in names]))
    rows.append(("searches per trip (mean)", [f"{mean([t['searches'] for t in runs[n].values()]):.1f}" for n in names]))
    rows.append(("stuck events per trip (mean)", [f"{mean([t['stuck'] for t in runs[n].values()]):.2f}" for n in names]))
    rows.append(("jumps per trip (mean)", [f"{mean([t['jumps'] for t in runs[n].values()]):.1f}" for n in names]))
    rows.append(("AI ms per tick (mean of trips)", [f"{mean([t['aiMsPerTick'] for t in runs[n].values()]):.2f}" for n in names]))
    rows.append(("AI ms, worst tick", [f"{top([t['aiMsMax'] for t in runs[n].values()]):.1f}" for n in names]))
    rows.append(("server ms per tick (mean of trips)", [f"{mean([t['serverMsAvg'] for t in runs[n].values()]):.1f}" for n in names]))
    rows.append(("server ms, worst tick", [f"{top([t['serverMsMax'] for t in runs[n].values()]):.1f}" for n in names]))
    for label, cells in rows:
        print(label.ljust(w) + "".join(f" | {c:>20}" for c in cells))
    if len(names) > 1:
        print()
        print("trips that ended differently:")
        for i in sorted(common):
            outs = [runs[n][i]["outcome"].split(":")[0] for n in names]
            if len(set(outs)) > 1:
                t = runs[names[0]][i]
                print(f"  #{i} {t['pair']['tag']} {t['pair']['start']} -> {t['pair']['goal']}: " + ", ".join(f"{n}={o}" for n, o in zip(names, outs)))


if __name__ == "__main__":
    main(sys.argv[1:])
