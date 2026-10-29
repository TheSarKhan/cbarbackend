"""Recurring payments: the same merchant at a steady interval and a steady amount (same rules as the Java code)."""
from datetime import timedelta

import numpy as np
import pandas as pd

BILLS = {"Kommunal"}


def _mad(values):
    values = np.asarray(values, dtype=float)
    return float(np.median(np.abs(values - np.median(values)))) if len(values) else 0.0


def recurring(df: pd.DataFrame, now):
    now = pd.Timestamp(now).tz_convert("UTC")
    today = now.tz_convert(df["local"].dt.tz).date() if len(df) else None
    out = []
    recent = df[df["at"] > now - pd.Timedelta(days=200)]
    for key, group in recent.groupby("key"):
        per_day = group.groupby("day")["amount"].sum().sort_index()
        if len(per_day) < 3:
            continue
        days = list(per_day.index)
        gaps = [(days[i] - days[i - 1]).days for i in range(1, len(days))]
        gap = float(np.median(gaps))
        if 27 <= gap <= 34:
            period, period_days = "MONTHLY", 30
        elif 13 <= gap <= 15:
            period, period_days = "BIWEEKLY", 14
        elif 6 <= gap <= 8:
            period, period_days = "WEEKLY", 7
        else:
            continue
        gap_irregularity = _mad(gaps) / max(2.0, 0.12 * gap)
        typical = float(np.median(per_day.to_numpy()))
        tolerance = 0.35 if group["category"].iloc[0] in BILLS else 0.12
        amount_irregularity = (_mad(per_day.to_numpy()) / typical) / tolerance if typical > 0 else 9
        if gap_irregularity > 1 or amount_irregularity > 1 or typical < 100:
            continue
        confidence = 0.5 * (1 - min(1, gap_irregularity)) + 0.5 * (1 - min(1, amount_irregularity))
        if confidence < 0.6:
            continue
        last = days[-1]
        if (today - last).days > 1.8 * period_days:
            continue
        nxt = _add_month(last) if period == "MONTHLY" else last + timedelta(days=period_days)
        out.append({"key": key, "typical": round(typical), "period": period, "period_days": period_days, "last": last, "next": nxt})
    return out


def _add_month(d):
    year, month = (d.year + 1, 1) if d.month == 12 else (d.year, d.month + 1)
    day = min(d.day, [31, 29 if year % 4 == 0 and (year % 100 != 0 or year % 400 == 0) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1])
    return d.replace(year=year, month=month, day=day)


def due_this_month(items, today, month_end):
    """Recurring payments still to come in the rest of the month (after today)."""
    total = 0
    for r in items:
        nxt = r["next"]
        while nxt <= month_end:
            if nxt > today:
                total += r["typical"]
            nxt = _add_month(nxt) if r["period"] == "MONTHLY" else nxt + timedelta(days=r["period_days"])
    return total
