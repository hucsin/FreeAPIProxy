#!/usr/bin/env python3
"""端到端验证用的本地上游：chunked SSE + echo + 慢响应。"""
import http.server
import socketserver
import time


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for i in range(6):
            payload = ("data: tick %d\n\n" % i).encode()
            self.wfile.write(("%x\r\n" % len(payload)).encode() + payload + b"\r\n")
            self.wfile.flush()
            time.sleep(0.4)
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def _echo(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n > 0 else b""
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            body = b""
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                body += self.rfile.read(size)
                self.rfile.readline()
        out = (
            '{"method":"%s","bytes":%d,"contentType":"%s","ua":"%s","te":"%s"}'
            % (
                self.command,
                len(body),
                self.headers.get("Content-Type", ""),
                self.headers.get("User-Agent", ""),
                self.headers.get("Transfer-Encoding", ""),
            )
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def do_GET(self):
        if self.path.startswith("/sse"):
            self._sse()
        elif self.path.startswith("/headers"):
            out = repr({k.lower(): v for k, v in self.headers.items()}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)
        else:
            self._echo()

    def do_POST(self):
        self._echo()


socketserver.ThreadingTCPServer.allow_reuse_address = True
with socketserver.ThreadingTCPServer(("127.0.0.1", 9999), H) as srv:
    srv.serve_forever()
