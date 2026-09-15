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
TASKS_FILE = os.path.join(DOWNLOAD_DIR, ".tasks.json")
os.makedirs(DOWNLOAD_DIR, exist_ok=True)

app = FastAPI(title="红果短剧下载器")


def check_auth(request: Request):
    if not APP_PASSWORD:
        return
    token = request.headers.get("X-Auth-Token", "")
    if token != APP_PASSWORD:
        raise HTTPException(status_code=401, detail="未授权")


@app.middleware("http")
async def auth_middleware(request: Request, call_next):
    public_paths = ["/api/login", "/api/config"]
    if APP_PASSWORD and request.url.path.startswith("/api/") and request.url.path not in public_paths:
        token = request.headers.get("X-Auth-Token", "")
        if token != APP_PASSWORD:
            return JSONResponse({"code": -1, "msg": "未授权"}, status_code=401)
    return await call_next(request)


download_tasks = {}
task_lock = threading.Lock()
merge_tasks = {}
merge_lock = threading.Lock()


def load_tasks():
    """从磁盘加载任务状态"""
    global download_tasks
    try:
        if os.path.exists(TASKS_FILE):
            with open(TASKS_FILE) as f:
                download_tasks = json.load(f)
    except Exception:
        download_tasks = {}


def save_tasks():
    """保存任务状态到磁盘"""
    try:
        with task_lock:
            with open(TASKS_FILE, "w") as f:
                json.dump(download_tasks, f, ensure_ascii=False)
    except Exception:
        pass


load_tasks()


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
            "series_id": req.series_id,
            "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
    save_tasks()

    def worker():
        try:
            info = hg.fetch_episode_list(req.series_id)
            title = info["series_title"]
            cover = info.get("cover", "")
            safe_name = re.sub(r'[<>:"/\\|?*]', "_", title)
            drama_dir = os.path.join(DOWNLOAD_DIR, safe_name)
            os.makedirs(drama_dir, exist_ok=True)

            # 保存元信息
            meta_path = os.path.join(drama_dir, ".meta.json")
            total_eps = len(info["episodes"])
            with open(meta_path, "w") as f:
                json.dump({"title": title, "cover": cover, "series_id": req.series_id, "total_episodes": total_eps}, f, ensure_ascii=False)

            selected_vids = set()
            for ep in info["episodes"]:
                if ep["vid_index"] in req.episodes:
                    selected_vids.add(ep["vid"])

            eps_to_dl = [e for e in info["episodes"] if e["vid"] in selected_vids]
            eps_to_dl.sort(key=lambda x: x["vid_index"])

            with task_lock:
                download_tasks[task_id]["title"] = title

            for ep in eps_to_dl:
                vid = ep["vid"]
                idx = ep["vid_index"]
                with task_lock:
                    download_tasks[task_id]["current"] = f"第{idx}集"
                save_tasks()

                out_path = os.path.join(drama_dir, f"{idx:03d}_第{idx}集.mp4")
                if os.path.exists(out_path) and os.path.getsize(out_path) > 10000:
                    with task_lock:
                        download_tasks[task_id]["done"] += 1
                    continue

                try:
                    enc_path = out_path + ".enc"
                    video_url = None
                    spade_a = None
                    dl_headers = {
                        "User-Agent": "Mozilla/5.0 (Linux; Android 9; SM-N9860) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Referer": "https://novelquickapp.com/",
                        "Origin": "https://novelquickapp.com",
                        "Accept": "*/*",
                    }
                    for attempt in range(3):
                        video_url, spade_a = hg.fetch_play_url(vid)
                        if not video_url:
                            continue
                        try:
                            r = hg.requests.get(video_url, headers=dl_headers, timeout=120)
                            r.raise_for_status()
                            break
                        except Exception as dl_err:
                            if attempt < 2:
                                time.sleep(2)
                                continue
                            raise
                    else:
                        raise Exception("无播放地址")
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
                save_tasks()

            with task_lock:
                download_tasks[task_id]["status"] = "done"
                download_tasks[task_id]["current"] = "完成"
        except Exception as e:
            with task_lock:
                download_tasks[task_id]["status"] = "error"
                download_tasks[task_id]["errors"].append(str(e))
        save_tasks()

    threading.Thread(target=worker, daemon=True).start()
    return {"code": 0, "task_id": task_id}


@app.get("/api/progress/{task_id}")
def progress(task_id: str):
    with task_lock:
        t = download_tasks.get(task_id)
        if not t:
            return {"code": -1, "msg": "任务不存在"}
        return {"code": 0, "data": dict(t)}


@app.get("/api/tasks")
def list_tasks():
    """返回所有任务（用于刷新后恢复状态）"""
    with task_lock:
        # 返回倒序，最新的在前
        items = sorted(download_tasks.items(), key=lambda x: x[0], reverse=True)
        return {"code": 0, "data": [{"task_id": k, **v} for k, v in items]}


@app.delete("/api/tasks/{task_id}")
def clear_task(task_id: str):
    with task_lock:
        download_tasks.pop(task_id, None)
    save_tasks()
    return {"code": 0}


@app.get("/api/files")
def list_files():
    result = []
    for drama_dir in sorted(Path(DOWNLOAD_DIR).iterdir(), reverse=True):
        if drama_dir.is_dir():
            files = sorted(drama_dir.glob("*.mp4"))
            total_size = sum(f.stat().st_size for f in files)
            title = drama_dir.name
            cover = ""
            total_eps = 0
            meta_path = drama_dir / ".meta.json"
            if meta_path.exists():
                try:
                    with open(meta_path) as f:
                        meta = json.load(f)
                        title = meta.get("title", drama_dir.name)
                        cover = meta.get("cover", "")
                        total_eps = meta.get("total_episodes", 0)
                except Exception:
                    pass
            downloaded_eps = set()
            merged_file = None
            for f in files:
                m = re.match(r'(\d+)_', f.name)
                if m:
                    downloaded_eps.add(int(m.group(1)))
                elif f.name.endswith('_全集.mp4'):
                    merged_file = f.name
            missing_list = []
            if total_eps and not merged_file:
                missing_list = [i for i in range(1, total_eps + 1) if i not in downloaded_eps]
            # merged 时只显示合并文件
            if merged_file:
                display_files = [merged_file]
                file_count = 1
            else:
                display_files = [f.name for f in files]
                file_count = len(files)
            result.append({
                "name": drama_dir.name,
                "title": title,
                "cover": cover,
                "count": file_count,
                "total_episodes": total_eps,
                "missing": len(missing_list),
                "missing_list": missing_list,
                "merged": bool(merged_file),
                "merged_file": merged_file or "",
                "size_mb": round(total_size / 1024 / 1024, 1),
                "files": display_files,
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

        # 检查是否已有合并任务在跑
        with merge_lock:
            if req.drama in merge_tasks and merge_tasks[req.drama]["status"] == "running":
                return {"code": -1, "msg": "正在合并中"}

        mp4_files = sorted([f for f in Path(drama_dir).glob("*.mp4") if not f.name.endswith("_全集.mp4")])
        if len(mp4_files) < 2:
            return {"code": -1, "msg": "至少需要2个视频才能合并"}

        with merge_lock:
            merge_tasks[req.drama] = {
                "status": "running",
                "logs": [],
                "total": len(mp4_files),
                "done": 0,
            }

        def worker():
            try:
                list_path = os.path.join(drama_dir, "concat_list.txt")
                with open(list_path, "w") as f:
                    for fp in mp4_files:
                        f.write(f"file '{fp.name}'\n")

                output_path = os.path.join(drama_dir, f"{req.drama}_全集.mp4")
                cmd = [
                    "ffmpeg", "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", list_path,
                    "-c", "copy",
                    "-movflags", "+faststart",
                    output_path
                ]
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         text=True, cwd=drama_dir)
                for line in proc.stdout:
                    line = line.strip()
                    if line:
                        with merge_lock:
                            merge_tasks[req.drama]["logs"].append(line)
                            if len(merge_tasks[req.drama]["logs"]) > 100:
                                merge_tasks[req.drama]["logs"] = merge_tasks[req.drama]["logs"][-100:]
                proc.wait()
                os.remove(list_path)

                if proc.returncode != 0:
                    with merge_lock:
                        merge_tasks[req.drama]["status"] = "error"
                        merge_tasks[req.drama]["logs"].append("❌ 合并失败")
                    return

                # 合并成功，删除单集文件
                for fp in mp4_files:
                    try:
                        os.remove(fp)
                    except Exception:
                        pass

                size_mb = round(os.path.getsize(output_path) / 1024 / 1024, 1)
                with merge_lock:
                    merge_tasks[req.drama]["status"] = "done"
                    merge_tasks[req.drama]["done"] = len(mp4_files)
                    merge_tasks[req.drama]["logs"].append(f"✅ 合并完成: {size_mb}MB")
            except Exception as e:
                with merge_lock:
                    merge_tasks[req.drama]["status"] = "error"
                    merge_tasks[req.drama]["logs"].append(f"❌ {e}")

        threading.Thread(target=worker, daemon=True).start()
        return {"code": 0, "data": {"drama": req.drama, "total": len(mp4_files)}}
    except Exception as e:
        return {"code": -1, "msg": str(e)}


@app.get("/api/merge_status/{drama}")
def merge_status(drama: str):
    with merge_lock:
        t = merge_tasks.get(drama)
        if not t:
            return {"code": 0, "data": {"status": "none"}}
        return {"code": 0, "data": dict(t)}


app.mount("/", StaticFiles(directory="static", html=True), name="static")
