"""本机/CI 专用 HTTPS 音频服务：合成 WAV、可控门控、CORS 与大小错误。无外部请求。"""
import io
import json
import ssl
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

buffer = io.BytesIO()
with wave.open(buffer, "wb") as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(16000)
    audio.writeframes(b"\0\0" * 48000)
WAV = buffer.getvalue()
lock = threading.Lock()
gates, counts, requests = {}, {}, {}

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_GET(self):
        parsed = urlsplit(self.path)
        key = parse_qs(parsed.query).get("key", ["default"])[0]
        name = parsed.path.rsplit("/", 1)[-1]
        if parsed.path == "/health":
            return self.reply(b"ok", "text/plain")
        if parsed.path == "/control/reset":
            with lock:
                gates[key] = threading.Event()
            return self.reply(b"ok", "text/plain")
        if parsed.path == "/control/release":
            with lock:
                gates.setdefault(key, threading.Event()).set()
            return self.reply(b"ok", "text/plain")
        if parsed.path == "/control/requests":
            with lock:
                data = requests.get(key, [])[:]
            return self.reply(json.dumps(data).encode(), "application/json")
        with lock:
            requests.setdefault(key, []).append({"path": parsed.path, "cookie": bool(self.headers.get("Cookie")), "referrer": bool(self.headers.get("Referer")), "authorization": bool(self.headers.get("Authorization"))})
            count_key = (key, name)
            counts[count_key] = count = counts.get(count_key, 0) + 1
            gate = gates.setdefault(key, threading.Event())
        if name == "unavailable" or name == "retry.wav" and count == 1:
            return self.reply(b"unavailable", "text/plain", 503)
        if name == "not-audio":
            return self.reply(b"<html>not audio</html>", "text/html")
        if name == "large-header":
            self.headers_for("audio/wav", 64*1024*1024+1)
            return
        if name == "large-body":
            self.headers_for("audio/wav", None)
            try:
                chunk = b"\0" * (1024*1024)
                for _ in range(66):
                    self.wfile.write(chunk)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass
            return
        self.headers_for("audio/wav", None if name == "no-length.wav" else len(WAV), cors=name != "no-cors.wav")
        try:
            if name == "gated.wav":
                self.wfile.write(WAV[:4096])
                self.wfile.flush()
                if not gate.wait(60):
                    return
                self.wfile.write(WAV[4096:])
            else:
                self.wfile.write(WAV)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            pass

    def headers_for(self, mime, size, status=200, cors=True):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        if size is not None:
            self.send_header("Content-Length", str(size))
        if cors:
            self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.close_connection = True

    def reply(self, data, mime, status=200):
        self.headers_for(mime, len(data), status)
        self.wfile.write(data)

server = ThreadingHTTPServer(("0.0.0.0", 8443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain("/tmp/cert.pem", "/tmp/key.pem")
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
