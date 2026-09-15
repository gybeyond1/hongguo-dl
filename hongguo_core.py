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
from Crypto.Cipher import AES

API = "https://api5-normal-sinfonlineb.fqnovel.com"
UA = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"

COMMON_QUERY = {
    "klink_egdi": "AAI29o4dI-eMiO73_SRSbZ_0By1v3fUSriNeu8-L951MoXhWT88pzj5B",
    "iid": "3788260546453235",
    "device_id": "538083340353620",
    "ac": "wifi",
    "channel": "oppo_8662_64",
    "aid": "8662",
    "app_name": "novelread",
    "version_code": "71532",
    "version_name": "7.1.5.32",
    "device_platform": "android",
    "os": "android",
    "ssmix": "a",
    "device_type": "SM-N9860",
    "device_brand": "Samsung",
    "language": "zh",
    "os_api": "28",
    "os_version": "9",
    "manifest_version_code": "71532",
    "resolution": "900*1600",
    "dpi": "320",
    "update_version_code": "71532",
    "host_abi": "arm64-v8a",
    "dragon_device_type": "pad",
    "pv_player": "71532",
    "compliance_status": "0",
    "need_personal_recommend": "1",
    "player_so_load": "1",
    "is_android_pad_screen": "0",
    "rom_version": "PQ3A.190705.10241111+release-keys",
    "cdid": "b4f93387-5319-4134-aab9-2cd4e9279b8f",
}

HEADERS = {
    "User-Agent": UA,
    "Accept-Encoding": "gzip",
    "Accept": "application/json; charset=utf-8,application/x-protobuf",
    "Content-Type": "application/json; charset=utf-8",
    "Host": "api5-normal-sinfonlineb.fqnovel.com",
}

DETAIL_BIZ_PARAM = {
    "detail_page_version": 0,
    "disable_digg_stat": False,
    "image_shrink_datas_str": "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjkwMCwic2hyaW5rX3R5cGUiOjN9LHsiaW1hZ2VfdHlwZSI6NCwiaW1hZ2Vfd2lkdGgiOjcyLCJzaHJpbmtfdHlwZSI6NH1d",
    "need_all_video_definition": False,
    "need_mp4_align": False,
    "screen_width_px": "900",
    "source": 7,
    "use_os_player": False,
    "use_server_dns": False,
}

MODEL_BIZ_PARAM = {
    "detail_page_version": 0,
    "device_level": 3,
    "disable_digg_stat": False,
    "need_all_video_definition": True,
    "need_mp4_align": False,
    "use_os_player": False,
    "use_server_dns": False,
    "video_platform": 1024,
}


def api_call(path, body):
    q = dict(COMMON_QUERY)
    q["_rticket"] = str(int(time.time() * 1000))
    resp = requests.post(API + path, json=body, params=q, headers=HEADERS, timeout=30)
    data = resp.json()
    if data.get("code") != 0:
        raise Exception(f"API错误: {data.get('message', data)}")
    return data


def resolve_series_id(share_url):
    trimmed = share_url.strip()
    if trimmed.isdigit():
        return trimmed
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
    body = {"biz_param": DETAIL_BIZ_PARAM, "dr_scene": "preload", "series_id": series_id}
    j = api_call("/novel/player/multi_video_detail/preload/v1", body)
    d = j.get("data", {})
    sid = list(d.keys())[0]
    vd = d[sid].get("video_data", {})
    vl = vd.get("video_list", [])
    eps = []
    for item in vl:
        eps.append({
            "vid": str(item["vid"]),
            "vid_index": item.get("vid_index", 0),
            "title": item.get("title", ""),
        })
    eps.sort(key=lambda x: x["vid_index"])
    return {
        "series_id": sid,
        "series_title": vd.get("series_title", "未命名"),
        "cover": vd.get("series_cover", ""),
        "episodes": eps,
    }


def fetch_play_url(vid):
    body = {
        "biz_param": MODEL_BIZ_PARAM,
        "dr_scene": "preload",
        "mixed_video_id_map": {"1004": [vid]},
    }
    j = api_call("/novel/player/multi_video_model/preload/v1", body)
    item = j.get("data", {}).get(vid, {})
    vm_str = item.get("video_model", "")
    vm = json.loads(vm_str) if isinstance(vm_str, str) else vm_str
    vl = vm.get("video_list", [])
    best = None
    best_score = -1
    for v in vl:
        meta = v.get("video_meta", {})
        defn = str(meta.get("definition", ""))
        dm = re.search(r"(\d+)", defn)
        p = int(dm.group(1)) if dm else 0
        w = int(meta.get("vwidth", 0))
        h = int(meta.get("vheight", 0))
        br = int(meta.get("bitrate", 0))
        score = p * 1000000 + w * h + br
        if score > best_score:
            best_score = score
            best = (v.get("main_url", ""), v.get("encrypt_info", {}).get("spade_a", ""))
    if not best:
        return None, None
    return best[0], best[1]


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
        full[k * 16:k * 16 + 8] = nonce[:8]
        struct.pack_into(">Q", full, k * 16 + 8, k)
    cipher_ecb = AES.new(key, AES.MODE_ECB)
    ks = cipher_ecb.encrypt(bytes(full))
    return bytes(c ^ k for c, k in zip(cipher, ks))


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
        stsz, stco, stsc, senc = children.get("stsz"), children.get("stco"), children.get("stsc"), children.get("senc")
        if not stsz or not stco or not stsc:
            continue
        ss = struct.unpack(">I", data[stsz["off"]+12:stsz["off"]+16])[0]
        n = struct.unpack(">I", data[stsz["off"]+16:stsz["off"]+20])[0]
        if ss == 0:
            sizes = [struct.unpack(">I", data[stsz["off"]+20+i*4:stsz["off"]+24+i*4])[0] for i in range(n)]
        else:
            sizes = [ss] * n
        nc = struct.unpack(">I", data[stco["off"]+12:stco["off"]+16])[0]
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
            for i in range(sc):
                ivs.append(bytes(data[senc["off"]+16+i*8:senc["off"]+24+i*8]))
        chunk_spc = {}
        for i, (fc, spc) in enumerate(stsc_tab):
            nxt = stsc_tab[i+1][0] - 1 if i + 1 < len(stsc_tab) else nc + 1
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

    def rebuild_box(box):
        children = box["children"]
        if not children:
            return None
        new_children = []
        for c in children:
            if c["typ"] in ["senc", "saio", "saiz", "sgpd", "sbgp"]:
                continue
            if c["typ"] == "stsd":
                content = c["off"] + 8
                count = struct.unpack(">I", data[content+4:content+8])[0]
                p = content + 8
                entries = []
                for _ in range(count):
                    esize = struct.unpack(">I", data[p:p+4])[0]
                    etyp = data[p+4:p+8].decode("latin1")
                    if etyp in ("encv", "enca"):
                        new_type = "hvc1" if etyp == "encv" else "mp4a"
                        hdr_size = 78 if etyp == "encv" else 28
                        entry = bytearray(8 + hdr_size)
                        entry[4:8] = new_type.encode("latin1")
                        entry[8:8+hdr_size] = data[p+8:p+8+hdr_size]
                        extra = bytearray()
                        q = p + 8 + hdr_size
                        while q + 8 <= p + esize:
                            s2 = struct.unpack(">I", data[q:q+4])[0]
                            t2 = data[q+4:q+8].decode("latin1")
                            if t2 != "sinf":
                                extra += data[q:q+s2]
                            q += s2
                        full_entry = entry + extra
                        struct.pack_into(">I", full_entry, 0, len(full_entry))
                        entries.append(bytes(full_entry))
                    else:
                        entries.append(bytes(data[p:p+esize]))
                    p += esize
                stsd_hdr = bytearray(data[c["off"]:content+8])
                struct.pack_into(">I", stsd_hdr, 12, len(entries))
                result = bytes(stsd_hdr) + b"".join(entries)
                struct.pack_into(">I", result, 0, len(result))
                new_children.append(result)
            else:
                sub = rebuild_box(c)
                if sub is not None:
                    new_children.append(sub)
        body = b"".join(new_children)
        hdr = bytearray(box["hdr"])
        if box["hdr"] == 8:
            struct.pack_into(">I", hdr, 0, box["hdr"] + len(body))
        else:
            struct.pack_into(">I", hdr, 0, 1)
            struct.pack_into(">Q", hdr, 8, 16 + len(body))
        return bytes(hdr) + body

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
