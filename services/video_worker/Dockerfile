FROM python:3.13-alpine3.24

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apk add --no-cache ffmpeg=8.1.3-r0 ca-certificates
WORKDIR /app
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
    && addgroup -g 10001 app \
    && adduser -D -H -u 10001 -G app app
COPY . /app/services/video_worker
USER 10001:10001
ENTRYPOINT ["python", "-m", "services.video_worker"]
