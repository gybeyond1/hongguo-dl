package com.hongguo.dl;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Port of hongguo_core.py MP4 AES-CTR decryption
 */
public class HongguoDecrypt {

    static byte[] avBase64Decode(String s) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        Map<Integer, Integer> table = new HashMap<>();
        for (int i = 0; i < chars.length(); i++) table.put((int) chars.charAt(i), i);
        ByteArrayOutputStream dst = new ByteArrayOutputStream();
        int i = 0;
        while (i + 4 <= s.length()) {
            int[] vals = new int[4];
            boolean ok = true;
            for (int j = 0; j < 4; j++) {
                int c = s.charAt(i + j);
                if (table.containsKey(c)) vals[j] = table.get(c);
                else { ok = false; break; }
            }
            if (!ok) {
                int v = 0;
                for (int j2 : vals) v = (v << 6) | j2;
                if (vals.length == 3) { dst.write((v >> 10) & 0xff); dst.write((v >> 2) & 0xff); }
                else if (vals.length == 2) dst.write((v >> 4) & 0xff);
                break;
            }
            int v = (vals[0] << 18) | (vals[1] << 12) | (vals[2] << 6) | vals[3];
            dst.write((v >> 16) & 0xff);
            dst.write((v >> 8) & 0xff);
            dst.write(v & 0xff);
            i += 4;
        }
        return dst.toByteArray();
    }

    static byte[] deriveKey(String spadeA) {
        if (spadeA == null || spadeA.isEmpty()) return null;
        try {
            // Use standard Base64 (with padding)
            String padded = spadeA;
            while (padded.length() % 4 != 0) padded += "=";
            byte[] buf = java.util.Base64.getDecoder().decode(padded);
            int L = buf.length;
            if (L < 3) return null;
            int key0 = (buf[0] & 0xff) ^ (buf[1] & 0xff) ^ (buf[2] & 0xff);
            int x27 = key0 - 0x30;
            int w22 = L - key0 + 0x2f;
            if (x27 < 2 || w22 < 2 || w22 > L - 1) return null;
            byte[] x21 = Arrays.copyOfRange(buf, 1, 1 + w22);
            int w11 = 0x55, w12 = 0xfa, w10 = 0xeb;
            for (int i = 0; i < x21.length; i++) {
                int w13 = x21[i] & 0xff;
                int pc = Integer.bitCount(i);
                int w14 = (i & 1) == 0 ? w12 : w11;
                if ((i & 1) == 1) w11 = w13;
                else w12 = w13;
                int w13xor = w14 ^ w13;
                int w14sub = w10 - pc;
                x21[i] = (byte) ((w14sub + w13xor) & 0xff);
            }
            int c0 = x21[0] & 0xff;
            int hval;
            if (c0 >= 0x30 && c0 <= 0x39) hval = c0 - 0x30;
            else if (c0 >= 0x61 && c0 <= 0x7a) hval = c0 - 0x57;
            else return null;
            int w9 = w22 - hval;
            if (w9 < 2) return null;
            String strA = new String(x21, 1, w9 - 1);
            if (strA.length() != 32) return null;
            return hexDecode(strA);
        } catch (Exception e) { return null; }
    }

    static byte[] hexDecode(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    static byte[] decryptSample(byte[] key, byte[] nonce, byte[] cipher) throws Exception {
        int nblocks = (cipher.length + 15) / 16;
        byte[] full = new byte[nblocks * 16];
        for (int k = 0; k < nblocks; k++) {
            System.arraycopy(nonce, 0, full, k * 16, 8);
            ByteBuffer.wrap(full, k * 16 + 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(k);
        }
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] ks = ecb.doFinal(full);
        byte[] result = new byte[cipher.length];
        for (int i = 0; i < cipher.length; i++) result[i] = (byte)(cipher[i] ^ ks[i]);
        return result;
    }

    static class Box {
        String typ;
        int off, size, hdr;
        List<Box> children;
    }

    static List<Box> parseBoxes(byte[] data, int start, int end) {
        List<Box> boxes = new ArrayList<>();
        int off = start;
        while (off + 8 <= end) {
            ByteBuffer bb = ByteBuffer.wrap(data, off, 8).order(ByteOrder.BIG_ENDIAN);
            int size = bb.getInt();
            String typ = new String(data, off + 4, 4);
            int hdr = 8;
            if (size == 1) {
                size = (int) ByteBuffer.wrap(data, off + 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                hdr = 16;
            } else if (size == 0) size = end - off;
            if (size < hdr || off + size > end) break;
            List<Box> children = null;
            if (Arrays.asList("moov","trak","mdia","minf","stbl","edts","dinf","udta","meta").contains(typ)) {
                children = parseBoxes(data, off + hdr, off + size);
            }
            Box b = new Box();
            b.typ = typ; b.off = off; b.size = size; b.hdr = hdr; b.children = children;
            boxes.add(b);
            off += size;
        }
        return boxes;
    }

    static int getInt32(byte[] data, int off) {
        return ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }
    static long getInt64(byte[] data, int off) {
        return ByteBuffer.wrap(data, off, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }
    static void putInt32(byte[] data, int off, int val) {
        ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).putInt(val);
    }

    public static void decryptMp4File(String srcPath, String dstPath, byte[] key) throws Exception {
        byte[] data;
        try (FileInputStream fis = new FileInputStream(srcPath)) {
            data = new byte[fis.available()];
            int read = 0;
            while (read < data.length) { int n = fis.read(data, read, data.length - read); if (n <= 0) break; read += n; }
        }
        int total = data.length;
        List<Box> top = parseBoxes(data, 0, total);
        Box moov = null;
        for (Box b : top) if (b.typ.equals("moov")) { moov = b; break; }
        if (moov == null) throw new Exception("no moov box");

        List<Box> stbls = new ArrayList<>();
        walkStbl(top, stbls);

        List<Map<String,Object>> trackInfo = new ArrayList<>();
        for (Box stbl : stbls) {
            Map<String,Box> children = new HashMap<>();
            for (Box c : stbl.children) children.put(c.typ, c);
            Box stsz = children.get("stsz");
            Box stco = children.get("stco");
            if (stco == null) stco = children.get("co64");
            Box stsc = children.get("stsc");
            Box senc = children.get("senc");
            if (stsz == null || stco == null || stsc == null) continue;

            int ss = getInt32(data, stsz.off + 12);
            int n = getInt32(data, stsz.off + 16);
            List<Integer> sizes = new ArrayList<>();
            if (ss == 0) {
                for (int i = 0; i < n; i++) sizes.add(getInt32(data, stsz.off + 20 + i * 4));
            } else {
                for (int i = 0; i < n; i++) sizes.add(ss);
            }
            boolean isCo64 = stco.typ.equals("co64");
            int nc = getInt32(data, stco.off + 12);
            List<Long> chunkOffs = new ArrayList<>();
            if (isCo64) {
                for (int i = 0; i < nc; i++) chunkOffs.add(getInt64(data, stco.off + 16 + i * 8));
            } else {
                for (int i = 0; i < nc; i++) chunkOffs.add((long) getInt32(data, stco.off + 16 + i * 4));
            }
            int ns = getInt32(data, stsc.off + 12);
            List<int[]> stscTab = new ArrayList<>();
            for (int i = 0; i < ns; i++) {
                int fc = getInt32(data, stsc.off + 16 + i * 12);
                int spc = getInt32(data, stsc.off + 20 + i * 12);
                stscTab.add(new int[]{fc, spc});
            }
            List<byte[]> ivs = new ArrayList<>();
            if (senc != null) {
                int sc = getInt32(data, senc.off + 12);
                int sencFlags = getInt32(data, senc.off + 8);
                int ivSize = 8;
                int ivDataStart;
                if ((sencFlags & 0x000002) != 0) {
                    ivSize = data[senc.off + 16] & 0xff;
                    ivDataStart = senc.off + 17;
                } else {
                    ivDataStart = senc.off + 16;
                }
                for (int i = 0; i < sc; i++) {
                    ivs.add(Arrays.copyOfRange(data, ivDataStart + i * ivSize, ivDataStart + (i + 1) * ivSize));
                }
            }
            Map<Integer,Integer> chunkSpc = new HashMap<>();
            for (int i = 0; i < stscTab.size(); i++) {
                int fc = stscTab.get(i)[0];
                int spc = stscTab.get(i)[1];
                int nxt = (i + 1 < stscTab.size()) ? stscTab.get(i+1)[0] : nc + 1;
                for (int ci = fc - 1; ci < nxt - 1; ci++) chunkSpc.put(ci, spc);
            }
            List<Integer> sampleOffs = new ArrayList<>();
            int si = 0;
            for (int ci = 0; ci < nc; ci++) {
                long off = chunkOffs.get(ci);
                int spc = chunkSpc.getOrDefault(ci, 1);
                for (int j = 0; j < spc; j++) {
                    if (si >= n) break;
                    sampleOffs.add((int) off);
                    off += sizes.get(si);
                    si++;
                }
            }
            Map<String,Object> ti = new HashMap<>();
            ti.put("sizes", sizes);
            ti.put("offs", sampleOffs);
            ti.put("ivs", ivs);
            trackInfo.add(ti);
        }

        for (Map<String,Object> ti : trackInfo) {
            @SuppressWarnings("unchecked")
            List<Integer> offs = (List<Integer>) ti.get("offs");
            @SuppressWarnings("unchecked")
            List<Integer> sizes = (List<Integer>) ti.get("sizes");
            @SuppressWarnings("unchecked")
            List<byte[]> ivs = (List<byte[]>) ti.get("ivs");
            for (int i = 0; i < offs.size(); i++) {
                int off = offs.get(i);
                int sz = sizes.get(i);
                if (off + sz > total || i >= ivs.size()) continue;
                byte[] nonce = Arrays.copyOfRange(ivs.get(i), 0, 8);
                byte[] cipher = Arrays.copyOfRange(data, off, off + sz);
                byte[] plain = decryptSample(key, nonce, cipher);
                System.arraycopy(plain, 0, data, off, sz);
            }
        }

        // stsd patches
        Map<Integer, byte[]> patches = new HashMap<>();
        for (Box tb : top) findStsd(tb, data, patches);

        // rebuild moov
        byte[] newMoov = rebuildBox(moov, data, patches, null);
        int delta = moov.size - newMoov.length;

        // adjust stco/co64
        for (Box tb : top) findStco(tb, data, delta);

        newMoov = rebuildBox(moov, data, patches, null);

        try (FileOutputStream fos = new FileOutputStream(dstPath)) {
            int off = 0;
            while (off < total) {
                int size = getInt32(data, off);
                String typ = new String(data, off + 4, 4);
                int hdr = 8;
                if (size == 1) {
                    size = (int) getInt64(data, off + 8);
                    hdr = 16;
                } else if (size == 0) size = total - off;
                if (typ.equals("moov")) {
                    fos.write(newMoov);
                } else {
                    fos.write(data, off, size);
                }
                off += size;
            }
        }
    }

    static void walkStbl(List<Box> boxes, List<Box> out) {
        for (Box b : boxes) {
            if (b.typ.equals("stbl")) out.add(b);
            if (b.children != null) walkStbl(b.children, out);
        }
    }

    static void findStsd(Box box, byte[] data, Map<Integer,byte[]> patches) {
        if (box.typ.equals("stsd")) {
            int content = box.off + 8;
            int count = getInt32(data, content + 4);
            int p = content + 8;
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            for (int e = 0; e < count; e++) {
                int esize = getInt32(data, p);
                String etyp = new String(data, p + 4, 4);
                if (etyp.equals("encv") || etyp.equals("enca")) {
                    int hdrSize = etyp.equals("encv") ? 78 : 28;
                    String newType;
                    if (etyp.equals("encv")) {
                        boolean isH265 = false;
                        int q2 = p + 8 + hdrSize;
                        while (q2 + 8 <= p + esize) {
                            int s3 = getInt32(data, q2);
                            String t3 = new String(data, q2 + 4, 4);
                            if (t3.equals("hvcC")) { isH265 = true; break; }
                            if (t3.equals("avcC")) break;
                            q2 += s3;
                        }
                        newType = isH265 ? "hvc1" : "avc1";
                    } else {
                        newType = "mp4a";
                    }
                    ByteArrayOutputStream entry = new ByteArrayOutputStream();
                    // size placeholder
                    entry.write(new byte[4], 0, 4);
                    entry.writeBytes(newType.getBytes());
                    entry.write(data, p + 8, hdrSize);
                    // extra boxes (skip sinf)
                    int q = p + 8 + hdrSize;
                    while (q + 8 <= p + esize) {
                        int s2 = getInt32(data, q);
                        String t2 = new String(data, q + 4, 4);
                        if (s2 == 1) s2 = (int) getInt64(data, q + 8);
                        else if (s2 == 0) s2 = p + esize - q;
                        if (!t2.equals("sinf")) entry.write(data, q, s2);
                        q += s2;
                    }
                    byte[] fullEntry = entry.toByteArray();
                    putInt32(fullEntry, 0, fullEntry.length);
                    result.write(fullEntry, 0, fullEntry.length);
                } else {
                    result.write(data, p, esize);
                }
                p += esize;
            }
            // stsd header
            int hdrLen = content + 8 - box.off;
            byte[] entries = result.toByteArray();
            byte[] fullResult = new byte[hdrLen + entries.length];
            System.arraycopy(data, box.off, fullResult, 0, hdrLen);
            System.arraycopy(entries, 0, fullResult, hdrLen, entries.length);
            putInt32(fullResult, 0, fullResult.length);
            putInt32(fullResult, 12, count);
            patches.put(box.off, fullResult);
        }
        if (box.children != null) {
            for (Box c : box.children) findStsd(c, data, patches);
        }
    }

    static byte[] rebuildBox(Box box, byte[] data, Map<Integer,byte[]> patches, Set<String> skip) {
        if (box.children == null) {
            byte[] patched = patches.get(box.off);
            if (patched != null) return patched;
            return Arrays.copyOfRange(data, box.off, box.off + box.size);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Box c : box.children) {
            if (Arrays.asList("senc","saio","saiz","sgpd","sbgp").contains(c.typ)) continue;
            byte[] sub = rebuildBox(c, data, patches, null);
            body.write(sub, 0, sub.length);
        }
        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        if (box.hdr == 8) {
            byte[] hdr = new byte[8];
            putInt32(hdr, 0, 8 + bodyBytes.length);
            System.arraycopy(data, box.off + 4, hdr, 4, 4);
            result.write(hdr, 0, 8);
        } else {
            byte[] hdr = new byte[16];
            putInt32(hdr, 0, 1);
            System.arraycopy(data, box.off + 4, hdr, 4, 4);
            ByteBuffer.wrap(hdr, 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(16 + bodyBytes.length);
            result.write(hdr, 0, 16);
        }
        result.write(bodyBytes, 0, bodyBytes.length);
        return result.toByteArray();
    }

    static void findStco(Box box, byte[] data, int delta) {
        if (box.typ.equals("stco") || box.typ.equals("co64")) {
            int nc = getInt32(data, box.off + 12);
            if (box.typ.equals("stco")) {
                for (int i = 0; i < nc; i++) {
                    int v = getInt32(data, box.off + 16 + i * 4);
                    if (v >= delta) putInt32(data, box.off + 16 + i * 4, v - delta);
                }
            } else {
                for (int i = 0; i < nc; i++) {
                    long v = getInt64(data, box.off + 16 + i * 8);
                    if (v >= delta) ByteBuffer.wrap(data, box.off + 16 + i * 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(v - delta);
                }
            }
        }
        if (box.children != null) for (Box c : box.children) findStco(c, data, delta);
    }
}
