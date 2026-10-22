"""Turning payments into a table, and into features that only look backwards in time (no future information leaks into a score)."""
import re
from datetime import timedelta, timezone

import numpy as np
import pandas as pd

LOCAL = timezone(timedelta(hours=4))  # Baku
INTERNAL = "Daxili köçürmə"
_NOISE = re.compile(r"\b(MMC|LTD|QSC|ASC|FİLİAL|FILIAL)\b")


def merchant_key(name: str) -> str:
    n = (name or "").upper()
    n = re.sub(r"[0-9#№*._\-/\\()]+", " ", n)
    n = _NOISE.sub(" ", n)
    n = re.sub(r"\s+", " ", n).strip()
    return n or (name or "").upper()


def spending_frame(txs) -> pd.DataFrame:
    """Money going out that counts as spending, oldest first, with local time columns."""
    rows = []
    for t in txs:
        if t.get("direction") != "OUT" or t.get("category") == INTERNAL:
            continue
        amount = int(t.get("amountMinor") or 0)
        if amount <= 0:
            continue
        rows.append({
            "id": t["id"],
            "at": pd.Timestamp(t["createdAt"]).tz_convert("UTC"),
            "amount": amount,
            "category": t.get("category") or "Digər",
            "merchant": t.get("name") or "",
            "key": merchant_key(t.get("name") or ""),
            "type": t.get("type") or "",
        })
    df = pd.DataFrame(rows)
    if df.empty:
        return pd.DataFrame(columns=["id", "at", "amount", "category", "merchant", "key", "type", "local", "day", "hour"])
    df = df.sort_values("at", kind="stable").reset_index(drop=True)
    df["local"] = df["at"].dt.tz_convert(LOCAL)
    df["day"] = df["local"].dt.date
    df["hour"] = df["local"].dt.hour
    return df


FEATURE_NAMES = [
    "log_amount", "vs_merchant", "vs_category", "vs_all", "vs_category_max",
    "hour_sin", "hour_cos", "weekday", "merchant_seen", "new_merchant",
    "gap_minutes", "burst_15m", "category_share",
]


def causal_features(df: pd.DataFrame) -> pd.DataFrame:
    """One row per payment, computed from that payment and the ones before it only."""
    n = len(df)
    out = np.zeros((n, len(FEATURE_NAMES)))
    by_key, by_cat, all_amounts = {}, {}, []
    cat_count = {}
    times = df["at"].astype("int64").to_numpy() // 10**9
    for i, row in enumerate(df.itertuples(index=False)):
        a = float(row.amount)
        m = by_key.get(row.key, [])
        c = by_cat.get(row.category, [])
        med_m = np.median(m) if len(m) >= 3 else np.nan
        med_c = np.median(c) if len(c) >= 3 else np.nan
        med_all = np.median(all_amounts) if len(all_amounts) >= 10 else np.nan
        fallback = med_all if not np.isnan(med_all) else a
        vs_m = np.log((a + 1) / (med_m + 1)) if not np.isnan(med_m) else np.log((a + 1) / ((med_c if not np.isnan(med_c) else fallback) + 1))
        vs_c = np.log((a + 1) / ((med_c if not np.isnan(med_c) else fallback) + 1))
        vs_all = np.log((a + 1) / (fallback + 1))
        vs_max = np.log((a + 1) / (max(c) + 1)) if len(c) >= 3 else 0.0
        gap = (times[i] - times[i - 1]) / 60.0 if i > 0 else 60 * 24.0
        burst = int(np.sum(times[max(0, i - 20):i + 1] > times[i] - 15 * 60))
        out[i] = [
            np.log1p(a), vs_m, vs_c, vs_all, vs_max,
            np.sin(2 * np.pi * row.hour / 24), np.cos(2 * np.pi * row.hour / 24), row.local.weekday(),
            np.log1p(len(m)), 1.0 if len(m) == 0 else 0.0,
            np.log1p(gap), burst, cat_count.get(row.category, 0) / max(i, 1),
        ]
        by_key.setdefault(row.key, []).append(a)
        by_cat.setdefault(row.category, []).append(a)
        all_amounts.append(a)
        cat_count[row.category] = cat_count.get(row.category, 0) + 1
    return pd.DataFrame(out, columns=FEATURE_NAMES, index=df.index)


# Hour of day, weekday and the gap to the previous payment made the forest worse on this data (average precision 0.71 against
# 0.86 without them: with so few unusual payments the random splits spend their effort on noise), so the model uses these.
MODEL_FEATURES = ["log_amount", "vs_merchant", "vs_category", "vs_all", "vs_category_max", "merchant_seen", "new_merchant", "burst_15m", "category_share"]
