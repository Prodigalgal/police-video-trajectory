FROM python@sha256:2d44738ebf4ce5f63b9d9ea871e9c7cfdc0426f7b0c97cac915fd88ded1d86bb
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1
WORKDIR /app
COPY services/appearance/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
    && groupadd -g 10001 app && useradd -u 10001 -g 10001 -M app
COPY services/appearance /app/services/appearance
COPY services/specialist_service /app/services/specialist_service
COPY services/video_worker /app/services/video_worker
USER 10001:10001
ENTRYPOINT ["python", "-m", "services.appearance.worker"]
