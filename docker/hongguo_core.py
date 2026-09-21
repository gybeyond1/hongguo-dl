#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""红果短剧核心下载逻辑"""

import requests
import json
import base64
import os
import re
import struct
import time
import hashlib
import random
from urllib.parse import urlencode
from Crypto.Cipher import AES

API = "https://api5-normal-sinfonlineb.fqnovel.com"
# 新版 7.3.5.32 UA（参考 juku-backend，可绕过风控）
UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

_device_id = None
_install_id = None


def _new_device_id():
    return str(1_000_000_000_000_000_000 + random.getrandbits(63) % 8_000_000_000_000_000_000)


def get_device_ids():
    """每次进程启动随机生成设备 ID / 安装 ID（与 juku-backend 一致）"""
    global _device_id, _install_id
    if _device_id is None:
        _device_id = _new_device_id()
        _install_id = _new_device_id()
    return _device_id, _install_id


def COMMON_QUERY():
    device_id, install_id = get_device_ids()
    return {
        "aid": "8662",
        "app_name": "novelread",
        "version_code": "73532",
        "version_name": "7.3.5.32",
        "manifest_version_code": "73532",
        "update_version_code": "73532",
        "channel": "update_64",
        "device_platform": "android",
        "os": "android",
        "ssmix": "a",
        "device_type": "25053RT47C",
        "device_brand": "Redmi",
        "language": "zh",
        "os_api": "36",
        "os_version": "16",
        "resolution": "1280*2772",
        "dpi": "520",
        "ac": "wifi",
        "device_id": device_id,
        "iid": install_id,
    }


HEADERS = {
    "User-Agent": UA,
    "Accept-Encoding": "gzip",
    "Accept": "application/json",
    "Content-Type": "application/json; charset=utf-8",
    "X-XS-From-Web": "0",
    "Sdk-Version": "2",
    "Host": "api5-normal-sinfonlineb.fqnovel.com",
}


def _rotl8(b, n):
    return ((b << n) | (b >> (8 - n))) & 0xff


def _rev8(b):
    return int("{:08b}".format(b)[::-1], 2)


def _sign_request(query_str, body, now):
    """复刻 juku-backend 的 X-Gorgon / X-Khronos 签名"""
    ts = int(now)
    query_hash = hashlib.md5(query_str.encode()).digest()
    payload = bytearray(20)
    payload[0:4] = query_hash[0:4]
    stub = None
    if body:
        body_hash = hashlib.md5(body).digest()
        payload[4:8] = body_hash[0:4]
        stub = body_hash.hex().upper()
    payload[12:16] = bytes([0, 6, 11, 28])
    struct.pack_into(">I", payload, 16, ts)
    key = bytes([0x44, 0xb9, 0xb9, 0xd9, 0xa4, 0xae, 0xf9, 0xfc,
                 0xa4, 0x93, 0xaa, 0x75, 0x7c, 0xa3, 0xc2, 0xc4,
                 0xa4, 0x96, 0x93, 0x8f])
    for i in range(len(payload)):
        payload[i] ^= key[i]
    for i in range(len(payload)):
        payload[i] = _rev8(_rotl8(payload[i], 4) ^ payload[(i + 1) % len(payload)]) ^ 0xff ^ len(payload)
    signature = bytes([0x84, 0x04, 0x40, 0x1c, 0, 0]) + bytes(payload)
    return {
        "X-Khronos": str(ts),
        "X-Gorgon": signature.hex().upper(),
        "X-SS-Req-Ticket": str(int(now * 1000)),
        "X-SS-STUB": stub,
    }


def api_call(path, body, retries=3):
    """带 X-Gorgon 签名的新版请求，失败自动重试"""
    q = COMMON_QUERY()
    q["_rticket"] = str(int(time.time() * 1000))
    # 签名必须与实际发送的 query 顺序一致（Python requests 按 dict 插入顺序编码）
    qs = urlencode(q)
    body_bytes = json.dumps(body, separators=(",", ":")).encode() if body else b""
    headers = dict(HEADERS)
    sig = _sign_request(qs, body_bytes, time.time())
    for k, v in sig.items():
        if v:
            headers[k] = v
    last_err = None
    for attempt in range(retries):
        try:
            resp = requests.post(API + path, data=body_bytes, params=q, headers=headers, timeout=30)
            if len(resp.content) == 0:
                last_err = Exception(f"API返回空（风控）: {path}")
            else:
                data = resp.json()
                if data.get("code") not in (0, None) and data.get("Code") not in (0, None):
                    last_err = Exception(f"API错误: {data.get('message') or data.get('Message') or data}")
                else:
                    return data
        except Exception as e:
            last_err = e
        if attempt < retries - 1:
            time.sleep(2 * (attempt + 1))
    raise last_err or Exception(f"API调用失败: {path}")


def resolve_series_id(share_url):
    trimmed = share_url.strip()
    if trimmed.isdigit():
        return trimmed
    # 从混合文本中提取 URL
    m = re.search(r'https?://[^\s<>"\'，。]+', trimmed)
    if m:
        trimmed = m.group(0).rstrip('.,;:!?，。；：！？')
    resp = requests.get(trimmed, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 9; SM-N9860)"}, timeout=30, allow_redirects=True)
    final_url = resp.url
    m = re.search(r"video_series_id=(\d+)", final_url)
    if m:
        return m.group(1)
    m = re.search(r"schemeParams[^&]*", final_url)
    if m:
        from urllib.parse import unquote
        raw = unquote(unquote(m.group(0)))
        jm = re.search(r'"video_id"\s*:\s*"(\d+)"', raw)
        if jm:
            return jm.group(1)
    text = resp.text
    m = re.search(r"video_series_id=(\d+)", text) or re.search(r'"video_id"\s*:\s*"(\d+)"', text) or re.search(r'"series_id"\s*:\s*"(\d+)"', text)
    if m:
        return m.group(1)
    raise Exception("无法从链接解析 series_id")


def fetch_episode_list(series_id):
    """新版接口 video_detail/v1/ 获取剧集列表（带签名）"""
    body = {"series_id": series_id}
    j = api_call("/novel/player/video_detail/v1/", body)
    d = j.get("data", {})
    vd = d.get("video_data", {})
    vl = vd.get("video_list", [])
    eps = []
    for item in vl:
        eps.append({
            "vid": str(item["vid"]),
            "vid_index": int(item.get("vid_index", len(eps) + 1)),
            "title": item.get("title", ""),
        })
    eps.sort(key=lambda x: x["vid_index"])
    return {
        "series_id": series_id,
        "series_title": vd.get("series_title", "未命名"),
        "cover": vd.get("series_cover", ""),
        "episodes": eps,
    }


def fetch_web_play_url(series_id, vid):
    """Web 页面取流：novelquickapp.com/player/{seriesID}/{videoID} 页面内嵌 main_url（未加密）"""
    page_url = f"https://novelquickapp.com/player/{series_id}/{vid}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 16; 25053RT47C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        "Referer": "https://novelquickapp.com/",
    }
    resp = requests.get(page_url, headers=headers, timeout=30)
    text = resp.text
    idx = text.find("video_player_info")
    if idx < 0:
        return None
    seg = text[idx:idx + 6000]
    m = re.search(r'"main_url"\s*:\s*"([^"]+)"', seg)
    if not m:
        return None
    main_url = m.group(1).replace("\\u002f", "/").replace("\\u002F", "/").replace("\\/", "/")
    if not main_url.startswith("http"):
        return None
    return main_url


def fetch_play_url(series_id, vid):
    """取播放地址：先 Web 页面取流（未加密直链，最稳），再 App 接口（签名，加密流兜底）"""
    # 1) Web 页面取流（未加密直链，可直接下载无需解密）
    web_url = fetch_web_play_url(series_id, vid)
    if web_url:
        return web_url, None, False

    # 2) App 原生接口 video_model/v1/（带签名，可能加密）
    body = {
        "video_id": vid,
        "content_type": 1,
        "biz_param": {"need_all_video_definition": True, "video_platform": 3},
    }
    try:
        j = api_call("/novel/player/video_model/v1/", body)
        data = j.get("data", {})
        vm = data.get("video_model", {})
        if isinstance(vm, str):
            vm = json.loads(vm) if vm else {}
        vl = vm.get("video_list", [])
        best = None
        best_score = -1
        for v in vl:
            meta = v.get("video_meta", {})
            # 跳过 bytevc2（不兼容编码）
            codec = str(meta.get("codec_type", "")).lower()
            if codec == "bytevc2":
                continue
            defn = str(meta.get("definition", ""))
            dm = re.search(r"(\d+)", defn)
            p = int(dm.group(1)) if dm else 0
            w = int(meta.get("vwidth", 0))
            h = int(meta.get("vheight", 0))
            score = p * 1000000 + w * h
            if score > best_score:
                best_score = score
                enc = v.get("encrypt_info", {}) or {}
                best = (v.get("main_url", ""), enc.get("spade_a", ""))
        if best and best[0]:
            return best[0], best[1], True
    except Exception:
        pass

    return None, None, False


def av_base64_decode(s):
    chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    table = {ord(c): i for i, c in enumerate(chars)}
    dst = bytearray()
    i = 0
    while i + 4 <= len(s):
        vals = []
        ok = True
        for j in range(4):
            c = ord(s[i + j])
            if c in table:
                vals.append(table[c])
            else:
                ok = False
                break
        if not ok:
            v = 0
            for j2 in vals:
                v = (v << 6) | j2
            if len(vals) == 3:
                dst.append((v >> 10) & 0xff)
                dst.append((v >> 2) & 0xff)
            elif len(vals) == 2:
                dst.append((v >> 4) & 0xff)
            break
        v = (vals[0] << 18) | (vals[1] << 12) | (vals[2] << 6) | vals[3]
        dst.append((v >> 16) & 0xff)
        dst.append((v >> 8) & 0xff)
        dst.append(v & 0xff)
        i += 4
    return bytes(dst)


def derive_key(spade_a):
    if not spade_a:
        return None
    buf = av_base64_decode(spade_a)
    L = len(buf)
    if L < 3:
        return None
    key0 = buf[0] ^ buf[1] ^ buf[2]
    x27 = key0 - 0x30
    w22 = L - key0 + 0x2f
    if x27 < 2 or w22 < 2 or w22 > L - 1:
        return None
    x21 = bytearray(buf[1:1 + w22])
    w11, w12, w10 = 0x55, 0xfa, 0xeb
    for i in range(len(x21)):
        w13 = x21[i]
        pc = bin(i).count("1")
        w14 = w12 if (i & 1) == 0 else w11
        if (i & 1) == 1:
            w11 = w13
        else:
            w12 = w13
        w13_xor = w14 ^ x21[i]
        w14_sub = w10 - pc
        x21[i] = (w14_sub + w13_xor) & 0xff
    c0 = x21[0]
    if 0x30 <= c0 <= 0x39:
        hval = c0 - 0x30
    elif 0x61 <= c0 <= 0x7a:
        hval = c0 - 0x57
    else:
        return None
    w9 = w22 - hval
    if w9 < 2:
        return None
    str_a = x21[1:1 + w9 - 1].decode("ascii")
    if len(str_a) != 32:
        return None
    try:
        return bytes.fromhex(str_a)
    except Exception:
        return None


def decrypt_sample(key, nonce, cipher):
    nblocks = (len(cipher) + 15) // 16
    full = bytearray(nblocks * 16)
    for k in range(nblocks):
        full[k * 16:k * 16 + 8] = bytes(nonce[:8])
        struct.pack_into(">Q", full, k * 16 + 8, k)
    cipher_ecb = AES.new(bytes(key), AES.MODE_ECB)
    ks = cipher_ecb.encrypt(bytes(full))
    result = bytearray(len(cipher))
    for i in range(len(cipher)):
        result[i] = cipher[i] ^ ks[i]
    return bytes(result)


def parse_boxes(data, start, end):
    boxes = []
    off = start
    while off + 8 <= end:
        size = struct.unpack(">I", data[off:off+4])[0]
        typ = data[off+4:off+8].decode("latin1")
        hdr = 8
        if size == 1:
            size = struct.unpack(">Q", data[off+8:off+16])[0]
            hdr = 16
        elif size == 0:
            size = end - off
        if size < hdr or off + size > end:
            break
        children = None
        if typ in ["moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta"]:
            children = parse_boxes(data, off + hdr, off + size)
        boxes.append({"typ": typ, "off": off, "size": size, "hdr": hdr, "children": children})
        off += size
    return boxes


def decrypt_mp4_file(src_path, dst_path, key):
    with open(src_path, "rb") as f:
        data = bytearray(f.read())
    total = len(data)
    top = parse_boxes(data, 0, total)
    moov = next((b for b in top if b["typ"] == "moov"), None)
    if not moov:
        raise Exception("no moov box")

    stbls = []
    def walk(boxes):
        for b in boxes:
            if b["typ"] == "stbl":
                stbls.append(b)
            if b["children"]:
                walk(b["children"])
    walk(top)

    track_info = []
    for stbl in stbls:
        children = {c["typ"]: c for c in stbl["children"]}
        stsz = children.get("stsz")
        stco = children.get("stco") or children.get("co64")
        stsc = children.get("stsc")
        senc = children.get("senc")
        sbgp = children.get("sbgp")
        if not stsz or not stco or not stsc:
            continue
        ss = struct.unpack(">I", data[stsz["off"]+12:stsz["off"]+16])[0]
        n = struct.unpack(">I", data[stsz["off"]+16:stsz["off"]+20])[0]
        if ss == 0:
            sizes = [struct.unpack(">I", data[stsz["off"]+20+i*4:stsz["off"]+24+i*4])[0] for i in range(n)]
        else:
            sizes = [ss] * n
        # stco 用 4 字节偏移，co64 用 8 字节偏移
        is_co64 = stco["typ"] == "co64"
        nc = struct.unpack(">I", data[stco["off"]+12:stco["off"]+16])[0]
        if is_co64:
            chunk_offs = [struct.unpack(">Q", data[stco["off"]+16+i*8:stco["off"]+24+i*8])[0] for i in range(nc)]
        else:
            chunk_offs = [struct.unpack(">I", data[stco["off"]+16+i*4:stco["off"]+20+i*4])[0] for i in range(nc)]
        ns = struct.unpack(">I", data[stsc["off"]+12:stsc["off"]+16])[0]
        stsc_tab = []
        for i in range(ns):
            fc = struct.unpack(">I", data[stsc["off"]+16+i*12:stsc["off"]+20+i*12])[0]
            spc = struct.unpack(">I", data[stsc["off"]+20+i*12:stsc["off"]+24+i*12])[0]
            stsc_tab.append((fc, spc))
        ivs = []
        if senc:
            sc = struct.unpack(">I", data[senc["off"]+12:senc["off"]+16])[0]
            senc_flags = struct.unpack(">I", data[senc["off"]+8:senc["off"]+12])[0]
            # flags bit 1 = per_sample_iv_size
            iv_size = 8
            if senc_flags & 0x000002:
                # per_sample_iv_size 紧跟在 sample_count 后面
                iv_size = data[senc["off"]+16]
                iv_data_start = senc["off"] + 17
            else:
                iv_data_start = senc["off"] + 16
            for i in range(sc):
                ivs.append(bytes(data[iv_data_start+i*iv_size:iv_data_start+(i+1)*iv_size]))
        chunk_spc = {}
        for i, (fc, spc) in enumerate(stsc_tab):
            nxt = stsc_tab[i+1][0] if i + 1 < len(stsc_tab) else nc + 1
            for ci in range(fc - 1, nxt - 1):
                chunk_spc[ci] = spc
        sample_offs = []
        si = 0
        for ci in range(nc):
            off = chunk_offs[ci]
            spc = chunk_spc.get(ci, 1)
            for _ in range(spc):
                if si >= n:
                    break
                sample_offs.append(off)
                off += sizes[si]
                si += 1
        track_info.append({"sizes": sizes, "offs": sample_offs, "ivs": ivs})

    for ti in track_info:
        for i in range(len(ti["offs"])):
            off = ti["offs"][i]
            sz = ti["sizes"][i]
            if off + sz > total or i >= len(ti["ivs"]):
                continue
            nonce = ti["ivs"][i][:8]
            cipher = bytes(data[off:off+sz])
            plain = decrypt_sample(key, nonce, cipher)
            data[off:off+sz] = plain

    # Build stsd patches first
    patches = {}
    def find_stsd(boxes):
        for b in boxes:
            if b["typ"] == "stsd":
                content = b["off"] + 8
                count = struct.unpack(">I", data[content+4:content+8])[0]
                p = content + 8
                entries = []
                for _ in range(count):
                    esize = struct.unpack(">I", data[p:p+4])[0]
                    etyp = data[p+4:p+8].decode("latin1")
                    if etyp in ("encv", "enca"):
                        hdr_size = 78 if etyp == "encv" else 28
                        if etyp == "encv":
                            is_h265 = False
                            q2 = p + 8 + hdr_size
                            while q2 + 8 <= p + esize:
                                s3 = struct.unpack(">I", data[q2:q2+4])[0]
                                t3 = data[q2+4:q2+8].decode("latin1")
                                if t3 == "hvcC":
                                    is_h265 = True
                                    break
                                if t3 == "avcC":
                                    break
                                q2 += s3
                            new_type = "hvc1" if is_h265 else "avc1"
                        else:
                            new_type = "mp4a"
                        entry = bytearray(8 + hdr_size)
                        entry[4:8] = new_type.encode("latin1")
                        entry[8:8+hdr_size] = data[p+8:p+8+hdr_size]
                        extra = bytearray()
                        q = p + 8 + hdr_size
                        while q + 8 <= p + esize:
                            s2 = struct.unpack(">I", data[q:q+4])[0]
                            t2 = data[q+4:q+8].decode("latin1")
                            if s2 == 1:
                                s2 = struct.unpack(">Q", data[q+8:q+16])[0]
                            elif s2 == 0:
                                s2 = p + esize - q
                            if t2 != "sinf":
                                extra += data[q:q+s2]
                            q += s2
                        full_entry = entry + extra
                        struct.pack_into(">I", full_entry, 0, len(full_entry))
                        entries.append(bytes(full_entry))
                    else:
                        entries.append(bytes(data[p:p+esize]))
                    p += esize
                stsd_hdr = bytearray(data[b["off"]:content+8])
                struct.pack_into(">I", stsd_hdr, 12, len(entries))
                result = bytearray(bytes(stsd_hdr) + b"".join(entries))
                struct.pack_into(">I", result, 0, len(result))
                patches[b["off"]] = bytes(result)
            if b["children"]:
                find_stsd(b["children"])
    find_stsd(top)

    def rebuild_box(box):
        children = box["children"]
        if not children:
            return patches.get(box["off"], bytes(data[box["off"]:box["off"]+box["size"]]))
        new_children = []
        for c in children:
            if c["typ"] in ["senc", "saio", "saiz", "sgpd", "sbgp"]:
                continue
            sub = rebuild_box(c)
            new_children.append(sub)
        body = b"".join(new_children)
        hdr = bytearray(data[box["off"]:box["off"]+box["hdr"]])
        if box["hdr"] == 8:
            struct.pack_into(">I", hdr, 0, box["hdr"] + len(body))
        else:
            struct.pack_into(">I", hdr, 0, 1)
            struct.pack_into(">Q", hdr, 8, 16 + len(body))
        return bytes(hdr) + body

    # First pass: calculate delta
    first_moov = rebuild_box(moov)
    delta = moov["size"] - len(first_moov)

    # Adjust stco/co64 chunk offsets in data
    def find_stco(boxes):
        for b in boxes:
            if b["typ"] in ("stco", "co64"):
                nc = struct.unpack(">I", data[b["off"]+12:b["off"]+16])[0]
                if b["typ"] == "stco":
                    for i in range(nc):
                        v = struct.unpack(">I", data[b["off"]+16+i*4:b["off"]+20+i*4])[0]
                        if v >= delta:
                            struct.pack_into(">I", data, b["off"]+16+i*4, v - delta)
                else:
                    for i in range(nc):
                        v = struct.unpack(">Q", data[b["off"]+16+i*8:b["off"]+24+i*8])[0]
                        if v >= delta:
                            struct.pack_into(">Q", data, b["off"]+16+i*8, v - delta)
            if b["children"]:
                find_stco(b["children"])
    find_stco(top)

    new_moov = rebuild_box(moov)
    with open(dst_path, "wb") as f:
        off = 0
        while off < total:
            size = struct.unpack(">I", data[off:off+4])[0]
            typ = data[off+4:off+8].decode("latin1")
            hdr = 8
            if size == 1:
                size = struct.unpack(">Q", data[off+8:off+16])[0]
                hdr = 16
            elif size == 0:
                size = total - off
            if typ == "moov":
                f.write(new_moov)
            else:
                f.write(data[off:off+size])
            off += size
