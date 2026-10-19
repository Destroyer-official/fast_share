import time
import urllib.request
import urllib.parse
import os
import base64

PC_URL = "http://localhost:8000"
PHONE_URL = "http://192.168.31.67:8000"
AUTH_USER = os.environ.get("FAST_SHARE_USER", "share")
AUTH_PASSWORD = os.environ.get("FAST_SHARE_PASSWORD", "")
AUTH_HEADER = "Basic " + base64.b64encode(f"{AUTH_USER}:{AUTH_PASSWORD}".encode()).decode("ascii")

def benchmark_pc_loopback():
    print("=== PC Rust Server Loopback Benchmark (Memory/SSD I/O) ===")
    test_size = 50 * 1024 * 1024 # 50 MB
    payload = os.urandom(test_size)
    
    # 1. Upload to PC
    t0 = time.perf_counter()
    req = urllib.request.Request(f"{PC_URL}/api/raw?path=scratch_test&name=benchmark_test.bin", data=payload, method="POST")
    req.add_header("Authorization", AUTH_HEADER)
    req.add_header("Content-Type", "application/octet-stream")
    with urllib.request.urlopen(req, timeout=30) as resp:
        _ = resp.read()
    t1 = time.perf_counter()
    up_speed_mb = (test_size / (1024 * 1024)) / (t1 - t0)
    up_speed_mbps = up_speed_mb * 8
    print(f"  PC Ingestion (Upload): {up_speed_mb:.2f} MB/s ({up_speed_mbps:.2f} Mbps) in {t1-t0:.2f}s")
    
    # 2. Download from PC
    t0 = time.perf_counter()
    req = urllib.request.Request(f"{PC_URL}/api/download?path=scratch_test%2Fbenchmark_test.bin")
    req.add_header("Authorization", AUTH_HEADER)
    downloaded = 0
    with urllib.request.urlopen(req, timeout=30) as resp:
        while True:
            chunk = resp.read(2 * 1024 * 1024)
            if not chunk:
                break
            downloaded += len(chunk)
    t1 = time.perf_counter()
    down_speed_mb = (downloaded / (1024 * 1024)) / (t1 - t0)
    down_speed_mbps = down_speed_mb * 8
    print(f"  PC Streaming (Download): {down_speed_mb:.2f} MB/s ({down_speed_mbps:.2f} Mbps) in {t1-t0:.2f}s")

def benchmark_phone_over_wifi():
    print("\n=== PC <-> Android Phone (5 GHz Wi-Fi) Benchmark ===")
    test_size = 25 * 1024 * 1024 # 25 MB
    payload = os.urandom(test_size)
    
    # 1. PC -> Phone Upload
    try:
        t0 = time.perf_counter()
        req = urllib.request.Request(f"{PHONE_URL}/api/raw?path=%2Fsdcard%2FDownload&name=speedtest_25mb.bin", data=payload, method="POST")
        req.add_header("Authorization", PHONE_AUTH)
        req.add_header("Content-Type", "application/octet-stream")
        with urllib.request.urlopen(req, timeout=60) as resp:
            res = resp.read().decode("utf-8", errors="ignore")
        t1 = time.perf_counter()
        up_speed_mb = (test_size / (1024 * 1024)) / (t1 - t0)
        up_speed_mbps = up_speed_mb * 8
        print(f"  PC -> Phone Upload: {up_speed_mb:.2f} MB/s ({up_speed_mbps:.2f} Mbps) in {t1-t0:.2f}s [Response: {res.strip()}]")
    except Exception as e:
        print("  PC -> Phone Upload Failed:", e)

    # 2. Phone -> PC Download
    try:
        t0 = time.perf_counter()
        req = urllib.request.Request(f"{PHONE_URL}/api/download?path=%2Fsdcard%2FDownload%2Fspeedtest_25mb.bin")
        req.add_header("Authorization", PHONE_AUTH)
        downloaded = 0
        with urllib.request.urlopen(req, timeout=60) as resp:
            while True:
                chunk = resp.read(2 * 1024 * 1024)
                if not chunk:
                    break
                downloaded += len(chunk)
        t1 = time.perf_counter()
        down_speed_mb = (downloaded / (1024 * 1024)) / (t1 - t0)
        down_speed_mbps = down_speed_mb * 8
        print(f"  Phone -> PC Download: {down_speed_mb:.2f} MB/s ({down_speed_mbps:.2f} Mbps) in {t1-t0:.2f}s ({downloaded / 1024 / 1024:.1f} MB)")
    except Exception as e:
        print("  Phone -> PC Download Failed:", e)

if __name__ == "__main__":
    benchmark_pc_loopback()
    benchmark_phone_over_wifi()
