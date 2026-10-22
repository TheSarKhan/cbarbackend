"""Isolation Forest on causal payment features, trained on each person's own earlier history."""
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest

from .features import MODEL_FEATURES, causal_features, spending_frame

WINDOW_DAYS = 21
MIN_HISTORY = 60


def score_recent(df: pd.DataFrame, now, contamination: float = 0.01, seed: int = 0, trees: int = 300) -> pd.DataFrame:
    """Anomaly score (higher = stranger) for every payment of the last three weeks, and the cut-off learned from history.

    The cut-off is the (1 - contamination) quantile of the scores the model gives to the person's own earlier payments, so
    it says "stranger than almost everything this person did before" without ever looking at an answer key.
    """
    now = pd.Timestamp(now).tz_convert("UTC")
    recent = (df["at"] > now - pd.Timedelta(days=WINDOW_DAYS)) & (df["at"] <= now)
    if (~recent).sum() < MIN_HISTORY or not recent.any():
        return pd.DataFrame(columns=["id", "score", "flagged", "cutoff"])
    features = causal_features(df)[MODEL_FEATURES]
    train = features[~recent]
    model = IsolationForest(n_estimators=trees, contamination="auto", random_state=seed).fit(train)
    train_scores = -model.score_samples(train)
    cutoff = float(np.quantile(train_scores, 1 - contamination))
    scores = -model.score_samples(features[recent])
    return pd.DataFrame({"id": df.loc[recent, "id"].to_numpy(), "score": scores, "flagged": scores > cutoff, "cutoff": cutoff})


def detect(txs, now, contamination: float = 0.01, seed: int = 0) -> pd.DataFrame:
    return score_recent(spending_frame(txs), now, contamination, seed)
