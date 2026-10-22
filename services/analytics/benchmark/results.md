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

