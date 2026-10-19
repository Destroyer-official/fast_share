use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::net::{Ipv4Addr, SocketAddrV4, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use rand::RngCore;
use serde::{Deserialize, Serialize};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

const UI_HTML: &str = include_str!("../assets/ui.html");
const CHUNK_SIZE: usize = 2 * 1024 * 1024; // 2 MB high-speed streaming chunk
const DISCOVERY_PORT: u16 = 8002;
const MAX_UPLOAD_BYTES: u64 = 20 * 1024 * 1024 * 1024;
const MAX_ZIP_BYTES: usize = 512 * 1024 * 1024;

#[derive(Clone, Serialize, Deserialize, Debug)]
struct NearbyDevice {
    name: String,
    os: String,
    ip: String,
    port: u16,
    user: String,
    time: f64,
}

#[derive(Serialize)]
struct RootDrive {
    name: String,
    path: String,
    total: u64,
    free: u64,
    used: u64,
    icon: String,
}

#[derive(Serialize)]
struct QuickPath {
    name: String,
    path: String,
}

#[derive(Serialize)]
struct FileItem {
    name: String,
    path: String,
    is_dir: bool,
    size: u64,
    mtime: u64,
}

#[derive(Serialize)]
struct Breadcrumb {
    name: String,
    path: String,
}

#[derive(Serialize)]
struct ListResponse {
    path: String,
    breadcrumbs: Vec<Breadcrumb>,
    items: Vec<FileItem>,
    dirs: usize,
    files: usize,
    total_size: u64,
    writable: bool,
    roots: Vec<RootDrive>,
    quick: Vec<QuickPath>,
    device: String,
    is_android: bool,
}

#[derive(Clone)]
struct AppState {
    port: u16,
    user: String,
    password: String,
    session_token: String,
    nearby: Arc<RwLock<HashMap<String, NearbyDevice>>>,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut port = 8000;
    let mut user = std::env::var("FAST_SHARE_USER").unwrap_or_else(|_| "share".to_string());
    let mut password = std::env::var("FAST_SHARE_PASSWORD").unwrap_or_else(|_| random_token());
    let mut no_browser = std::env::var("FAST_SHARE_NO_BROWSER").is_ok();

    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        if args[i] == "-h" || args[i] == "--help" {
            println!("FastShare Rust Core v3.5.0\n\
Usage:\n  fastshare [port] [--user USER] [--password PASS] [--no-browser]\n\n\
Options:\n  [port]               Port to listen on (default: 8000)\n  --user USER          Username for HTTP Basic authentication (default: share)\n  --password PASS      Password for authentication (auto-generated if omitted)\n  --no-browser, -n     Do not auto-open the web browser\n  -h, --help           Show this help message and exit\n  -v, --version        Show version and exit");
            return Ok(());
        } else if args[i] == "-v" || args[i] == "--version" {
            println!("FastShare Rust Core v3.5.0");
            return Ok(());
        } else if args[i] == "-n" || args[i] == "--no-browser" {
            no_browser = true;
        } else if let Ok(p) = args[i].parse::<u16>() {
            port = p;
        } else if args[i] == "--password" && i + 1 < args.len() {
            password = args[i + 1].clone();
            i += 1;
        } else if args[i] == "--user" && i + 1 < args.len() {
            user = args[i + 1].clone();
            i += 1;
        }
        i += 1;
    }

    let session_token = random_token();
    let nearby = Arc::new(RwLock::new(HashMap::new()));

    let state = AppState {
        port,
        user: user.clone(),
        password: password.clone(),
        session_token,
        nearby: nearby.clone(),
    };

    // Start UDP 8002 Ecosystem Radar / Auto-Discovery Beacon
    start_discovery_beacon(port, user.clone(), nearby);

    let lan_ip = get_lan_ip();
    println!("==============================================================");
    println!("  FAST SHARE (Rust Core) | Gigabit Engine | Ultra-Low Latency");
    println!("  THIS DEVICE:   http://localhost:{}", port);
    println!("  OTHER DEVICE:  http://{}:{}  (same Wi-Fi)", lan_ip, port);
    println!("  DISCOVERY:     UDP port {} active", DISCOVERY_PORT);
    println!("  Login user:    {}", user);
    println!("  Password:      {}", password);
    println!("==============================================================");

    // Open socket with 4 MB send and receive buffers for RFC 1323 TCP window scaling
    let bind_addr = SocketAddrV4::new(Ipv4Addr::new(0, 0, 0, 0), port);
    let socket = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::STREAM, None)?;
    let _ = socket.set_reuse_address(true);
    let _ = socket.set_recv_buffer_size(4 * 1024 * 1024);
    let _ = socket.set_send_buffer_size(4 * 1024 * 1024);
    let _ = socket.set_nodelay(true);
    socket.bind(&bind_addr.into())?;
    socket.listen(128)?;

    let listener: std::net::TcpListener = socket.into();
    let server = Server::from_listener(listener, None)
        .map_err(|e| io::Error::new(io::ErrorKind::Other, e.to_string()))?;
    let server_arc = Arc::new(server);

    // Auto-open browser in background if enabled
    if !no_browser {
        let open_port = port;
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(600));
            let _ = open_browser(&format!("http://localhost:{}", open_port));
        });
    }

    // Multi-threaded worker pool
    let pool_size = 32;
    let mut handles = Vec::new();

    for _ in 0..pool_size {
        let srv = server_arc.clone();
        let st = state.clone();
        let handle = std::thread::spawn(move || loop {
            match srv.recv() {
                Ok(request) => {
                    handle_request(request, &st);
                }
                Err(e) => {
                    eprintln!("Worker receive error: {}", e);
                    break;
                }
            }
        });
        handles.push(handle);
    }

    for h in handles {
        let _ = h.join();
    }

    Ok(())
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

fn open_browser(url: &str) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("cmd")
            .args(["/C", "start", url])
            .spawn()?;
    }
    Ok(())
}

fn get_lan_ip() -> String {
    if let Ok(s) = UdpSocket::bind("0.0.0.0:0") {
        if s.connect("8.8.8.8:80").is_ok() {
            if let Ok(addr) = s.local_addr() {
                return addr.ip().to_string();
            }
        }
    }
    "127.0.0.1".to_string()
}

fn get_roots() -> Vec<RootDrive> {
    let mut roots = Vec::new();
    #[cfg(target_os = "windows")]
    {
        for c in b'A'..=b'Z' {
            let drive_str = format!("{}:\\", c as char);
            let path = Path::new(&drive_str);
            if path.exists() {
                let (total, free) = get_disk_space(&drive_str);
                let used = if total >= free { total - free } else { 0 };
                let is_c = (c as char) == 'C';
                roots.push(RootDrive {
                    name: if is_c {
                        "Local Disk (C:)".to_string()
                    } else {
                        format!("Drive ({}:)", c as char)
                    },
                    path: drive_str,
                    total,
                    free,
                    used,
                    icon: if is_c {
                        "💻".to_string()
                    } else {
                        "💽".to_string()
                    },
                });
            }
        }
    }
    if roots.is_empty() {
        roots.push(RootDrive {
            name: "Home".to_string(),
            path: std::env::var("USERPROFILE").unwrap_or_else(|_| "C:\\".to_string()),
            total: 0,
            free: 0,
            used: 0,
            icon: "📁".to_string(),
        });
    }
    roots
}

#[cfg(target_os = "windows")]
fn get_disk_space(path: &str) -> (u64, u64) {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;

    let wide: Vec<u16> = OsStr::new(path)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let mut free_caller: u64 = 0;
    let mut total_bytes: u64 = 0;
    let mut total_free: u64 = 0;

    extern "system" {
        fn GetDiskFreeSpaceExW(
            lpDirectoryName: *const u16,
            lpFreeBytesAvailableToCaller: *mut u64,
            lpTotalNumberOfBytes: *mut u64,
            lpTotalNumberOfFreeBytes: *mut u64,
        ) -> i32;
    }

    unsafe {
        if GetDiskFreeSpaceExW(
            wide.as_ptr(),
            &mut free_caller,
            &mut total_bytes,
            &mut total_free,
        ) != 0
        {
            (total_bytes, free_caller)
        } else {
            (0, 0)
        }
    }
}

#[cfg(not(target_os = "windows"))]
fn get_disk_space(_path: &str) -> (u64, u64) {
    (0, 0)
}

fn get_quick_access() -> Vec<QuickPath> {
    let mut quick = Vec::new();
    let home = std::env::var("USERPROFILE").unwrap_or_default();
    if !home.is_empty() {
        let items = [
            ("Downloads", "Downloads"),
            ("Documents", "Documents"),
            ("Desktop", "Desktop"),
            ("Pictures", "Pictures"),
            ("Videos", "Videos"),
            ("Music", "Music"),
        ];
        for (label, sub) in items {
            let p = Path::new(&home).join(sub);
            if p.is_dir() {
                quick.push(QuickPath {
                    name: label.to_string(),
                    path: p.to_string_lossy().to_string(),
                });
            }
        }
    }
    quick
}

fn start_discovery_beacon(
    http_port: u16,
    username: String,
    nearby: Arc<RwLock<HashMap<String, NearbyDevice>>>,
) {
    std::thread::spawn(move || {
        let sock = match UdpSocket::bind(format!("0.0.0.0:{}", DISCOVERY_PORT)) {
            Ok(s) => s,
            Err(_) => return,
        };
        let _ = sock.set_broadcast(true);
        let _ = sock.set_read_timeout(Some(Duration::from_millis(1000)));

        let sock_send = match sock.try_clone() {
            Ok(s) => s,
            Err(_) => return,
        };

        // Broadcast loop
        let user_clone = username.clone();
        let nearby_clone_bc = nearby.clone();
        std::thread::spawn(move || {
            let mut loop_cnt: usize = 0;
            loop {
                let lan = get_lan_ip();
                let payload = serde_json::json!({
                    "proto": "fastshare_v1",
                    "name": "PC • Windows Destroyer",
                    "os": "windows",
                    "ip": lan,
                    "port": http_port,
                    "user": user_clone,
                });
                let bytes = payload.to_string().into_bytes();
                let _ = sock_send.send_to(&bytes, format!("255.255.255.255:{}", DISCOVERY_PORT));
                let parts: Vec<&str> = lan.split('.').collect();
                if parts.len() == 4 {
                    let subnet_bc = format!(
                        "{}.{}.{}.255:{}",
                        parts[0], parts[1], parts[2], DISCOVERY_PORT
                    );
                    let _ = sock_send.send_to(&bytes, subnet_bc);
                }

                if let Ok(map) = nearby_clone_bc.read() {
                    for pip in map.keys() {
                        let _ = sock_send.send_to(&bytes, format!("{}:{}", pip, DISCOVERY_PORT));
                    }
                }

                loop_cnt += 1;
                if loop_cnt % 2 == 0 {
                    probe_arp(&lan, nearby_clone_bc.clone(), Some(&sock_send));
                }

                std::thread::sleep(Duration::from_millis(1500));
            }
        });

        // Listen loop
        let mut buf = [0u8; 4096];
        let self_ip = get_lan_ip();
        while let Ok((n, src)) = sock.recv_from(&mut buf) {
            let sender_ip = src.ip().to_string();
            if sender_ip == self_ip || sender_ip == "127.0.0.1" {
                continue;
            }
            if let Ok(val) = serde_json::from_slice::<serde_json::Value>(&buf[..n]) {
                if val.get("proto").and_then(|p| p.as_str()) == Some("fastshare_v1") {
                    let name = val
                        .get("name")
                        .and_then(|n| n.as_str())
                        .unwrap_or("Remote Device")
                        .to_string();
                    let os = val
                        .get("os")
                        .and_then(|o| o.as_str())
                        .unwrap_or("device")
                        .to_string();
                    let port = val.get("port").and_then(|p| p.as_u64()).unwrap_or(8000) as u16;
                    let user = val
                        .get("user")
                        .and_then(|u| u.as_str())
                        .unwrap_or("share")
                        .to_string();
                    let now = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .map(|d| d.as_secs_f64())
                        .unwrap_or(0.0);

                    if let Ok(mut map) = nearby.write() {
                        map.insert(
                            sender_ip.clone(),
                            NearbyDevice {
                                name,
                                os,
                                ip: sender_ip.clone(),
                                port,
                                user,
                                time: now,
                            },
                        );
                    }

                    // Only reply if explicitly queried via ping to avoid infinite ping-pong packet storm
                    if val.get("action").and_then(|a| a.as_str()) == Some("ping") {
                        let reply = serde_json::json!({
                            "proto": "fastshare_v1",
                            "action": "pong",
                            "name": "PC • Windows Destroyer",
                            "os": "windows",
                            "ip": self_ip,
                            "port": http_port,
                            "user": username,
                        });
                        let reply_bytes = reply.to_string().into_bytes();
                        let _ = sock.send_to(&reply_bytes, src);
                    }
                }
            }
        }
    });
}

fn probe_arp(
    lan: &str,
    nearby: Arc<RwLock<HashMap<String, NearbyDevice>>>,
    sock: Option<&UdpSocket>,
) {
    let parts: Vec<&str> = lan.split('.').collect();
    if parts.len() != 4 {
        return;
    }
    let prefix = format!("{}.{}.{}.", parts[0], parts[1], parts[2]);

    #[cfg(target_os = "windows")]
    let out = std::process::Command::new("arp").arg("-a").output();
    #[cfg(not(target_os = "windows"))]
    let out = std::process::Command::new("arp").arg("-n").output();

    if let Ok(out) = out {
        let text = String::from_utf8_lossy(&out.stdout);
        for line in text.lines() {
            for word in line.split_whitespace() {
                if word.starts_with(&prefix)
                    && !word.ends_with(".255")
                    && !word.ends_with(".1")
                    && word != lan
                {
                    let ip = word.to_string();
                    let nearby_clone = nearby.clone();

                    if let Some(s) = sock {
                        let ping = serde_json::json!({
                            "proto": "fastshare_v1",
                            "name": "PC • Windows Destroyer",
                            "os": "windows",
                            "ip": lan,
                            "port": 8000,
                            "user": "share",
                            "action": "ping",
                        });
                        let _ = s.send_to(
                            ping.to_string().as_bytes(),
                            format!("{}:{}", ip, DISCOVERY_PORT),
                        );
                    }

                    std::thread::spawn(move || {
                        if let Ok(addr) = format!("{}:8000", ip).parse() {
                            if let Ok(mut stream) = std::net::TcpStream::connect_timeout(
                                &addr,
                                Duration::from_millis(800),
                            ) {
                                let _ = stream.set_read_timeout(Some(Duration::from_millis(1200)));
                                let _ = stream.set_write_timeout(Some(Duration::from_millis(1200)));
                                let req = format!("GET /api/info HTTP/1.1\r\nHost: {}:8000\r\nUser-Agent: FastShare-Probe\r\nConnection: close\r\n\r\n", ip);
                                if stream.write_all(req.as_bytes()).is_ok() {
                                    let mut buf = Vec::new();
                                    let _ = stream.read_to_end(&mut buf);
                                    let resp = String::from_utf8_lossy(&buf);
                                    if let Some(pos) = resp.find("\r\n\r\n") {
                                        let body = &resp[pos + 4..];
                                        if let Ok(val) =
                                            serde_json::from_str::<serde_json::Value>(body)
                                        {
                                            let name = val
                                                .get("device")
                                                .or_else(|| val.get("hostname"))
                                                .and_then(|s| s.as_str())
                                                .unwrap_or("Android Phone")
                                                .to_string();
                                            let os = if val
                                                .get("is_android")
                                                .and_then(|b| b.as_bool())
                                                .unwrap_or(false)
                                            {
                                                "android".to_string()
                                            } else {
                                                val.get("platform")
                                                    .and_then(|s| s.as_str())
                                                    .unwrap_or("device")
                                                    .to_string()
                                            };
                                            let port = val
                                                .get("port")
                                                .and_then(|p| p.as_u64())
                                                .unwrap_or(8000)
                                                as u16;
                                            let user = "share".to_string();
                                            let now = SystemTime::now()
                                                .duration_since(UNIX_EPOCH)
                                                .map(|d| d.as_secs_f64())
                                                .unwrap_or(0.0);
                                            if let Ok(mut map) = nearby_clone.write() {
                                                map.insert(
                                                    ip.clone(),
                                                    NearbyDevice {
                                                        name,
                                                        os,
                                                        ip,
                                                        port,
                                                        user,
                                                        time: now,
                                                    },
                                                );
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    });
                }
            }
        }
    }
}

fn handle_request(mut req: Request, state: &AppState) {
    let method = req.method().clone();
    let url_raw = req.url().to_string();
    let (path_part, query_part) = if let Some(idx) = url_raw.find('?') {
        (&url_raw[..idx], &url_raw[idx + 1..])
    } else {
        (url_raw.as_str(), "")
    };

    let query: HashMap<String, String> = query_part
        .split('&')
        .filter_map(|kv| {
            let mut parts = kv.splitn(2, '=');
            let k = urlencoding::decode(parts.next()?).ok()?.to_string();
            let v = urlencoding::decode(parts.next().unwrap_or(""))
                .ok()?
                .to_string();
            Some((k, v))
        })
        .collect();

    // CORS preflight
    if method == Method::Options {
        let resp = Response::empty(200)
            .with_header(
                Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap(),
            )
            .with_header(
                Header::from_bytes(
                    &b"Access-Control-Allow-Methods"[..],
                    &b"GET, POST, OPTIONS, HEAD"[..],
                )
                .unwrap(),
            )
            .with_header(
                Header::from_bytes(&b"Access-Control-Allow-Headers"[..], &b"*"[..]).unwrap(),
            );
        let _ = req.respond(resp);
        return;
    }

    // Root UI
    if path_part == "/" || path_part == "/index.html" {
        let lan_ip = get_lan_ip();
        let client_ip = req
            .remote_addr()
            .map(|a| a.ip().to_string())
            .unwrap_or_default();
        let is_local = client_ip == "127.0.0.1" || client_ip == "::1";
        let meta = serde_json::json!({
            "is_local": is_local,
            "local_token": if is_local { &state.session_token } else { "" },
            "user": &state.user,
            "device": "PC • Windows Destroyer",
            "port": state.port,
            "ip": lan_ip,
            "authenticated": is_local || is_authorized(&req, state, &query),
        });
        let meta_tag = format!("window.__FS_META__ = {};", meta);
        let html = UI_HTML.replace("/*__SERVER_META__*/", &meta_tag);
        let resp = Response::from_data(html)
            .with_header(
                Header::from_bytes(&b"Content-Type"[..], &b"text/html; charset=utf-8"[..]).unwrap(),
            )
            .with_header(
                Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap(),
            );
        let _ = req.respond(resp);
        return;
    }

    // Public /api/info
    if path_part == "/api/info" {
        let lan_ip = get_lan_ip();
        let client_ip = req
            .remote_addr()
            .map(|a| a.ip().to_string())
            .unwrap_or_default();
        let info = serde_json::json!({
            "device": "Windows Destroyer",
            "platform": "Windows",
            "hostname": "Destroyer",
            "ip": lan_ip,
            "port": state.port,
            "client_ip": client_ip,
            "version": "3.5 (Rust Core)",
            "is_android": false,
            "roots": get_roots().len()
        });
        send_json(req, &info, 200);
        return;
    }

    // Public /api/nearby
    if path_part == "/api/nearby" {
        let lan_ip = get_lan_ip();
        probe_arp(&lan_ip, state.nearby.clone(), None);
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs_f64())
            .unwrap_or(0.0);
        let list: Vec<NearbyDevice> = if let Ok(map) = state.nearby.read() {
            map.values()
                .filter(|v| now - v.time < 15.0)
                .cloned()
                .collect()
        } else {
            Vec::new()
        };
        send_json(req, &list, 200);
        return;
    }

    // Login API
    if path_part == "/api/login" && method == Method::Post {
        let mut body = String::new();
        let _ = req.as_reader().read_to_string(&mut body);
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&body) {
            let pwd = val.get("password").and_then(|p| p.as_str()).unwrap_or("");
            if pwd == state.password {
                let resp_body = serde_json::json!({
                    "ok": true,
                    "token": &state.session_token,
                    "user": &state.user,
                    "device": "PC • Windows Destroyer"
                });
                let resp = Response::from_data(resp_body.to_string())
                    .with_header(
                        Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap(),
                    )
                    .with_header(
                        Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap(),
                    )
                    .with_header(
                        Header::from_bytes(
                            &b"Set-Cookie"[..],
                            format!(
                                "fastshare_token={}; Path=/; Max-Age=2592000",
                                state.session_token
                            )
                            .as_bytes(),
                        )
                        .unwrap(),
                    );
                let _ = req.respond(resp);
                return;
            }
        }
        send_json(
            req,
            &serde_json::json!({"ok": false, "error": "Invalid password"}),
            401,
        );
        return;
    }

    // Authorization check for protected endpoints
    if !is_authorized(&req, state, &query) {
        send_json(
            req,
            &serde_json::json!({"error": "unauthorized", "login_required": true}),
            401,
        );
        return;
    }

    // Drives
    if path_part == "/api/drives" {
        send_json(req, &get_roots(), 200);
        return;
    }

    // Directory list
    if path_part == "/api/list" {
        let raw_path = query.get("path").cloned().unwrap_or_default();
        let roots = get_roots();
        let cur_path = if raw_path.is_empty() {
            roots
                .first()
                .map(|r| r.path.clone())
                .unwrap_or_else(|| "C:\\".to_string())
        } else {
            raw_path
        };

        let target_path = match confined_path(&cur_path) {
            Some(path) => path,
            None => {
                send_json(
                    req,
                    &serde_json::json!({"error": "path outside shared roots"}),
                    403,
                );
                return;
            }
        };
        if !target_path.exists() {
            send_json(req, &serde_json::json!({"error": "not found"}), 404);
            return;
        }

        let mut items = Vec::new();
        let mut dirs_count = 0;
        let mut files_count = 0;
        let mut total_size = 0;

        if let Ok(entries) = fs::read_dir(&target_path) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if name.starts_with('.') {
                    continue;
                }
                let is_dir = entry.file_type().map(|t| t.is_dir()).unwrap_or(false);
                let size = if is_dir {
                    0
                } else {
                    entry.metadata().map(|m| m.len()).unwrap_or(0)
                };
                let mtime = entry
                    .metadata()
                    .and_then(|m| m.modified())
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs())
                    .unwrap_or(0);

                if is_dir {
                    dirs_count += 1;
                } else {
                    files_count += 1;
                    total_size += size;
                }

                items.push(FileItem {
                    name,
                    path: entry.path().to_string_lossy().to_string(),
                    is_dir,
                    size,
                    mtime,
                });
            }
        }

        let mut breadcrumbs = Vec::new();
        let p_str = cur_path.replace('/', "\\");
        let mut accum = String::new();
        for part in p_str.split('\\') {
            if part.is_empty() {
                continue;
            }
            if accum.is_empty() {
                accum = format!("{}\\", part);
            } else {
                accum = format!("{}\\{}", accum.trim_end_matches('\\'), part);
            }
            breadcrumbs.push(Breadcrumb {
                name: part.to_string(),
                path: accum.clone(),
            });
        }

        let resp = ListResponse {
            path: target_path.to_string_lossy().to_string(),
            breadcrumbs,
            items,
            dirs: dirs_count,
            files: files_count,
            total_size,
            writable: true,
            roots,
            quick: get_quick_access(),
            device: "Windows Destroyer".to_string(),
            is_android: false,
        };
        send_json(req, &resp, 200);
        return;
    }

    // High-speed file download with Range support (RFC 7233)
    if path_part == "/api/download" || path_part == "/api/view" || path_part == "/api/thumb" {
        let raw_path = query.get("path").cloned().unwrap_or_default();
        let path = match confined_path(&raw_path) {
            Some(path) => path,
            None => {
                send_json(
                    req,
                    &serde_json::json!({"error": "path outside shared roots"}),
                    403,
                );
                return;
            }
        };
        if !path.is_file() {
            send_json(req, &serde_json::json!({"error": "not found"}), 404);
            return;
        }

        let mut file = match File::open(&path) {
            Ok(f) => f,
            Err(_) => {
                send_json(req, &serde_json::json!({"error": "access denied"}), 403);
                return;
            }
        };

        let file_len = file.metadata().map(|m| m.len()).unwrap_or(0);
        let range_header = req
            .headers()
            .iter()
            .find(|h| h.field.equiv("range"))
            .map(|h| h.value.as_str());

        let mut start: u64 = 0;
        let mut end: u64 = if file_len > 0 { file_len - 1 } else { 0 };
        let mut is_partial = false;

        if let Some(r) = range_header {
            if let Some(bytes_part) = r.strip_prefix("bytes=") {
                let parts: Vec<&str> = bytes_part.split('-').collect();
                if let Ok(s) = parts[0].parse::<u64>() {
                    start = s;
                    is_partial = true;
                }
                if parts.len() > 1 && !parts[1].is_empty() {
                    if let Ok(e) = parts[1].parse::<u64>() {
                        end = std::cmp::min(e, file_len - 1);
                        is_partial = true;
                    }
                }
            }
        }

        if start > end || start >= file_len {
            start = 0;
            end = if file_len > 0 { file_len - 1 } else { 0 };
            is_partial = false;
        }

        let length = end - start + 1;
        let _ = file.seek(SeekFrom::Start(start));
        let fname = path
            .file_name()
            .map(|n| n.to_string_lossy())
            .unwrap_or_default();
        let enc_name = urlencoding::encode(&fname);

        let mut resp = Response::new(
            StatusCode(if is_partial { 206 } else { 200 }),
            vec![
                Header::from_bytes(&b"Content-Type"[..], &b"application/octet-stream"[..]).unwrap(),
                Header::from_bytes(&b"Accept-Ranges"[..], &b"bytes"[..]).unwrap(),
                Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap(),
                Header::from_bytes(
                    &b"Access-Control-Expose-Headers"[..],
                    &b"Content-Length, Content-Range, Content-Disposition"[..],
                )
                .unwrap(),
                Header::from_bytes(
                    &b"Content-Disposition"[..],
                    format!("attachment; filename*=UTF-8''{}", enc_name).as_bytes(),
                )
                .unwrap(),
            ],
            file.take(length),
            Some(length as usize),
            None,
        );

        if is_partial {
            resp.add_header(
                Header::from_bytes(
                    &b"Content-Range"[..],
                    format!("bytes {}-{}/{}", start, end, file_len).as_bytes(),
                )
                .unwrap(),
            );
        }

        let _ = req.respond(resp);
        return;
    }

    // High-speed raw streamed upload
    if path_part == "/api/raw" && method == Method::Post {
        let dest_dir_str = query.get("path").cloned().unwrap_or_default();
        let file_name = query.get("name").cloned().unwrap_or_else(|| {
            format!(
                "upload_{}",
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_millis()
            )
        });
        let dest_dir = if dest_dir_str.is_empty() {
            dirs_fallback_download()
        } else {
            match confined_path(&dest_dir_str) {
                Some(path) => path,
                None => {
                    send_json(
                        req,
                        &serde_json::json!({"error": "path outside shared roots"}),
                        403,
                    );
                    return;
                }
            }
        };

        if file_name.is_empty()
            || file_name.contains('/')
            || file_name.contains('\\')
            || file_name == "."
            || file_name == ".."
        {
            send_json(req, &serde_json::json!({"error": "bad name"}), 400);
            return;
        }

        let content_length = req
            .headers()
            .iter()
            .find(|h| h.field.equiv("content-length"))
            .and_then(|h| h.value.as_str().parse::<u64>().ok());
        if content_length.is_none() || content_length.unwrap() > MAX_UPLOAD_BYTES {
            send_json(req, &serde_json::json!({"error": "bad length"}), 400);
            return;
        }

        if !dest_dir.is_dir() {
            let _ = fs::create_dir_all(&dest_dir);
        }

        let tmp_path = dest_dir.join(format!(
            "{}.part-{}",
            file_name,
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let mut tmp_file = match File::create(&tmp_path) {
            Ok(f) => f,
            Err(_) => {
                send_json(
                    req,
                    &serde_json::json!({"error": "failed to create file"}),
                    500,
                );
                return;
            }
        };

        let reader = req.as_reader();
        let mut buf = vec![0u8; CHUNK_SIZE];
        let mut total_written: u64 = 0;

        loop {
            match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if tmp_file.write_all(&buf[..n]).is_err() {
                        let _ = fs::remove_file(&tmp_path);
                        send_json(req, &serde_json::json!({"error": "disk write error"}), 500);
                        return;
                    }
                    total_written += n as u64;
                }
                Err(e) => {
                    let _ = fs::remove_file(&tmp_path);
                    send_json(
                        req,
                        &serde_json::json!({"error": format!("read error: {}", e)}),
                        500,
                    );
                    return;
                }
            }
        }
        let _ = tmp_file.flush();
        drop(tmp_file);

        let dest_path = dest_dir.join(&file_name);
        let final_path = if dest_path.exists() {
            let stem = dest_path
                .file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_default();
            let ext = dest_path
                .extension()
                .map(|e| format!(".{}", e.to_string_lossy()))
                .unwrap_or_default();
            let mut k = 1;
            loop {
                let candidate = dest_dir.join(format!("{} ({}){}", stem, k, ext));
                if !candidate.exists() {
                    break candidate;
                }
                k += 1;
            }
        } else {
            dest_path
        };

        if fs::rename(&tmp_path, &final_path).is_ok() {
            send_json(
                req,
                &serde_json::json!({"ok": true, "saved": [final_path.file_name().unwrap().to_string_lossy()], "bytes": total_written}),
                200,
            );
        } else {
            let _ = fs::remove_file(&tmp_path);
            send_json(req, &serde_json::json!({"error": "rename failed"}), 500);
        }
        return;
    }

    // High-speed folder ZIP streaming (STORED - uncompressed)
    if path_part == "/api/zip" {
        let raw_path = query.get("path").cloned().unwrap_or_default();
        let folder = match confined_path(&raw_path) {
            Some(path) => path,
            None => {
                send_json(
                    req,
                    &serde_json::json!({"error": "path outside shared roots"}),
                    403,
                );
                return;
            }
        };
        if !folder.is_dir() {
            send_json(
                req,
                &serde_json::json!({"error": "directory not found"}),
                404,
            );
            return;
        }

        let folder_name = folder
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "files".to_string());
        let zip_name = format!("{}.zip", folder_name);

        let mut zip_bytes = Vec::new();
        if create_stored_zip(&folder, &mut zip_bytes).is_ok() {
            let _len = zip_bytes.len();
            let resp = Response::from_data(zip_bytes)
                .with_header(
                    Header::from_bytes(&b"Content-Type"[..], &b"application/zip"[..]).unwrap(),
                )
                .with_header(
                    Header::from_bytes(
                        &b"Content-Disposition"[..],
                        format!("attachment; filename=\"{}\"", zip_name).as_bytes(),
                    )
                    .unwrap(),
                )
                .with_header(
                    Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap(),
                );
            let _ = req.respond(resp);
        } else {
            send_json(req, &serde_json::json!({"error": "zip failed"}), 500);
        }
        return;
    }

    // Directory operations
    if path_part == "/api/mkdir" && method == Method::Post {
        let mut body = String::new();
        let _ = req.as_reader().read_to_string(&mut body);
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&body) {
            let base = val.get("path").and_then(|p| p.as_str()).unwrap_or("");
            let name = val.get("name").and_then(|n| n.as_str()).unwrap_or("");
            if let Some(base) = confined_path(base) {
                if !name.is_empty()
                    && !name.contains('/')
                    && !name.contains('\\')
                    && name != "."
                    && name != ".."
                {
                    let target = base.join(name);
                    if confined_new_path(&target).is_none() {
                        send_json(
                            req,
                            &serde_json::json!({"error": "path outside shared roots"}),
                            403,
                        );
                        return;
                    }
                    if fs::create_dir_all(&target).is_ok() {
                        send_json(req, &serde_json::json!({"ok": true}), 200);
                        return;
                    }
                }
            }
        }
        send_json(req, &serde_json::json!({"error": "failed"}), 400);
        return;
    }

    if path_part == "/api/delete" && method == Method::Post {
        let mut body = String::new();
        let _ = req.as_reader().read_to_string(&mut body);
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&body) {
            let p = val.get("path").and_then(|p| p.as_str()).unwrap_or("");
            let target = match confined_path(p) {
                Some(path) => path,
                None => {
                    send_json(
                        req,
                        &serde_json::json!({"error": "path outside shared roots"}),
                        403,
                    );
                    return;
                }
            };
            let ok = if target.is_dir() {
                fs::remove_dir_all(target).is_ok()
            } else {
                fs::remove_file(target).is_ok()
            };
            if ok {
                send_json(req, &serde_json::json!({"ok": true}), 200);
                return;
            }
        }
        send_json(req, &serde_json::json!({"error": "failed"}), 500);
        return;
    }

    if path_part == "/api/rename" && method == Method::Post {
        let mut body = String::new();
        let _ = req.as_reader().read_to_string(&mut body);
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&body) {
            let p = val.get("path").and_then(|p| p.as_str()).unwrap_or("");
            let nn = val.get("newName").and_then(|n| n.as_str()).unwrap_or("");
            let old = match confined_path(p) {
                Some(path) => path,
                None => {
                    send_json(
                        req,
                        &serde_json::json!({"error": "path outside shared roots"}),
                        403,
                    );
                    return;
                }
            };
            if let Some(parent) = old.parent() {
                if nn.is_empty() || nn.contains('/') || nn.contains('\\') || nn == "." || nn == ".."
                {
                    send_json(req, &serde_json::json!({"error": "bad name"}), 400);
                    return;
                }
                let new_path = parent.join(nn);
                if confined_new_path(&new_path).is_none() {
                    send_json(
                        req,
                        &serde_json::json!({"error": "path outside shared roots"}),
                        403,
                    );
                    return;
                }
                if fs::rename(old, new_path).is_ok() {
                    send_json(req, &serde_json::json!({"ok": true}), 200);
                    return;
                }
            }
        }
        send_json(req, &serde_json::json!({"error": "failed"}), 500);
        return;
    }

    let _ = req.respond(Response::empty(404));
}

fn is_authorized(req: &Request, state: &AppState, query: &HashMap<String, String>) -> bool {
    let client_ip = req
        .remote_addr()
        .map(|a| a.ip().to_string())
        .unwrap_or_default();
    if client_ip == "127.0.0.1" || client_ip == "::1" {
        return true;
    }

    // Query token
    if let Some(tok) = query.get("token") {
        if tok == &state.session_token {
            return true;
        }
    }

    // Cookie
    for h in req.headers() {
        if h.field.equiv("cookie") {
            let val = h.value.as_str();
            for cookie in val.split(';') {
                let mut parts = cookie.trim().splitn(2, '=');
                if parts.next() == Some("fastshare_token")
                    && parts.next() == Some(state.session_token.as_str())
                {
                    return true;
                }
            }
        }
        if h.field.equiv("authorization") {
            let val = h.value.as_str();
            if let Some(b64) = val.strip_prefix("Basic ") {
                if let Ok(bytes) = base64_decode(b64.trim()) {
                    if let Ok(cred) = String::from_utf8(bytes) {
                        let mut parts = cred.splitn(2, ':');
                        let u = parts.next().unwrap_or("");
                        let p = parts.next().unwrap_or("");
                        if u == state.user && p == state.password {
                            return true;
                        }
                    }
                }
            }
        }
    }

    false
}

fn shared_root_paths() -> Vec<PathBuf> {
    get_roots()
        .into_iter()
        .filter_map(|root| fs::canonicalize(root.path).ok())
        .collect()
}

fn confined_path(raw: &str) -> Option<PathBuf> {
    let candidate = fs::canonicalize(raw).ok()?;
    shared_root_paths()
        .into_iter()
        .any(|root| candidate.starts_with(&root))
        .then_some(candidate)
}

fn confined_new_path(path: &Path) -> Option<PathBuf> {
    let parent = path.parent()?;
    let parent = confined_path(parent.to_string_lossy().as_ref())?;
    let name = path.file_name()?;
    Some(parent.join(name))
}

fn base64_decode(s: &str) -> Result<Vec<u8>, ()> {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = Vec::new();
    let mut buf: u32 = 0;
    let mut bits: u8 = 0;
    for &b in s.as_bytes() {
        if b == b'=' {
            break;
        }
        let val = match TABLE.iter().position(|&c| c == b) {
            Some(idx) => idx as u32,
            None => continue,
        };
        buf = (buf << 6) | val;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    Ok(out)
}

fn send_json<T: Serialize>(req: Request, data: &T, code: u16) {
    let json_bytes = serde_json::to_vec(data).unwrap_or_default();
    let resp = Response::from_data(json_bytes)
        .with_status_code(StatusCode(code))
        .with_header(
            Header::from_bytes(
                &b"Content-Type"[..],
                &b"application/json; charset=utf-8"[..],
            )
            .unwrap(),
        )
        .with_header(Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap());
    let _ = req.respond(resp);
}

fn dirs_fallback_download() -> PathBuf {
    let home = std::env::var("USERPROFILE").unwrap_or_else(|_| "C:\\".to_string());
    let dl = Path::new(&home).join("Downloads");
    if dl.is_dir() {
        dl
    } else {
        PathBuf::from(home)
    }
}

fn create_stored_zip(folder: &Path, out: &mut Vec<u8>) -> io::Result<()> {
    let mut entries = Vec::new();
    collect_files(folder, folder, &mut entries)?;

    let mut cdir = Vec::new();
    let mut offset: u32 = 0;

    for (rel_path, abs_path) in entries {
        let mut f = File::open(&abs_path)?;
        let size = f.metadata()?.len() as u32;
        let mut crc = 0u32;
        let mut fbuf = vec![0u8; 1024 * 1024];

        loop {
            let n = f.read(&mut fbuf)?;
            if n == 0 {
                break;
            }
            crc = update_crc32(crc, &fbuf[..n]);
        }

        if out.len() > MAX_ZIP_BYTES || size as usize > MAX_ZIP_BYTES.saturating_sub(out.len()) {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "zip too large"));
        }

        let name_bytes = rel_path.as_bytes();
        let name_len = name_bytes.len() as u16;

        // Local file header
        out.extend_from_slice(&0x04034b50u32.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes()); // version
        out.extend_from_slice(&0x800u16.to_le_bytes()); // utf-8
        out.extend_from_slice(&0u16.to_le_bytes()); // stored
        out.extend_from_slice(&0u16.to_le_bytes()); // time
        out.extend_from_slice(&0u16.to_le_bytes()); // date
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&size.to_le_bytes());
        out.extend_from_slice(&size.to_le_bytes());
        out.extend_from_slice(&name_len.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // extra len
        out.extend_from_slice(name_bytes);

        let local_offset = offset;
        offset += 30 + (name_len as u32);

        // Copy file content
        f.seek(SeekFrom::Start(0))?;
        loop {
            let n = f.read(&mut fbuf)?;
            if n == 0 {
                break;
            }
            out.extend_from_slice(&fbuf[..n]);
            offset += n as u32;
        }

        cdir.push((name_bytes.to_vec(), crc, size, local_offset));
    }

    let cdir_start = offset;
    for (name_bytes, crc, size, local_off) in &cdir {
        let name_len = name_bytes.len() as u16;
        out.extend_from_slice(&0x02014b50u32.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes());
        out.extend_from_slice(&0x800u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&size.to_le_bytes());
        out.extend_from_slice(&size.to_le_bytes());
        out.extend_from_slice(&name_len.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&local_off.to_le_bytes());
        out.extend_from_slice(name_bytes);
        offset += 46 + (name_len as u32);
    }

    let cdir_len = offset - cdir_start;
    out.extend_from_slice(&0x06054b50u32.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&(cdir.len() as u16).to_le_bytes());
    out.extend_from_slice(&(cdir.len() as u16).to_le_bytes());
    out.extend_from_slice(&cdir_len.to_le_bytes());
    out.extend_from_slice(&cdir_start.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());

    Ok(())
}

fn collect_files(base: &Path, current: &Path, out: &mut Vec<(String, PathBuf)>) -> io::Result<()> {
    if current.is_dir() {
        for entry in fs::read_dir(current)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_dir() {
                collect_files(base, &path, out)?;
            } else if path.is_file() {
                let rel = path
                    .strip_prefix(base)
                    .unwrap_or(&path)
                    .to_string_lossy()
                    .replace('\\', "/");
                out.push((rel, path));
            }
        }
    }
    Ok(())
}

fn update_crc32(mut crc: u32, buf: &[u8]) -> u32 {
    crc = !crc;
    for &b in buf {
        crc ^= b as u32;
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xEDB88320 & (!((crc & 1).wrapping_sub(1))));
        }
    }
    !crc
}
