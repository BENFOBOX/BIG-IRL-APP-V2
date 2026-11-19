#!/usr/bin/env bash
set -euo pipefail

sudo tee /usr/local/bin/bigirl-telemetry-api.py >/dev/null <<'PY'
#!/usr/bin/env python3
import json
import os
import subprocess
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

RECORDING_DIR = "/home/benfobox/bigirl-recordings"
last_path = ""
last_size = 0
last_time = time.time()
last_probe_time = 0
cached_width = 0
cached_height = 0
cached_fps = 0.0

def newest_recording():
    files = [os.path.join(RECORDING_DIR, name) for name in os.listdir(RECORDING_DIR) if name.endswith('.ts')]
    return max(files, key=os.path.getmtime) if files else ""

def probe(path):
    global last_probe_time, cached_width, cached_height, cached_fps
    now = time.time()
    if not path or now - last_probe_time < 5:
        return cached_width, cached_height, cached_fps
    last_probe_time = now
    try:
        output = subprocess.check_output([
            "/usr/bin/ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate",
            "-of", "json", path,
        ], universal_newlines=True, timeout=4)
        stream = json.loads(output).get("streams", [{}])[0]
        cached_width = int(stream.get("width", 0))
        cached_height = int(stream.get("height", 0))
        rate = stream.get("avg_frame_rate", "0/1")
        if rate in ("0/0", "0/1", ""):
            rate = stream.get("r_frame_rate", "0/1")
        a, b = rate.split("/", 1)
        value = float(a) / float(b) if float(b) else 0.0
        cached_fps = value if 0 < value <= 120 else 0.0
    except Exception:
        pass
    return cached_width, cached_height, cached_fps

def snapshot():
    global last_path, last_size, last_time
    now = time.time()
    path = newest_recording()
    size = os.path.getsize(path) if path and os.path.exists(path) else 0
    elapsed = max(now - last_time, 0.1)
    received_kbps = 0
    if path == last_path and size >= last_size:
        received_kbps = int(((size - last_size) * 8) / elapsed / 1000)
    last_path, last_size, last_time = path, size, now
    connected = received_kbps > 20
    width, height, fps = probe(path)
    try:
        temperature = int(open('/sys/devices/virtual/thermal/thermal_zone0/temp').read().strip()) / 1000.0
    except Exception:
        temperature = 0.0
    return {
        "connected": connected,
        "received_kbps": received_kbps,
        "width": width,
        "height": height,
        "fps": fps,
        "temperature_c": temperature,
        "recording": os.path.basename(path) if path else "",
    }

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/status':
            self.send_error(404)
            return
        body = json.dumps(snapshot()).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, fmt, *args):
        pass

HTTPServer(('0.0.0.0', 8082), Handler).serve_forever()
PY

sudo chmod +x /usr/local/bin/bigirl-telemetry-api.py
sudo tee /etc/systemd/system/bigirl-telemetry-api.service >/dev/null <<'SERVICE'
[Unit]
Description=BIG IRL StreamBox telemetry API
After=network-online.target bigirl-receiver.service
Wants=network-online.target

[Service]
Type=simple
User=benfobox
Group=benfobox
ExecStart=/usr/bin/python3 /usr/local/bin/bigirl-telemetry-api.py
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SERVICE

sudo systemctl daemon-reload
sudo systemctl enable --now bigirl-telemetry-api.service
systemctl is-active bigirl-telemetry-api.service
curl -s http://127.0.0.1:8082/status
echo
