"""
FAST Share — Full-device file manager (PC <-> Android)
======================================================
ONE file, zero dependencies, works on BOTH sides:

    PC (Windows/Linux/Mac):  python share.py [port] [--password YOUR_PASSWORD]
    -> shares ENTIRE PC (all drives C:, D:, ... + Quick access)
    -> automatically opens your browser: http://localhost:8000
    -> open from Android browser: http://<pc-ip>:8000

    ANDROID (Termux):  pkg install python -y && python share.py [port] [--password YOUR_PASSWORD]
    -> shares ENTIRE ANDROID (/sdcard, storage, Download, DCIM...)
    -> open from PC browser: http://<android-ip>:8000

Speed: threaded server, TCP_NODELAY, Range/resume, OS sendfile zero-copy (POSIX),
raw parallel uploads via /api/raw with .part + rename safety, instant STORED streaming
folder-ZIPs (no recompression), socket buffers tuned on Windows, os.scandir listing.

UI: Apple Liquid Glass + Google Material 3 Expressive
  - Cosmic aurora mesh gradient backdrop with ambient glowing glass orbs.
  - Floating frosted glass shells with real-time blur, specular edge highlights.
  - Nearby Devices Radar (UDP 8002 auto-discovery for Android phones and laptops).
  - In-Page Liquid Glass authentication modal (zero browser-native 401 prompts!).
  - Localhost auto-authentication (zero typing needed when run on PC).
  - Quick Send drag-and-drop zone and storage drive navigator.
  - Floating Transfer HUD with live MB/s, progress bar, and ETA.
"""
import http.server
import socketserver
import socket
import os
import re
import json
import sys
import string
import shutil
import platform
import tempfile
import zipfile
import urllib.parse
import urllib.request
import mimetypes
import time
import itertools
import base64
import hmac
import secrets
import threading
import webbrowser
from datetime import datetime
import io
import ipaddress
try:
    from PIL import Image, ImageOps
    _HAS_PIL = True
except ImportError:
    _HAS_PIL = False

_THUMB_CACHE = {}
_THUMB_LOCK = threading.Lock()

def get_thumbnail_bytes(filepath):
    try:
        mtime = os.path.getmtime(filepath)
    except OSError:
        return None
    cache_key = f"{filepath}:{mtime}"
    with _THUMB_LOCK:
        if cache_key in _THUMB_CACHE:
            return _THUMB_CACHE[cache_key]

    ext = os.path.splitext(filepath)[1].lower()
    if _HAS_PIL and ext in ('.jpg', '.jpeg', '.png', '.webp', '.bmp', '.gif'):
        try:
            with Image.open(filepath) as img:
                try:
                    img = ImageOps.exif_transpose(img)
                except Exception:
                    pass
                img.thumbnail((320, 320))
                if img.mode in ("RGBA", "P"):
                    img = img.convert("RGB")
                buf = io.BytesIO()
                img.save(buf, format="JPEG", quality=80, optimize=True)
                data = buf.getvalue()
                with _THUMB_LOCK:
                    if len(_THUMB_CACHE) > 500:
                        _THUMB_CACHE.clear()
                    _THUMB_CACHE[cache_key] = data
                return data
        except Exception:
            pass

    try:
        if os.path.getsize(filepath) <= 5 * 1024 * 1024 and ext in ('.jpg', '.jpeg', '.png', '.webp', '.bmp', '.gif', '.svg'):
            with open(filepath, 'rb') as f:
                data = f.read()
            with _THUMB_LOCK:
                if len(_THUMB_CACHE) > 500:
                    _THUMB_CACHE.clear()
                _THUMB_CACHE[cache_key] = data
            return data
    except Exception:
        pass
    return None


_ZIP_SEQ = itertools.count(1)
_RAW_SEQ = itertools.count(1)
_ROOTS_CACHE = {"at": 0.0, "roots": None}
_ROOTS_TTL = 10.0  # disk_usage on every request is wasteful; refresh at most 1x/10s
DISCOVERY_PORT = 8002
NEARBY_DEVICES = {}
_NEARBY_LOCK = threading.Lock()
_SESSION_LOCK = threading.Lock()
VALID_SESSIONS = set()
LOCAL_TOKEN = secrets.token_urlsafe(32)
VALID_SESSIONS.add(LOCAL_TOKEN)
CURRENT_BEACON = None


def get_roots_cached():
    now = time.monotonic()
    if _ROOTS_CACHE["roots"] is None or now - _ROOTS_CACHE["at"] > _ROOTS_TTL:
        _ROOTS_CACHE["roots"] = get_roots()
        _ROOTS_CACHE["at"] = now
    return _ROOTS_CACHE["roots"]


def get_config_paths():
    home_cfg = os.path.join(os.path.expanduser("~"), ".fastshare_cred.json")
    local_cfg = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fastshare_cred.json")
    return home_cfg, local_cfg


def parse_options():
    port = 8000
    user = os.environ.get("FAST_SHARE_USER")
    password = os.environ.get("FAST_SHARE_PASSWORD")
    no_browser = bool(os.environ.get("FAST_SHARE_NO_BROWSER"))
    args = sys.argv[1:]
    idx = 0
    while idx < len(args):
        arg = args[idx]
        if arg in ("-h", "--help"):
            print("""FastShare — High-speed cross-platform file sharing (PC <-> Android)
Usage:
  python share.py [port] [--user USERNAME] [--password PASSWORD] [--no-browser]

Options:
  [port]               Port to listen on (default: 8000)
  --user USER          Username for HTTP Basic authentication (default: share)
  --password PASS      Password for authentication (auto-generated if omitted)
  --no-browser, -n     Do not automatically open the default web browser on launch
  -h, --help           Show this help message and exit
  -v, --version        Show version and exit
""")
            sys.exit(0)
        elif arg in ("-v", "--version"):
            print("FastShare v3.5.0")
            sys.exit(0)
        elif arg in ("-n", "--no-browser"):
            no_browser = True
            idx += 1
        elif arg == "--password" and idx + 1 < len(args):
            password = args[idx + 1]
            idx += 2
        elif arg == "--user" and idx + 1 < len(args):
            user = args[idx + 1]
            idx += 2
        elif arg.isdigit():
            port = int(arg)
            idx += 1
        else:
            idx += 1

    home_cfg, local_cfg = get_config_paths()
    if not password or not user:
        for cfg_path in (local_cfg, home_cfg):
            if os.path.isfile(cfg_path):
                try:
                    with open(cfg_path, "r", encoding="utf-8") as f:
                        data = json.load(f)
                        if not password and data.get("password"):
                            password = data.get("password")
                        if not user and data.get("user"):
                            user = data.get("user")
                except Exception:
                    pass

    if not user:
        user = "share"
    if not password:
        password = secrets.token_urlsafe(12)

    try:
        cfg_data = json.dumps({"user": user, "password": password, "port": port}, indent=2)
        for cfg_path in (local_cfg, home_cfg):
            with open(cfg_path, "w", encoding="utf-8") as f:
                f.write(cfg_data)
    except Exception:
        pass

    return port, user, password, no_browser


PORT, USERNAME, PASSWORD, NO_BROWSER = parse_options()
CHUNK = 2 * 1024 * 1024  # 2 MB buffer for gigabit Wi-Fi throughput
BIG_DIRECT = 50 * 1024 * 1024  # >50MB: use native direct browser streaming for maximum speed


# ---------------------------------------------------------------- platform
def is_android():
    if "com.termux" in os.environ.get("PREFIX", ""):
        return True
    if os.environ.get("ANDROID_ROOT", "") == "/system":
        return True
    if os.path.exists("/system/build.prop"):
        return True
    return False


def device_label():
    if is_android():
        try:
            model = ""
            for p in ("/system/build.prop",):
                if os.path.exists(p):
                    with open(p, errors="ignore") as f:
                        for line in f:
                            if line.startswith("ro.product.model="):
                                model = line.strip().split("=", 1)[1]
                                break
                    if model:
                        break
        except Exception:
            model = ""
        return ("Android " + model).strip() or "Android device"
    return f"{platform.system()} {socket.gethostname()}"


def get_lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        try:
            ip = socket.gethostbyname(socket.gethostname())
        except Exception:
            ip = "127.0.0.1"
    finally:
        s.close()
    return ip


def disk_of(path):
    try:
        total, used, free = shutil.disk_usage(path)
        return total, free, used
    except Exception:
        return 0, 0, 0


def get_roots():
    """Entire-device roots. Windows -> all drives. Android -> all storage.
    Linux/Mac -> /, home, /media, /mnt. Always the WHOLE device, never one dir."""
    roots = []
    seen = set()

    def add(name, path, icon=""):
        try:
            ap = os.path.abspath(os.path.normpath(path))
        except Exception:
            return
        if not os.path.exists(ap):
            return
        key = ap.lower() if os.name == "nt" else ap
        if key in seen:
            return
        seen.add(key)
        total, free, used = disk_of(ap)
        roots.append({"name": name, "path": ap, "total": total,
                      "free": free, "used": used, "icon": icon})

    if os.name == "nt":
        for letter in string.ascii_uppercase:
            p = f"{letter}:\\"
            if os.path.exists(p):
                add(f"Drive {letter}:", p, "💽")
    if is_android():
        add("Internal storage", "/sdcard", "📱")
        add("Internal storage", "/storage/emulated/0", "📱")
        home = os.path.expanduser("~")
        add("Termux home", home, "🏠")
        for c in (os.path.join(home, "storage", "shared"),
                  os.path.join(home, "storage", "downloads"),
                  os.path.join(home, "storage", "dcim"),
                  os.path.join(home, "storage", "pictures"),
                  os.path.join(home, "storage", "music"),
                  os.path.join(home, "storage", "movies"),
                  os.path.join(home, "storage", "documents")):
            n = os.path.basename(c.rstrip("/")) or "storage"
            add(n.capitalize(), c, "📁")
        try:
            if os.path.isdir("/storage"):
                for e in os.listdir("/storage"):
                    if e == "emulated":
                        continue
                    add(f"SD / {e}", os.path.join("/storage", e), "💾")
        except OSError:
            pass
        for sub in ("Download", "DCIM", "Pictures", "Music", "Movies",
                    "Documents", "WhatsApp", "Telegram", "Snapchat"):
            for base in ("/sdcard", "/storage/emulated/0"):
                add(sub, os.path.join(base, sub), "📁")
    else:
        if os.name != "nt":
            add("Root /", "/", "🖥️")
            add("Home", os.path.expanduser("~"), "🏠")
            for base in ("/media", "/mnt", "/run/media", "/Volumes"):
                try:
                    if os.path.isdir(base):
                        for e in os.listdir(base):
                            add(e, os.path.join(base, e), "💾")
                            sub = os.path.join(base, e)
                            if base == "/run/media" and os.path.isdir(sub):
                                try:
                                    for e2 in os.listdir(sub):
                                        add(e2, os.path.join(sub, e2), "💾")
                                except OSError:
                                    pass
                except OSError:
                    pass
        else:
            home = os.path.expanduser("~")
            for n in ("Downloads", "Desktop", "Documents", "Music", "Pictures", "Videos"):
                p = os.path.join(home, n)
                if os.path.isdir(p):
                    add(n, p, "⭐")
            add("User home", home, "🏠")
    return roots


def quick_access():
    q = []
    if is_android():
        for base in ("/sdcard", "/storage/emulated/0",
                     os.path.join(os.path.expanduser("~"), "storage", "shared")):
            for sub in ("Download", "DCIM", "Pictures", "Music", "Movies", "Documents"):
                p = os.path.join(base, sub)
                if os.path.isdir(p):
                    q.append({"name": sub, "path": os.path.abspath(p)})
                    if len(q) >= 8:
                        return q
        return q
    home = os.path.expanduser("~")
    for n in ("Downloads", "Desktop", "Documents", "Music", "Pictures", "Videos"):
        p = os.path.join(home, n)
        if os.path.isdir(p):
            q.append({"name": n, "path": os.path.abspath(p)})
    return q


def fmt_size(n):
    if n is None:
        return "-"
    try:
        n = int(n)
    except Exception:
        return "-"
    if n >= 1024 ** 3:
        return f"{n / 1024 ** 3:.2f} GB"
    if n >= 1024 ** 2:
        return f"{n / 1024 ** 2:.1f} MB"
    if n >= 1024:
        return f"{n / 1024:.0f} KB"
    return f"{n} B"


# ==================================================================== UI ===
UI_HTML = r"""<!DOCTYPE html>
<html lang="en" data-theme="dark"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#060a1e">
<title>Fast Share — Apple Liquid Glass + Material 3</title>
<style>
/* ============ MATERIAL GLASS DESIGN SYSTEM ============ */
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
:root{
  --acc:#38bdf8; --acc2:#818cf8; --acc3:#a855f7; --acc-glow:rgba(56,189,248,.35);
  --emerald:#10b981; --emerald-glow:rgba(16,185,129,.35);
  --rose:#f43f5e; --amber:#f59e0b;
  --txt:#f8fafc; --sub:rgba(241,245,249,.65); --faint:rgba(241,245,249,.42);
  --glass-blur:26px; --glass-sat:190%; --glass-alpha:.14;
  --glass-stroke:rgba(255,255,255,.16); --glass-specular:rgba(255,255,255,.32);
  --r-lg:26px; --r-md:18px; --r-sm:12px;
  --spring:cubic-bezier(.2,0,0,1); --spring-bouncy:cubic-bezier(.34,1.45,.4,1);
  --shadow:0 16px 40px rgba(0,0,0,.5);
  color-scheme:dark;
}
html[data-theme="light"]{
  --acc:#0284c7; --acc2:#6366f1; --acc3:#7c3aed; --acc-glow:rgba(2,132,199,.25);
  --emerald:#059669; --emerald-glow:rgba(5,150,105,.25);
  --txt:#0f172a; --sub:rgba(15,23,42,.68); --faint:rgba(15,23,42,.45);
  --glass-alpha:.75; --glass-stroke:rgba(15,23,42,.12); --glass-specular:rgba(255,255,255,.85);
  --shadow:0 16px 36px rgba(15,23,42,.12);
  color-scheme:light;
}
html {
  width: 100%;
  height: 100%;
  overflow-x: hidden;
  -webkit-text-size-adjust: 100%;
}
body {
  width: 100%;
  min-height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-y pinch-zoom;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", Roboto, "Segoe UI", system-ui, sans-serif;
  color: var(--txt);
  padding-bottom: 140px;
  background: #060a1e;
  transition: color .3s var(--spring);
}
.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}
.grad-txt{background:linear-gradient(135deg,#fff 20%,var(--acc) 60%,var(--acc2) 100%);-webkit-background-clip:text;-webkit-text-fill-color:transparent;display:inline-block}

/* Wallpaper */
.wall{
  position:fixed;inset:0;z-index:-4;
  background:radial-gradient(1100px 650px at 10% -10%,#1e3a8a 0%,transparent 60%),
             radial-gradient(900px 620px at 100% 10%,#581c87 0%,transparent 60%),
             radial-gradient(850px 750px at 85% 95%,#831843 0%,transparent 60%),
             radial-gradient(900px 700px at 5% 100%,#0369a1 0%,transparent 60%),
             linear-gradient(180deg,#060a1e,#0a0f2e);
}
html[data-theme="light"] .wall{
  background:radial-gradient(1100px 650px at 10% -10%,#bae6fd 0%,transparent 60%),
             radial-gradient(900px 620px at 100% 10%,#e9d5ff 0%,transparent 60%),
             radial-gradient(850px 750px at 85% 95%,#fbcfe8 0%,transparent 60%),
             linear-gradient(180deg,#f0f4fd,#faf5ff);
}
.blob{position:fixed;border-radius:50%;filter:blur(80px);z-index:-3;pointer-events:none;opacity:.75}
.blob.b1{width:45vw;height:45vw;min-width:320px;min-height:320px;left:-10vw;top:-10vw;background:#0ea5e9;animation:drift 14s ease-in-out infinite alternate}
.blob.b2{width:38vw;height:38vw;min-width:280px;min-height:280px;right:-8vw;top:-4vw;background:#8b5cf6;animation:drift 18s ease-in-out infinite alternate-reverse}
.blob.b3{width:32vw;height:32vw;min-width:240px;min-height:240px;right:8vw;bottom:-10vw;background:#ec4899;opacity:.6;animation:drift 22s ease-in-out infinite alternate}
@keyframes drift{to{transform:translate(6vw,8vh) scale(1.15)}}
.vig{position:fixed;inset:0;z-index:-1;pointer-events:none;background:radial-gradient(120% 90% at 50% 40%,transparent 55%,rgba(2,4,14,.55) 100%)}

/* Glass Core */
.glass{
  position:relative;
  background:rgba(255,255,255,var(--glass-alpha));
  backdrop-filter:blur(var(--glass-blur)) saturate(var(--glass-sat));
  -webkit-backdrop-filter:blur(var(--glass-blur)) saturate(var(--glass-sat));
  border:1px solid var(--glass-stroke);
  border-radius:var(--r-md);
  box-shadow:var(--shadow),inset 0 1px 0 var(--glass-specular);
}
html[data-theme="light"] .glass{background:rgba(255,255,255,.78)}

/* Top Loading Bar */
#topbar{position:fixed;top:0;left:0;height:4px;width:0;z-index:300;background:linear-gradient(90deg,var(--acc),var(--acc2),var(--emerald));box-shadow:0 0 14px var(--acc);transition:width .2s var(--spring),opacity .4s}

/* Floating Navigation */
.nav{
  position:sticky;top:12px;z-index:100;max-width:1220px;margin:12px auto 0;
  border-radius:24px;padding:10px 18px;display:flex;align-items:center;gap:14px;
}
.brand{display:flex;align-items:center;gap:12px;flex:1;min-width:0}
.logo-icon{
  width:46px;height:46px;border-radius:15px;display:flex;align-items:center;justify-content:center;
  font-size:22px;background:linear-gradient(135deg,var(--acc),var(--acc2));color:#fff;
  box-shadow:0 6px 20px var(--acc-glow),inset 0 1px 0 rgba(255,255,255,.6);
  flex-shrink:0;cursor:pointer;transition:transform .3s var(--spring-bouncy);
}
.logo-icon:active{transform:scale(.9) rotate(-8deg)}
.brand-meta{display:flex;flex-direction:column;min-width:0}
.brand-title{font-size:18px;font-weight:800;letter-spacing:-.4px;line-height:1.2;display:flex;align-items:center;gap:8px}
.brand-badge{font-size:10px;font-weight:800;letter-spacing:.5px;padding:2px 8px;border-radius:99px;background:rgba(56,189,248,.18);color:var(--acc);border:1px solid rgba(56,189,248,.3)}
.brand-sub{display:flex;align-items:center;gap:8px;margin-top:3px;flex-wrap:wrap}
.chip-pill{
  font-size:11px;font-weight:700;padding:3px 10px;border-radius:99px;
  background:rgba(255,255,255,.12);border:1px solid var(--glass-stroke);
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
}
.ip-chip{cursor:pointer;transition:background .2s}
.ip-chip:hover{background:rgba(255,255,255,.25)}
.ip-chip:active{transform:scale(.96)}

.nav-actions{display:flex;align-items:center;gap:8px}

/* TRANSFERS BUTTON IN NAVBAR */
.nav-transfers-btn{
  display:inline-flex;align-items:center;gap:8px;padding:8px 16px;border-radius:99px;
  background:rgba(56,189,248,.15);border:1px solid rgba(56,189,248,.4);
  color:var(--txt);font-size:13px;font-weight:700;cursor:pointer;
  box-shadow:0 4px 16px rgba(56,189,248,.15);transition:all .25s var(--spring-bouncy);
}
.nav-transfers-btn:hover{background:rgba(56,189,248,.25);transform:translateY(-1px);border-color:var(--acc)}
.nav-transfers-btn:active{transform:scale(.96)}
.nt-badge{
  font-size:10.5px;font-weight:800;padding:2px 7px;border-radius:99px;
  background:var(--acc);color:#070d24;min-width:18px;text-align:center;
}
.nt-speed{
  font-size:11px;font-family:ui-monospace,monospace;font-weight:700;
  color:#34d399;display:inline-flex;align-items:center;gap:4px;
}
.nt-speed.active{
  animation:speedPulse 1.5s infinite;
}
@keyframes speedPulse{0%,100%{opacity:1}50%{opacity:.65}}

.iconbtn{
  width:42px;height:42px;border-radius:50%;border:1px solid var(--glass-stroke);
  background:rgba(255,255,255,.1);color:var(--txt);font-size:18px;cursor:pointer;
  display:flex;align-items:center;justify-content:center;transition:transform .2s var(--spring-bouncy),background .2s;
}
.iconbtn:hover{background:rgba(255,255,255,.2)}
.iconbtn:active{transform:scale(.9)}

/* Main Layout */
.layout{max-width:1220px;margin:16px auto 0;padding:0 14px;display:flex;flex-direction:column;gap:16px}

/* ============ ECOSYSTEM RADAR ============ */
.radar-card{
  padding:16px 20px;border-radius:var(--r-lg);
  background:linear-gradient(135deg,rgba(56,189,248,.12),rgba(129,140,248,.08) 50%,rgba(255,255,255,.05));
  border:1px solid rgba(56,189,248,.35);
  box-shadow:0 16px 40px rgba(0,0,0,.45),0 0 25px rgba(56,189,248,.15);
}
.radar-header{display:flex;align-items:center;gap:12px}
.radar-ring-box{
  width:44px;height:44px;border-radius:50%;position:relative;display:flex;
  align-items:center;justify-content:center;background:rgba(56,189,248,.15);
  border:1px solid rgba(56,189,248,.4);flex-shrink:0;overflow:hidden;
}
.radar-ping{
  position:absolute;inset:0;border-radius:50%;border:2px solid var(--acc);
  animation:ping 2s cubic-bezier(0,0,.2,1) infinite;opacity:.75;
}
@keyframes ping{75%,100%{transform:scale(2.2);opacity:0}}
.radar-core{font-size:20px;z-index:2}
.radar-info{flex:1;min-width:0}
.radar-title{font-size:15px;font-weight:800;letter-spacing:-.2px;display:flex;align-items:center;gap:8px}
.radar-live-dot{width:8px;height:8px;border-radius:50%;background:#22c55e;box-shadow:0 0 10px #22c55e;display:inline-block}
.radar-sub{font-size:12px;color:var(--sub);margin-top:2px}
.radar-toggle-btn{background:transparent;border:none;color:var(--sub);cursor:pointer;font-size:18px;padding:4px}
.nearby-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px;margin-top:12px}
.nearby-device{
  padding:12px 14px;border-radius:18px;display:flex;align-items:center;gap:12px;
  background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.2);
  transition:transform .25s var(--spring-bouncy),border-color .2s;
}
.nearby-device:hover{border-color:var(--acc);transform:translateY(-2px)}
.nd-icon{
  width:42px;height:42px;border-radius:14px;display:flex;align-items:center;justify-content:center;
  font-size:20px;background:linear-gradient(135deg,var(--acc),var(--acc2));color:#fff;
  flex-shrink:0;box-shadow:0 4px 12px var(--acc-glow);
}
.nd-info{flex:1;min-width:0}
.nd-name{font-size:13.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.nd-ip{font-size:11px;color:var(--sub);margin-top:2px;font-family:monospace}
.nd-actions{display:flex;gap:6px;margin-top:6px}
.btn-sm{
  padding:5px 12px;font-size:11.5px;font-weight:700;border-radius:99px;border:none;cursor:pointer;
  display:inline-flex;align-items:center;gap:4px;transition:transform .2s;
}
.btn-sm:active{transform:scale(.94)}
.btn-send{background:linear-gradient(135deg,var(--acc),var(--acc2));color:#fff;box-shadow:0 4px 12px var(--acc-glow)}
.btn-browse{background:rgba(255,255,255,.15);border:1px solid var(--glass-stroke);color:var(--txt)}

.nearby-device.active-session-card{
  background:linear-gradient(135deg,rgba(34,197,94,.12),rgba(56,189,248,.12));
  border-color:rgba(34,197,94,.45);box-shadow:0 4px 18px rgba(34,197,94,.15);
}
.active-tag{
  display:inline-block;font-size:10px;font-weight:800;text-transform:uppercase;
  letter-spacing:.5px;padding:2px 7px;border-radius:99px;background:rgba(34,197,94,.25);
  color:#4ade80;margin-left:6px;vertical-align:middle;
}
.radar-empty{
  padding:14px;text-align:center;font-size:12.5px;color:var(--sub);grid-column:1/-1;
  background:rgba(255,255,255,.04);border-radius:14px;border:1px dashed var(--glass-stroke);
}

/* ============ QUICK ACTIONS TOOLBAR ============ */
.quick-toolbar{
  padding:12px 18px;border-radius:var(--r-md);display:flex;align-items:center;
  gap:12px;flex-wrap:wrap;justify-content:space-between;
}
.qt-left{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.qt-right{display:flex;align-items:center;gap:10px}
.action-btn{
  padding:9px 18px;font-size:13px;font-weight:700;border-radius:99px;cursor:pointer;
  display:inline-flex;align-items:center;gap:7px;border:1px solid var(--glass-stroke);
  transition:all .2s var(--spring-bouncy);background:rgba(255,255,255,.1);color:var(--txt);
}
.action-btn:hover{background:rgba(255,255,255,.2);transform:translateY(-1px)}
.action-btn:active{transform:scale(.95)}
.action-btn.primary{
  background:linear-gradient(135deg,var(--acc),var(--acc2));color:#fff;
  border-color:transparent;box-shadow:0 4px 16px var(--acc-glow);
}
.action-btn.primary:hover{box-shadow:0 6px 22px var(--acc-glow)}

/* Global Drag & Drop Overlay */
#dragoverlay{
  position:fixed;inset:0;z-index:900;background:rgba(6,10,30,.88);
  backdrop-filter:blur(30px);-webkit-backdrop-filter:blur(30px);
  display:none;align-items:center;justify-content:center;
  border:4px dashed var(--acc);pointer-events:none;
}
.do-card{
  padding:40px;border-radius:32px;text-align:center;background:rgba(56,189,248,.1);
  border:1px solid rgba(56,189,248,.4);box-shadow:0 30px 80px rgba(0,0,0,.7);
}
.do-icon{font-size:56px;margin-bottom:14px;animation:bounce 1s infinite alternate}
@keyframes bounce{to{transform:translateY(-10px)}}
.do-title{font-size:24px;font-weight:800}
.do-sub{font-size:14px;color:var(--sub);margin-top:6px}

/* ============ DUAL-PANE EXPLORER ============ */
.content-grid{
  display:grid;grid-template-columns:290px 1fr;gap:16px;align-items:start;
}
@media (max-width:880px){.content-grid{grid-template-columns:1fr}}

/* Sidebar */
.sidebar{display:flex;flex-direction:column;gap:14px}
.panel{padding:16px;border-radius:var(--r-md)}
.section-title{font-size:11px;font-weight:800;letter-spacing:.6px;text-transform:uppercase;color:var(--sub);margin-bottom:12px}
.roots-list{display:flex;flex-direction:column;gap:8px}
.root-item{
  padding:10px 12px;border-radius:14px;background:rgba(255,255,255,.06);
  border:1px solid var(--glass-stroke);display:flex;align-items:center;gap:12px;
  cursor:pointer;transition:all .2s;
}
.root-item:hover{background:rgba(255,255,255,.14);border-color:var(--acc)}
.root-item.cur{background:rgba(56,189,248,.18);border-color:var(--acc);box-shadow:0 4px 16px var(--acc-glow)}
.root-icon{font-size:24px;flex-shrink:0}
.root-meta{flex:1;min-width:0}
.root-name{font-size:13px;font-weight:700}
.root-path{font-size:11px;color:var(--sub);font-family:monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.root-bar{height:4px;border-radius:99px;background:rgba(255,255,255,.15);overflow:hidden;margin-top:6px}
.root-bar>i{display:block;height:100%;background:linear-gradient(90deg,var(--acc),var(--acc2));border-radius:99px}

.quick-chips{display:flex;flex-wrap:wrap;gap:8px}
.qchip{
  padding:7px 14px;border-radius:99px;font-size:12px;font-weight:700;
  background:rgba(255,255,255,.08);border:1px solid var(--glass-stroke);
  color:var(--txt);cursor:pointer;transition:all .2s;display:inline-flex;align-items:center;gap:6px;
}
.qchip:hover{background:rgba(255,255,255,.2);border-color:var(--acc);transform:translateY(-1px)}

/* Live Bandwidth Widget in Sidebar */
.stats-widget{
  padding:14px;border-radius:18px;background:rgba(56,189,248,.08);
  border:1px solid rgba(56,189,248,.25);display:flex;flex-direction:column;gap:10px;
}
.sw-row{display:flex;align-items:center;justify-content:space-between;font-size:12px}
.sw-lbl{color:var(--sub)}
.sw-val{font-family:monospace;font-weight:700;color:var(--txt)}
.sw-val.highlight{color:#34d399}

/* Explorer Main */
.explorer{padding:16px 20px;border-radius:var(--r-md);display:flex;flex-direction:column;gap:12px}
.explorer-top{display:flex;align-items:center;gap:10px;overflow-x:auto;padding-bottom:2px}
.crumbs-bar{display:flex;align-items:center;gap:6px;flex:1;overflow-x:auto}
.crumb-chip{
  padding:6px 14px;border-radius:99px;font-size:12px;font-weight:700;
  background:rgba(255,255,255,.08);border:1px solid var(--glass-stroke);
  color:var(--txt);cursor:pointer;white-space:nowrap;transition:all .2s;
}
.crumb-chip:hover{background:rgba(255,255,255,.2);border-color:var(--acc)}
.crumb-chip.cur{background:rgba(56,189,248,.22);border-color:var(--acc);color:#fff}
.crumb-chip.remote-chip{
  background:linear-gradient(135deg,#0ea5e9,#8b5cf6);color:#fff;border-color:transparent;
  box-shadow:0 2px 10px rgba(14,165,233,.35);
}

.explorer-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.search-box{flex:1;min-width:200px;position:relative;display:flex;align-items:center}
.search-input{
  width:100%;padding:9px 14px 9px 36px;border-radius:99px;border:1px solid var(--glass-stroke);
  background:rgba(255,255,255,.08);color:var(--txt);font-size:13px;outline:none;
  transition:border-color .2s,background .2s;
}
.search-input::placeholder{color:rgba(255,255,255,.6)}
.search-input:focus{border-color:var(--acc);background:rgba(255,255,255,.14)}
.search-icon{position:absolute;left:13px;font-size:14px;opacity:.6;pointer-events:none}
.view-toggle{display:flex;border-radius:99px;background:rgba(255,255,255,.08);border:1px solid var(--glass-stroke);overflow:hidden}
.view-btn{
  padding:7px 12px;background:transparent;border:none;color:var(--sub);
  cursor:pointer;font-size:13px;transition:all .2s;
}
.view-btn.active{background:rgba(255,255,255,.2);color:var(--txt)}

/* File Items */
.file-list{display:flex;flex-direction:column;gap:6px;margin-top:6px}
.file-item{
  padding:10px 14px;border-radius:14px;background:rgba(255,255,255,.05);
  border:1px solid var(--glass-stroke);display:flex;align-items:center;gap:12px;
  transition:all .2s;
}
.file-item:hover{background:rgba(255,255,255,.12);border-color:var(--acc);transform:translateX(2px)}
.file-item.sel{background:rgba(56,189,248,.18);border-color:var(--acc)}
.file-check{
  width:22px;height:22px;border-radius:6px;border:1.5px solid var(--glass-stroke);
  background:rgba(255,255,255,.08);color:var(--txt);cursor:pointer;display:flex;
  align-items:center;justify-content:center;font-size:12px;font-weight:800;transition:all .2s;flex-shrink:0;
}
.file-item.sel .file-check{background:var(--acc);border-color:var(--acc);color:#070d24}
.file-lead{font-size:26px;cursor:pointer;flex-shrink:0}
.file-mid{flex:1;min-width:0;cursor:pointer}
.file-name{font-size:13.5px;font-weight:700;word-break:break-all;line-height:1.3}
.file-meta{font-size:11px;color:var(--sub);margin-top:2px;font-family:monospace}
.file-trail{display:flex;gap:6px;flex-shrink:0;align-items:center}
.abtn{
  padding:6px 12px;font-size:12px;font-weight:700;border-radius:99px;cursor:pointer;
  background:rgba(255,255,255,.1);border:1px solid var(--glass-stroke);color:var(--txt);
  transition:all .2s;
}
.abtn:hover{background:rgba(255,255,255,.2);transform:scale(1.04)}
.abtn:active{transform:scale(.95)}
.abtn.go{background:rgba(16,185,129,.2);border-color:rgba(16,185,129,.4);color:#34d399}
.abtn.go:hover{background:rgba(16,185,129,.35)}
.abtn.danger{background:rgba(244,63,94,.18);border-color:rgba(244,63,94,.35);color:#fb7185}

/* Sort Button & Glass Popover Menu */
.sort-wrap {
  position: relative;
  display: inline-block;
}
.sort-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  font-size: 12.5px;
  font-weight: 600;
  border-radius: 99px;
  cursor: pointer;
  background: rgba(255,255,255,0.08);
  border: 1px solid var(--glass-stroke);
  color: var(--txt);
  transition: all 0.2s;
  white-space: nowrap;
}
.sort-btn:hover {
  background: rgba(255,255,255,0.16);
  border-color: rgba(255,255,255,0.3);
}
.sort-menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  min-width: 220px;
  z-index: 500;
  background: rgba(15, 23, 42, 0.94);
  backdrop-filter: blur(28px);
  -webkit-backdrop-filter: blur(28px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 16px;
  box-shadow: 0 16px 40px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.08);
  padding: 8px 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  transform-origin: top right;
  animation: sortPop .18s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes sortPop {
  from { opacity: 0; transform: scale(0.92) translateY(-6px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}
.sort-menu-header {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
  color: var(--sub);
  padding: 6px 12px 4px;
}
.sort-option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 12.5px;
  color: var(--txt);
  transition: background 0.15s, color 0.15s;
}
.sort-option:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}
.sort-option.active {
  background: rgba(14, 165, 233, 0.22);
  color: #38bdf8;
  font-weight: 700;
}
.sort-opt-icon {
  font-size: 14px;
  width: 18px;
  text-align: center;
}
.sort-opt-text {
  flex: 1;
}
.sort-check {
  font-size: 12px;
  font-weight: bold;
  color: #38bdf8;
  opacity: 0;
}
.sort-option.active .sort-check {
  opacity: 1;
}
.sort-divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.08);
  margin: 4px 6px;
}



/* ============ MOBILE, ANDROID & 120FPS GPU BOOST ============ */
@media (max-width: 880px), (hover: none) and (pointer: coarse) {
  body {
    --glass-blur: 0px !important;
    -webkit-overflow-scrolling: touch;
    overscroll-behavior-y: contain;
    padding-bottom: 140px !important;
  }
  .blob {
    display: none !important; /* Zero GPU strain: disable full-screen 80px blur animation loops */
  }
  .glass,
  .card,
  .file-item,
  .panel,
  .action-btn,
  .location-hud,
  .mobile-dock,
  .radar-card,
  .explorer,
  .auth-card {
    backdrop-filter: none !important;
    -webkit-backdrop-filter: none !important;
    background: rgba(13, 20, 38, 0.94) !important;
    border-color: rgba(56, 189, 248, 0.16) !important;
  }
  .file-item:hover,
  .file-item:active {
    background: rgba(26, 38, 68, 0.95) !important;
  }
  .mobile-dock {
    backdrop-filter: none !important;
    -webkit-backdrop-filter: none !important;
    background: rgba(10, 16, 32, 0.96) !important;
    border: 1px solid rgba(56, 189, 248, 0.35) !important;
    box-shadow: 0 -8px 24px rgba(0, 0, 0, 0.6) !important;
    bottom: max(10px, env(safe-area-inset-bottom, 10px)) !important;
    padding-bottom: max(6px, env(safe-area-inset-bottom, 6px)) !important;
  }
}
body.in-webview header.nav {
  display: none !important; /* Hide redundant desktop navbar in Android app */
}

/* ============ LOCATION HUD & SMART BREADCRUMBS ============ */
.nav-location-badge {
  color: #38bdf8 !important;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.nav-pulse-dot {
  display: inline-block;
  font-size: 10px;
  animation: pulseDot 1.6s infinite;
}
@keyframes pulseDot {
  0% { transform: scale(0.9); opacity: 0.7; }
  50% { transform: scale(1.3); opacity: 1; }
  100% { transform: scale(0.9); opacity: 0.7; }
}

.crumb-sep {
  color: rgba(255, 255, 255, 0.35);
  font-size: 14px;
  font-weight: 700;
  user-select: none;
  margin: 0 1px;
}
.crumb-chip.active-crumb {
  background: rgba(14, 165, 233, 0.25) !important;
  border-color: rgba(56, 189, 248, 0.5) !important;
  color: #38bdf8 !important;
  font-weight: 800;
  box-shadow: 0 0 12px rgba(14, 165, 233, 0.35);
}
.crumb-chip.remote-device-chip {
  background: rgba(16, 185, 129, 0.2) !important;
  border-color: rgba(52, 211, 153, 0.45) !important;
  color: #34d399 !important;
  font-weight: 700;
}
.crumb-chip.local-device-chip {
  background: rgba(255, 255, 255, 0.12) !important;
  color: #fff !important;
  font-weight: 700;
}
.crumb-return-x {
  margin-left: 6px;
  opacity: 0.6;
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 50%;
  background: rgba(0,0,0,0.3);
}
.crumb-chip.remote-device-chip:hover .crumb-return-x {
  opacity: 1;
  background: rgba(244, 63, 94, 0.4);
}

.location-hud {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  margin-top: 10px;
  margin-bottom: 4px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--glass-stroke);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}
.loc-main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.loc-folder-icon {
  font-size: 26px;
  flex-shrink: 0;
}
.loc-current-folder {
  font-size: 16px;
  font-weight: 800;
  color: var(--txt);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 450px;
}
.loc-path-sub {
  font-size: 11px;
  color: var(--sub);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 450px;
}
.loc-stats {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.loc-count-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 4px 10px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid var(--glass-stroke);
  color: var(--txt);
}
.drive-active-pill {
  font-size: 9px;
  font-weight: 800;
  padding: 1px 6px;
  border-radius: 99px;
  background: rgba(14, 165, 233, 0.3);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.4);
  margin-left: 6px;
}
.root-item.active-drive {
  border-color: rgba(56, 189, 248, 0.45) !important;
  background: rgba(14, 165, 233, 0.12) !important;
  box-shadow: 0 0 16px rgba(14, 165, 233, 0.2);
}

/* Grid View Mode */
.file-list.grid-mode{
  display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px;
}
.file-list.grid-mode .file-item{
  flex-direction:column;align-items:stretch;padding:16px;text-align:center;
}
.file-list.grid-mode .file-lead{font-size:42px;margin:6px 0}
.file-list.grid-mode .file-trail{justify-content:center;margin-top:10px}

/* ============ THUMBNAILS & MEDIA LIGHTBOX ============ */
.thumb-lead {
  display: flex !important;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.thumb-frame {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  overflow: hidden;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.12);
  box-shadow: 0 4px 12px rgba(0,0,0,0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.25s ease;
}
.thumb-img.visible {
  opacity: 1;
}
.thumb-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}
.video-play-badge {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,0.35);
  color: #fff;
  font-size: 13px;
  pointer-events: none;
}

/* Grid Mode Thumbnails */
.file-list.grid-mode .thumb-lead {
  width: 100%;
  margin: 0;
}
.file-list.grid-mode .thumb-frame {
  width: 100%;
  height: 135px;
  border-radius: 14px;
  margin: 4px 0 10px 0;
  background: rgba(0,0,0,0.25);
  border: 1px solid rgba(255,255,255,0.14);
  box-shadow: 0 8px 24px rgba(0,0,0,0.3), inset 0 0 20px rgba(0,0,0,0.2);
}
.file-list.grid-mode .thumb-img {
  border-radius: 14px;
  transition: transform 0.4s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.3s ease;
}
.file-list.grid-mode .file-item:hover .thumb-img.visible {
  transform: scale(1.06);
}
.file-list.grid-mode .thumb-fallback {
  font-size: 42px;
}
.file-list.grid-mode .video-play-badge {
  position: absolute;
  bottom: 8px;
  right: 8px;
  top: auto;
  left: auto;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(14, 165, 233, 0.9);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  box-shadow: 0 4px 14px rgba(0,0,0,0.4);
  border: 1px solid rgba(255,255,255,0.3);
  font-size: 13px;
}

/* Lightbox Modal */
.media-lightbox {
  position: fixed;
  inset: 0;
  z-index: 998;
  background: rgba(6, 10, 30, 0.88);
  backdrop-filter: blur(36px);
  -webkit-backdrop-filter: blur(36px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.lb-card {
  width: 100%;
  max-width: 900px;
  max-height: 92vh;
  display: flex;
  flex-direction: column;
  background: rgba(15, 23, 42, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 24px;
  box-shadow: 0 30px 80px rgba(0,0,0,0.7);
  overflow: hidden;
}
.lb-head {
  padding: 16px 20px;
  border-bottom: 1px solid var(--glass-stroke);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.lb-file-info {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.lb-icon {
  font-size: 24px;
}
.lb-filename {
  font-weight: 700;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 500px;
}
.lb-meta {
  font-size: 11px;
  color: var(--sub);
  margin-top: 2px;
}
.lb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.lb-content {
  flex: 1;
  min-height: 250px;
  max-height: calc(92vh - 150px);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  padding: 12px;
  background: rgba(0,0,0,0.3);
}
.lb-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.5);
  opacity: 0;
  transition: opacity 0.3s ease;
}
.lb-img.ready {
  opacity: 1;
}
.lb-video {
  max-width: 100%;
  max-height: 100%;
  border-radius: 12px;
  outline: none;
  box-shadow: 0 10px 40px rgba(0,0,0,0.5);
}
.lb-nav-bar {
  padding: 12px 20px;
  border-top: 1px solid var(--glass-stroke);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(255,255,255,0.03);
}
.lb-nav-btn {
  padding: 8px 18px;
  font-size: 13px;
  font-weight: 700;
  border-radius: 99px;
  cursor: pointer;
  background: rgba(255,255,255,0.1);
  border: 1px solid var(--glass-stroke);
  color: var(--txt);
  transition: all 0.2s;
}
.lb-nav-btn:hover {
  background: rgba(255,255,255,0.2);
  transform: scale(1.04);
}
@media (max-width: 600px) {
  .media-lightbox { padding: 8px; }
  .lb-card { border-radius: 18px; }
  .lb-filename { max-width: 180px; font-size: 13px; }
  .file-list.grid-mode .thumb-frame { height: 110px; }
}

/* ============ TRANSFERS & DOWNLOADS SLIDE-IN HUB ============ */
.transfers-drawer{
  position:fixed;top:0;right:0;width:440px;max-width:100vw;height:100vh;z-index:600;
  background:rgba(10,15,40,.88);backdrop-filter:blur(36px);-webkit-backdrop-filter:blur(36px);
  border-left:1px solid rgba(255,255,255,.18);box-shadow:-20px 0 60px rgba(0,0,0,.7);
  display:flex;flex-direction:column;transform:translateX(105%);transition:transform .35s var(--spring);
}
.transfers-drawer.open{transform:translateX(0)}
.td-head{
  padding:20px;border-bottom:1px solid var(--glass-stroke);display:flex;
  align-items:center;justify-content:space-between;
}
.td-title{font-size:17px;font-weight:800;letter-spacing:-.2px;display:flex;align-items:center;gap:8px}
.td-close{
  width:34px;height:34px;border-radius:50%;background:rgba(255,255,255,.1);border:1px solid var(--glass-stroke);
  color:var(--txt);font-size:16px;cursor:pointer;display:flex;align-items:center;justify-content:center;
  transition:all .2s;
}
.td-close:hover{background:rgba(255,255,255,.2);transform:scale(1.05)}

/* Speedometer Bar */
.td-speedometer{
  padding:14px 20px;background:rgba(56,189,248,.08);border-bottom:1px solid var(--glass-stroke);
  display:grid;grid-template-columns:1fr 1fr;gap:12px;
}
.sm-metric{display:flex;flex-direction:column;gap:3px}
.sm-label{font-size:11px;font-weight:700;color:var(--sub);text-transform:uppercase;letter-spacing:.5px}
.sm-val{font-size:16px;font-weight:800;font-family:ui-monospace,monospace;color:#38bdf8}
.sm-val.upload{color:#a855f7}
.sm-sub{font-size:10.5px;color:var(--sub)}

/* Tabs */
.td-tabs{
  display:flex;padding:12px 20px 0;gap:8px;border-bottom:1px solid var(--glass-stroke);
}
.td-tab{
  padding:8px 16px;font-size:12.5px;font-weight:700;border:none;background:transparent;
  color:var(--sub);cursor:pointer;border-bottom:2.5px solid transparent;transition:all .2s;
}
.td-tab.active{color:var(--acc);border-bottom-color:var(--acc)}

/* Transfer Items List */
.td-list{
  flex:1;overflow-y:auto;padding:16px 20px;display:flex;flex-direction:column;gap:12px;
}
.td-card{
  padding:14px;border-radius:18px;background:rgba(255,255,255,.07);border:1px solid var(--glass-stroke);
  display:flex;flex-direction:column;gap:8px;transition:all .2s;
}
.td-card:hover{border-color:var(--acc);background:rgba(255,255,255,.1)}
.td-card-top{display:flex;align-items:center;justify-content:space-between;gap:8px}
.td-badge{
  font-size:9.5px;font-weight:800;padding:2px 8px;border-radius:99px;text-transform:uppercase;
}
.td-badge.down{background:rgba(56,189,248,.2);color:#38bdf8}
.td-badge.up{background:rgba(168,85,247,.2);color:#c084fc}
.td-badge.done{background:rgba(16,185,129,.2);color:#34d399}
.td-name{font-size:13px;font-weight:700;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.td-meta{font-size:11px;color:var(--sub);display:flex;justify-content:space-between;font-family:monospace}
.td-pbar{
  height:6px;border-radius:99px;background:rgba(255,255,255,.15);overflow:hidden;position:relative;
}
.td-pbar>i{
  display:block;height:100%;width:0;background:linear-gradient(90deg,var(--acc),var(--acc2),var(--emerald));
  border-radius:99px;transition:width .2s;
}
.td-stats{
  display:flex;align-items:center;justify-content:space-between;font-size:11px;
  color:var(--sub);font-family:ui-monospace,monospace;
}
.td-spd{font-weight:700;color:#38bdf8}
.td-empty{
  padding:36px 20px;text-align:center;color:var(--sub);font-size:13px;
  display:flex;flex-direction:column;align-items:center;gap:12px;
}
.td-empty-icon{font-size:42px;opacity:.6}

/* ============ FLOATING DYNAMIC ISLAND HUD ============ */
#dynamic-island{
  position:fixed;bottom:24px;right:24px;z-index:500;padding:10px 18px;border-radius:99px;
  display:none;align-items:center;gap:12px;background:rgba(10,15,40,.88);
  border:1.5px solid var(--acc);box-shadow:0 16px 40px rgba(0,0,0,.6),0 0 24px var(--acc-glow);
  cursor:pointer;transition:transform .3s var(--spring-bouncy);
}
#dynamic-island:hover{transform:scale(1.03)}
.di-icon{font-size:18px;animation:spin 3s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
.di-info{display:flex;flex-direction:column}
.di-name{font-size:12px;font-weight:800;max-width:180px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.di-meta{font-size:10.5px;color:#38bdf8;font-family:monospace;font-weight:700}

/* Selection Bar */
#selbar{
  position:fixed;bottom:24px;left:50%;transform:translateX(-50%) translateY(120px);
  z-index:200;padding:12px 20px;border-radius:99px;display:flex;align-items:center;gap:14px;
  box-shadow:0 20px 50px rgba(0,0,0,.6);transition:transform .3s var(--spring-bouncy);
}
#selbar.on{transform:translateX(-50%) translateY(0)}

/* Auth Modal */
.auth-scrim{
  position:fixed;inset:0;z-index:999;background:rgba(6,10,30,.82);
  backdrop-filter:blur(30px);-webkit-backdrop-filter:blur(30px);
  display:flex;align-items:center;justify-content:center;padding:20px;
}
.auth-card{
  width:100%;max-width:420px;padding:34px 28px;border-radius:28px;text-align:center;
  background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.25);
  box-shadow:0 24px 60px rgba(0,0,0,.6),0 0 30px rgba(56,189,248,.2);
}
.auth-icon{
  width:64px;height:64px;border-radius:20px;margin:0 auto 16px;display:flex;
  align-items:center;justify-content:center;font-size:32px;
  background:linear-gradient(135deg,var(--acc),var(--acc2));color:#fff;
  box-shadow:0 8px 24px var(--acc-glow);
}
.auth-title{font-size:22px;font-weight:800;letter-spacing:-.4px}
.auth-sub{font-size:13.5px;color:var(--sub);margin:6px 0 20px}
.auth-field{
  display:flex;align-items:center;gap:10px;padding:6px 14px;border-radius:99px;
  background:rgba(255,255,255,.1);border:1px solid var(--glass-stroke);margin-bottom:16px;
}
.auth-field input{flex:1;border:none;background:transparent;color:var(--txt);font-size:15px;padding:10px 0;outline:none}
.auth-eye{background:transparent;border:none;color:var(--txt);cursor:pointer;font-size:16px;opacity:.7}
.auth-btn{width:100%;padding:14px;font-size:15px}
.auth-err{color:#f87171;font-size:12.5px;margin-bottom:12px;font-weight:700}

/* Toast */
#snack{
  position:fixed;bottom:24px;left:50%;transform:translateX(-50%);z-index:800;
  padding:12px 24px;border-radius:99px;font-size:13.5px;font-weight:700;
  background:rgba(15,23,42,.92);color:#fff;border:1px solid rgba(255,255,255,.2);
  backdrop-filter:blur(16px);box-shadow:0 12px 30px rgba(0,0,0,.5);
  display:none;white-space:nowrap;
}

/* ============ MOBILE-SPECIFIC ERGONOMICS (Apple Glass + Material 3) ============ */
.mobile-dock {
  display: none;
}
@media (max-width: 880px) {
  body {
    padding-bottom: 140px;
  }
  .nav {
    margin: 8px 10px 0;
    padding: 8px 12px;
    border-radius: 18px;
    gap: 8px;
  }
  .brand {
    gap: 8px;
    flex: 1;
    min-width: 0;
  }
  .logo-icon {
    width: 36px;
    height: 36px;
    font-size: 17px;
    border-radius: 11px;
  }
  .brand-title {
    font-size: 15px;
  }
  .brand-badge {
    font-size: 9px;
    padding: 1px 6px;
  }
  .brand-sub {
    display: none;
  }
  .nav-actions {
    gap: 6px;
  }
  .nav-transfers-btn {
    padding: 6px 12px;
    font-size: 11px;
    gap: 5px;
  }
  .nav-transfers-btn .btn-text-label {
    display: none;
  }
  .nt-badge {
    font-size: 9.5px;
    padding: 1px 6px;
  }
  .nt-speed {
    font-size: 10px;
  }
  .iconbtn {
    width: 36px;
    height: 36px;
    font-size: 15px;
  }
  .layout {
    display: flex;
    flex-direction: column;
    padding: 0 10px;
    margin-top: 10px;
    gap: 12px;
  }
  .content-grid {
    order: 1;
  }
  .quick-toolbar {
    order: 2;
  }
  .radar-card {
    order: 3;
    padding: 12px 14px;
    border-radius: 20px;
  }
  .radar-title {
    font-size: 13.5px;
  }
  .radar-sub {
    font-size: 11px;
  }
  .quick-toolbar {
    padding: 12px;
    border-radius: 18px;
    gap: 10px;
    flex-direction: column;
    align-items: stretch;
    white-space: normal;
    overflow: hidden;
    max-width: 100%;
  }
  .qt-left {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
    width: 100%;
  }
  .action-btn {
    padding: 9px 8px;
    font-size: 12px;
    justify-content: center;
    width: 100%;
    box-sizing: border-box;
    text-align: center;
    white-space: nowrap;
  }
  .qt-right {
    width: 100%;
    justify-content: center;
    text-align: center;
    font-size: 11.5px;
    word-break: break-word;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  #droptarget {
    word-break: break-all;
  }
  .nearby-grid {
    grid-template-columns: 1fr;
  }
  .loc-current-folder {
    max-width: 200px;
  }
  .loc-path-sub {
    max-width: 200px;
  }
  .content-grid {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .explorer {
    order: 1; /* Primary files explorer is at the top on mobile */
    padding: 14px 12px;
    border-radius: 20px;
    min-width: 0 !important;
    max-width: 100% !important;
    overflow-x: hidden !important;
  }
  .sidebar {
    order: 2; /* Drives & Session Telemetry placed right below files */
    gap: 10px;
  }
  .explorer-top {
    flex-wrap: wrap;
    gap: 8px;
    max-width: 100%;
    overflow-x: hidden;
  }
  .crumbs-bar {
    width: 100%;
    margin-bottom: 2px;
    max-width: 100%;
    overflow-x: auto;
    white-space: nowrap;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
  }
  .crumbs-bar::-webkit-scrollbar {
    display: none;
  }
  .crumb-chip {
    padding: 5px 10px;
    font-size: 11px;
    flex-shrink: 0;
  }
  .explorer-actions {
    gap: 8px;
    width: 100%;
    justify-content: space-between;
  }
  .search-box {
    min-width: 120px;
    flex: 1;
  }
  .search-input {
    padding: 7px 10px 7px 30px;
    font-size: 12px;
    width: 100%;
  }
  .file-item {
    padding: 10px 10px;
    gap: 8px;
    border-radius: 12px;
    min-width: 0;
  }
  .file-lead {
    font-size: 22px;
  }
  .file-name {
    font-size: 12.5px;
    word-break: break-word;
    overflow-wrap: break-word;
  }
  .file-meta {
    font-size: 10px;
  }
  .file-trail {
    gap: 4px;
    flex-wrap: wrap;
  }
  .abtn {
    padding: 5px 8px;
    font-size: 11px;
  }
  .file-list.grid-mode {
    display: grid !important;
    grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
    gap: 8px !important;
    width: 100% !important;
    max-width: 100% !important;
  }
  .file-list.grid-mode .file-item {
    padding: 12px 8px !important;
    min-width: 0 !important;
    max-width: 100% !important;
    box-sizing: border-box !important;
    overflow: hidden !important;
  }
  .file-list.grid-mode .file-lead {
    font-size: 34px !important;
    margin: 4px 0 !important;
  }
  .file-list.grid-mode .thumb-frame {
    height: 100px !important;
    border-radius: 10px !important;
  }
  .file-list.grid-mode .file-name {
    font-size: 11.5px !important;
    max-height: 2.6em;
    line-height: 1.3;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    word-break: break-word;
  }
  .file-list.grid-mode .file-meta {
    font-size: 9.5px !important;
  }
  .file-list.grid-mode .file-trail {
    gap: 3px !important;
    margin-top: 6px !important;
    width: 100% !important;
    justify-content: center !important;
    flex-wrap: wrap !important;
  }
  .file-list.grid-mode .file-trail .abtn {
    padding: 4px 6px !important;
    font-size: 10px !important;
  }
  .transfers-drawer {
    width: 100vw;
    max-width: 100vw;
    border-left: none;
    border-top: 1px solid var(--glass-stroke);
    border-radius: 26px 26px 0 0;
  }
  .td-head {
    padding: 14px 16px;
  }
  .td-title {
    font-size: 15.5px;
  }
  .td-speedometer {
    padding: 12px 16px;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .sm-val {
    font-size: 16px;
  }
  .td-tabs {
    padding: 8px 16px 0;
  }
  .td-tab {
    padding: 6px 12px;
    font-size: 12px;
  }
  .td-list {
    padding: 12px 16px;
  }
  .td-card {
    padding: 12px;
    border-radius: 14px;
  }
  #dynamic-island {
    bottom: auto;
    top: 10px;
    left: 12px;
    right: 12px;
    margin: 0 auto;
    max-width: 380px;
    padding: 7px 14px;
    border-radius: 99px;
    justify-content: center;
    box-shadow: 0 10px 30px rgba(0,0,0,0.7), 0 0 20px var(--acc-glow);
    z-index: 700;
  }
  .di-name {
    max-width: 140px;
    font-size: 11px;
  }
  .di-meta {
    font-size: 9.5px;
  }
  #selbar {
    bottom: 80px;
    padding: 8px 14px;
    font-size: 12px;
    gap: 8px;
    max-width: calc(100% - 24px);
  }
  .mobile-dock {
    display: flex;
    position: fixed;
    bottom: 12px;
    left: 12px;
    right: 12px;
    max-width: 440px;
    margin: 0 auto;
    z-index: 450;
    padding: 6px 10px;
    border-radius: 99px;
    align-items: center;
    justify-content: space-around;
    box-shadow: 0 16px 40px rgba(0,0,0,0.65), 0 0 24px var(--acc-glow);
  }
  .md-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: none;
    color: var(--sub);
    cursor: pointer;
    padding: 4px 10px;
    border-radius: 16px;
    position: relative;
    transition: all 0.2s var(--spring-bouncy);
    font-family: inherit;
    text-decoration: none;
  }
  .md-item:hover, .md-item.active {
    color: var(--txt);
    background: rgba(255,255,255,0.12);
  }
  .md-item.active {
    color: var(--acc);
  }
  .md-item:active {
    transform: scale(0.92);
  }
  .md-icon {
    font-size: 18px;
    line-height: 1;
  }
  .md-label {
    font-size: 10px;
    font-weight: 700;
    margin-top: 2px;
    letter-spacing: 0.2px;
  }
  .md-badge {
    position: absolute;
    top: 0;
    right: 6px;
    background: var(--acc);
    color: #070d24;
    font-size: 9px;
    font-weight: 800;
    padding: 1px 5px;
    border-radius: 99px;
    min-width: 14px;
  }
}
body.in-webview {
  max-width: 100vw !important;
  overflow-x: hidden !important;
  width: 100vw !important;
  padding-bottom: 140px !important;
}
body.in-webview .layout,
body.in-webview .content-grid,
body.in-webview .explorer,
body.in-webview .sidebar,
body.in-webview .quick-toolbar,
body.in-webview .radar-card {
  max-width: 100% !important;
  box-sizing: border-box !important;
  overflow-x: hidden !important;
}
</style>
</head>
<body>
<div id="topbar"></div>
<div class="wall"></div>
<div class="blob b1"></div>
<div class="blob b2"></div>
<div class="blob b3"></div>
<div class="vig"></div>

<!-- Drag & Drop Full-Window Backdrop Overlay -->
<div id="dragoverlay">
  <div class="do-card glass">
    <div class="do-icon">📥</div>
    <div class="do-title">Drop Files to Fast Upload</div>
    <div class="do-sub">Files will be streamed at full gigabit speed into <b id="droptarget-overlay">current folder</b></div>
  </div>
</div>

<!-- ==================== TOP NAVIGATION ==================== -->
<header class="nav glass">
  <div class="brand">
    <div class="logo-icon" onclick="goHome()" title="Home Storage">⚡</div>
    <div class="brand-meta">
      <div class="brand-title">
        <span class="grad-txt">Fast Share</span>
        <span class="brand-badge">GIGABIT</span>
      </div>
      <div class="brand-sub">
        <span class="chip-pill" id="devchip">🖥️ PC • Windows</span>
        <span class="chip-pill ip-chip" id="ipchip" onclick="copyLink()" title="Click to copy host link">📡 http://…</span>
      </div>
    </div>
  </div>

  <div class="nav-actions">
    <!-- PROMINENT TRANSFERS & DOWNLOADS HUB BUTTON -->
    <button class="nav-transfers-btn glass" id="btn-transfers" onclick="toggleTransferDrawer()" title="View Downloads &amp; Active Transfers">
      <span>📥</span>
      <span class="btn-text-label">Transfers</span>
      <span class="nt-badge" id="nt-badge">0</span>
      <span class="nt-speed" id="nt-speed" style="display:none">⚡ 0 MB/s</span>
    </button>

    <button class="iconbtn" onclick="openSettingsModal()" title="Device Settings &amp; Security">⚙️</button>
    <button class="iconbtn" onclick="toggleTheme()" title="Toggle Light / Dark Mode">🌓</button>
    <button class="iconbtn" onclick="pollNearby()" title="Refresh Ecosystem Radar">📡</button>
  </div>
</header>

<!-- ==================== MAIN CONTENT CONTAINER ==================== -->
<main class="layout">
  <!-- NEARBY DEVICES ECOSYSTEM RADAR -->
  <section class="radar-card glass" id="radar-card">
    <div class="radar-header">
      <div class="radar-ring-box">
        <div class="radar-ping"></div>
        <span class="radar-core">📡</span>
      </div>
      <div class="radar-info">
        <div class="radar-title">ECOSYSTEM RADAR <span class="radar-live-dot"></span></div>
        <div class="radar-sub" id="radarstat">Scanning local Wi-Fi for Android phones &amp; Windows PCs…</div>
      </div>
      <button class="abtn" onclick="pollNearby()">⟳ Scan</button>
      <button class="radar-toggle-btn" onclick="toggleRadarCollapse()" id="btn-radar-toggle" title="Collapse / Expand Radar">▲</button>
    </div>
    <div class="nearby-grid" id="nearby-grid">
      <div class="radar-empty">Scanning Wi-Fi… Open Fast Share on your phone or laptop to connect instantly!</div>
    </div>
  </section>

  <!-- QUICK ACTIONS TOOLBAR -->
  <section class="quick-toolbar glass">
    <div class="qt-left">
      <label for="fin" class="action-btn primary" onclick="remoteTarget=null">
        <span>⬆</span> Upload Files
      </label>
      <label for="findir" class="action-btn" onclick="remoteTarget=null">
        <span>📁</span> Upload Folder
      </label>
      <button class="action-btn" onclick="mkdir()">
        <span>＋</span> New Folder
      </button>
      <button class="action-btn" onclick="zipCur()">
        <span>🗜</span> ZIP Folder
      </button>
    </div>
    <div class="qt-right">
      <span style="font-size:12px;color:var(--sub)">Saving to: <b id="droptarget" style="color:var(--txt)">Storage</b></span>
    </div>
  </section>

  <!-- DUAL-PANE STORAGE EXPLORER -->
  <div class="content-grid">
    <!-- Sidebar: Drives, Quick Access, & Bandwidth Stats -->
    <aside class="sidebar" id="sidebar-panel">
      <div class="panel glass">
        <div class="section-title">Storage &amp; Drives</div>
        <div class="roots-list" id="roots-list"></div>
      </div>

      <div class="panel glass" id="quickpanel">
        <div class="section-title">Quick Access</div>
        <div class="quick-chips" id="quick-chips"></div>
      </div>

      <!-- Live Bandwidth & Health Widget -->
      <div class="panel glass">
        <div class="section-title">Session Performance</div>
        <div class="stats-widget">
          <div class="sw-row">
            <span class="sw-lbl">⬇ Downloaded</span>
            <span class="sw-val highlight" id="sw-down-vol">0.0 MB</span>
          </div>
          <div class="sw-row">
            <span class="sw-lbl">⬆ Uploaded</span>
            <span class="sw-val highlight" id="sw-up-vol">0.0 MB</span>
          </div>
          <div class="sw-row">
            <span class="sw-lbl">⚡ Current Speed</span>
            <span class="sw-val" id="sw-cur-speed">0.0 MB/s (0 Mbps)</span>
          </div>
        </div>
      </div>
    </aside>

    <!-- Main Explorer Pane -->
    <section class="explorer glass" id="explorer-card">
      <!-- Remote Browsing Banner -->
      <div id="remote-banner" class="glass" style="display:none;margin-bottom:6px;padding:12px 18px;border-radius:18px;align-items:center;justify-content:space-between;background:linear-gradient(135deg,rgba(56,189,248,.18),rgba(129,140,248,.18));border:1px solid rgba(56,189,248,.4);box-shadow:0 8px 24px rgba(56,189,248,.2)">
        <div style="display:flex;align-items:center;gap:10px;font-size:13.5px;font-weight:700">
          <span id="remote-icon" style="font-size:20px">📱</span>
          <span>Browsing <span id="remote-label" class="grad-txt">Remote Device</span></span>
          <span style="font-size:10.5px;padding:2px 8px;border-radius:12px;background:rgba(34,197,94,.2);color:#4ade80;border:1px solid rgba(34,197,94,.4)">Connected</span>
        </div>
        <button class="abtn" onclick="exitRemote()" style="font-weight:800;padding:8px 18px;border-radius:99px;background:linear-gradient(135deg,var(--acc),var(--acc2));color:#ffffff;border:none;box-shadow:0 4px 14px var(--acc-glow);cursor:pointer">⏏ Return to Local Device</button>
      </div>

      <div class="explorer-top">
        <button class="abtn" onclick="goUp()">⬆ Up</button>
        <div class="crumbs-bar" id="crumbs-bar"></div>
      </div>

      <div class="explorer-actions">
        <div class="search-box">
          <span class="search-icon">🔍</span>
          <input type="text" class="search-input" id="search-input" placeholder="Search files in current directory…" oninput="filterFiles()">
        </div>
        <div class="view-toggle">
          <button class="view-btn active" id="btn-list-view" onclick="setViewMode('list')" title="List View">☰</button>
          <button class="view-btn" id="btn-grid-view" onclick="setViewMode('grid')" title="Grid View">☵</button>
        </div>
        <div class="sort-wrap" id="sort-wrap">
          <button type="button" class="sort-btn" id="btn-sort" onclick="toggleSortMenu(event)" title="Sort options">
            <span>⇅</span>
            <span id="sort-current-label">Name (A→Z)</span>
            <span style="font-size:9px;opacity:.7">▼</span>
          </button>
          <div class="sort-menu" id="sort-menu" style="display:none">
            <div class="sort-menu-header">SORT ORDER</div>
            <div class="sort-option" data-sort="name-asc" onclick="applySort('name','asc')">
              <span class="sort-opt-icon">🔤</span>
              <span class="sort-opt-text">Name (A → Z)</span>
              <span class="sort-check">✓</span>
            </div>
            <div class="sort-option" data-sort="name-desc" onclick="applySort('name','desc')">
              <span class="sort-opt-icon">🔤</span>
              <span class="sort-opt-text">Name (Z → A)</span>
              <span class="sort-check">✓</span>
            </div>
            <div class="sort-divider"></div>
            <div class="sort-option" data-sort="date-desc" onclick="applySort('date','desc')">
              <span class="sort-opt-icon">🕒</span>
              <span class="sort-opt-text">Date (Newest First)</span>
              <span class="sort-check">✓</span>
            </div>
            <div class="sort-option" data-sort="date-asc" onclick="applySort('date','asc')">
              <span class="sort-opt-icon">🕒</span>
              <span class="sort-opt-text">Date (Oldest First)</span>
              <span class="sort-check">✓</span>
            </div>
            <div class="sort-divider"></div>
            <div class="sort-option" data-sort="size-desc" onclick="applySort('size','desc')">
              <span class="sort-opt-icon">📊</span>
              <span class="sort-opt-text">Size (Largest First)</span>
              <span class="sort-check">✓</span>
            </div>
            <div class="sort-option" data-sort="size-asc" onclick="applySort('size','asc')">
              <span class="sort-opt-icon">📊</span>
              <span class="sort-opt-text">Size (Smallest First)</span>
              <span class="sort-check">✓</span>
            </div>
            <div class="sort-divider"></div>
            <div class="sort-option" data-sort="type-asc" onclick="applySort('type','asc')">
              <span class="sort-opt-icon">🏷️</span>
              <span class="sort-opt-text">File Extension</span>
              <span class="sort-check">✓</span>
            </div>
          </div>
        </div>
        <button class="abtn" onclick="load()" title="Reload Directory">⟳</button>
      </div>

      <div class="section-title" id="listhead" style="margin-top:6px">Files &amp; Folders</div>
      <div class="file-list" id="file-list"></div>
    </section>
  </div>
</main>

<!-- Hidden file inputs -->
<input type="file" id="fin" multiple style="position:fixed;top:-1000px;left:-1000px;opacity:0.001;pointer-events:none;">
<input type="file" id="findir" webkitdirectory style="position:fixed;top:-1000px;left:-1000px;opacity:0.001;pointer-events:none;">

<!-- ==================== TRANSFERS & DOWNLOADS DRAWER ==================== -->
<aside class="transfers-drawer" id="transfers-drawer">
  <div class="td-head">
    <div class="td-title">
      <span>📥</span> Transfers &amp; Downloads
    </div>
    <button class="td-close" onclick="toggleTransferDrawer()">✕</button>
  </div>

  <!-- Live Speedometer -->
  <div class="td-speedometer">
    <div class="sm-metric">
      <span class="sm-label">⬇ Download Speed</span>
      <span class="sm-val" id="td-dl-speed">0.0 MB/s</span>
      <span class="sm-sub" id="td-dl-mbps">0 Mbps</span>
    </div>
    <div class="sm-metric">
      <span class="sm-label">⬆ Upload Speed</span>
      <span class="sm-val upload" id="td-ul-speed">0.0 MB/s</span>
      <span class="sm-sub" id="td-ul-mbps">0 Mbps</span>
    </div>
  </div>

  <!-- Tabs Switcher -->
  <div class="td-tabs">
    <button class="td-tab active" id="tab-active" onclick="switchTransferTab('active')">
      Active (<span id="tab-active-count">0</span>)
    </button>
    <button class="td-tab" id="tab-history" onclick="switchTransferTab('history')">
      History (<span id="tab-hist-count">0</span>)
    </button>
    <span style="flex:1"></span>
    <button class="abtn" id="btn-clear-history" style="padding:4px 10px;font-size:11px;display:none" onclick="clearTransferHistory()">Clear History</button>
  </div>

  <!-- Transfers List View -->
  <div class="td-list" id="td-list-active">
    <div class="td-empty" id="td-empty-active">
      <span class="td-empty-icon">⚡</span>
      <b>No Active Transfers</b>
      <span>Download or upload any file to watch gigabit speed in real time.</span>
    </div>
  </div>

  <div class="td-list" id="td-list-history" style="display:none">
    <div class="td-empty" id="td-empty-history">
      <span class="td-empty-icon">📁</span>
      <b>No Transfer History</b>
      <span>Completed downloads and uploads will appear here.</span>
    </div>
  </div>
</aside>

<!-- ==================== FLOATING DYNAMIC ISLAND HUD ==================== -->
<div id="dynamic-island" onclick="openTransferDrawer()">
  <span class="di-icon">⚡</span>
  <div class="di-info">
    <span class="di-name" id="di-name">Transferring…</span>
    <span class="di-meta" id="di-meta">0.0 MB/s • 0%</span>
  </div>
</div>

<!-- Floating Batch Selection Bar -->
<div id="selbar" class="glass">
  <b id="selcount">0 selected</b>
  <button class="action-btn primary" onclick="dlSelected()">⬇ Download Selected</button>
  <button class="abtn" onclick="clearSel()">✕</button>
</div>

<!-- Mobile Floating Bottom Dock (Apple Glass + Material 3) -->
<nav class="mobile-dock glass" id="mobile-dock">
  <button class="md-item active" id="md-files" onclick="mobileScrollTo('explorer-card')">
    <span class="md-icon">📁</span>
    <span class="md-label">Files</span>
  </button>
  <button class="md-item" id="md-transfers" onclick="toggleTransferDrawer()">
    <span class="md-icon">📥</span>
    <span class="md-badge" id="md-transfers-badge" style="display:none">0</span>
    <span class="md-label">Transfers</span>
  </button>
  <button class="md-item" id="md-radar" onclick="mobileScrollTo('radar-card')">
    <span class="md-icon">📡</span>
    <span class="md-label">Radar</span>
  </button>
  <button class="md-item" id="md-storage" onclick="mobileScrollTo('sidebar-panel')">
    <span class="md-icon">💾</span>
    <span class="md-label">Storage</span>
  </button>
  <label for="fin" class="md-item" style="cursor:pointer">
    <span class="md-icon">⚡</span>
    <span class="md-label">Upload</span>
  </label>
</nav>

<!-- In-Page Liquid Glass Auth Modal -->
<div id="authmodal" class="auth-scrim" style="display:none">
  <div class="auth-card glass">
    <button type="button" class="abtn" onclick="closeAuthModal()" style="position:absolute;top:14px;right:14px;padding:4px 10px;font-size:12px;border-radius:99px">✕ Cancel</button>
    <div class="auth-icon">🛡️</div>
    <h2 class="auth-title">Fast Share Security</h2>
    <p class="auth-sub" id="authdesc">Enter password to connect to this device</p>
    <div class="auth-field">
      <span style="opacity:.7">🔑</span>
      <input type="password" id="authpwd" placeholder="Enter password…" autocomplete="current-password" onkeydown="if(event.key==='Enter')submitLogin()">
      <button type="button" class="auth-eye" onclick="togglePwd()">👁️</button>
    </div>
    <div id="autherr" class="auth-err" style="display:none"></div>
    <button class="action-btn primary auth-btn" onclick="submitLogin()">Unlock &amp; Connect</button>
  </div>
</div>

<!-- Settings & Security Modal -->
<div id="settingsmodal" class="auth-scrim" style="display:none">
  <div class="auth-card glass" style="max-width:440px;text-align:left">
    <button type="button" class="abtn" onclick="closeSettingsModal()" style="position:absolute;top:14px;right:14px;padding:4px 10px;font-size:12px;border-radius:99px">✕ Close</button>
    <div style="display:flex;align-items:center;gap:12px;margin-bottom:12px">
      <div class="auth-icon" style="margin:0;font-size:30px">⚙️</div>
      <div>
        <h2 class="auth-title" style="margin:0;font-size:18px;text-align:left">Device Settings &amp; Security</h2>
        <p class="auth-sub" style="margin:0;font-size:12px;text-align:left">Change access credentials for phones &amp; remote devices</p>
      </div>
    </div>

    <div style="margin:16px 0 6px">
      <label style="font-size:11px;font-weight:700;letter-spacing:0.6px;color:var(--sub);text-transform:uppercase">Username</label>
      <div class="auth-field" style="margin-top:5px">
        <span style="opacity:.7">👤</span>
        <input type="text" id="set-user" placeholder="Username (default: share)" autocomplete="username">
      </div>
    </div>

    <div style="margin:12px 0 6px">
      <label style="font-size:11px;font-weight:700;letter-spacing:0.6px;color:var(--sub);text-transform:uppercase">Access Password</label>
      <div class="auth-field" style="margin-top:5px">
        <span style="opacity:.7">🔑</span>
        <input type="password" id="set-pass" placeholder="Enter new password…" autocomplete="new-password">
        <button type="button" class="auth-eye" onclick="toggleSetPwd()">👁️</button>
      </div>
    </div>

    <div style="display:flex;gap:8px;margin-top:12px">
      <button type="button" class="abtn" onclick="generateRandomPass()" style="font-size:11px;padding:6px 12px">🎲 Generate Password</button>
      <button type="button" class="abtn" onclick="copyPass()" style="font-size:11px;padding:6px 12px">📋 Copy</button>
    </div>

    <div id="setmsg" class="auth-err" style="display:none;margin-top:12px"></div>

    <div style="margin-top:20px;display:flex;justify-content:flex-end;gap:10px">
      <button type="button" class="abtn" onclick="closeSettingsModal()">Cancel</button>
      <button type="button" class="action-btn primary" onclick="saveSettings()" style="padding:8px 20px">💾 Save &amp; Apply</button>
    </div>
  </div>
</div>

<!-- Full-Screen Media Lightbox Modal -->
<div id="media-lightbox" class="media-lightbox" style="display:none" onclick="if(event.target===this)closeLightbox()">
  <div class="lb-card glass">
    <div class="lb-head">
      <div class="lb-file-info">
        <span class="lb-icon" id="lb-icon">🖼️</span>
        <div>
          <div class="lb-filename" id="lb-filename">Media Preview</div>
          <div class="lb-meta" id="lb-meta">1 of 1</div>
        </div>
      </div>
      <div class="lb-actions">
        <button type="button" class="abtn go" id="lb-btn-dl" onclick="lbDownload()">⬇ Download</button>
        <button type="button" class="abtn" onclick="closeLightbox()">✕</button>
      </div>
    </div>
    <div class="lb-content" id="lb-content"></div>
    <div class="lb-nav-bar">
      <button type="button" class="lb-nav-btn" id="lb-prev" onclick="lbNav(-1)">◀ Previous</button>
      <button type="button" class="lb-nav-btn" id="lb-next" onclick="lbNav(1)">Next ▶</button>
    </div>
  </div>
</div>

<div id="snack"></div>

<script>
/*__SERVER_META__*/
"use strict";
let cur="",items=[],roots=[],quick=[],info={},crumbs=[],sel=new Set();
let transfers={},remoteTarget=null,remoteNode=null;
let transferHistory=[];
let sessionDownloaded=0,sessionUploaded=0;
let viewMode="list";
const $=id=>document.getElementById(id);
if(location.search.includes('webview=1') || (window.__FS_META__ && window.__FS_META__.is_webview) || navigator.userAgent.includes('wv') || /Android.*Version\/[\d.]+\s+Chrome/i.test(navigator.userAgent)){
  document.body.classList.add('in-webview');
}

function getBase(){return remoteNode?('http://'+remoteNode.ip+':'+remoteNode.port):''}
function getToken(){return remoteNode?(sessionStorage.getItem(remoteNode.ip+'_token')||''):(sessionStorage.getItem('fs_token')||'')}
function getHeaders(extra){let h=extra?Object.assign({},extra):{};let tok=getToken();if(tok)h['Authorization']=tok.includes(' ')?tok:('Basic '+tok);return h}

function browseDevice(ip,port,name,isPhone){remoteNode={ip,port,name,isPhone};cur='';sel.clear();$('search-input').value='';snack('Connecting to '+name+' ('+ip+')…');updateRemoteBanner();load()}
function exitRemote(){remoteNode=null;cur='';sel.clear();$('search-input').value='';snack('Returned to local storage');updateRemoteBanner();load()}

function updateNavbarLocation(){
  let brandSub = document.querySelector('.brand-sub');
  let brandBadge = document.querySelector('.brand-badge');
  let dname = cur ? (cur.split(/[\\/]/).pop() || cur) : 'Storage';
  if(remoteNode){
    if(brandSub){
      brandSub.innerHTML = `<span class="nav-pulse-dot">🟢</span> Browsing <b>${esc(remoteNode.name)}</b> (${esc(remoteNode.ip)})`;
      brandSub.className = 'brand-sub nav-location-badge';
    }
    if(brandBadge) brandBadge.textContent = remoteNode.name.toUpperCase();
    document.title = `${dname} — ${remoteNode.name} | Fast Share`;
  } else {
    if(brandSub){
      brandSub.innerHTML = `💻 Local Device • <b>${esc((info && info.device) || 'This PC')}</b>`;
      brandSub.className = 'brand-sub';
    }
    if(brandBadge) brandBadge.textContent = 'GIGABIT';
    document.title = `${dname} — Fast Share`;
  }
}

function updateLocationHUD(folderName, fullPath, count){
  let headEl = $('listhead');
  if(!headEl) return;
  let devLabel = remoteNode ? `on ${esc(remoteNode.name)} (${esc(remoteNode.ip)})` : `Local Device`;
  headEl.innerHTML = `
    <div class="location-hud">
      <div class="loc-main">
        <span class="loc-folder-icon">${remoteNode ? (remoteNode.isPhone ? '📱' : '💻') : '📁'}</span>
        <div>
          <div class="loc-current-folder">${esc(folderName)}</div>
          <div class="loc-path-sub">${devLabel} • ${esc(fullPath || 'Root Storage')}</div>
        </div>
      </div>
      <div class="loc-stats">
        <span class="loc-count-pill">${count} item${count === 1 ? '' : 's'}</span>
      </div>
    </div>
  `;
}

function updateRemoteBanner(){let b=$('remote-banner');if(!b)return;if(remoteNode){$('remote-icon').textContent=remoteNode.isPhone?'📱':'💻';$('remote-label').textContent=remoteNode.name+' ('+remoteNode.ip+')';b.style.display='flex'}else{b.style.display='none'}}

function snack(m){
  let t=$('snack');t.textContent=m;t.style.display='block';
  clearTimeout(t._h);t._h=setTimeout(()=>t.style.display='none',2600);
}

function copyLink(){
  let url=location.origin;
  try{navigator.clipboard.writeText(url)}catch(e){}
  snack('Link copied: '+url);
}

function topbar(p){
  let b=$('topbar');b.style.opacity='1';b.style.width=p+'%';
  if(p>=100)setTimeout(()=>b.style.opacity='0',400);
}

function fmt(n){
  if(n===0)return'0 B';if(!n)return'-';
  if(n>=1073741824)return(n/1073741824).toFixed(2)+' GB';
  if(n>=1048576)return(n/1048576).toFixed(1)+' MB';
  if(n>=1024)return(n/1024).toFixed(0)+' KB';
  return n+' B';
}

function fmeta(ms){
  if(!ms||ms<1000)return Math.max(0,Math.round(ms))+'ms';
  let s=Math.floor(ms/1000);if(s<60)return s+'s';
  return Math.floor(s/60)+'m '+(s%60)+'s';
}

function icon(n,d){
  if(d)return'📁';n=(n||'').toLowerCase();
  if(n.endsWith('.apk')||n.endsWith('.apks'))return'📦';
  if(/mp4|mkv|avi|mov|webm|3gp$/.test(n))return'🎬';
  if(/mp3|wav|flac|m4a|ogg|opus$/.test(n))return'🎵';
  if(/jpg|jpeg|png|gif|webp|bmp|heic$/.test(n))return'🖼️';
  if(/zip|rar|7z|tar|gz$/.test(n))return'🗜️';
  if(/pdf$/.test(n))return'📕';
  if(/doc|docx|txt|md|log$/.test(n))return'📝';
  if(/xls|xlsx|csv$/.test(n))return'📊';
  if(/ppt|pptx$/.test(n))return'📽️';
  if(/exe|msi$/.test(n))return'⚙️';
  if(/html|htm|js|py|json|css$/.test(n))return'🌐';
  return'📄';
}

function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')}

/* ---------- THEME & VIEW MODE ---------- */
function setTheme(m){
  document.documentElement.dataset.theme=m;
  try{localStorage.setItem('fs-theme',m)}catch(e){}
}
function toggleTheme(){
  setTheme(document.documentElement.dataset.theme==='dark'?'light':'dark');
}

function setViewMode(mode){
  viewMode=mode;
  $('btn-list-view').classList.toggle('active',mode==='list');
  $('btn-grid-view').classList.toggle('active',mode==='grid');
  $('file-list').classList.toggle('grid-mode',mode==='grid');
  try{localStorage.setItem('fs-view-mode',mode)}catch(e){}
}

function toggleRadarCollapse(){
  let grid=$('nearby-grid');
  let btn=$('btn-radar-toggle');
  let hide=grid.style.display!=='none';
  grid.style.display=hide?'none':'grid';
  btn.textContent=hide?'▼':'▲';
}

/* ---------- TRANSFERS & DOWNLOADS ENGINE ---------- */
function toggleTransferDrawer(){
  $('transfers-drawer').classList.toggle('open');
}
function openTransferDrawer(){
  $('transfers-drawer').classList.add('open');
}

function switchTransferTab(tab){
  $('tab-active').classList.toggle('active',tab==='active');
  $('tab-history').classList.toggle('active',tab==='history');
  $('td-list-active').style.display=tab==='active'?'flex':'none';
  $('td-list-history').style.display=tab==='history'?'flex':'none';
  $('btn-clear-history').style.display=tab==='history'&&transferHistory.length?'inline-block':'none';
}

function createTransferCard(id,name,direction,destLabel,totalBytes){
  let container=$('td-list-active');
  $('td-empty-active').style.display='none';

  let card=document.createElement('div');
  card.className='td-card';
  card.id='card-'+id;
  card.innerHTML=`
    <div class="td-card-top">
      <span class="td-badge ${direction==='down'?'down':'up'}">${direction==='down'?'⬇ DOWNLOAD':'⬆ UPLOAD'}</span>
      <span class="td-name" title="${esc(name)}">${esc(name)}</span>
      <button class="abtn" style="padding:2px 8px;font-size:11px" onclick="cancelTransfer('${id}')">✕</button>
    </div>
    <div class="td-meta">
      <span>${esc(destLabel)}</span>
      <span class="card-progress-text">0 / ${fmt(totalBytes)} (0%)</span>
    </div>
    <div class="td-pbar"><i style="width:0%"></i></div>
    <div class="td-stats">
      <span class="td-spd">0.0 MB/s (0 Mbps)</span>
      <span class="card-eta">Calculating…</span>
    </div>
  `;
  container.prepend(card);

  let T={
    id,name,direction,totalBytes,startTime:Date.now(),lastLoaded:0,lastTime:Date.now(),
    card,
    fill:card.querySelector('.td-pbar i'),
    txtProg:card.querySelector('.card-progress-text'),
    txtSpd:card.querySelector('.td-spd'),
    txtEta:card.querySelector('.card-eta')
  };
  transfers[id]=T;
  updateTransferBadges();
  return T;
}

function updateTransferProgress(T,loaded,total){
  let now=Date.now();
  let uiDelta=(now-(T.lastUiUpdate||0))/1000;
  if(total && loaded<total && uiDelta<0.12 && T.lastUiUpdate) return;
  T.lastUiUpdate=now;

  let timeDelta=(now-T.lastTime)/1000;
  let bytesDelta=loaded-T.lastLoaded;

  let currentSpeed=0;
  if(timeDelta>=0.2){
    currentSpeed=bytesDelta/timeDelta; // bytes/sec
    T.instantSpeed=currentSpeed;
    T.lastLoaded=loaded;
    T.lastTime=now;
  }else{
    currentSpeed=T.instantSpeed||0;
  }

  let totalElapsed=(now-T.startTime)/1000;
  let avgSpeed=totalElapsed>0?loaded/totalElapsed:0;
  let speedToDisplay=currentSpeed>0?currentSpeed:avgSpeed;

  let mbps=((speedToDisplay*8)/(1024*1024)).toFixed(1);
  let mbpsStr=(speedToDisplay/1048576).toFixed(1)+' MB/s ('+mbps+' Mbps)';

  let percent=total>0?Math.min(100,(loaded/total)*100):0;
  T.fill.style.width=percent.toFixed(1)+'%';
  T.txtProg.textContent=fmt(loaded)+' / '+(total?fmt(total):'?')+' ('+percent.toFixed(1)+'%)';
  T.txtSpd.textContent=mbpsStr;

  if(total&&speedToDisplay>0){
    let remainingBytes=total-loaded;
    let etaSec=remainingBytes/speedToDisplay;
    T.txtEta.textContent=etaSec>0?'ETA '+fmeta(etaSec*1000):'Finishing…';
  }else{
    T.txtEta.textContent='';
  }

  // Update Topbar and Floating Island
  topbar(Math.round(percent||50));
  updateDynamicIsland(T.name,mbpsStr,percent);
  updateAggregateSpeedometer();
}

function finishTransfer(T,success,message){
  let elapsed=(Date.now()-T.startTime)/1000;
  let avgSpeed=elapsed>0?T.totalBytes/elapsed:0;

  if(success){
    T.fill.style.width='100%';
    T.txtProg.textContent='Completed';
    T.txtSpd.textContent=avgSpeed>0?(avgSpeed/1048576).toFixed(1)+' MB/s avg':'Done';
    T.txtEta.textContent='✅ Done';
    snack(message);

    if(T.direction==='down'){sessionDownloaded+=T.totalBytes||0;}
    else{sessionUploaded+=T.totalBytes||0;}
    updateSessionWidget();

    // Add to Persistent Transfer History
    addToHistory({
      id:T.id,
      name:T.name,
      direction:T.direction,
      size:T.totalBytes,
      speed:avgSpeed,
      time:Date.now()
    });
  }else{
    T.fill.style.background='var(--rose)';
    T.txtProg.textContent=message;
    T.txtSpd.textContent='';
    T.txtEta.textContent='❌ Failed';
  }

  setTimeout(()=>{
    try{T.card.remove()}catch(e){}
    delete transfers[T.id];
    updateTransferBadges();
    updateAggregateSpeedometer();
  },3000);
}

function cancelTransfer(id){
  let T=transfers[id];
  if(T){
    T.cancel&&T.cancel();
    finishTransfer(T,false,'Canceled by user');
  }
}

function updateTransferBadges(){
  let activeKeys=Object.keys(transfers);
  let n=activeKeys.length;
  let bEl=$('nt-badge');if(bEl)bEl.textContent=n;
  let cEl=$('tab-active-count');if(cEl)cEl.textContent=n;
  let mdBadge=$('md-transfers-badge');
  if(mdBadge){
    mdBadge.textContent=n;
    mdBadge.style.display=n>0?'inline-block':'none';
  }

  let list=$('td-list-active');
  let empty=$('td-empty-active');
  let island=$('dynamic-island');
  let ntSpeed=$('nt-speed');

  if(n===0){
    if(list&&!list.querySelector('.td-card')){
      list.innerHTML=`<div class="td-empty" id="td-empty-active">
        <span class="td-empty-icon">⚡</span>
        <b>No Active Transfers</b>
        <span>Download or upload any file to watch gigabit speed in real time.</span>
      </div>`;
    }else if(empty){
      empty.style.display='flex';
    }
    if(island)island.style.display='none';
    if(ntSpeed){
      ntSpeed.style.display='none';
      ntSpeed.classList.remove('active');
    }
  }else{
    if(empty)empty.style.display='none';
    if(island)island.style.display='flex';
    if(ntSpeed){
      ntSpeed.style.display='inline-flex';
      ntSpeed.classList.add('active');
    }
  }
}

function mobileScrollTo(id){
  document.querySelectorAll('.md-item').forEach(b=>b.classList.remove('active'));
  if(id==='explorer-card'){
    window.scrollTo({top:0,behavior:'smooth'});
    $('md-files')?.classList.add('active');
    return;
  }
  let el=$(id);
  if(el){
    el.scrollIntoView({behavior:'smooth',block:'start'});
    if(id==='radar-card')$('md-radar')?.classList.add('active');
    else if(id==='sidebar-panel')$('md-storage')?.classList.add('active');
  }
}

function updateDynamicIsland(name,speedStr,percent){
  $('di-name').textContent=name;
  $('di-meta').textContent=speedStr+' • '+percent.toFixed(0)+'%';
}

function updateAggregateSpeedometer(){
  let dlSpeed=0,ulSpeed=0;
  for(let k in transfers){
    let t=transfers[k];
    let spd=t.instantSpeed||0;
    if(t.direction==='down')dlSpeed+=spd;
    else ulSpeed+=spd;
  }
  let dlMb=(dlSpeed/1048576).toFixed(1);
  let dlMbps=((dlSpeed*8)/1048576).toFixed(0);
  let ulMb=(ulSpeed/1048576).toFixed(1);
  let ulMbps=((ulSpeed*8)/1048576).toFixed(0);

  $('td-dl-speed').textContent=dlMb+' MB/s';
  $('td-dl-mbps').textContent=dlMbps+' Mbps';
  $('td-ul-speed').textContent=ulMb+' MB/s';
  $('td-ul-mbps').textContent=ulMbps+' Mbps';

  let totalMb=((dlSpeed+ulSpeed)/1048576).toFixed(1);
  $('nt-speed').textContent='⚡ '+totalMb+' MB/s';
  $('sw-cur-speed').textContent=totalMb+' MB/s ('+((dlSpeed+ulSpeed)*8/1048576).toFixed(0)+' Mbps)';
}

function updateSessionWidget(){
  $('sw-down-vol').textContent=fmt(sessionDownloaded);
  $('sw-up-vol').textContent=fmt(sessionUploaded);
}

/* History Storage */
function loadHistoryFromStorage(){
  try{
    let s=localStorage.getItem('fs_transfer_history');
    if(s)transferHistory=JSON.parse(s);
  }catch(e){transferHistory=[]}
  renderHistory();
}

function addToHistory(record){
  transferHistory.unshift(record);
  if(transferHistory.length>50)transferHistory.pop();
  try{localStorage.setItem('fs_transfer_history',JSON.stringify(transferHistory))}catch(e){}
  renderHistory();
}

function clearTransferHistory(){
  transferHistory=[];
  try{localStorage.removeItem('fs_transfer_history')}catch(e){}
  renderHistory();
  snack('Transfer history cleared');
}

function renderHistory(){
  let countEl=$('tab-hist-count');
  if(countEl)countEl.textContent=transferHistory.length;
  let list=$('td-list-history');
  let btnClear=$('btn-clear-history');
  if(!list)return;

  if(btnClear)btnClear.style.display=transferHistory.length?'inline-block':'none';

  if(!transferHistory.length){
    list.innerHTML=`<div class="td-empty" id="td-empty-history">
      <span class="td-empty-icon">📁</span>
      <b>No Transfer History</b>
      <span>Completed downloads and uploads will appear here.</span>
    </div>`;
    return;
  }

  list.innerHTML=transferHistory.map(h=>{
    let dt=new Date(h.time).toLocaleTimeString();
    let avgStr=h.speed>0?(h.speed/1048576).toFixed(1)+' MB/s':'Done';
    return `<div class="td-card">
      <div class="td-card-top">
        <span class="td-badge done">✅ ${h.direction==='down'?'DOWNLOAD':'UPLOAD'}</span>
        <span class="td-name" title="${esc(h.name)}">${esc(h.name)}</span>
      </div>
      <div class="td-meta">
        <span>${fmt(h.size)} • ${avgStr}</span>
        <span>${dt}</span>
      </div>
    </div>`;
  }).join('');
}

/* ---------- DOWNLOAD IMPLEMENTATION ---------- */
async function dl(path,name,total){
  let nm=name||path.split(/[\\/]/).pop()||'file';
  let id='dl-'+Date.now()+'-'+Math.random().toString(36).substr(2,4);
  let destLabel=remoteNode?('From '+remoteNode.name):'Local Server';
  let T=createTransferCard(id,nm,'down',destLabel,total||0);
  snack('⬇ Downloading '+nm+'…');

  let ctl=new AbortController();
  T.cancel=()=>ctl.abort();

  let tok=getToken();
  let rawTok=tok.replace(/^(Basic|Bearer)\s+/i,'');
  let directUrl=remoteNode
    ? `http://${remoteNode.ip}:${remoteNode.port}/api/download?path=${encodeURIComponent(path)}${rawTok?('&token='+encodeURIComponent(rawTok)):''}`
    : `/api/download?path=${encodeURIComponent(path)}${tok?('&token='+encodeURIComponent(tok)):''}`;
  let proxyUrl=remoteNode
    ? `/api/proxy_download?ip=${encodeURIComponent(remoteNode.ip)}&port=${encodeURIComponent(remoteNode.port)}&path=${encodeURIComponent(path)}&name=${encodeURIComponent(nm)}${rawTok?('&token='+encodeURIComponent(rawTok)):''}`
    : directUrl;

  // High-performance streaming for large files (>60MB)
  if(window.showSaveFilePicker && (total > 60 * 1024 * 1024)){
    try{
      const handle = await window.showSaveFilePicker({suggestedName: nm});
      const writable = await handle.createWritable();
      let r;
      try{
        r = await fetch(directUrl, {signal: ctl.signal, headers: getHeaders()});
        if(!r.ok) throw new Error('HTTP '+r.status);
      }catch(fe){
        if(proxyUrl !== directUrl){
          r = await fetch(proxyUrl, {signal: ctl.signal, headers: getHeaders()});
          if(!r.ok) throw fe;
        }else throw fe;
      }
      let len = +r.headers.get('Content-Length') || total || 0;
      T.totalBytes = len;
      let loaded = 0;
      let lastProg = 0;
      if(window.TransformStream && r.body && r.body.pipeThrough){
        const progressStream = new TransformStream({
          transform(chunk, controller){
            loaded += chunk.length;
            let now = Date.now();
            if(now - lastProg >= 150){
              updateTransferProgress(T, loaded, len);
              lastProg = now;
            }
            controller.enqueue(chunk);
          },
          flush(){
            updateTransferProgress(T, loaded, len);
          }
        });
        await r.body.pipeThrough(progressStream).pipeTo(writable, {signal: ctl.signal});
      }else{
        let rec = r.body.getReader();
        while(true){
          let o = await rec.read();
          if(o.done) break;
          await writable.write(o.value);
          loaded += o.value.length;
          let now = Date.now();
          if(now - lastProg >= 150){
            updateTransferProgress(T, loaded, len);
            lastProg = now;
          }
        }
        await writable.close();
      }
      finishTransfer(T, true, '✅ Downloaded ' + nm);
      return;
    }catch(fsErr){
      if(fsErr && fsErr.name === 'AbortError'){
        finishTransfer(T, false, 'Download canceled');
        return;
      }
    }
  }

  // Large file fallback for mobile WebViews or browsers without showSaveFilePicker
  if(total > 60 * 1024 * 1024){
    let a = document.createElement('a');
    a.href = directUrl;
    a.download = nm;
    document.body.appendChild(a);
    a.click();
    a.remove();
    finishTransfer(T, true, '✅ Streaming to browser download manager');
    return;
  }

  try{
    let r;
    try{
      r=await fetch(directUrl,{signal:ctl.signal,headers:getHeaders()});
    }catch(err){
      if(proxyUrl!==directUrl)r=await fetch(proxyUrl,{signal:ctl.signal,headers:getHeaders()});
      else throw err;
    }
    if(!r.ok)throw new Error('HTTP '+r.status);

    let len=+r.headers.get('Content-Length')||total||0;
    T.totalBytes=len;
    let rec=r.body.getReader();
    let chunks=[];
    let loaded=0;
    let lastProg=0;

    while(true){
      let o=await rec.read();
      if(o.done)break;
      chunks.push(o.value);
      loaded+=o.value.length;
      let now=Date.now();
      if(now-lastProg>=150){
        updateTransferProgress(T,loaded,len);
        lastProg=now;
      }
    }
    updateTransferProgress(T,loaded,len);

    let blob=new Blob(chunks,{type:'application/octet-stream'});
    chunks=null;
    let blobUrl=URL.createObjectURL(blob);
    let a=document.createElement('a');
    a.href=blobUrl;a.download=nm;document.body.appendChild(a);a.click();a.remove();
    setTimeout(()=>URL.revokeObjectURL(blobUrl),15000);
    finishTransfer(T,true,'✅ Downloaded '+nm);
  }catch(e){
    if(e&&e.name==='AbortError')finishTransfer(T,false,'Download canceled');
    else{
      // Fallback: browser direct link dispatch if streaming fetch was blocked
      let a=document.createElement('a');
      a.href=directUrl;a.download=nm;document.body.appendChild(a);a.click();a.remove();
      finishTransfer(T,true,'✅ Browser download dispatched');
    }
  }
}

/* ---------- UPLOADS IMPLEMENTATION ---------- */
function initDrop(){
  let overlay=$('dragoverlay');
  let dragCounter=0;
  window.addEventListener('dragenter',e=>{
    e.preventDefault();
    dragCounter++;
    overlay.style.display='flex';
  });
  window.addEventListener('dragleave',e=>{
    e.preventDefault();
    dragCounter--;
    if(dragCounter<=0){dragCounter=0;overlay.style.display='none';}
  });
  window.addEventListener('dragover',e=>{e.preventDefault();});
  window.addEventListener('drop',e=>{
    e.preventDefault();
    dragCounter=0;
    overlay.style.display='none';
    if(e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files.length){
      uploadFiles(e.dataTransfer.files);
    }
  });
}

function uploadFiles(files){
  if(!files.length)return;
  let target=remoteTarget;
  remoteTarget=null;
  for(let f of files){
    let tid='up-'+Date.now()+'-'+Math.random().toString(36).substr(2,4);
    let name=f.webkitRelativePath||f.name;
    let destLabel=target?(target.ip):(remoteNode?remoteNode.name:'Local Storage');
    let T=createTransferCard(tid,name,'up','To '+destLabel,f.size||0);
    uploadOne(f,name,target,tid,T);
  }
  snack('⬆ Uploading '+files.length+' item(s)…');
}

function uploadOne(file,name,target,tid,T){
  let base=target?`http://${target.ip}:${target.port}`:getBase();
  let tok=target?(sessionStorage.getItem(target.ip+'_token')||''):getToken();
  let url=`${base}/api/raw?path=${encodeURIComponent(cur)}&name=${encodeURIComponent(name)}`;
  let xhr=new XMLHttpRequest();
  xhr.upload.onprogress=e=>{
    if(e.lengthComputable){
      updateTransferProgress(T,e.loaded,e.total);
    }
  };
  xhr.onload=()=>{
    if(xhr.status>=200&&xhr.status<300){
      finishTransfer(T,true,'✅ Uploaded '+name);
      load();
    }else{
      finishTransfer(T,false,'Failed HTTP '+xhr.status);
    }
  };
  xhr.onerror=()=>finishTransfer(T,false,'Connection error');
  xhr.onabort=()=>finishTransfer(T,false,'Upload canceled');
  T.cancel=()=>xhr.abort();
  xhr.open('POST',url);
  if(tok)xhr.setRequestHeader('Authorization',tok.startsWith('Basic ')||tok.startsWith('Bearer ')?tok:('Basic '+tok));
  xhr.send(file);
}

function sendToDevice(ip,port){
  remoteTarget={ip:ip,port:port};
  $('fin').click();
}

async function zipCur(){
  let nm=(crumbs.length?crumbs[crumbs.length-1].name:'files').replace(/[^\w\- ]+/g,'')+'.zip';
  let id='zip-'+Date.now()+'-'+Math.random().toString(36).substr(2,4);
  let T=createTransferCard(id,nm,'down','ZIP Archive',0);
  snack('🗜 Streaming folder ZIP…');

  let ctl=new AbortController();
  T.cancel=()=>ctl.abort();

  let targetUrl='';
  let tok=getToken();
  if(remoteNode){
    let rawTok=tok.replace(/^(Basic|Bearer)\s+/i,'');
    targetUrl='/api/proxy_zip?ip='+encodeURIComponent(remoteNode.ip)+
              '&port='+encodeURIComponent(remoteNode.port)+
              '&path='+encodeURIComponent(cur)+
              '&name='+encodeURIComponent(nm)+
              (rawTok?('&token='+encodeURIComponent(rawTok)):'');
  }else{
    let zUrl='/api/zip?path='+encodeURIComponent(cur);
    targetUrl=tok?(zUrl+'&token='+encodeURIComponent(tok)):zUrl;
  }

  try{
    let r=await fetch(targetUrl,{signal:ctl.signal,headers:getHeaders()});
    if(!r.ok)throw new Error('HTTP '+r.status);
    let len=+r.headers.get('Content-Length')||0;
    T.totalBytes=len;
    let rec=r.body.getReader();
    let chunks=[];
    let loaded=0;
    while(true){
      let o=await rec.read();
      if(o.done)break;
      chunks.push(o.value);
      loaded+=o.value.length;
      updateTransferProgress(T,loaded,len);
    }
    let blob=new Blob(chunks,{type:'application/zip'});
    chunks=null;
    let blobUrl=URL.createObjectURL(blob);
    let a=document.createElement('a');
    a.href=blobUrl;a.download=nm;document.body.appendChild(a);a.click();a.remove();
    setTimeout(()=>URL.revokeObjectURL(blobUrl),15000);
    finishTransfer(T,true,'✅ Saved '+nm);
  }catch(e){
    if(e&&e.name==='AbortError')finishTransfer(T,false,'ZIP canceled');
    else finishTransfer(T,false,'ZIP failed');
  }
}

/* ---------- NEARBY RADAR ---------- */
const FS_CLIENT_VERSION="3.5";
async function pollNearby(){
  try{
    let r=await fetch('/api/nearby');
    if(!r.ok)return;
    let peers=await r.json();
    renderNearby(peers);
    let ir=await fetch('/api/info');
    if(ir.ok){
      let sinfo=await ir.json();
      if(sinfo.version&&sinfo.version!==FS_CLIENT_VERSION){location.reload();}
    }
  }catch(e){}
}

function renderNearby(peers){
  let box=$('nearby-grid');
  let myIp=(info&&info.ip)||'';
  let viewerIp=(info&&info.client_ip)||'';
  let activeViewer=null,otherPeers=[];

  for(let d of (peers||[])){
    if(!d||!d.ip)continue;
    if(d.ip===myIp||d.ip==='127.0.0.1'||d.ip==='localhost')continue;
    if(viewerIp&&d.ip===viewerIp)activeViewer=d;
    else otherPeers.push(d);
  }

  if(activeViewer){
    $('radarstat').innerHTML=`<span style="color:#4ade80">🟢 Connected:</span> <b>${esc(activeViewer.name)}</b> (${esc(activeViewer.ip)}) • Active Session`;
  }else if(!otherPeers.length){
    $('radarstat').textContent='Active on UDP 8002 — No other devices online yet';
  }else{
    $('radarstat').textContent=otherPeers.length+' device(s) active on Wi-Fi';
  }

  if(!otherPeers.length&&!activeViewer){
    box.innerHTML='<div class="radar-empty">Scanning Wi-Fi… Open Fast Share on your phone or laptop to connect instantly!</div>';
    return;
  }

  let html='';
  if(activeViewer){
    let isPhone=(activeViewer.os||'').toLowerCase().includes('android');
    html+=`<div class="nearby-device active-session-card">
      <div class="nd-icon">${isPhone?'📱':'💻'}</div>
      <div class="nd-info">
        <div class="nd-name">${esc(activeViewer.name)} <span class="active-tag">${isPhone ? 'This Phone' : 'Active Client'}</span></div>
        <div class="nd-ip">${esc(activeViewer.ip)}:${esc(activeViewer.port)} • 🟢 ${isPhone ? 'Connected phone' : 'Connected client'}</div>
        <div class="nd-actions">
          <button class="btn-sm btn-send" onclick="sendToDevice('${esc(activeViewer.ip)}',${activeViewer.port})">⚡ Push Files</button>
        </div>
      </div>
    </div>`;
  }

  html+=otherPeers.map(d=>{
    let isPhone=(d.os||'').toLowerCase().includes('android');
    return `<div class="nearby-device">
      <div class="nd-icon">${isPhone?'📱':'💻'}</div>
      <div class="nd-info">
        <div class="nd-name">${esc(d.name)}</div>
        <div class="nd-ip">${esc(d.ip)}:${esc(d.port)} • 🟢 Active</div>
        <div class="nd-actions">
          <button class="btn-sm btn-send" onclick="sendToDevice('${esc(d.ip)}',${d.port})">⚡ Send</button>
          <button class="btn-sm btn-browse" onclick="browseDevice('${esc(d.ip)}',${d.port},'${esc(d.name)}',${isPhone})">📂 Browse</button>
        </div>
      </div>
    </div>`;
  }).join('');
  box.innerHTML=html;
}

/* ---------- EXPLORER FILE RENDERING ---------- */
async function load(){
  topbar(25);
  let box=$('file-list');
  box.innerHTML='<div style="padding:24px;text-align:center;color:var(--sub)">Loading directory…</div>';
  try{
    let base=getBase();
    let url=cur?(base+'/api/list?path='+encodeURIComponent(cur)):(base+'/api/list');
    let r=await fetch(url,{headers:getHeaders()});
    if(r.status===401){checkAuthModal(false);return;}
    checkAuthModal(true);
    let j=await r.json();
    if(j.roots&&j.roots.length)roots=j.roots;
    if(j.quick&&j.quick.length)quick=j.quick;
    crumbs=j.breadcrumbs||[];items=j.items||[];
    cur=j.path!=null?j.path:'';

    renderRoots();renderQuick();drawCrumbs(crumbs);
    let devTitle=(remoteNode?remoteNode.name:((info&&info.device)||'This PC'));
    let dname=cur?cur.split(/[\\/]/).pop()||cur:devTitle;
    let targetLabel=remoteNode?`${remoteNode.name} (${dname})`:dname;
    $('droptarget').textContent=targetLabel;
    $('droptarget-overlay').textContent=targetLabel;
    updateNavbarLocation();
    renderFiles();
    updateLocationHUD(dname, cur, items.length);
    topbar(100);
  }catch(e){
    topbar(100);
    box.innerHTML='<div style="padding:24px;text-align:center;color:var(--sub)">❌ Could not load directory.<br><br><button class="abtn go" onclick="goHome()">All storage</button></div>';
  }
}

function renderRoots(){
  $('roots-list').innerHTML=roots.map(x=>{
    let pc=x.total?Math.round(x.used/x.total*100):0;
    let normCur = (cur || '').replace(/\/storage\/emulated\/0/g, '/sdcard').replace(/\\/g, '/').toLowerCase();
    let normPath = (x.path || '').replace(/\/storage\/emulated\/0/g, '/sdcard').replace(/\\/g, '/').toLowerCase();
    let isCur = (normCur === normPath) || (normCur.startsWith(normPath + '/'));
    return `<div class="root-item ${isCur ? 'cur active-drive' : ''}" onclick="rootGo('${esc(x.path)}')">
      <div class="root-icon">${x.icon||'💽'}</div>
      <div class="root-meta">
        <div class="root-name">${esc(x.name)}</div>
        <div class="root-path">${esc(x.path)}</div>
        ${x.total?`<div class="root-bar"><i style="width:${pc}%"></i></div><div style="font-size:10.5px;color:var(--sub);margin-top:4px">${fmt(x.free)} free of ${fmt(x.total)}</div>`:''}
      </div>
    </div>`;
  }).join('');
}

function renderQuick(){
  if(!quick.length){$('quickpanel').style.display='none';return;}
  $('quickpanel').style.display='block';
  $('quick-chips').innerHTML=quick.map(q=>`<button class="qchip" onclick="rootGo('${esc(q.path)}')">⭐ ${esc(q.name)}</button>`).join('');
}

function drawCrumbs(bc){
  let h = '';
  // 1. Root / Device chip
  let isRootCur = !cur;
  if(remoteNode){
    h += `<button class="crumb-chip remote-device-chip ${isRootCur ? 'cur active-crumb' : ''}" onclick="goHome()" title="View all drives and storage roots">
      <span class="nav-pulse-dot">🟢</span> ${remoteNode.isPhone ? '📱' : '💻'} ${esc(remoteNode.name)}
    </button>`;
  } else {
    h += `<button class="crumb-chip local-device-chip ${isRootCur ? 'cur active-crumb' : ''}" onclick="goHome()" title="View all drives and storage roots">
      💻 ${esc((info && info.device) || 'This PC')}
    </button>`;
  }

  // 2. Clean breadcrumb path calculation
  let cleanItems = [];
  let pathStr = cur || '';

  if (pathStr.startsWith('/storage/emulated/0')) {
    cleanItems.push({ name: 'Internal Storage', path: '/storage/emulated/0' });
    let sub = pathStr.slice('/storage/emulated/0'.length).replace(/^[\\/]+/, '');
    if (sub) {
      let parts = sub.split(/[\\/]+/);
      let acc = '/storage/emulated/0';
      for (let pt of parts) {
        if (!pt) continue;
        acc += '/' + pt;
        cleanItems.push({ name: pt, path: acc });
      }
    }
  } else if (pathStr === '/sdcard' || pathStr.startsWith('/sdcard/')) {
    cleanItems.push({ name: 'Internal Storage', path: '/sdcard' });
    let sub = pathStr.slice(7).replace(/^[\\/]+/, '');
    if (sub) {
      let parts = sub.split(/[\\/]+/);
      let acc = '/sdcard';
      for (let pt of parts) {
        if (!pt) continue;
        acc += '/' + pt;
        cleanItems.push({ name: pt, path: acc });
      }
    }
  } else {
    cleanItems = (bc || []).map(b => ({
      name: b.name === '/' ? 'Root' : b.name,
      path: b.path
    }));
  }

  cleanItems.forEach((b, i) => {
    let isLast = i === cleanItems.length - 1;
    h += `<span class="crumb-sep">›</span>`;
    h += `<button class="crumb-chip ${isLast ? 'cur active-crumb' : ''}" onclick="crumbGo('${esc(b.path)}')" title="${esc(b.path)}">
      ${isLast ? '📁 ' : ''}${esc(b.name)}
    </button>`;
  });

  $('crumbs-bar').innerHTML = h;
}

let currentSortField = (function(){try{return localStorage.getItem('fs_sort_field')||'name'}catch(e){return 'name'}})();
let currentSortOrder = (function(){try{return localStorage.getItem('fs_sort_order')||'asc'}catch(e){return 'asc'}})();

function getSortLabel(field, order){
  if(field==='name') return order==='asc' ? 'Name (A→Z)' : 'Name (Z→A)';
  if(field==='date') return order==='desc' ? 'Date (Newest)' : 'Date (Oldest)';
  if(field==='size') return order==='desc' ? 'Size (Largest)' : 'Size (Smallest)';
  if(field==='type') return 'File Extension';
  return 'Sort';
}

function updateSortUI(){
  let labelEl=$('sort-current-label');
  if(labelEl)labelEl.textContent=getSortLabel(currentSortField, currentSortOrder);
  let key=`${currentSortField}-${currentSortOrder}`;
  document.querySelectorAll('.sort-option').forEach(el=>{
    let match=el.getAttribute('data-sort')===key;
    el.classList.toggle('active', match);
  });
}

function toggleSortMenu(e){
  if(e)e.stopPropagation();
  let menu=$('sort-menu');
  if(!menu)return;
  let isOpen=menu.style.display!=='none';
  menu.style.display=isOpen ? 'none' : 'flex';
  if(!isOpen)updateSortUI();
}

function closeSortMenu(){
  let menu=$('sort-menu');
  if(menu)menu.style.display='none';
}

document.addEventListener('click', e=>{
  let wrap=$('sort-wrap');
  if(wrap && !wrap.contains(e.target)){
    closeSortMenu();
  }
});

function applySort(field, order){
  currentSortField=field;
  currentSortOrder=order;
  try{
    localStorage.setItem('fs_sort_field', field);
    localStorage.setItem('fs_sort_order', order);
  }catch(e){}
  updateSortUI();
  closeSortMenu();
  renderFiles();
}

function sortItems(list){
  return list.sort((a,b)=>{
    if(a.is_dir && !b.is_dir) return -1;
    if(!a.is_dir && b.is_dir) return 1;
    let cmp=0;
    if(currentSortField==='name'){
      cmp=a.name.localeCompare(b.name, undefined, {numeric:true, sensitivity:'base'});
      if(currentSortOrder==='desc') cmp=-cmp;
    } else if(currentSortField==='date'){
      let ta=a.mtime||0, tb=b.mtime||0;
      if(typeof ta==='string') ta=new Date(ta).getTime()||0;
      if(typeof tb==='string') tb=new Date(tb).getTime()||0;
      cmp=currentSortOrder==='desc' ? (tb-ta) : (ta-tb);
      if(cmp===0) cmp=a.name.localeCompare(b.name, undefined, {numeric:true, sensitivity:'base'});
    } else if(currentSortField==='size'){
      let sa=a.size||0, sb=b.size||0;
      cmp=currentSortOrder==='desc' ? (sb-sa) : (sa-sb);
      if(cmp===0) cmp=a.name.localeCompare(b.name, undefined, {numeric:true, sensitivity:'base'});
    } else if(currentSortField==='type'){
      let extA=a.name.includes('.') ? a.name.split('.').pop().toLowerCase() : '';
      let extB=b.name.includes('.') ? b.name.split('.').pop().toLowerCase() : '';
      cmp=extA.localeCompare(extB) || a.name.localeCompare(b.name, undefined, {numeric:true, sensitivity:'base'});
      if(currentSortOrder==='desc') cmp=-cmp;
    }
    return cmp;
  });
}

function isImageFile(n){
  return /\.(jpe?g|png|gif|webp|bmp|svg|heic|heif|avif)$/i.test(n||'');
}
function isVideoFile(n){
  return /\.(mp4|mkv|avi|mov|webm|3gp|flv|wmv|m4v)$/i.test(n||'');
}
function isMediaFile(n){
  return isImageFile(n) || isVideoFile(n);
}

function getCleanToken(){
  let tok = getToken();
  return (tok || '').replace(/^Basic\s+/i, '').trim();
}

function getThumbUrl(p){
  let rawTok = getCleanToken();
  if(remoteNode){
    // When browsing a remote phone from PC browser, proxy through local server to avoid CORS/PNA issues:
    let myHost = location.hostname;
    if(myHost === 'localhost' || myHost === '127.0.0.1' || myHost !== remoteNode.ip){
      return `/api/proxy_thumb?ip=${encodeURIComponent(remoteNode.ip)}&port=${remoteNode.port}&path=${encodeURIComponent(p)}${rawTok?('&token='+encodeURIComponent(rawTok)):''}`;
    }
  }
  let base = getBase();
  return `${base}/api/thumb?path=${encodeURIComponent(p)}${rawTok?('&token='+encodeURIComponent(rawTok)):''}`;
}

function getViewUrl(p){
  let rawTok = getCleanToken();
  if(remoteNode){
    let myHost = location.hostname;
    if(myHost === 'localhost' || myHost === '127.0.0.1' || myHost !== remoteNode.ip){
      return `/api/proxy_view?ip=${encodeURIComponent(remoteNode.ip)}&port=${remoteNode.port}&path=${encodeURIComponent(p)}${rawTok?('&token='+encodeURIComponent(rawTok)):''}`;
    }
  }
  let base = getBase();
  return `${base}/api/view?path=${encodeURIComponent(p)}${rawTok?('&token='+encodeURIComponent(rawTok)):''}`;
}

function handleThumbError(img, name){
  if(!img.dataset.retried){
    img.dataset.retried = '1';
    let curSrc = img.src;
    if(curSrc.includes('/api/thumb')){
      img.src = curSrc.replace('/api/thumb', '/api/view');
      return;
    } else if(curSrc.includes('/api/proxy_thumb')){
      img.src = curSrc.replace('/api/proxy_thumb', '/api/proxy_view');
      return;
    }
  }
  img.style.display = 'none';
  if(img.nextElementSibling) img.nextElementSibling.style.display = 'flex';
}

let mediaItems = [];
let currentMediaIdx = -1;

function openMediaPreview(path, name){
  let q = ($('search-input').value || '').toLowerCase();
  let f = items.filter(x => x.name.toLowerCase().includes(q) && !x.is_dir && isMediaFile(x.name));
  sortItems(f);
  mediaItems = f;
  currentMediaIdx = mediaItems.findIndex(x => x.path === path);
  if (currentMediaIdx === -1) {
    mediaItems = [{ path: path, name: name, size: 0 }];
    currentMediaIdx = 0;
  }
  showCurrentMedia();
}

function showCurrentMedia(){
  if (currentMediaIdx < 0 || currentMediaIdx >= mediaItems.length) return;
  let item = mediaItems[currentMediaIdx];
  let isVid = isVideoFile(item.name);
  let base = getBase();
  let tok = getToken();
  let streamUrl = getViewUrl(item.path);
  
  $('lb-icon').textContent = isVid ? '🎬' : '🖼️';
  $('lb-filename').textContent = item.name;
  $('lb-meta').textContent = `${currentMediaIdx + 1} of ${mediaItems.length}${item.size ? (' • ' + fmt(item.size)) : ''}`;
  
  let box = $('lb-content');
  if (isVid) {
    box.innerHTML = `<video class="lb-video" controls autoplay playsinline src="${streamUrl}"></video>`;
  } else {
    box.innerHTML = `<img class="lb-img" src="${streamUrl}" alt="${esc(item.name)}" onload="this.classList.add('ready')">`;
  }
  
  $('lb-prev').style.visibility = currentMediaIdx > 0 ? 'visible' : 'hidden';
  $('lb-next').style.visibility = currentMediaIdx < mediaItems.length - 1 ? 'visible' : 'hidden';
  
  $('media-lightbox').style.display = 'flex';
  document.body.style.overflow = 'hidden';
}

function lbNav(delta){
  let n = currentMediaIdx + delta;
  if (n >= 0 && n < mediaItems.length) {
    currentMediaIdx = n;
    showCurrentMedia();
  }
}

function closeLightbox(){
  let lb = $('media-lightbox');
  if (lb) {
    lb.style.display = 'none';
    let box = $('lb-content');
    if (box) box.innerHTML = '';
  }
  document.body.style.overflow = '';
}

function lbDownload(){
  if (currentMediaIdx >= 0 && currentMediaIdx < mediaItems.length) {
    let item = mediaItems[currentMediaIdx];
    dl(item.path, item.name, item.size || 0);
  }
}

window.addEventListener('keydown', e => {
  let lb = $('media-lightbox');
  if (lb && lb.style.display === 'flex') {
    if (e.key === 'Escape') closeLightbox();
    else if (e.key === 'ArrowLeft') lbNav(-1);
    else if (e.key === 'ArrowRight') lbNav(1);
  }
});

function renderFiles(){
  let q=($('search-input').value||'').toLowerCase();
  let f=items.filter(x=>x.name.toLowerCase().includes(q));
  sortItems(f);
  let box=$('file-list');
  if(!f.length){
    box.innerHTML='<div style="padding:32px;text-align:center;color:var(--sub)">Empty directory. Drop files to upload!</div>';
    return;
  }
  box.innerHTML=f.map(x=>{
    let dt='-';
    if(x.mtime){
      if(typeof x.mtime==='number')dt=new Date(x.mtime>1e11?x.mtime:x.mtime*1000).toLocaleDateString();
      else{let d=new Date(x.mtime);dt=isNaN(d.getTime())?String(x.mtime):d.toLocaleDateString();}
    }
    let isSel=sel.has(x.path);
    let isDrive = !!x.is_drive;
    let isImg = !isDrive && isImageFile(x.name);
    let isVid = !isDrive && isVideoFile(x.name);
    let hasMedia = !x.is_dir && (isImg || isVid);
    let thumbUrl = hasMedia ? getThumbUrl(x.path) : '';
    let clickAction = hasMedia ? `openMediaPreview('${esc(x.path)}','${esc(x.name)}')` :
                      x.is_dir ? `openDir('${esc(x.path)}')` :
                      `dlFile('${esc(x.path)}','${esc(x.name)}',${x.size})`;
    
    let leadHtml = '';
    if (hasMedia) {
      leadHtml = `<div class="file-lead thumb-lead" onclick="${clickAction}">
        <div class="thumb-frame ${isVid ? 'video-frame' : ''}">
          <img class="thumb-img" src="${thumbUrl}" loading="lazy" alt="${esc(x.name)}"
               onload="this.classList.add('visible')"
               onerror="handleThumbError(this, '${esc(x.name)}')">
          <div class="thumb-fallback" style="display:none">${icon(x.name, x.is_dir)}</div>
          ${isVid ? '<div class="video-play-badge">▶</div>' : ''}
        </div>
      </div>`;
    } else {
      let leadIcon = isDrive ? (x.icon || '💽') : icon(x.name, x.is_dir);
      leadHtml = `<div class="file-lead" onclick="${clickAction}">${leadIcon}</div>`;
    }

    let metaText = isDrive ? (x.size ? `${fmt(x.free)} free of ${fmt(x.size)}` : 'STORAGE DRIVE') :
                   (x.is_dir ? 'DIRECTORY' : `${fmt(x.size)} • ${dt}`);
    let openBtnText = isDrive ? 'Open Drive' : (x.is_dir ? 'Open' : '⬇ Download');

    return `<div class="file-item ${isSel?'sel':''} ${isDrive?'drive-item':''}">
      ${isDrive ? '' : `<button class="file-check" onclick="toggleSel('${esc(x.path)}')">${isSel?'✓':''}</button>`}
      ${leadHtml}
      <div class="file-mid" onclick="${clickAction}">
        <div class="file-name" title="${esc(x.name)}">${esc(x.name)}</div>
        <div class="file-meta">${metaText}</div>
      </div>
      <div class="file-trail">
        ${hasMedia ? `<button class="abtn" onclick="openMediaPreview('${esc(x.path)}','${esc(x.name)}')" title="Preview full screen">👁</button>` : ''}
        ${x.is_dir?`<button class="abtn ${isDrive?'go':''}" onclick="openDir('${esc(x.path)}')">${openBtnText}</button>`:`<button class="abtn go" onclick="dlFile('${esc(x.path)}','${esc(x.name)}',${x.size})" title="Download file">⬇ Download</button>`}
      </div>
    </div>`;
  }).join('');
  updateSelBar();
  updateSortUI();
}

function filterFiles(){renderFiles()}
function rootGo(p){cur=p;load()}
function crumbGo(p){cur=p;load()}
function openDir(p){cur=p;load()}
function goHome(){cur='';load()}
function goUp(){
  if(!cur)return;
  let p=cur.replace(/[\\/]+$/,'');
  let i=Math.max(p.lastIndexOf('\\'),p.lastIndexOf('/'));
  if(i<0 || (p[1]===':'&&i<=2) || p==='/sdcard' || p==='/storage/emulated/0'){goHome();return;}
  cur=p.slice(0,i)||'';load();
}

function dlFile(p,n,s){dl(p,n,s)}
function toggleSel(p){sel.has(p)?sel.delete(p):sel.add(p);renderFiles()}
function clearSel(){sel.clear();renderFiles()}
function updateSelBar(){
  $('selbar').classList.toggle('on',sel.size>0);
  $('selcount').textContent=sel.size+' selected';
}

async function dlSelected(){
  if(!sel.size)return;
  snack('▼ Downloading '+sel.size+' item(s)…');
  let list=[...sel];
  for(let i=0;i<list.length;i++){
    let p=list[i];
    let it=items.find(x=>x.path===p);
    let nm=(it?it.name:(p.split(/[\\/]/).pop()||'file'));
    if(it&&it.is_dir)nm+='.zip';
    await dl(p,nm,(it&&it.size)||0);
  }
  clearSel();
}



async function mkdir(){
  let n=prompt('New folder name:');
  if(!n)return;
  let base=getBase();
  await fetch(base+'/api/mkdir',{
    method:'POST',
    headers:getHeaders({'Content-Type':'application/json'}),
    body:JSON.stringify({path:cur,name:n})
  });
  load();
}

/* ---------- AUTHENTICATION ---------- */
function initAuth(){
  let meta=window.__FS_META__||{};
  if(meta.is_local&&meta.local_token){
    sessionStorage.setItem('fs_token',meta.local_token);
    document.cookie='fastshare_token='+meta.local_token+';path=/;max-age=2592000;SameSite=Lax';
    $('authmodal').style.display='none';
    return;
  }
  let tok=sessionStorage.getItem('fs_token');
  if(tok)document.cookie='fastshare_token='+tok+';path=/;max-age=2592000;SameSite=Lax';
}

function checkAuthModal(reqOk){
  if(!reqOk){
    let meta=window.__FS_META__||{};
    let targetName=remoteNode?`${remoteNode.name} (${remoteNode.ip})`:(meta.device||'Fast Share Device');
    $('authdesc').textContent='Enter password to connect to '+targetName;
    $('authpwd').value='';
    $('autherr').style.display='none';
    $('authmodal').style.display='flex';
    setTimeout(()=>{$('authpwd').focus()},60);
  }else{
    $('authmodal').style.display='none';
  }
}

function closeAuthModal(){
  $('authmodal').style.display='none';
  if(remoteNode)exitRemote();
}

async function submitLogin(){
  let pwd=$('authpwd').value.trim();
  if(!pwd)return;
  $('autherr').style.display='none';
  try{
    if(remoteNode){
      let basicTok='Basic '+btoa('share:'+pwd);
      let r=await fetch('http://'+remoteNode.ip+':'+remoteNode.port+'/api/list',{
        headers:{'Authorization':basicTok}
      });
      if(r.ok){
        sessionStorage.setItem(remoteNode.ip+'_token',basicTok);
        $('authmodal').style.display='none';
        snack('✅ Connected to '+remoteNode.name);
        try{ if(window.AndroidBridge && window.AndroidBridge.onAuthSuccess) window.AndroidBridge.onAuthSuccess(pwd); }catch(e){}
        load();
      }else{
        $('autherr').textContent='Invalid password for '+remoteNode.name;
        $('autherr').style.display='block';
      }
    }else{
      let r=await fetch('/api/login',{
        method:'POST',
        headers:{'Content-Type':'application/json'},
        body:JSON.stringify({password:pwd})
      });
      let j=await r.json();
      if(j.ok&&j.token){
        sessionStorage.setItem('fs_token',j.token);
        document.cookie='fastshare_token='+j.token+';path=/;max-age=2592000;SameSite=Lax';
        $('authmodal').style.display='none';
        snack('✅ Authenticated successfully!');
        try{ if(window.AndroidBridge && window.AndroidBridge.onAuthSuccess) window.AndroidBridge.onAuthSuccess(pwd); }catch(e){}
        load();
      }else{
        $('autherr').textContent=j.error||'Invalid password';
        $('autherr').style.display='block';
      }
    }
  }catch(e){
    $('autherr').textContent='Connection error: could not reach device';
    $('autherr').style.display='block';
  }
}

function togglePwd(){
  let f=$('authpwd');
  f.type=f.type==='password'?'text':'password';
}

/* ---------- SETTINGS MODAL ---------- */
async function openSettingsModal(){
  $('settingsmodal').style.display='flex';
  $('setmsg').style.display='none';
  try{
    let r=await fetch('/api/settings');
    let j=await r.json();
    if(j.user) $('set-user').value=j.user;
    if(j.password) $('set-pass').value=j.password;
  }catch(e){}
}
function closeSettingsModal(){
  $('settingsmodal').style.display='none';
}
function toggleSetPwd(){
  let f=$('set-pass');
  f.type=f.type==='password'?'text':'password';
}
function generateRandomPass(){
  const chars='ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%';
  let p='';
  for(let i=0;i<12;i++) p+=chars.charAt(Math.floor(Math.random()*chars.length));
  $('set-pass').value=p;
  $('set-pass').type='text';
  snack('🎲 Generated random password');
}
function copyPass(){
  let p=$('set-pass').value;
  if(!p) return;
  navigator.clipboard.writeText(p);
  snack('📋 Password copied to clipboard');
}
async function saveSettings(){
  let u=$('set-user').value.trim()||'share';
  let p=$('set-pass').value.trim();
  let err=$('setmsg');
  if(!p){
    err.textContent='Password cannot be empty';
    err.style.color='var(--red,#ef4444)';
    err.style.display='block';
    return;
  }
  try{
    let r=await fetch('/api/settings',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({user:u,password:p})
    });
    let j=await r.json();
    if(j.ok){
      err.textContent='✅ Settings saved & updated immediately!';
      err.style.color='var(--emerald,#10b981)';
      err.style.display='block';
      snack('🔐 Updated credentials: '+u+' / '+p);
      setTimeout(()=>{ closeSettingsModal(); },1200);
    }else{
      err.textContent=j.error||'Failed to save settings';
      err.style.color='var(--red,#ef4444)';
      err.style.display='block';
    }
  }catch(e){
    err.textContent='Error saving settings: '+e.message;
    err.style.color='var(--red,#ef4444)';
    err.style.display='block';
  }
}

/* ---------- INIT ---------- */
async function init(){
  try{
    if(/Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent)||window.innerWidth<=880){
      document.body.classList.add('is-mobile');
    }
    if(window.AndroidBridge){
      document.body.classList.add('in-webview');
    }
  }catch(e){}

  try{
    let th=localStorage.getItem('fs-theme');
    if(th)setTheme(th);
    let vm=localStorage.getItem('fs-view-mode');
    if(vm)setViewMode(vm);
  }catch(e){}

  initAuth();
  initDrop();
  loadHistoryFromStorage();

  $('fin').addEventListener('change',e=>{if(e.target.files.length)uploadFiles(e.target.files);e.target.value=''});
  $('findir').addEventListener('change',e=>{if(e.target.files.length)uploadFiles(e.target.files);e.target.value=''});

  try{
    let r=await fetch('/api/info');
    info=await r.json();
    $('devchip').textContent=(info.platform==='Windows'?'🖥️ PC • ':'🤖 ')+(info.device||'Device');
    $('ipchip').textContent='📡 http://'+(info.ip||location.hostname)+':'+(info.port||location.port);
  }catch(e){}

  pollNearby();
  setInterval(pollNearby,2500);
  await load();
}

init();
</script>
</body>
</html>
"""


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FastShare/3.0"

    def log_message(self, *a):
        pass  # silent = faster

    def authorized(self):
        # 1. Localhost auto-auth: running on local PC gets instant access
        client_ip = self.client_address[0]
        if client_ip in ("127.0.0.1", "::1", "localhost"):
            return True
        # 2. Authorization header: Basic or Bearer
        header = self.headers.get("Authorization", "")
        if header.startswith("Basic "):
            try:
                decoded = base64.b64decode(header[6:].strip(), validate=True).decode("utf-8")
                u, supplied = decoded.split(":", 1)
                if hmac.compare_digest(u, USERNAME) and hmac.compare_digest(supplied, PASSWORD):
                    return True
            except Exception:
                pass
        elif header.startswith("Bearer "):
            tok = header[7:].strip()
            with _SESSION_LOCK:
                if tok in VALID_SESSIONS:
                    return True
        # 3. Cookie header: fastshare_token
        cookie = self.headers.get("Cookie", "")
        if "fastshare_token=" in cookie:
            for piece in cookie.split(";"):
                piece = piece.strip()
                if piece.startswith("fastshare_token="):
                    tok = piece.split("=", 1)[1].strip()
                    with _SESSION_LOCK:
                        if tok in VALID_SESSIONS:
                            return True
        # 4. URL query param token
        try:
            u = urllib.parse.urlparse(self.path)
            q = urllib.parse.parse_qs(u.query)
            tok = (q.get("token") or [""])[0]
            if tok:
                with _SESSION_LOCK:
                    if tok in VALID_SESSIONS:
                        return True
        except Exception:
            pass
        return False

    def require_auth(self):
        if self.authorized():
            return True
        # Do NOT send WWW-Authenticate: Basic realm=... header to prevent browser modal popup!
        self.send_json({"error": "unauthorized", "login_required": True}, code=401)
        return False

    # ---------- helpers ----------
    def send_json(self, obj, code=200):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def send_html(self, text):
        data = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.end_headers()
        self.wfile.write(data)

    def read_json_body(self):
        try:
            ln = int(self.headers.get("Content-Length", 0))
        except Exception:
            ln = 0
        if ln <= 0 or ln > 10 * 1024 * 1024:
            return {}
        try:
            return json.loads(self.rfile.read(ln).decode("utf-8", "errors"))
        except Exception:
            return {}

    @staticmethod
    def safe_path(p):
        if not p:
            return ""
        if "%" in p:
            p = urllib.parse.unquote(p)
        try:
            return os.path.abspath(os.path.normpath(p))
        except Exception:
            return ""

    def confined_path(self, p):
        candidate = self.safe_path(p)
        if not candidate:
            return ""
        try:
            candidate = os.path.realpath(candidate)
            for root in get_roots_cached():
                try:
                    allowed = os.path.realpath(root["path"])
                    if os.path.commonpath((candidate, allowed)).lower() == allowed.lower():
                        return candidate
                except (OSError, ValueError):
                    continue
        except Exception:
            pass
        return ""

    def breadcrumbs(self, path):
        if not path:
            return []
        bc = []
        m = re.match(r"^([A-Za-z]:\\)", path)
        if m:
            drv = m.group(1)
            bc.append({"name": drv, "path": drv})
            rest = path[len(drv):].strip("\\/")
            cur = drv
            for part in re.split(r"[\\/]+", rest):
                if not part:
                    continue
                cur = os.path.join(cur, part)
                bc.append({"name": part, "path": cur})
        else:
            if path.startswith("/"):
                bc.append({"name": "/", "path": "/"})
            cur = ""
            for part in re.split(r"/+", path.strip("/")):
                if not part:
                    continue
                cur = cur + "/" + part if cur or path.startswith("/") else part
                bc.append({"name": part, "path": cur})
        return bc

    def is_root(self, ap):
        for r in get_roots_cached():
            a = r["path"]
            if os.name == "nt":
                if ap.lower() == a.lower():
                    return True
            elif ap == a:
                return True
        return False

    @staticmethod
    def valid_proxy_target(ip, port):
        try:
            address = ipaddress.ip_address(ip)
            number = int(port)
            return address.version == 4 and address.is_private and not address.is_loopback and 1 <= number <= 65535
        except (ValueError, TypeError):
            return False

    def serve_thumb(self, fpath):
        if not os.path.isfile(fpath):
            self.send_error(404, "Not found")
            return
        data = get_thumbnail_bytes(fpath)
        if data:
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "public, max-age=604800, immutable")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            return
        ext = os.path.splitext(fpath)[1].lower()
        if ext in ('.jpg', '.jpeg', '.png', '.webp', '.bmp', '.gif', '.svg'):
            self.serve_file(fpath, download=False)
            return
        self.send_error(404, "No thumbnail")

    # ---------- fast file serving (range/resume) ----------
    def serve_file(self, fpath, download=True, delete_after=False):
        if not os.path.isfile(fpath):
            self.send_error(404, "Not found")
            return
        ctype = "application/octet-stream"
        if not download:
            ctype, _ = mimetypes.guess_type(fpath)
            ctype = ctype or "application/octet-stream"
        try:
            f = open(fpath, "rb", buffering=2 * 1024 * 1024)
        except OSError:
            self.send_error(403)
            return
        try:
            size = os.fstat(f.fileno()).st_size
            mtime = os.path.getmtime(fpath)
            rh = self.headers.get("Range")
            start, end, partial = 0, size - 1, False
            if rh:
                m = re.match(r"bytes=(\d*)-(\d*)", rh)
                if m:
                    s_str, e_str = m.groups()
                    try:
                        if s_str == "" and e_str != "":
                            start = size - int(e_str)
                        elif e_str == "":
                            start = int(s_str)
                        else:
                            start, end = int(s_str), int(e_str)
                        start = max(0, start)
                        end = min(size - 1, end)
                        if start <= end:
                            partial = True
                    except ValueError:
                        pass
            length = end - start + 1 if partial else size
            self.send_response(206 if partial else 200)
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            fname = os.path.basename(fpath)
            qname = urllib.parse.quote(fname)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(length))
            self.send_header("Last-Modified", self.date_time_string(mtime))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Disposition")
            self.send_header("Content-Disposition",
                             f"{'attachment' if download else 'inline'}; filename*=UTF-8''{qname}")
            self.end_headers()
            if self.command == "HEAD":
                f.close()
                return
            off, left = (start if partial else 0), length
            sent_ok = False
            if os.name != "nt" and hasattr(os, "sendfile"):
                try:
                    self.wfile.flush()
                    out_fd = self.connection.fileno()
                    in_fd = f.fileno()
                    while left > 0:
                        n = os.sendfile(out_fd, in_fd, off, left)
                        if not n:
                            break
                        off += n
                        left -= n
                    sent_ok = left <= 0
                    f.close()
                except (OSError, ValueError):
                    try:
                        f.seek(off)
                    except OSError:
                        pass
            if not sent_ok:
                try:
                    f.seek(off)
                except OSError:
                    pass
                try:
                    while left > 0:
                        buf = f.read(min(CHUNK, left))
                        if not buf:
                            break
                        try:
                            self.wfile.write(buf)
                        except (BrokenPipeError, ConnectionResetError):
                            break
                        left -= len(buf)
                finally:
                    f.close()
        except (BrokenPipeError, ConnectionResetError):
            try:
                f.close()
            except Exception:
                pass
        finally:
            if delete_after:
                try:
                    os.remove(fpath)
                except Exception:
                    pass

    def serve_zip(self, folder):
        import struct
        import binascii
        folder = os.path.abspath(folder)
        if not os.path.isdir(folder):
            self.send_error(404)
            return
        base = re.sub(r"[^\w\-. ]+", "_", os.path.basename(folder.rstrip("\\/")) or "files")
        entries = []
        for root, _dirs, files in os.walk(folder):
            for fn in files:
                fp = os.path.join(root, fn)
                try:
                    sz = os.path.getsize(fp)
                except OSError:
                    continue
                rel = os.path.relpath(fp, folder).replace("\\", "/")
                entries.append((rel, fp, sz))
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Disposition", f'attachment; filename="{base}.zip"')
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Expose-Headers", "Content-Disposition")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def cwrite(data):
            if data:
                self.wfile.write(("%X\r\n" % len(data)).encode("ascii") + data + b"\r\n")

        cdir = []
        offset = 0
        try:
            for rel, fp, sz in entries:
                crc = 0
                try:
                    with open(fp, "rb") as f:
                        while True:
                            b = f.read(CHUNK)
                            if not b:
                                break
                            crc = binascii.crc32(b, crc)
                except OSError:
                    continue
                crc &= 0xFFFFFFFF
                name_b = rel.encode("utf-8")
                lhdr = struct.pack(
                    "<4sHHHHHIIIHH",
                    b"PK\x03\x04", 20, 0, 0, 0, 0, crc, sz, sz, len(name_b), 0
                )
                cwrite(lhdr + name_b)
                local_off = offset
                offset += len(lhdr) + len(name_b)
                try:
                    with open(fp, "rb") as f:
                        while True:
                            b = f.read(CHUNK)
                            if not b:
                                break
                            cwrite(b)
                            offset += len(b)
                except OSError:
                    pass
                cdir.append((name_b, crc, sz, local_off))

            cdir_start = offset
            for name_b, crc, sz, local_off in cdir:
                chead = struct.pack(
                    "<4sHHHHHHIIIHHHHHII",
                    b"PK\x01\x02", 20, 20, 0, 0, 0, 0, crc, sz, sz,
                    len(name_b), 0, 0, 0, 0, 0, local_off
                )
                cwrite(chead + name_b)
                offset += len(chead) + len(name_b)
            cdir_len = offset - cdir_start
            eocd = struct.pack(
                "<4sHHHHIIH",
                b"PK\x05\x06", 0, 0, len(cdir), len(cdir), cdir_len, cdir_start, 0
            )
            cwrite(eocd)
            self.wfile.write(b"0\r\n\r\n")
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ---------- routing ----------
    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        p = u.path
        q = urllib.parse.parse_qs(u.query)

        # 1. UI Root: Always serve HTML with embedded server metadata (no 401 prompt!)
        if p == "/" or p == "/index.html":
            client_ip = self.client_address[0]
            is_local = client_ip in ("127.0.0.1", "::1", "localhost")
            meta = {
                "is_local": is_local,
                "local_token": LOCAL_TOKEN if is_local else "",
                "user": USERNAME,
                "device": device_label(),
                "port": PORT,
                "ip": get_lan_ip(),
                "authenticated": self.authorized()
            }
            if is_local:
                meta["password"] = PASSWORD
            html = UI_HTML.replace("/*__SERVER_META__*/", f"window.__FS_META__ = {json.dumps(meta)};")
            self.send_html(html)
            return

        # 2. Public API info / nearby / auth_check
        if p == "/api/info":
            lan = get_lan_ip()
            client_ip = self.client_address[0] if self.client_address else ""
            info = {
                "device": device_label(),
                "platform": platform.system(),
                "hostname": socket.gethostname(),
                "ip": lan,
                "port": PORT,
                "client_ip": client_ip,
                "version": "3.5",
                "is_android": is_android(),
                "roots": len(get_roots_cached())
            }
            if client_ip in ("127.0.0.1", "::1", "localhost"):
                info["password"] = PASSWORD
                info["user"] = USERNAME
            self.send_json(info)
            return

        if p == "/api/nearby":
            now = time.time()
            with _NEARBY_LOCK:
                active = [v for k, v in NEARBY_DEVICES.items() if now - v.get("time", 0) < 8]
            self.send_json(active)
            return

        if p == "/api/settings":
            if not self.require_auth():
                return
            client_ip = self.client_address[0] if self.client_address else ""
            is_local = client_ip in ("127.0.0.1", "::1", "localhost")
            self.send_json({"ok": True, "user": USERNAME, "password": PASSWORD, "port": PORT, "is_local": is_local})
            return

        # 3. Protected endpoints
        if not self.require_auth():
            return

        if p == "/api/drives":
            self.send_json(get_roots_cached())
            return

        if p == "/api/list":
            raw = (q.get("path") or [""])[0]
            show_hidden = (q.get("hidden") or ["0"])[0] == "1"
            rts = get_roots_cached()
            if not raw:
                items = []
                for r_item in rts:
                    items.append({
                        "name": r_item.get("name") or "Drive",
                        "path": r_item["path"],
                        "is_dir": True,
                        "is_drive": True,
                        "size": r_item.get("total") or 0,
                        "free": r_item.get("free") or 0,
                        "used": r_item.get("used") or 0,
                        "icon": r_item.get("icon") or "💽",
                        "mtime": int(time.time())
                    })
                self.send_json({
                    "path": "",
                    "breadcrumbs": [{"name": device_label(), "path": ""}],
                    "items": items,
                    "roots": rts,
                    "quick": quick_access(),
                    "dirs": len(items),
                    "files": 0,
                    "total_size": 0,
                    "device": device_label(),
                    "is_android": is_android()
                })
                return
            ap = self.confined_path(raw)
            if not os.path.exists(ap):
                self.send_json({"error": "not found"}, 404)
                return
            if os.path.isfile(ap):
                self.serve_file(ap, download=True)
                return
            try:
                it = os.scandir(ap)
            except OSError:
                self.send_json({"error": "no permission"}, 403)
                return
            items, dc, fc, tsz = [], 0, 0, 0
            try:
                with it:
                    for e in it:
                        n = e.name
                        if not show_hidden and n.startswith("."):
                            continue
                        try:
                            st = e.stat(follow_symlinks=False)
                            isd = e.is_dir(follow_symlinks=False)
                        except OSError:
                            continue
                        sz = 0 if isd else st.st_size
                        if isd:
                            dc += 1
                        else:
                            fc += 1
                            tsz += sz
                        items.append({"name": n, "path": os.path.join(ap, n),
                                      "is_dir": isd, "size": sz,
                                      "mtime": int(st.st_mtime)})
            except OSError:
                self.send_json({"error": "no permission"}, 403)
                return
            self.send_json({"path": ap, "breadcrumbs": self.breadcrumbs(ap),
                            "items": items, "dirs": dc, "files": fc,
                            "total_size": tsz,
                            "writable": os.access(ap, os.W_OK),
                            "roots": get_roots_cached(), "quick": quick_access()})
            return

        if p in ("/api/download", "/api/view"):
            raw = (q.get("path") or [""])[0]
            self.serve_file(self.confined_path(raw), download=(p == "/api/download"))
            return

        if p == "/api/thumb":
            raw = (q.get("path") or [""])[0]
            self.serve_thumb(self.confined_path(raw))
            return

        if p == "/api/zip":
            raw = (q.get("path") or [""])[0]
            ap = self.confined_path(raw)
            if not ap or not os.path.isdir(ap):
                self.send_json({"error": "need folder"}, 400)
                return
            self.serve_zip(ap)
            return

        if p in ("/api/proxy_thumb", "/api/proxy_view"):
            ip = (q.get("ip") or [""])[0]
            port = (q.get("port") or ["8000"])[0]
            rpath = (q.get("path") or [""])[0]
            token = (q.get("token") or [""])[0]
            if not ip or not rpath:
                self.send_error(400, "missing ip or path")
                return
            if not self.valid_proxy_target(ip, port):
              self.send_error(403, "invalid proxy target")
              return

            cache_key = f"{ip}:{port}:{rpath}"
            with _THUMB_LOCK:
                if p == "/api/proxy_thumb" and cache_key in _THUMB_CACHE:
                    cached_data = _THUMB_CACHE[cache_key]
                    self.send_response(200)
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(len(cached_data)))
                    self.send_header("Cache-Control", "public, max-age=604800, immutable")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    self.wfile.write(cached_data)
                    return

            auth_val = None
            if token:
                auth_val = token if ("Basic " in token or "Bearer " in token) else f"Basic {token}"
            else:
              self.send_error(401, "remote authentication required")
              return

            endpoint = "/api/thumb" if p == "/api/proxy_thumb" else "/api/view"
            target_url = f"http://{ip}:{port}{endpoint}?path={urllib.parse.quote(rpath)}"
            req = urllib.request.Request(target_url)
            req.add_header("Authorization", auth_val)
            try:
                with urllib.request.urlopen(req, timeout=8) as resp:
                    data = resp.read()
                    if p == "/api/proxy_thumb":
                        with _THUMB_LOCK:
                            if len(_THUMB_CACHE) > 500:
                                _THUMB_CACHE.clear()
                            _THUMB_CACHE[cache_key] = data
                    self.send_response(200)
                    self.send_header("Content-Type", resp.headers.get("Content-Type", "image/jpeg"))
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Cache-Control", "public, max-age=604800, immutable")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    self.wfile.write(data)
                    return
            except Exception:
                if p == "/api/proxy_thumb" and _HAS_PIL:
                    try:
                        view_url = f"http://{ip}:{port}/api/view?path={urllib.parse.quote(rpath)}"
                        req_v = urllib.request.Request(view_url)
                        req_v.add_header("Authorization", auth_val)
                        with urllib.request.urlopen(req_v, timeout=8) as resp:
                            raw_img = resp.read()
                            with Image.open(io.BytesIO(raw_img)) as img:
                                try:
                                    img = ImageOps.exif_transpose(img)
                                except Exception:
                                    pass
                                img.thumbnail((320, 320))
                                if img.mode in ("RGBA", "P"):
                                    img = img.convert("RGB")
                                buf = io.BytesIO()
                                img.save(buf, format="JPEG", quality=80, optimize=True)
                                thumb_data = buf.getvalue()
                                with _THUMB_LOCK:
                                    if len(_THUMB_CACHE) > 500:
                                        _THUMB_CACHE.clear()
                                    _THUMB_CACHE[cache_key] = thumb_data
                                self.send_response(200)
                                self.send_header("Content-Type", "image/jpeg")
                                self.send_header("Content-Length", str(len(thumb_data)))
                                self.send_header("Cache-Control", "public, max-age=604800, immutable")
                                self.send_header("Access-Control-Allow-Origin", "*")
                                self.end_headers()
                                self.wfile.write(thumb_data)
                                return
                    except Exception:
                        pass
                self.send_error(404, "Thumbnail proxy failed")
            return

        if p == "/api/proxy_download":
            ip = (q.get("ip") or [""])[0]
            port = (q.get("port") or ["8000"])[0]
            rpath = (q.get("path") or [""])[0]
            name = (q.get("name") or [""])[0]
            token = (q.get("token") or [""])[0]
            if not ip or not rpath:
                self.send_json({"error": "missing ip or path"}, 400)
                return
            if not self.valid_proxy_target(ip, port):
              self.send_json({"error": "invalid proxy target"}, 403)
              return
            target_url = f"http://{ip}:{port}/api/download?path={urllib.parse.quote(rpath)}"
            req = urllib.request.Request(target_url)
            range_hdr = self.headers.get("Range")
            if range_hdr:
                req.add_header("Range", range_hdr)
            if token:
                auth_val = token if ("Basic " in token or "Bearer " in token) else f"Basic {token}"
                req.add_header("Authorization", auth_val)
            else:
              self.send_json({"error": "remote authentication required"}, 401)
              return
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    self.send_response(resp.status)
                    ctype = resp.headers.get("Content-Type", "application/octet-stream")
                    self.send_header("Content-Type", ctype)
                    clen = resp.headers.get("Content-Length")
                    if clen:
                        self.send_header("Content-Length", clen)
                    crange = resp.headers.get("Content-Range")
                    if crange:
                        self.send_header("Content-Range", crange)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Access-Control-Allow-Private-Network", "true")
                    self.send_header("Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Disposition")
                    fname = name or os.path.basename(rpath.replace("\\", "/")) or "file"
                    qname = urllib.parse.quote(fname)
                    self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{qname}")
                    self.end_headers()
                    shutil.copyfileobj(resp, self.wfile, length=2 * 1024 * 1024)
            except Exception as e:
                self.send_json({"error": f"Proxy download failed: {e}"}, 502)
            return

        if p == "/api/proxy_zip":
            ip = (q.get("ip") or [""])[0]
            port = (q.get("port") or ["8000"])[0]
            rpath = (q.get("path") or [""])[0]
            name = (q.get("name") or ["files.zip"])[0]
            token = (q.get("token") or [""])[0]
            if not ip or not rpath:
                self.send_json({"error": "missing ip or path"}, 400)
                return
            if not self.valid_proxy_target(ip, port):
              self.send_json({"error": "invalid proxy target"}, 403)
              return
            target_url = f"http://{ip}:{port}/api/zip?path={urllib.parse.quote(rpath)}"
            req = urllib.request.Request(target_url)
            if token:
                auth_val = token if ("Basic " in token or "Bearer " in token) else f"Basic {token}"
                req.add_header("Authorization", auth_val)
            try:
                with urllib.request.urlopen(req, timeout=120) as resp:
                    self.send_response(resp.status)
                    self.send_header("Content-Type", "application/zip")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    qname = urllib.parse.quote(name)
                    self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{qname}")
                    self.end_headers()
                    shutil.copyfileobj(resp, self.wfile, length=2 * 1024 * 1024)
            except Exception as e:
                self.send_json({"error": f"Proxy zip failed: {e}"}, 502)
            return

        self.send_error(404)

    def do_HEAD(self):
        u = urllib.parse.urlparse(self.path)
        if u.path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            return
        if not self.require_auth():
            return
        if u.path in ("/api/download", "/api/view"):
            q = urllib.parse.parse_qs(u.query)
            raw = (q.get("path") or [""])[0]
            self.serve_file(self.confined_path(raw), download=(u.path == "/api/download"))
            return
        if u.path == "/api/proxy_download":
            q = urllib.parse.parse_qs(u.query)
            ip = (q.get("ip") or [""])[0]
            port = (q.get("port") or ["8000"])[0]
            rpath = (q.get("path") or [""])[0]
            name = (q.get("name") or [""])[0]
            token = (q.get("token") or [""])[0]
            if not self.valid_proxy_target(ip, port):
              self.send_json({"error": "invalid proxy target"}, 403)
              return
            target_url = f"http://{ip}:{port}/api/download?path={urllib.parse.quote(rpath)}"
            req = urllib.request.Request(target_url, method="HEAD")
            if token:
                auth_val = token if ("Basic " in token or "Bearer " in token) else f"Basic {token}"
                req.add_header("Authorization", auth_val)
            else:
              self.send_json({"error": "remote authentication required"}, 401)
              return
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    self.send_response(resp.status)
                    ctype = resp.headers.get("Content-Type", "application/octet-stream")
                    self.send_header("Content-Type", ctype)
                    clen = resp.headers.get("Content-Length")
                    if clen:
                        self.send_header("Content-Length", clen)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    fname = name or os.path.basename(rpath.replace("\\", "/")) or "file"
                    qname = urllib.parse.quote(fname)
                    self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{qname}")
                    self.end_headers()
            except Exception:
                self.send_error(502)
            return
        self.send_error(404)

    def do_POST(self):
        global USERNAME, PASSWORD
        u = urllib.parse.urlparse(self.path)
        p = u.path
        q = urllib.parse.parse_qs(u.query)

        # In-page authentication endpoint
        if p == "/api/login":
            j = self.read_json_body()
            u_supplied = (j.get("user") or "").strip()
            pwd_supplied = (j.get("password") or "").strip()
            # If user omitted, default to USERNAME
            valid_user = (u_supplied == USERNAME or not u_supplied)
            if valid_user and hmac.compare_digest(pwd_supplied, PASSWORD):
                tok = secrets.token_urlsafe(32)
                with _SESSION_LOCK:
                    VALID_SESSIONS.add(tok)
                data = json.dumps({"ok": True, "token": tok, "user": USERNAME, "device": device_label()}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Set-Cookie", f"fastshare_token={tok}; Path=/; SameSite=Lax; Max-Age=2592000")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(data)
            else:
                self.send_json({"ok": False, "error": "Invalid password"}, code=401)
            return

        if not self.require_auth():
            return

        if p == "/api/settings":
            client_ip = self.client_address[0] if self.client_address else ""
            is_local = client_ip in ("127.0.0.1", "::1", "localhost")
            j = self.read_json_body()
            new_u = (j.get("user") or "").strip()
            new_p = (j.get("password") or "").strip()
            if not new_p:
                self.send_json({"ok": False, "error": "Password cannot be empty"}, code=400)
                return
            if new_u:
                USERNAME = new_u
            PASSWORD = new_p
            if CURRENT_BEACON:
                CURRENT_BEACON.username = USERNAME
            home_cfg, local_cfg = get_config_paths()
            cfg_data = json.dumps({"user": USERNAME, "password": PASSWORD, "port": PORT}, indent=2)
            for cfg_path in (local_cfg, home_cfg):
                try:
                    with open(cfg_path, "w", encoding="utf-8") as f:
                        f.write(cfg_data)
                except Exception:
                    pass
            self.send_json({"ok": True, "user": USERNAME, "password": PASSWORD})
            return

        if p == "/api/raw":
            raw = (q.get("path") or [""])[0]
            target = self.confined_path(raw) if raw else ""
            if not target:
                dl = os.path.join(os.path.expanduser("~"), "Downloads")
                if os.path.isdir(dl):
                    target = dl
                else:
                    rs = get_roots_cached()
                    target = rs[0]["path"] if rs else os.path.expanduser("~")
            if os.path.isfile(target):
                target = os.path.dirname(target)
            rel = (q.get("name") or [""])[0]
            parts = [x for x in rel.replace("\\", "/").split("/") if x not in ("", ".", "..")]
            if not target or not parts:
                self.send_json({"error": "bad name"}, 400)
                return
            try:
                total = int(self.headers.get("Content-Length", -1))
            except ValueError:
                total = -1
            if total < 0 or total > 20 * 1024 ** 3:
                self.send_json({"error": "bad length"}, 400)
                return
            dest_dir = os.path.join(target, *parts[:-1]) if len(parts) > 1 else target
            try:
                os.makedirs(dest_dir, exist_ok=True)
            except OSError:
                self.send_json({"error": "bad target"}, 400)
                return
            tmp = os.path.join(dest_dir, parts[-1] + f".part-{os.getpid()}-{next(_RAW_SEQ)}")
            try:
                left = total
                with open(tmp, "wb", buffering=2 * 1024 * 1024) as f:
                    while left > 0:
                        buf = self.rfile.read(min(CHUNK, left))
                        if not buf:
                            break
                        f.write(buf)
                        left -= len(buf)
                if left != 0:
                    raise OSError("truncated")
                dest = os.path.join(dest_dir, parts[-1])
                if os.path.exists(dest):
                    stem, ext = os.path.splitext(dest)
                    k = 1
                    while os.path.exists(dest):
                        dest = f"{stem} ({k}){ext}"
                        k += 1
                os.rename(tmp, dest)
                self.send_json({"ok": True, "saved": [os.path.basename(dest)]})
            except OSError:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                self.send_json({"error": "upload failed"}, 500)
            return

        if p == "/api/mkdir":
            j = self.read_json_body()
            base = self.confined_path(j.get("path", ""))
            name = os.path.basename((j.get("name") or "").strip())
            if not base or not name or name in (".", ".."):
                self.send_json({"error": "bad name"}, 400)
                return
            try:
                os.makedirs(os.path.join(base, name), exist_ok=False)
                self.send_json({"ok": True})
            except Exception as e:
                self.send_json({"error": str(e)}, 500)
            return

        if p == "/api/delete":
            j = self.read_json_body()
            ap = self.confined_path(j.get("path", ""))
            if not ap or self.is_root(ap):
                self.send_json({"error": "refuse to delete storage root"}, 400)
                return
            try:
                if os.path.isdir(ap) and not os.path.islink(ap):
                    shutil.rmtree(ap)
                else:
                    os.remove(ap)
                self.send_json({"ok": True})
            except Exception as e:
                self.send_json({"error": str(e)}, 500)
            return

        if p == "/api/delete_many":
            j = self.read_json_body()
            paths = j.get("paths") or []
            ok, fail = 0, []
            for raw in paths[:500]:
                ap = self.confined_path(raw)
                if not ap or self.is_root(ap):
                    fail.append(raw)
                    continue
                try:
                    if os.path.isdir(ap) and not os.path.islink(ap):
                        shutil.rmtree(ap)
                    else:
                        os.remove(ap)
                    ok += 1
                except Exception:
                    fail.append(raw)
            self.send_json({"ok": True, "deleted": ok, "failed": fail})
            return

        if p == "/api/rename":
            j = self.read_json_body()
            ap = self.confined_path(j.get("path", ""))
            nn = os.path.basename((j.get("newName") or "").strip())
            if not ap or not nn or nn in (".", ".."):
                self.send_json({"error": "bad name"}, 400)
                return
            try:
                os.rename(ap, os.path.join(os.path.dirname(ap), nn))
                self.send_json({"ok": True})
            except Exception as e:
                self.send_json({"error": str(e)}, 500)
            return

        self.send_error(404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,HEAD,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Expose-Headers", "*")
        self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Content-Length", "0")
        self.end_headers()


class FastServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 128

    def server_bind(self):
        # RFC 1323 TCP Window Scaling: 4MB buffers configured prior to listen/bind
        try:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 * 1024 * 1024)
        except OSError:
            pass
        super().server_bind()

    def get_request(self):
        sock, addr = super().get_request()
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 * 1024 * 1024)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
        except OSError:
            pass
        return sock, addr

    def handle_error(self, request, client_address):
        exc_type, exc_val, _ = sys.exc_info()
        if exc_type in (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, TimeoutError):
            return
        if isinstance(exc_val, OSError) and getattr(exc_val, "winerror", None) in (10053, 10054):
            return
        super().handle_error(request, client_address)


class DiscoveryBeacon(threading.Thread):
    def __init__(self, port, http_port, username):
        super().__init__(daemon=True)
        self.port = port
        self.http_port = http_port
        self.username = username
        self.running = True

    def stop(self):
        self.running = False

    def probe_arp(self):
        lan = get_lan_ip()
        parts = lan.split(".")
        if len(parts) != 4:
            return
        prefix = f"{parts[0]}.{parts[1]}.{parts[2]}."
        ips = set()
        try:
            cmd = "arp -a"
            out = os.popen(cmd).read()
            for line in out.splitlines():
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", line)
                if m:
                    ip = m.group(1)
                    if ip.startswith(prefix) and not ip.endswith(".255") and not ip.endswith(".1") and ip != lan:
                        ips.add(ip)
        except Exception:
            pass

        for ip in ips:
            def probe(target_ip):
                try:
                    req = urllib.request.Request(f"http://{target_ip}:8000/api/info", headers={"User-Agent": "FastShare-Probe"})
                    with urllib.request.urlopen(req, timeout=0.8) as resp:
                        if resp.status == 200:
                            data = json.loads(resp.read().decode("utf-8"))
                            name = data.get("device") or data.get("hostname") or "Device"
                            os_type = "android" if data.get("is_android") else (data.get("platform") or "device").lower()
                            port = data.get("port") or 8000
                            with _NEARBY_LOCK:
                                NEARBY_DEVICES[target_ip] = {
                                    "name": name,
                                    "os": os_type,
                                    "ip": target_ip,
                                    "port": port,
                                    "user": "share",
                                    "time": time.time()
                                }
                except Exception:
                    pass
            threading.Thread(target=probe, args=(ip,), daemon=True).start()

    def run(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        except Exception:
            pass
        try:
            sock.bind(("0.0.0.0", self.port))
        except Exception:
            pass
        sock.settimeout(1.0)

        def listen_loop():
            while self.running:
                try:
                    data, addr = sock.recvfrom(4096)
                    if not data:
                        continue
                    try:
                        msg = json.loads(data.decode("utf-8", "ignore"))
                    except Exception:
                        continue
                    sender_ip = addr[0]
                    if msg.get("proto") == "fastshare_v1":
                        if sender_ip != get_lan_ip():
                            with _NEARBY_LOCK:
                                NEARBY_DEVICES[sender_ip] = {
                                    "name": msg.get("name", "Unknown Device"),
                                    "os": msg.get("os", "device"),
                                    "ip": sender_ip,
                                    "port": msg.get("port", 8000),
                                    "user": msg.get("user", "share"),
                                    "time": time.time()
                                }
                            # Only reply if explicitly queried via ping to avoid infinite ping-pong packet storm
                            if msg.get("action") == "ping":
                                reply = json.dumps({
                                    "proto": "fastshare_v1",
                                    "action": "pong",
                                    "name": device_label(),
                                    "os": "windows" if os.name == "nt" else ("android" if is_android() else "unix"),
                                    "ip": get_lan_ip(),
                                    "port": self.http_port,
                                    "user": self.username
                                }).encode("utf-8")
                                try:
                                    sock.sendto(reply, (sender_ip, self.port))
                                except Exception:
                                    pass
                except socket.timeout:
                    continue
                except Exception:
                    time.sleep(0.5)

        t = threading.Thread(target=listen_loop, daemon=True)
        t.start()

        loop_cnt = 0
        while self.running:
            try:
                lan = get_lan_ip()
                payload = json.dumps({
                    "proto": "fastshare_v1",
                    "action": "beacon",
                    "name": device_label(),
                    "os": "windows" if os.name == "nt" else ("android" if is_android() else "unix"),
                    "ip": lan,
                    "port": self.http_port,
                    "user": self.username
                }).encode("utf-8")
                sock.sendto(payload, ("255.255.255.255", self.port))
                parts = lan.split(".")
                if len(parts) == 4:
                    subnet_bc = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
                    sock.sendto(payload, (subnet_bc, self.port))

                # Also send direct unicast to all known peers (bypasses router broadcast drop)
                with _NEARBY_LOCK:
                    peers_to_ping = list(NEARBY_DEVICES.keys())
                for pip in peers_to_ping:
                    try:
                        sock.sendto(payload, (pip, self.port))
                    except Exception:
                        pass
            except Exception:
                pass

            loop_cnt += 1
            if loop_cnt % 2 == 0:
                self.probe_arp()

            time.sleep(1.5)


if __name__ == "__main__":
    lan_ip = get_lan_ip()
    beacon = DiscoveryBeacon(DISCOVERY_PORT, PORT, USERNAME)
    CURRENT_BEACON = beacon
    beacon.start()

    if not NO_BROWSER:
        def auto_open_browser():
            time.sleep(0.5)
            try:
                webbrowser.open(f"http://localhost:{PORT}")
            except Exception:
                pass

        threading.Thread(target=auto_open_browser, daemon=True).start()

    with FastServer(("0.0.0.0", PORT), Handler) as httpd:
        if os.name == "nt":
            try:
                httpd.socket.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 * 1024 * 1024)
                httpd.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
            except Exception:
                pass
        print("=" * 62)
        print(f"  FAST SHARE  |  {device_label()}  |  threaded + range + zip")
        print(f"  THIS DEVICE:   http://localhost:{PORT}  (opening in browser...)")
        print(f"  OTHER DEVICE:  http://{lan_ip}:{PORT}  (same Wi-Fi)")
        print(f"  DISCOVERY:     UDP port {DISCOVERY_PORT} active")
        print(f"  Login user:    {USERNAME}")
        print(f"  Password:      {PASSWORD}")
        print(f"  Credentials:   Saved in .fastshare_cred.json")
        print(f"  Storage: {[d['path'] for d in get_roots()]}")
        if not is_android():
            print("  To browse ANDROID files from here: open Fast Share on Android")
            print("  and your phone/PC will auto-discover each other instantly!")
        print("=" * 62)
        httpd.serve_forever()
