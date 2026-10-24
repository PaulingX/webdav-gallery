"""Minimal WebDAV test server for verifying webdav-gallery (PROPFIND/GET/PUT, Basic auth, Range 206)."""
import base64
import datetime
import email.utils
import http.server
import os
import re
import socketserver
import sys
import urllib.parse

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'wdroot')
USER, PASS = 'user', 'pass123'

TYPES = {
    '.jpg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif',
    '.webp': 'image/webp', '.mp4': 'video/mp4', '.webm': 'video/webm',
    '.txt': 'text/plain',
}


def ext_type(name):
    return TYPES.get(os.path.splitext(name)[1].lower(), 'application/octet-stream')


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def _drain_body(self):
        length = int(self.headers.get('Content-Length', '0') or 0)
        if length:
            self.rfile.read(length)

    def _authed(self):
        header = self.headers.get('Authorization', '')
        if header.startswith('Basic '):
            try:
                u, p = base64.b64decode(header[6:]).decode().split(':', 1)
                if u == USER and p == PASS:
                    return True
            except Exception:
                pass
        self.send_response(401)
        self.send_header('WWW-Authenticate', 'Basic realm="test"')
        self.send_header('Content-Length', '0')
        self.end_headers()
        return False

    def _fs_path(self):
        p = urllib.parse.unquote(self.path.split('?')[0])
        full = os.path.realpath(os.path.join(ROOT, p.lstrip('/')))
        if not full.startswith(os.path.realpath(ROOT)):
            return None
        return full

    def _empty(self, code):
        self.send_response(code)
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_OPTIONS(self):
        self._drain_body()
        self.send_response(200)
        self.send_header('DAV', '1, 2')
        self.send_header('Allow', 'OPTIONS, GET, PUT, PROPFIND')
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_PROPFIND(self):
        self._drain_body()
        if not self._authed():
            return
        full = self._fs_path()
        if full is None or not os.path.exists(full):
            return self._empty(404)
        depth = self.headers.get('Depth', '1')
        # 先解码再统一编码，避免对已编码路径双重编码
        base = urllib.parse.quote(urllib.parse.unquote(self.path.split('?')[0]))
        entries = [(base.rstrip('/') or '/', full)]
        if depth == '1' and os.path.isdir(full):
            for name in sorted(os.listdir(full)):
                child = os.path.join(full, name)
                prefix = base.rstrip('/')
                entries.append((f'{prefix}/{urllib.parse.quote(name)}', child))
        xml = ['<?xml version="1.0" encoding="utf-8"?>', '<D:multistatus xmlns:D="DAV:">']
        for href, path in entries:
            isdir = os.path.isdir(path)
            name = os.path.basename(path) or '/'
            xml.append('<D:response>')
            xml.append(f'<D:href>{href}</D:href>')
            xml.append('<D:propstat><D:prop>')
            xml.append(f'<D:displayname>{name}</D:displayname>')
            xml.append('<D:resourcetype><D:collection/></D:resourcetype>' if isdir else '<D:resourcetype/>')
            if not isdir:
                mtime = email.utils.format_datetime(
                    datetime.datetime.fromtimestamp(os.path.getmtime(path), datetime.timezone.utc))
                xml.append(f'<D:getcontentlength>{os.path.getsize(path)}</D:getcontentlength>')
                xml.append(f'<D:getlastmodified>{mtime}</D:getlastmodified>')
                xml.append(f'<D:getcontenttype>{ext_type(name)}</D:getcontenttype>')
            xml.append('</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>')
            xml.append('</D:response>')
        xml.append('</D:multistatus>')
        body = ''.join(xml).encode('utf-8')
        self.send_response(207)
        self.send_header('Content-Type', 'application/xml; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._authed():
            return
        full = self._fs_path()
        if full is None or not os.path.isfile(full):
            return self._empty(404)
        size = os.path.getsize(full)
        start, end, status = 0, size - 1, 200
        rng = self.headers.get('Range')
        if rng:
            m = re.match(r'bytes=(\d*)-(\d*)', rng.strip())
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(size - int(m.group(2)), 0)
                status = 206
        if start >= size:
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{size}')
            return self._empty(416)
        self.send_response(status)
        self.send_header('Content-Type', ext_type(os.path.basename(full)))
        self.send_header('Accept-Ranges', 'bytes')
        if status == 206:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.send_header('Content-Length', str(end - start + 1))
        self.end_headers()
        with open(full, 'rb') as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = f.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def do_PUT(self):
        if not self._authed():
            return
        full = self._fs_path()
        if full is None:
            return self._empty(403)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        length = int(self.headers.get('Content-Length', '0') or 0)
        with open(full, 'wb') as f:
            f.write(self.rfile.read(length))
        self._empty(201)

    def log_message(self, fmt, *args):
        sys.stderr.write("[wdtest] %s\n" % (fmt % args))


class ThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    os.makedirs(ROOT, exist_ok=True)
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9999
    with ThreadingTCPServer(('127.0.0.1', port), Handler) as srv:
        print(f'WebDAV test server on http://127.0.0.1:{port}/', flush=True)
        srv.serve_forever()
