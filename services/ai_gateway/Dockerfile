FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY services/ai_gateway/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home app
COPY services/ai_gateway /app/services/ai_gateway
USER 10001:10001
EXPOSE 8080
CMD ["uvicorn", "services.ai_gateway.app:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
