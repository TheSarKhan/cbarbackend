# Kampus analytics (Python, machine learning)

Machine-learning counterparts of the statistical analytics in the Java API (`Insights.java`), written so that the two can be
measured against each other on the same data. They are **not** used by the live app today; the comparison and the reasoning are in
[`docs/ML_COMPARISON.md`](../../docs/ML_COMPARISON.md).

| Task | Python model | Java counterpart |
|---|---|---|
| Unusual payments | Isolation Forest on backward-looking features (per customer) | median/MAD, new record, burst rules |
| Month-end forecast | Holt-Winters with weekly seasonality on the non-recurring daily series | spend so far + recurring due + pace |
| Recurring payments | port of the same rules | rules |

## Run it

```sh
cd services/analytics
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest -q                 # unit tests
../../scripts/benchmark.sh                    # regenerate the shared benchmark and print the comparison
.venv/bin/uvicorn kampus_analytics.service:app --port 8090   # HTTP service: /health, /v1/anomalies, /v1/forecast
```

The benchmark (`benchmark/benchmark.json`, about 10 MB) is written by the Java generator `SyntheticSpending`, so both sides see the
same people and the same answer keys. It is not committed; `benchmark/results.md` holds the last result.
