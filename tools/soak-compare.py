#!/usr/bin/env python3
"""Tabulate the key numbers of several soak reports (run/ab/<name>/soak-report.md) side by side.

    tools/soak-compare.py base16a base16b new16a ...
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "run" / "ab"


def section(text, title):
    m = re.search(r"^## " + re.escape(title) + r"\n\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not m or m.group(1).strip() == "None.":
        return {}
    out = {}
    for line in m.group(1).splitlines():
        lm = re.match(r"- (\d+) × (.*)", line)
        if lm:
            out[lm.group(2)] = int(lm.group(1))
    return out


def metrics(name):
    f = ROOT / name / "soak-report.md"
    if not f.exists():
        return None
    t = f.read_text()
    g = lambda pat, cast=float: cast(re.search(pat, t, re.M).group(1)) if re.search(pat, t, re.M) else None
    m = {}
    comps = g(r"^(\d+) companions", int)
    mins = g(r"free mode, (\d+) minutes", int)
    hours = comps * mins / 60
    m["companions"] = comps
    m["deaths"] = g(r"\| Deaths \| (\d+)", int)
    m["stalls"] = g(r"\| Stalls \(watchdog\) \| (\d+)", int)
    m["jobs ok"] = g(r"\| Jobs finished / failed \| (\d+)", int)
    m["jobs failed"] = g(r"\| Jobs finished / failed \| \d+ / (\d+)", int)
    m["AI ms/tick"] = g(r"avg ([\d.]+) ms")
    m["AI max ms"] = g(r"max ([\d.]+) ms")
    m["budget ms"] = g(r"AI budget per tick \| avg ([\d.]+)")
    m["server ms"] = g(r"whole server, smoothed\) \| avg ([\d.]+)")
    m["server >50ms"] = g(r"over 50 ms on (\d+) ticks", int)
    m["stored"] = g(r"\| Items stored at the base \| (\d+)", int)
    m["searches"] = g(r"\| Path searches \| (\d+)", int)
    m["nodes/search"] = g(r"\| Path searches \| \d+ \((\d+) nodes", int)
    m["wait median"] = g(r"median (\d+),", int)
    m["wait p90"] = g(r"p90 (\d+)", int)
    m["wait p99"] = g(r"p99 (\d+)", int)
    m["wait max"] = g(r"p99 \d+, max (\d+)", int)
    m["waiting ticks"] = g(r"waiting on a search \| (\d+)", int)
    m["starved ticks"] = g(r"of which (\d+) got no nodes", int)
    m["search ms/tick"] = g(r"\| Search CPU \| ([\d.]+)")
    if m["search ms/tick"] and m["searches"] and m["nodes/search"]:
        m["us/node"] = round(m["search ms/tick"] * 1000 * mins * 1200 / (m["searches"] * m["nodes/search"]), 2)
    m["stuck"] = g(r"Stuck events / navigation failures \| (\d+)", int)
    m["nav fails"] = g(r"Stuck events / navigation failures \| \d+ / (\d+)", int)
    st = section(t, "Stuck while following a path")
    if st:
        m["stuck"] = sum(v for k, v in st.items() if not k.startswith("replanned"))
    ev = section(t, "Other navigation events")
    ev.update({k: v for k, v in st.items() if k.startswith("replanned")})
    m["world-change replans"] = sum(v for k, v in ev.items() if k.startswith("replanned"))
    m["planned-ahead used"] = sum(v for k, v in ev.items() if k.startswith("partial path ended"))
    outc = section(t, "Search outcomes")
    m["early partials"] = sum(v for k, v in outc.items() if "early" in k)
    m["step timeouts"] = sum(v for k, v in st.items() if k.startswith("step took"))
    m["path timeouts"] = sum(v for k, v in st.items() if k.startswith("path timeout"))
    m["bridge fails"] = sum(v for k, v in st.items() if k.startswith("could not place bridge"))
    rescues = sum(section(t, "Failsafe rescues (free mode, sent home)").values())
    m["rescues"] = rescues
    # Per companion-hour rates for the noisy counts.
    for k in ["deaths", "stuck", "nav fails", "stalls", "jobs failed", "jobs ok"]:
        if m.get(k) is not None:
            m[k + " /ch"] = round(m[k] / hours, 1)
    # Work done: sum the companion table columns.
    rows = re.findall(r"^\| (Soak\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \| (\d+) \|", t, re.M)
    if rows:
        m["walked/ch"] = round(sum(int(r[2]) for r in rows) / hours)
        m["logs+ore/ch"] = round(sum(int(r[3]) + int(r[4]) + int(r[5]) + int(r[6]) for r in rows) / hours, 1)
        m["diamonds"] = sum(int(r[6]) for r in rows)
    outcomes = section(t, "Search outcomes")
    m["escape searches"] = sum(v for k, v in outcomes.items() if "escape" in k)
    m["capped/failed"] = sum(v for k, v in outcomes.items() if k.startswith("failed"))
    return m


def main():
    names = sys.argv[1:]
    data = {n: metrics(n) for n in names}
    names = [n for n in names if data[n]]
    keys = []
    for n in names:
        for k in data[n]:
            if k not in keys:
                keys.append(k)
    w = max(len(k) for k in keys)
    print(" " * w + " | " + " | ".join(f"{n:>10}" for n in names))
    for k in keys:
        vals = [data[n].get(k) for n in names]
        print(f"{k:<{w}} | " + " | ".join(f"{'-' if v is None else v:>10}" for v in vals))


if __name__ == "__main__":
    main()
