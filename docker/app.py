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
    response = await call_next(request)
    # 禁止缓存 HTML/JS，确保前端始终最新
    if request.url.path == "/" or request.url.path.endswith(".html") or request.url.path.endswith(".js"):
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    return response


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

            # juku 兼容标记：.drama-id 写入剧集 ID，便于与 juku 下载目录互通
            try:
                with open(os.path.join(drama_dir, ".drama-id"), "w") as f:
                    f.write(req.series_id)
            except Exception:
                pass

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

                # juku 兼容命名：纯数字 001.mp4（juku 格式），同时兼容旧格式 001_第1集.mp4
                out_path = os.path.join(drama_dir, f"{idx:03d}.mp4")
                legacy_path = os.path.join(drama_dir, f"{idx:03d}_第{idx}集.mp4")
                if os.path.exists(out_path) and os.path.getsize(out_path) > 10000:
                    with task_lock:
                        download_tasks[task_id]["done"] += 1
                    continue
                # 旧格式文件存在则迁移到新格式
                if os.path.exists(legacy_path) and os.path.getsize(legacy_path) > 10000:
                    os.rename(legacy_path, out_path)
                    with task_lock:
                        download_tasks[task_id]["done"] += 1
                    continue

                try:
                    enc_path = out_path + ".enc"
                    video_url = None
                    spade_a = None
                    encrypted = False
                    dl_headers = {
                        "User-Agent": "Mozilla/5.0 (Linux; Android 9; SM-N9860) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Referer": "https://novelquickapp.com/",
                        "Origin": "https://novelquickapp.com",
                        "Accept": "*/*",
                    }
                    for attempt in range(3):
                        video_url, spade_a, encrypted = hg.fetch_play_url(req.series_id, vid)
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
                    if encrypted and spade_a:
                        key = hg.derive_key(spade_a)
                        if key:
                            hg.decrypt_mp4_file(enc_path, out_path, key)
                            os.remove(enc_path)
                        else:
                            os.rename(enc_path, out_path)
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
            series_id = ""
            meta_path = drama_dir / ".meta.json"
            if meta_path.exists():
                try:
                    with open(meta_path) as f:
                        meta = json.load(f)
                        title = meta.get("title", drama_dir.name)
                        cover = meta.get("cover", "")
                        total_eps = meta.get("total_episodes", 0)
                        series_id = meta.get("series_id", "")
                except Exception:
                    pass
            # juku 目录兼容：无 .meta.json 时读 .drama-id 作为 series_id
            if not series_id:
                drama_id_path = drama_dir / ".drama-id"
                if drama_id_path.exists():
                    try:
                        series_id = open(drama_id_path).read().strip()
                    except Exception:
                        pass
            downloaded_eps = set()
            merged_file = None
            for f in files:
                # 分集：兼容 juku 格式（001.mp4）与旧格式（001_第1集.mp4）
                m = re.match(r'^(\d+)', f.name)
                if m and not f.name.endswith('.part.mp4'):
                    downloaded_eps.add(int(m.group(1)))
                # 合并文件：旧格式 {标题}_全集.mp4 或 juku 格式 {标题}{start}-{end}.mp4
                if f.name.endswith('_全集.mp4'):
                    merged_file = f.name
                else:
                    mj = re.match(r'^(.+?)(\d+)-(\d+)\.mp4$', f.name)
                    if mj and len(mj.group(1)) > 0:
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
                "series_id": series_id,
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

        # 收集分集：兼容 juku 格式（001.mp4）与旧格式（001_第1集.mp4）
        # 排除已合并输出（juku: {标题}{start}-{end}.mp4；旧: {标题}_全集.mp4；临时 .part.mp4）
        merged_names = set()
        for f in Path(drama_dir).glob("*.mp4"):
            bn = f.name
            if bn.endswith("_全集.mp4") or ".part.mp4" in bn:
                merged_names.add(bn)
            # juku 合并格式：标题后跟数字-数字.mp4（如 标题1-200.mp4）
            m = re.match(r'^(.+?)(\d+)-(\d+)\.mp4$', bn)
            if m and len(m.group(1)) > 0:
                merged_names.add(bn)
        mp4_files = sorted([f for f in Path(drama_dir).glob("*.mp4")
                            if f.name not in merged_names and not f.name.startswith(".")])
        if len(mp4_files) < 2:
            return {"code": -1, "msg": "至少需要2个视频才能合并"}

        # 按集数排序（001.mp4 → 1）
        def ep_key(fp):
            m = re.match(r'^(\d+)', fp.name)
            return int(m.group(1)) if m else 999999
        mp4_files.sort(key=ep_key)

        with merge_lock:
            merge_tasks[req.drama] = {
                "status": "running",
                "logs": [],
                "total": len(mp4_files),
                "done": 0,
            }

        def worker():
            try:
                add_log = lambda s: merge_tasks[req.drama]["logs"].append(s) if len(merge_tasks[req.drama]["logs"]) < 200 else None
                with merge_lock:
                    merge_tasks[req.drama]["logs"].clear()

                # ========== 复刻 juku media_merge.go：probe → 归一化 → concat → 校验 ==========
                def probe_media(path, timeout=20):
                    """用 ffprobe 提取编码签名（codec/尺寸/采样率等）"""
                    cmd = ["ffprobe", "-v", "error", "-show_entries",
                           "stream=codec_name,codec_type,width,height,sample_rate,channels,time_base",
                           "-show_entries", "format=duration", "-of", "json", path]
                    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
                    if p.returncode != 0:
                        raise Exception(f"ffprobe 失败: {p.stderr[:200]}")
                    j = json.loads(p.stdout)
                    v = next((s for s in j.get("streams", []) if s.get("codec_type") == "video"), None)
                    a = next((s for s in j.get("streams", []) if s.get("codec_type") == "audio"), None)
                    if not v or not j.get("format", {}).get("duration"):
                        raise Exception(f"{os.path.basename(path)} 缺少视频流或时长")
                    duration = float(j["format"]["duration"])
                    sig_parts = [
                        v.get("codec_name", ""),
                        f"{v.get('width',0)}x{v.get('height',0)}",
                        v.get("time_base", ""),
                        a.get("codec_name", "") if a else "",
                        str(a.get("sample_rate", "")) if a else "",
                        str(a.get("channels", "")) if a else "",
                    ]
                    return {
                        "signature": "|".join(sig_parts),
                        "width": int(v.get("width", 0)),
                        "height": int(v.get("height", 0)),
                        "audio": bool(a),
                        "duration": duration,
                    }

                # 1) 逐集探测，判断编码是否一致
                metadata = []
                total_dur = 0.0
                max_w, max_h = 0, 0
                any_audio = False
                normalize = False
                for i, fp in enumerate(mp4_files):
                    with merge_lock:
                        merge_tasks[req.drama]["logs"].append(f"🔍 检测分集编码 {i+1}/{len(mp4_files)}")
                    info = probe_media(str(fp))
                    if i > 0 and info["signature"] != metadata[0]["signature"]:
                        normalize = True
                    metadata.append(info)
                    total_dur += info["duration"]
                    max_w = max(max_w, info["width"])
                    max_h = max(max_h, info["height"])
                    any_audio = any_audio or info["audio"]

                if not any_audio:
                    with merge_lock:
                        merge_tasks[req.drama]["logs"].append("⚠️ 所有分集均无音轨")
                # 2) 确定合并方式
                work = os.path.join(drama_dir, ".merge-work")
                os.makedirs(work, exist_ok=True)
                method = "原编码快速合并"
                inputs = [str(fp) for fp in mp4_files]

                if normalize:
                    method = "兼容合并（H.264 / AAC 统一编码）"
                    with merge_lock:
                        merge_tasks[req.drama]["logs"].append("🎬 检测到编码不一致，统一转码为 H.264/AAC 后合并")
                    # 目标分辨率：取最大，保持偶数
                    if max_w % 2: max_w += 1
                    if max_h % 2: max_h += 1
                    processed = 0.0
                    for i, fp in enumerate(mp4_files):
                        with merge_lock:
                            merge_tasks[req.drama]["logs"].append(f"🔄 统一格式 {i+1}/{len(mp4_files)}")
                        tmp = os.path.join(work, f"{i+1:06d}.mp4")
                        info = metadata[i]
                        # juku normalizedMergeArgs 等价实现
                        vf = (f"setpts=PTS-STARTPTS,fps=30,scale={max_w}:{max_h}:force_original_aspect_ratio=decrease:"
                              f"force_divisible_by=2,pad={max_w}:{max_h}:(ow-iw)/2:(oh-ih)/2,setsar=1")
                        cmd = ["ffmpeg", "-y", "-hide_banner", "-nostdin", "-xerror", "-threads", "2",
                               "-i", str(fp)]
                        if info["audio"]:
                            cmd += ["-map", "0:v:0", "-map", "0:a:0", "-c:a", "aac", "-b:a", "128k",
                                    "-ar", "48000", "-ac", "2",
                                    "-af", "aresample=48000:async=1:first_pts=0,apad", "-shortest"]
                        else:
                            cmd += ["-map", "0:v:0", "-an"]
                        cmd += ["-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                                "-profile:v", "high", "-pix_fmt", "yuv420p", "-g", "60",
                                "-video_track_timescale", "90000", "-max_muxing_queue_size", "4096",
                                "-t", f"{info['duration']:.6f}", "-f", "mp4", "-y", tmp]
                        p = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
                        if p.returncode != 0:
                            raise Exception(f"转码第{i+1}集失败: {p.stderr[-300:]}")
                        inputs[i] = tmp
                        processed += info["duration"]

                # 3) concat 合并
                list_path = os.path.join(work, "inputs.ffconcat")
                with open(list_path, "w") as f:
                    for p in inputs:
                        ap = os.path.abspath(p).replace("'", "'\\''")
                        f.write(f"file '{ap}'\n")

                # 合并输出命名：juku 格式 {标题}{start}-{end}.mp4
                start_ep = ep_key(mp4_files[0])
                end_ep = ep_key(mp4_files[-1])
                output_path = os.path.join(drama_dir, f"{req.drama}{start_ep}-{end_ep}.mp4")
                part_path = os.path.join(drama_dir, f".{req.drama}{start_ep}-{end_ep}.part.mp4")
                if os.path.exists(part_path):
                    os.remove(part_path)

                with merge_lock:
                    merge_tasks[req.drama]["logs"].append(f"🔗 {method}：{len(mp4_files)}集 → {os.path.basename(output_path)}")
                cmd = ["ffmpeg", "-y", "-hide_banner", "-nostdin", "-protocol_whitelist", "file,pipe",
                       "-f", "concat", "-safe", "0", "-i", list_path,
                       "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-c", "copy",
                       "-movflags", "+faststart", part_path]
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         text=True, cwd=drama_dir)
                for line in proc.stdout:
                    line = line.strip()
                    if line:
                        with merge_lock:
                            if len(merge_tasks[req.drama]["logs"]) < 200:
                                merge_tasks[req.drama]["logs"].append(line)
                proc.wait()
                if proc.returncode != 0:
                    raise Exception("concat 失败")

                # 4) 校验合并结果：存在性 + 时长比对
                if not os.path.exists(part_path) or os.path.getsize(part_path) < 50000:
                    raise Exception("合并输出文件无效")

                merged_info = probe_media(part_path, timeout=30)
                tolerance = max(total_dur / 100, 2.0)
                if abs(merged_info["duration"] - total_dur) > tolerance:
                    raise Exception(
                        f"合并时长不符（预计 {total_dur:.1f}s，实际 {merged_info['duration']:.1f}s），保留单集文件")

                os.replace(part_path, output_path)

                # 5) 校验通过，删除单集与临时文件
                deleted = 0
                for fp in mp4_files:
                    try:
                        os.remove(fp)
                        deleted += 1
                    except Exception:
                        pass
                shutil.rmtree(work, ignore_errors=True)

                out_size = os.path.getsize(output_path)
                with merge_lock:
                    merge_tasks[req.drama]["status"] = "done"
                    merge_tasks[req.drama]["done"] = len(mp4_files)
                    merge_tasks[req.drama]["logs"].append(
                        f"✅ 合并完成: {len(mp4_files)}集, {round(out_size/1024/1024,1)}MB, "
                        f"时长{round(merged_info['duration']/60,1)}分钟, 删除{deleted}个单集")
            except Exception as e:
                with merge_lock:
                    merge_tasks[req.drama]["status"] = "error"
                    merge_tasks[req.drama]["logs"].append(f"❌ 合并失败: {e}")

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
