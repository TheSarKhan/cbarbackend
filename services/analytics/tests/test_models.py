import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from kampus_analytics.anomaly import score_recent
from kampus_analytics.features import causal_features, merchant_key, spending_frame
from kampus_analytics.forecast import holt_winters
from kampus_analytics.recurring import recurring
from kampus_analytics.service import app

NOW = pd.Timestamp("2026-10-20T08:00:00Z")


def make(day, hour, name, amount, category="Yemək", n=[0]):
    n[0] += 1
    at = (pd.Timestamp(day, tz="Asia/Baku") + pd.Timedelta(hours=hour)).tz_convert("UTC")
    return {"id": f"t{n[0]}", "createdAt": at.isoformat(), "direction": "OUT", "amountMinor": amount, "name": name, "category": category, "type": "QR"}


def history(days=150, seed=1):
    rng = np.random.default_rng(seed)
    txs = []
    for d in range(days, 0, -1):
        day = (NOW - pd.Timedelta(days=d)).tz_convert("Asia/Baku").date()
        for _ in range(rng.integers(0, 3)):
            txs.append(make(str(day), int(rng.integers(8, 21)), rng.choice(["Bravo", "Araz Market", "Kafe Xazar"]), int(rng.normal(900, 250)) + 100))
    return txs


def test_merchant_names_are_normalised():
    assert merchant_key("BRAVO supermarket 12") == merchant_key("Bravo Supermarket")
    assert merchant_key("Bolt MMC") == "BOLT"


def test_features_only_look_backwards():
    txs = history()
    base = causal_features(spending_frame(txs))
    changed = list(txs)
    changed[-1] = {**changed[-1], "amountMinor": 999_999}
    other = causal_features(spending_frame(changed))
    # every payment before the changed one has exactly the same features
    assert np.allclose(base.iloc[:-1].to_numpy(), other.iloc[:-1].to_numpy())


def test_a_planted_outlier_gets_the_highest_score_and_is_flagged():
    txs = history() + [make("2026-10-18", 14, "Tech Store", 40_000, "Alış-veriş")]
    scored = score_recent(spending_frame(txs), NOW)
    top = scored.sort_values("score", ascending=False).iloc[0]
    assert top["id"] == txs[-1]["id"] and bool(top["flagged"])


def test_ordinary_spending_raises_few_alarms():
    scored = score_recent(spending_frame(history()), NOW, contamination=0.01)
    assert scored["flagged"].sum() <= 2


def test_a_monthly_subscription_is_recurring():
    txs = history() + [make(f"2026-{m:02d}-10", 9, "Netflix", 1299, "Abunəliklər") for m in range(5, 11)]
    found = {r["key"]: r for r in recurring(spending_frame(txs), NOW)}
    assert "NETFLIX" in found and found["NETFLIX"]["period"] == "MONTHLY" and found["NETFLIX"]["typical"] == 1299


def test_steady_spending_is_projected_steadily():
    txs = [make(str((NOW - pd.Timedelta(days=d)).tz_convert("Asia/Baku").date()), 12, "Bravo", 1000) for d in range(60, 0, -1)]
    df = spending_frame(txs)
    cut = pd.Timestamp("2026-10-12T20:00:00Z")
    result = holt_winters(df[df["at"] <= cut], cut, recurring_aware=False)
    # 12 days in October so far at 1000 a day, 19 days left (the 13th included): about 31000
    assert result["projected"] == pytest.approx(31_000, rel=0.05)


def test_service_endpoints():
    client = TestClient(app)
    assert client.get("/health").json() == {"status": "UP"}
    body = {"txs": history() + [make("2026-10-18", 14, "Tech Store", 40_000, "Alış-veriş")], "now": NOW.isoformat()}
    anomalies = client.post("/v1/anomalies", json=body).json()
    assert anomalies["anomalies"] and anomalies["anomalies"][0]["flagged"]
    forecast = client.post("/v1/forecast", json=body).json()
    assert forecast["holtWinters"]["projected"] > 0 and forecast["runRate"]["projected"] > 0
