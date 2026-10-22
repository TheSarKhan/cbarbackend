"""Month-end spending forecast with Holt-Winters (weekly seasonality) on the non-recurring daily series."""
import calendar
from datetime import date, timedelta

import numpy as np
import pandas as pd
import warnings
from statsmodels.tsa.holtwinters import ExponentialSmoothing

from .features import LOCAL
from .recurring import due_this_month, recurring


def _context(df: pd.DataFrame, now):
    now = pd.Timestamp(now).tz_convert("UTC")
    local = now.tz_convert(LOCAL)
    today = local.date()
    month_start = today.replace(day=1)
    month_end = today.replace(day=calendar.monthrange(today.year, today.month)[1])
    known = df[df["at"] <= now]
    mtd = int(known[(known["day"] >= month_start) & (known["day"] <= today)]["amount"].sum())
    today_left = 1 - (local.hour * 3600 + local.minute * 60 + local.second) / 86400.0
    return now, today, month_start, month_end, known, mtd, today_left


def run_rate(df: pd.DataFrame, now) -> dict:
    """The obvious baseline: what has been spent so far per elapsed day, carried to the end of the month."""
    now, today, month_start, month_end, known, mtd, today_left = _context(df, now)
    elapsed = (today - month_start).days + (1 - today_left)
    days = month_end.day
    return {"projected": mtd / max(elapsed, 1e-9) * days}


def holt_winters(df: pd.DataFrame, now, recurring_aware: bool = True, repetitions: int = 400, seed: int = 0) -> dict:
    now, today, month_start, month_end, known, mtd, today_left = _context(df, now)
    items = recurring(known, now) if recurring_aware else []
    keys = {r["key"] for r in items}
    series_rows = known[~known["key"].isin(keys)]
    if series_rows.empty:
        return run_rate(df, now)
    first = series_rows["day"].min()
    days = pd.date_range(first, today - timedelta(days=1), freq="D")
    daily = series_rows.groupby("day")["amount"].sum()
    series = pd.Series([float(daily.get(d.date(), 0.0)) for d in days], index=days)
    horizon = (month_end - today).days + 1  # today (partly spent already) and every day after it
    due = due_this_month(items, today, month_end) if recurring_aware else 0
    if len(series) < 21:
        rate = float(series.mean()) if len(series) else 0.0
        return {"projected": mtd + due + rate * (horizon - 1 + today_left), "low": None, "high": None}
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        fit = ExponentialSmoothing(series, trend=None, seasonal="add", seasonal_periods=7, initialization_method="estimated").fit()
        mean = np.clip(fit.forecast(horizon).to_numpy(), 0, None)
        simulated = np.clip(fit.simulate(horizon, repetitions=repetitions, error="add", random_state=seed).to_numpy(), 0, None)
    weights = np.ones(horizon)
    weights[0] = today_left
    projected = mtd + due + float(mean @ weights)
    # a few simulated paths can blow up when the fit is unstable: judge the range on the sane ones
    with np.errstate(all="ignore"):
        totals = mtd + due + simulated.T @ weights
    totals = totals[np.isfinite(totals) & (totals < 20 * max(projected, 1))]
    if len(totals) < repetitions * 0.5:
        return {"projected": projected, "low": None, "high": None}
    return {"projected": projected, "low": float(np.quantile(totals, 0.1)), "high": float(np.quantile(totals, 0.9))}
