FROM python:3.11-slim

# 下载静态编译的 ffmpeg（单个二进制，不依赖系统库）
RUN apt-get update && apt-get install -y --no-install-recommends xz-utils curl ca-certificates && \
    curl -L https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz -o /tmp/ffmpeg.tar.xz && \
    tar xf /tmp/ffmpeg.tar.xz -C /tmp && \
    cp /tmp/ffmpeg-*-static/ffmpeg /usr/local/bin/ && \
    cp /tmp/ffmpeg-*-static/ffprobe /usr/local/bin/ && \
    chmod +x /usr/local/bin/ffmpeg /usr/local/bin/ffprobe && \
    rm -rf /tmp/ffmpeg* && \
    apt-get remove -y xz-utils curl && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .
COPY hongguo_core.py .
COPY static/ ./static/

ENV DOWNLOAD_DIR=/downloads
EXPOSE 8080

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080"]
