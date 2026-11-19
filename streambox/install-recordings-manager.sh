#!/usr/bin/env bash
set -euo pipefail

RECORDING_DIR="/home/benfobox/bigirl-recordings"
TOKEN="$(openssl rand -hex 12)"

sudo mkdir -p "$RECORDING_DIR"
sudo chown -R benfobox:benfobox "$RECORDING_DIR"

sudo tee /usr/local/bin/bigirl-recordings-web.py >/dev/null <<'PY'
#!/usr/bin/env python3
import json
import mimetypes
import os
import re
import subprocess
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

RECORDING_DIR = os.environ.get("BIGIRL_RECORDING_DIR", "/home/benfobox/bigirl-recordings")
DELETE_TOKEN = os.environ.get("BIGIRL_DELETE_TOKEN", "")
NAME_RE = re.compile(r"^bigirl-[0-9_-]+\.ts$")

PAGE = r'''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>BENFOBOX Recordings</title>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1"></script>
<style>
:root{color-scheme:dark;font-family:system-ui;background:#071019;color:#fff}
body{margin:0;padding:16px;background:#071019}main{max-width:1200px;margin:auto}
a{color:#51e1a6}nav{display:flex;gap:14px;margin:8px 0 16px}
.card{background:#101d2b;border-radius:12px;padding:14px;margin:10px 0}
.meta{color:#b7c7d8;margin:6px 0 12px}.actions{display:flex;gap:8px;flex-wrap:wrap}
button,.button{border:0;border-radius:9px;padding:10px 13px;background:#51e1a6;color:#002116;font-weight:700;text-decoration:none}
.delete{background:#e46b75;color:white}video{display:none;width:100%;background:#000;border-radius:12px;margin:12px 0}
#status{padding:10px 12px;background:#101d2b;border-radius:10px;color:#51e1a6}
</style></head><body><main>
<h1>BENFOBOX StreamBox</h1>
<nav><a href="http://192.168.1.27/">Live monitor</a><a href="/">Recordings</a></nav>
<div id="status">Loading recordings…</div>
<video id="player" controls playsinline></video>
<div id="list"></div>
<script>
const list=document.getElementById('list'),status=document.getElementById('status'),player=document.getElementById('player');
let hls=null;
function fmt(n){const u=['B','KB','MB','GB'];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return n.toFixed(i?1:0)+' '+u[i]}
function escapeHtml(s){return s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
async function load(){const r=await fetch('/api/recordings');const d=await r.json();status.textContent=`${d.count} recordings • ${fmt(d.free_bytes)} free`;
list.innerHTML=d.items.map(x=>`<div class="card"><strong>${escapeHtml(x.name)}</strong><div class="meta">${new Date(x.modified*1000).toLocaleString()} • ${fmt(x.size)}</div><div class="actions"><button onclick="watch('${x.name}')">Watch</button><a class="button" href="/files/${x.name}" download>Download</a><button class="delete" onclick="removeFile('${x.name}')">Delete</button></div></div>`).join('')||'<p>No completed recordings yet.</p>'}
function watch(name){player.style.display='block';player.scrollIntoView({behavior:'smooth'});const src='/playlist/'+encodeURIComponent(name)+'.m3u8';if(hls)hls.destroy();if(Hls.isSupported()){hls=new Hls();hls.loadSource(src);hls.attachMedia(player);hls.on(Hls.Events.MANIFEST_PARSED,()=>player.play())}else{player.src=src;player.play()}}
async function removeFile(name){if(!confirm('Delete '+name+' permanently?'))return;let token=localStorage.getItem('bigirlDeleteToken')||prompt('Enter StreamBox delete token');if(!token)return;localStorage.setItem('bigirlDeleteToken',token);const r=await fetch('/api/recordings/'+encodeURIComponent(name),{method:'DELETE',headers:{'X-Delete-Token':token}});if(!r.ok){alert(await r.text());if(r.status===403)localStorage.removeItem('bigirlDeleteToken')}await load()}
load().catch(e=>status.textContent='Could not load recordings: '+e);
</script></main></body></html>'''

class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class Handler(BaseHTTPRequestHandler):
    server_version = "BIGIRLRecordings/1.0"

    def send_bytes(self, data, content_type, status=200, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if extra:
            for key, value in extra.items(): self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def safe_path(self, name):
        name = urllib.parse.unquote(name)
        if not NAME_RE.match(name): return None
        path = os.path.realpath(os.path.join(RECORDING_DIR, name))
        root = os.path.realpath(RECORDING_DIR) + os.sep
        return path if path.startswith(root) else None

    def do_GET(self):
        p = urllib.parse.urlparse(self.path).path
        if p == "/":
            return self.send_bytes(PAGE.encode(), "text/html; charset=utf-8")
        if p == "/api/recordings":
            items=[]
            for name in os.listdir(RECORDING_DIR):
                path=self.safe_path(name)
                if path and os.path.isfile(path):
                    st=os.stat(path); items.append({"name":name,"size":st.st_size,"modified":int(st.st_mtime)})
            items.sort(key=lambda x:x["modified"], reverse=True)
            free=os.statvfs(RECORDING_DIR); data={"count":len(items),"free_bytes":free.f_bavail*free.f_frsize,"items":items}
            return self.send_bytes(json.dumps(data).encode(), "application/json")
        if p.startswith("/files/"):
            path=self.safe_path(p[len("/files/"):])
            if not path or not os.path.isfile(path): return self.send_error(404)
            size=os.path.getsize(path); start=0; end=size-1; status=200
            rh=self.headers.get("Range")
            if rh and rh.startswith("bytes="):
                a,b=rh[6:].split("-",1); start=int(a or 0); end=int(b or end); end=min(end,size-1); status=206
            length=end-start+1
            self.send_response(status); self.send_header("Content-Type","video/mp2t"); self.send_header("Accept-Ranges","bytes")
            self.send_header("Content-Length",str(length)); self.send_header("Content-Disposition",'inline; filename="%s"'%os.path.basename(path))
            if status==206:self.send_header("Content-Range",f"bytes {start}-{end}/{size}")
            self.end_headers()
            with open(path,"rb") as f:
                f.seek(start); remaining=length
                while remaining:
                    chunk=f.read(min(1024*1024,remaining))
                    if not chunk:break
                    self.wfile.write(chunk);remaining-=len(chunk)
            return
        if p.startswith("/playlist/") and p.endswith(".m3u8"):
            name=urllib.parse.unquote(p[len("/playlist/"):-len(".m3u8")])
            path=self.safe_path(name)
            if not path or not os.path.isfile(path): return self.send_error(404)
            try:
                out=subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration","-of","default=nw=1:nk=1",path],text=True,timeout=10)
                duration=max(float(out.strip()),0.1)
            except Exception: duration=3600.0
            playlist=f"#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:{int(duration)+1}\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:{duration:.3f},\n/files/{urllib.parse.quote(name)}\n#EXT-X-ENDLIST\n"
            return self.send_bytes(playlist.encode(), "application/vnd.apple.mpegurl")
        self.send_error(404)

    def do_DELETE(self):
        p=urllib.parse.urlparse(self.path).path
        if not p.startswith("/api/recordings/"): return self.send_error(404)
        if not DELETE_TOKEN or self.headers.get("X-Delete-Token","") != DELETE_TOKEN:
            return self.send_bytes(b"Invalid delete token", "text/plain", 403)
        name=p[len("/api/recordings/"):]; path=self.safe_path(name)
        if not path or not os.path.isfile(path): return self.send_error(404)
        if (int(__import__('time').time()) - int(os.path.getmtime(path))) < 30:
            return self.send_bytes(b"Recording is still active or too recent. Wait 30 seconds.", "text/plain", 409)
        os.remove(path)
        return self.send_bytes(b'{"deleted":true}', "application/json")

    def log_message(self, fmt, *args):
        print("[recordings-web]", fmt % args, flush=True)

ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
PY

sudo chmod +x /usr/local/bin/bigirl-recordings-web.py
sudo tee /etc/bigirl-recordings-web.env >/dev/null <<ENV
BIGIRL_RECORDING_DIR=$RECORDING_DIR
BIGIRL_DELETE_TOKEN=$TOKEN
ENV
sudo chown root:benfobox /etc/bigirl-recordings-web.env
sudo chmod 640 /etc/bigirl-recordings-web.env

sudo tee /etc/systemd/system/bigirl-recordings-web.service >/dev/null <<'SERVICE'
[Unit]
Description=BENFOBOX StreamBox recordings web manager
After=network-online.target bigirl-receiver.service
Wants=network-online.target

[Service]
Type=simple
User=benfobox
Group=benfobox
EnvironmentFile=/etc/bigirl-recordings-web.env
ExecStart=/usr/bin/python3 /usr/local/bin/bigirl-recordings-web.py
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SERVICE

sudo systemctl daemon-reload
sudo systemctl enable --now bigirl-recordings-web.service

echo
echo "Recordings manager installed."
echo "Open: http://192.168.1.27:8081"
echo "Delete token: $TOKEN"
echo "Save this token in your password manager."
