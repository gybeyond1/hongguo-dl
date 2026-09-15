#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""红果短剧下载器 Web 后端"""

import os
import re
import threading
import time
from pathlib import Path
from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

import hongguo_core as hg

DOWNLOAD_DIR = os.environ.get("DOWNLOAD_DIR", "/downloads")
os.makedirs(DOWNLOAD_DIR, exist_ok=True)

app = FastAPI(title="红果短剧下载器")

download_tasks = {}
task_lock = threading.Lock()


class ParseRequest(BaseModel):
    url: str


class DownloadRequest(BaseModel):
    series_id: str
    episodes: list[int]


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
            safe_name = re.sub(r'[<>:"/\\|?*]', "_", title)
            drama_dir = os.path.join(DOWNLOAD_DIR, safe_name)
            os.makedirs(drama_dir, exist_ok=True)

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
    for drama_dir in Path(DOWNLOAD_DIR).iterdir():
        if drama_dir.is_dir():
            files = sorted(drama_dir.glob("*.mp4"))
            result.append({
                "name": drama_dir.name,
                "count": len(files),
                "files": [f.name for f in files],
            })
    return {"code": 0, "data": result}


app.mount("/", StaticFiles(directory="static", html=True), name="static")
