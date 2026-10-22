"""A small HTTP service around the models. It is not part of the live stack: the Java statistics are what runs today,
see docs/ML_COMPARISON.md for why and for what would change that."""
from typing import List, Optional

import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel, Field

from .anomaly import score_recent
from .features import spending_frame
from .forecast import holt_winters, run_rate

app = FastAPI(title="Kampus analytics (ML)", version="0.1.0")


class Tx(BaseModel):
    id: str
    createdAt: str
    direction: str
    amountMinor: int
    name: str = ""
    category: str = ""
    type: str = ""


class Request(BaseModel):
    txs: List[Tx]
    now: Optional[str] = Field(default=None, description="ISO time; defaults to the newest payment")
    contamination: float = Field(default=0.01, gt=0, lt=0.2)


def _frame(request: Request):
    df = spending_frame([t.model_dump() for t in request.txs])
    now = pd.Timestamp(request.now) if request.now else (df["at"].max() if len(df) else pd.Timestamp.now(tz="UTC"))
    return df, now.tz_convert("UTC") if now.tzinfo else now.tz_localize("UTC")


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/v1/anomalies")
def anomalies(request: Request):
    df, now = _frame(request)
    scored = score_recent(df, now, contamination=request.contamination)
    flagged = scored[scored["flagged"]] if len(scored) else scored
    return {"scored": int(len(scored)), "anomalies": flagged.sort_values("score", ascending=False).to_dict("records")}


@app.post("/v1/forecast")
def forecast(request: Request):
    df, now = _frame(request)
    known = df[df["at"] <= now]
    return {"runRate": run_rate(known, now), "holtWinters": holt_winters(known, now, recurring_aware=True)}
