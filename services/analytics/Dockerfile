# Optional ML service (not part of the live stack, see docs/ML_COMPARISON.md).
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY kampus_analytics kampus_analytics
EXPOSE 8090
CMD ["uvicorn", "kampus_analytics.service:app", "--host", "0.0.0.0", "--port", "8090"]
