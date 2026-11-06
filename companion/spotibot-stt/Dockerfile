FROM python:3.12-slim
RUN pip install --no-cache-dir faster-whisper==1.* fastapi "uvicorn[standard]" numpy
WORKDIR /app
COPY app.py .
# bake the model into the image so startup needs no network
RUN python -c "from faster_whisper import WhisperModel; [WhisperModel(m, device='cpu', compute_type='int8', download_root='/models') for m in ('base.en',)]"
USER 1000:100
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8078", "--workers", "1"]
