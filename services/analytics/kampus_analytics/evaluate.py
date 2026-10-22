"""Measures the Python models and the Java statistics on the very same generated people and answer keys.

    python -m kampus_analytics.evaluate benchmark/benchmark.json [--write benchmark/results.md]

The benchmark file is written by the Java side: mvn -q test -Dtest=BenchmarkExportTests -Dbenchmark.out=PATH
"""
import argparse
import json
from collections import defaultdict

import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score

from .anomaly import WINDOW_DAYS, score_recent
from .features import spending_frame
from .forecast import holt_winters, run_rate
from .recurring import recurring


def pct(x):
    return f"{100 * x:.1f}%"


# ---------------------------------------------------------------------------------------------------------------- anomalies

def evaluate_anomalies(people, now):
    """Pooled precision/recall on people with planted unusual payments, false alarms on people without."""
    methods = ["Java statistics", "Isolation Forest 1%", "Isolation Forest 2%", "Hybrid: both agree", "Hybrid: either"]
    tp = defaultdict(int)
    flagged = defaultdict(int)
    planted_total = 0
    false_alarms = defaultdict(int)
    people_without = 0
    ap_scores = {"Java statistics": ([], []), "Isolation Forest": ([], [])}
    for person in people:
        df = spending_frame(person["txs"])
        truth = set(person["anomalyIds"])
        java = {a["txId"]: a["score"] for a in person["javaAnomalies"]}
        results = {}
        for contamination in (0.01, 0.02):
            s = score_recent(df, now, contamination=contamination)
            results[contamination] = s
        s1, s2 = results[0.01], results[0.02]
        if s1.empty:
            continue
        ids = list(s1["id"])
        if_flag1 = set(s1.loc[s1["flagged"], "id"])
        if_flag2 = set(s2.loc[s2["flagged"], "id"])
        sets = {
            "Java statistics": set(java),
            "Isolation Forest 1%": if_flag1,
            "Isolation Forest 2%": if_flag2,
            "Hybrid: both agree": set(java) & if_flag2,
            "Hybrid: either": set(java) | if_flag1,
        }
        if person["planted"] > 0:
            planted_total += len(truth)
            for m in methods:
                flagged[m] += len(sets[m])
                tp[m] += len(sets[m] & truth)
            y = [1 if i in truth else 0 for i in ids]
            ap_scores["Java statistics"][0].extend(y)
            ap_scores["Java statistics"][1].extend([java.get(i, 0) for i in ids])
            ap_scores["Isolation Forest"][0].extend(y)
            ap_scores["Isolation Forest"][1].extend(list(s1["score"]))
        else:
            people_without += 1
            for m in methods:
                false_alarms[m] += len(sets[m])
    rows = []
    for m in methods:
        precision = tp[m] / flagged[m] if flagged[m] else 0
        recall = tp[m] / planted_total if planted_total else 0
        rows.append((m, recall, precision, false_alarms[m] / max(people_without, 1)))
    ap = {name: (average_precision_score(y, s) if sum(y) else 0) for name, (y, s) in ap_scores.items()}
    return rows, ap


# ---------------------------------------------------------------------------------------------------------------- forecast

def evaluate_forecast(forecasts):
    errors = defaultdict(list)
    inside = defaultdict(list)
    for item in forecasts:
        df = spending_frame(item["txs"])
        truth = item["truthMinor"]
        for cut in item["cuts"]:
            now = pd.Timestamp(cut["cut"])
            known = df[df["at"] <= now]
            candidates = {
                "Java statistics": (cut["javaProjected"], cut["javaLow"], cut["javaHigh"]),
                "Run rate (so far x days)": (run_rate(known, now)["projected"], None, None),
            }
            hw = holt_winters(known, now, recurring_aware=False)
            candidates["Holt-Winters (all spend)"] = (hw["projected"], hw.get("low"), hw.get("high"))
            hw2 = holt_winters(known, now, recurring_aware=True)
            candidates["Holt-Winters + recurring bills"] = (hw2["projected"], hw2.get("low"), hw2.get("high"))
            for name, (projected, low, high) in candidates.items():
                errors[name].append(abs(projected - truth) / truth)
                if low is not None and high is not None:
                    inside[name].append(low <= truth <= high)
    rng = np.random.default_rng(0)
    rows = []
    for name in ["Run rate (so far x days)", "Java statistics", "Holt-Winters (all spend)", "Holt-Winters + recurring bills"]:
        e = np.array(errors[name])
        cov = np.mean(inside[name]) if inside[name] else None
        rows.append((name, float(np.median(e)), float(e.mean()), float(np.percentile(e, 90)), cov))
    # is the difference to the Java statistics more than luck? paired bootstrap of the mean error difference
    java = np.array(errors["Java statistics"])
    paired = []
    for name in ("Holt-Winters (all spend)", "Holt-Winters + recurring bills"):
        diff = np.array(errors[name]) - java
        means = [diff[rng.integers(0, len(diff), len(diff))].mean() for _ in range(2000)]
        paired.append((name, float(diff.mean()), float(np.quantile(means, 0.025)), float(np.quantile(means, 0.975))))
    return rows, paired


# ---------------------------------------------------------------------------------------------------------------- recurring

def evaluate_recurring(people, now):
    found = planted = wrong = java_found = java_wrong = 0
    for person in people:
        if person["planted"] != 0:
            continue
        df = spending_frame(person["txs"])
        keys = {r["key"] for r in recurring(df, now)}
        truth = set(person["recurringKeys"])
        planted += len(truth)
        found += len(keys & truth)
        wrong += len(keys - truth)
        jk = set(person["javaRecurring"])
        java_found += len(jk & truth)
        java_wrong += len(jk - truth)
    return {"planted": planted, "python": (found, wrong), "java": (java_found, java_wrong)}


# ---------------------------------------------------------------------------------------------------------------- report

def report(path):
    data = json.load(open(path))
    now = pd.Timestamp(data["now"])
    lines = []
    people = data["people"]
    rows, ap = evaluate_anomalies(people, now)
    n_with = sum(1 for p in people if p["planted"] > 0)
    n_without = len(people) - n_with
    lines += [f"## Unusual payments ({n_with} people with 3 planted cases, {n_without} people without)", "",
              "| Method | Recall | Precision | False alarms per person (3 weeks) |", "|---|---|---|---|"]
    for m, recall, precision, fa in rows:
        lines.append(f"| {m} | {pct(recall)} | {pct(precision)} | {fa:.2f} |")
    lines += ["", f"Ranking quality (average precision, threshold free): Java statistics {ap['Java statistics']:.3f}, Isolation Forest {ap['Isolation Forest']:.3f}", ""]
    forecast_rows, paired = evaluate_forecast(data["forecasts"])
    lines += ["## Month-end forecast (30 people, cut on the 12th and the 20th)", "",
              "| Method | Median error | Mean error | 90th percentile error | Truth inside the 80% range |", "|---|---|---|---|---|"]
    for name, median, mean, p90, cov in forecast_rows:
        lines.append(f"| {name} | {pct(median)} | {pct(mean)} | {pct(p90)} | {pct(cov) if cov is not None else 'no range'} |")
    lines += ["", "Mean error minus the Java statistics' mean error, with a 95% bootstrap interval (negative = better than Java):", ""]
    for name, diff, lo, hi in paired:
        lines.append(f"- {name}: {100 * diff:+.2f} points ({100 * lo:+.2f} to {100 * hi:+.2f})")
    rec = evaluate_recurring(people, now)
    lines += ["", f"## Recurring payments ({rec['planted']} planted subscriptions and bills)", "",
              "| Method | Found | False detections |", "|---|---|---|",
              f"| Java rules | {rec['java'][0]} / {rec['planted']} | {rec['java'][1]} |",
              f"| Python port of the same rules | {rec['python'][0]} / {rec['planted']} | {rec['python'][1]} |", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("benchmark")
    parser.add_argument("--write")
    args = parser.parse_args()
    text = report(args.benchmark)
    print(text)
    if args.write:
        open(args.write, "w").write(text + "\n")
