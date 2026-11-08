FROM python@sha256:2d44738ebf4ce5f63b9d9ea871e9c7cfdc0426f7b0c97cac915fd88ded1d86bb
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1
WORKDIR /app
COPY services/specialist_service/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
    && groupadd -g 10001 app && useradd -u 10001 -g 10001 -M app
COPY services/specialist_service /app/services/specialist_service
COPY services/ai_gateway /app/services/ai_gateway
COPY services/video_worker /app/services/video_worker
COPY services/scene_analysis /app/services/scene_analysis
COPY services/web_gateway/server.py /app/services/web_gateway/server.py
USER 10001:10001
EXPOSE 8446
ENTRYPOINT ["python", "-m", "services.specialist_service"]
