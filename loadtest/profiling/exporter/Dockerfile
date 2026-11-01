FROM python:3.13-slim-bookworm
WORKDIR /app
COPY exporter.py .
# The local Engine socket requires root on Docker Desktop. No shell/API writes are used.
EXPOSE 9404
CMD ["python", "-B", "exporter.py"]
