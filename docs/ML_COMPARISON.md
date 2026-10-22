# Spending analytics: Java statistics against Python machine learning

Question: the analytics in the app are classical statistics written in Java (`Insights.java`). Would a machine-learning model written in
Python do better? Both are measured here on the **same generated people and the same answer keys** (`SyntheticSpending` plants
subscriptions, bills and unusual payments, so the right answers are known). Reproduce with `scripts/benchmark.sh`.

## What was compared

- **Unusual payments:** the Java rules (robust z-score against the merchant, category or everything; a new record amount; a burst of
  payments) against an **Isolation Forest** trained per customer on features that only look backwards in time. The forest first used
  hour, weekday and the gap to the previous payment and scored worse; dropping them raised its average precision from 0.71 to 0.86,
  so the comparison uses the better version.
- **Month-end forecast:** the Java method (spend so far + recurring payments still due + capped recent daily pace, with a band) against
  **Holt-Winters** with weekly seasonality, with and without taking recurring bills out of the series, and against the naive run rate.
- **Recurring payments:** the same rules in both languages (they should agree).

## Results

## Unusual payments (40 people with 3 planted cases, 40 people without)

| Method | Recall | Precision | False alarms per person (3 weeks) |
|---|---|---|---|
| Java statistics | 98.3% | 92.2% | 0.20 |
| Isolation Forest 1% | 59.2% | 86.6% | 0.20 |
| Isolation Forest 2% | 77.5% | 64.1% | 0.42 |
| Hybrid: both agree | 77.5% | 98.9% | 0.03 |
| Hybrid: either | 98.3% | 85.5% | 0.38 |

Ranking quality (average precision, threshold free): Java statistics 0.963, Isolation Forest 0.855

## Month-end forecast (30 people, cut on the 12th and the 20th)

| Method | Median error | Mean error | 90th percentile error | Truth inside the 80% range |
|---|---|---|---|---|
| Run rate (so far x days) | 8.6% | 9.7% | 21.2% | no range |
| Java statistics | 6.5% | 8.0% | 17.2% | 78.3% |
| Holt-Winters (all spend) | 6.7% | 7.8% | 15.4% | 83.3% |
| Holt-Winters + recurring bills | 6.6% | 7.1% | 14.6% | 83.3% |

Mean error minus the Java statistics' mean error, with a 95% bootstrap interval (negative = better than Java):

- Holt-Winters (all spend): -0.23 points (-2.44 to +1.85)
- Holt-Winters + recurring bills: -0.88 points (-2.49 to +0.78)

## Recurring payments (220 planted subscriptions and bills)

| Method | Found | False detections |
|---|---|---|
| Java rules | 220 / 220 | 0 |
| Python port of the same rules | 220 / 220 | 0 |


## What this says

1. **Unusual payments: the Java statistics win.** Average precision 0.96 against 0.86 for the best Isolation Forest, and at the
   operating points tried the forest either misses more (59% recall at 1%) or raises more false alarms (2%). The reason is plain: the
   planted cases are *unusually large amounts*, which is exactly what the statistical rule measures directly, while the forest has to
   rediscover "large" from many features.
2. **Using both is useful in one way:** when a payment is flagged by the Java rules *and* by the forest, 98.9% of such alerts are real
   and false alarms fall to 0.03 per person in three weeks, at the price of 77.5% recall. That fits a **high-confidence tier** (for
   example, a stronger notification), not the replacement of the rules.
3. **Forecast: no proven difference.** Holt-Winters with recurring bills taken out has a lower mean error (7.1% against 8.0%), a
   lower 90th-percentile error and a range that is better calibrated (83% of outcomes inside an 80% range against 78%), but the
   95% bootstrap interval of the difference includes zero, so with 60 cases this could be luck. The median error is the same.
4. **Recurring payments: identical** (220 of 220, no false detections in either).

## Decision

Keep the Java statistics in the app. Nothing here justifies running a second service (deploy, monitoring, versioning) in production:
the machine-learning models do not beat the rules on this data, and the rules can tell the customer *why* ("6 times your usual
shopping amount"). The Python code stays as a tested, runnable service (`services/analytics`, 7 unit tests) with the benchmark.

## What would change the decision

- **Real customer data.** All of this is generated data; a model learns what the generator does. With real transactions the forest
  can learn each person's rhythm (hours, merchants, weekdays) where the rules only see amounts, and the measurement should be repeated
  with labelled cases (customers' "this is me / not me" answers are already collected by the app and are the natural labels).
- **A pattern the rules cannot express,** such as account takeover from a changed rhythm rather than a large amount.
- **A longer history and more customers,** which would settle whether Holt-Winters really forecasts better.

Until then the benchmark is the safety net: any future model must beat the rules here, and on real labels, before it replaces them.
