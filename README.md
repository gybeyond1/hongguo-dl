# 红果短剧下载器

支持在 NAS（Docker）和 Android 手机上下载红果短剧，自动解密加密视频流，合并为完整 MP4。

## 功能

- 粘贴分享链接自动解析剧集列表
- 选择集数批量下载（AES-CTR 解密 + MP4 box 重建）
- 自动合并所有集数为单个 MP4（ffmpeg concat copy）
- 合并前自动检测缺集并提示
- 下载失败集数一键重试
- 任务状态持久化，刷新页面不丢失
- Web UI 响应式设计，手机电脑都能用
- 支持访问密码保护
- Android APK 支持本地下载和远程连接 NAS

## Docker 部署

### 方式一：直接拉取镜像

```bash
mkdir -p /volume1/gy/hongguo-downloads
cd /volume1/docker/hg
```

`docker-compose.yml`:

```yaml
services:
  hongguo-dl:
    image: gybeyond/hongguo-dl:latest
    container_name: hongguo-dl
    restart: unless-stopped
    ports:
      - "8800:8080"
    environment:
      - APP_PASSWORD=gy19971126
      - DOWNLOAD_DIR=/downloads
    volumes:
      - /volume1/gy/hongguo-downloads:/downloads
```

```bash
docker compose up -d
```

### 方式二：离线导入镜像（Docker Hub 拉不下来时）

从 [Releases](../../releases) 下载 `hongguo-dl-docker.tar.gz`，然后：

```bash
# 导入镜像
docker load < hongguo-dl-docker.tar.gz

# 启动
docker compose up -d
```

访问 `http://NAS_IP:8800`，输入密码即可使用。

## Android APK

从 [Releases](../../releases) 下载 `hongguo-dl.apk`，安装后：

- **本地模式**：直接在手机上解析和下载到 `/Download/HG_Download/`
- **云端模式**：在设置里填入 NAS 地址和密码，远程下载

支持直接从红果 App 分享链接到本应用自动解析。

## 技术原理

- 调用字节跳动 CDN API 获取剧集列表和播放地址
- 使用 spade_a 字段推导 AES-128 密钥
- AES-CTR 解密每个 sample
- 重建 MP4 moov box 使视频可正常播放
- ffmpeg concat copy 合并所有集数

## 技术栈

- 后端：Python FastAPI
- 前端：原生 HTML/CSS/JS
- 解密：AES-CTR (Web Crypto API / Python cryptography)
- 合并：ffmpeg (静态编译)
- Android：WebView + JS Bridge
