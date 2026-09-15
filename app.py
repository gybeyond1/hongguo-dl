#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""红果短剧下载器 Web 后端"""

import os
import re
import json
import shutil
import subprocess
import threading
import time
from pathlib import Path
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

import hongguo_core as hg

DOWNLOAD_DIR = os.environ.get("DOWNLOAD_DIR", "/downloads")
APP_PASSWORD = os.environ.get("APP_PASSWORD", "")
os.makedirs(DOWNLOAD_DIR, exist_ok=True)

app = FastAPI(title="红果短剧下载器")


@app.middleware("http")
async def auth_middleware(request: Request, call_next):
    if APP_PASSWORD and request.url.path.startswith("/api/"):
        token = request.headers.get("X-Auth-Token", "")
        if token != APP_PASSWORD:
            return JSONResponse({"code": -1, "msg": "未授权"}, status_code=401)
    return await call_next(request)


download_tasks = {}
task_lock = threading.Lock()


class ParseRequest(BaseModel):
    url: str


class DownloadRequest(BaseModel):
    series_id: str
    episodes: list[int]


class DeleteRequest(BaseModel):
    drama: str
    filename: str = ""


class MergeRequest(BaseModel):
    drama: str


class LoginRequest(BaseModel):
    password: str


@app.post("/api/login")
def login(req: LoginRequest):
    if not APP_PASSWORD:
        return {"code": 0, "need_auth": False}
    if req.password == APP_PASSWORD:
        return {"code": 0, "need_auth": True}
    return {"code": -1, "msg": "密码错误"}


@app.get("/api/config")
def get_config():
    return {"code": 0, "need_auth": bool(APP_PASSWORD)}


@app.post("/api/parse")
def parse(req: ParseRequest):
    try:
        sid = hg.resolve_series_id(req.url)
        info = hg.fetch_episode_list(sid)
        return {"code": 0, "data": info}
    except Exception as e:
        return {"code": -1, "msg": str(e)}


@app.post("/api/download")
def start_download(req: DownloadRequest):
    task_id = str(int(time.time() * 1000))
    with task_lock:
        download_tasks[task_id] = {
            "status": "running",
            "total": len(req.episodes),
            "done": 0,
            "current": "",
            "errors": [],
        }

    def worker():
        try:
            info = hg.fetch_episode_list(req.series_id)
            title = info["series_title"]
            cover = info.get("cover", "")
            safe_name = re.sub(r'[<>:"/\\|?*]', "_", title)
            drama_dir = os.path.join(DOWNLOAD_DIR, safe_name)
            os.makedirs(drama_dir, exist_ok=True)

            meta_path = os.path.join(drama_dir, ".meta.json")
            with open(meta_path, "w") as f:
                json.dump({"title": title, "cover": cover, "series_id": req.series_id}, f, ensure_ascii=False)

            selected_vids = set()
            for ep in info["episodes"]:
                if ep["vid_index"] in req.episodes:
                    selected_vids.add(ep["vid"])

            eps_to_dl = [e for e in info["episodes"] if e["vid"] in selected_vids]
            eps_to_dl.sort(key=lambda x: x["vid_index"])

            for ep in eps_to_dl:
                vid = ep["vid"]
                idx = ep["vid_index"]
                with task_lock:
                    download_tasks[task_id]["current"] = f"第{idx}集"

                out_path = os.path.join(drama_dir, f"{idx:03d}_第{idx}集.mp4")
                if os.path.exists(out_path) and os.path.getsize(out_path) > 10000:
                    with task_lock:
                        download_tasks[task_id]["done"] += 1
                    continue

                try:
                    video_url, spade_a = hg.fetch_play_url(vid)
                    if not video_url:
                        raise Exception("无播放地址")
                    enc_path = out_path + ".enc"
                    r = hg.requests.get(video_url, headers={"User-Agent": hg.UA, "Referer": "https://novelquickapp.com/"}, timeout=120)
                    r.raise_for_status()
                    with open(enc_path, "wb") as f:
                        f.write(r.content)
                    key = hg.derive_key(spade_a)
                    if key:
                        hg.decrypt_mp4_file(enc_path, out_path, key)
                        os.remove(enc_path)
                    else:
                        os.rename(enc_path, out_path)
                except Exception as e:
                    with task_lock:
                        download_tasks[task_id]["errors"].append(f"第{idx}集: {e}")

                with task_lock:
                    download_tasks[task_id]["done"] += 1

            with task_lock:
                download_tasks[task_id]["status"] = "done"
                download_tasks[task_id]["current"] = "完成"
        except Exception as e:
            with task_lock:
                download_tasks[task_id]["status"] = "error"
                download_tasks[task_id]["errors"].append(str(e))

    threading.Thread(target=worker, daemon=True).start()
    return {"code": 0, "task_id": task_id}


@app.get("/api/progress/{task_id}")
def progress(task_id: str):
    with task_lock:
        t = download_tasks.get(task_id)
        if not t:
            return {"code": -1, "msg": "任务不存在"}
        return {"code": 0, "data": dict(t)}


@app.get("/api/files")
def list_files():
    result = []
    for drama_dir in sorted(Path(DOWNLOAD_DIR).iterdir(), reverse=True):
        if drama_dir.is_dir():
            files = sorted(drama_dir.glob("*.mp4"))
            total_size = sum(f.stat().st_size for f in files)
            title = drama_dir.name
            cover = ""
            meta_path = drama_dir / ".meta.json"
            if meta_path.exists():
                try:
                    with open(meta_path) as f:
                        meta = json.load(f)
                        title = meta.get("title", drama_dir.name)
                        cover = meta.get("cover", "")
                except Exception:
                    pass
            result.append({
                "name": drama_dir.name,
                "title": title,
                "cover": cover,
                "count": len(files),
                "size_mb": round(total_size / 1024 / 1024, 1),
                "files": [f.name for f in files],
            })
    return {"code": 0, "data": result}


@app.post("/api/delete")
def delete_file(req: DeleteRequest):
    try:
        if req.filename:
            full = os.path.join(DOWNLOAD_DIR, req.drama, req.filename)
            if os.path.exists(full):
                os.remove(full)
        else:
            shutil.rmtree(os.path.join(DOWNLOAD_DIR, req.drama), ignore_errors=True)
        return {"code": 0}
    except Exception as e:
        return {"code": -1, "msg": str(e)}


@app.post("/api/merge")
def merge_episodes(req: MergeRequest):
    try:
        drama_dir = os.path.join(DOWNLOAD_DIR, req.drama)
        if not os.path.isdir(drama_dir):
            return {"code": -1, "msg": "目录不存在"}

        mp4_files = sorted(Path(drama_dir).glob("*.mp4"))
        if len(mp4_files) < 2:
            return {"code": -1, "msg": "至少需要2个视频才能合并"}

        list_path = os.path.join(drama_dir, "concat_list.txt")
        with open(list_path, "w") as f:
            for fp in mp4_files:
                f.write(f"file '{fp.name}'\n")

        output_path = os.path.join(drama_dir, f"{req.drama}_全集.mp4")
        cmd = ["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", list_path, "-c", "copy", "-movflags", "+faststart", output_path]
        result = subprocess.run(cmd, capture_output=True, text=True, cwd=drama_dir, timeout=300)
        os.remove(list_path)

        if result.returncode != 0:
            return {"code": -1, "msg": f"ffmpeg错误: {result.stderr[-500:]}"}

        size_mb = round(os.path.getsize(output_path) / 1024 / 1024, 1)
        return {"code": 0, "data": {"file": f"{req.drama}_全集.mp4", "size_mb": size_mb}}
    except Exception as e:
        return {"code": -1, "msg": str(e)}


app.mount("/", StaticFiles(directory="static", html=True), name="static")
