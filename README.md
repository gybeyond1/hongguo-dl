# 红果短剧下载器

Web UI + Docker 化的红果短剧批量下载工具，自动解密，无水印 MP4。

## 功能

- 粘贴分享链接或 series_id，自动解析剧集列表
- 选集批量下载
- 自动解密 CENC-AES-CTR 加密，输出标准 MP4
- Web UI 操作，实时进度显示
- Docker 部署，持久化存储

## Docker Compose 部署

```yaml
version: "3.8"
services:
  hongguo-dl:
    image: gybeyond1/hongguo-dl:latest
    container_name: hongguo-dl
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - /你的下载目录:/downloads
```

```bash
docker compose up -d
```

访问 `http://你的NAS IP:8080`
