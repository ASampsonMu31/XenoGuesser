"""Serves the trailer folder at http://localhost:8123 with byte ranges, which the browser needs to
start a clip partway through (Python's own http.server sends whole files only).

Usage:  python tools/trailer/serve.py [port]
"""
import http.server
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "trailer")


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def send_head(self):
        wanted = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", ""))
        path = self.translate_path(self.path)
        if not wanted or not os.path.isfile(path):
            return super().send_head()
        size = os.path.getsize(path)
        first = int(wanted.group(1)) if wanted.group(1) else max(0, size - int(wanted.group(2)))
        last = int(wanted.group(2)) if wanted.group(1) and wanted.group(2) else size - 1
        last = min(last, size - 1)
        if first > last:
            self.send_error(416)
            return None
        f = open(path, "rb")
        f.seek(first)
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {first}-{last}/{size}")
        self.send_header("Content-Length", str(last - first + 1))
        self.end_headers()
        self.remaining = last - first + 1
        return f

    def copyfile(self, source, outputfile):
        remaining = getattr(self, "remaining", None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        while remaining > 0:
            chunk = source.read(min(65536, remaining))
            if not chunk:
                break
            try:
                outputfile.write(chunk)
            except (ConnectionResetError, BrokenPipeError):
                break
            remaining -= len(chunk)
        self.remaining = None

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8123
    print(f"The trailer: http://localhost:{port}/")
    http.server.ThreadingHTTPServer(("127.0.0.1", port), RangeHandler).serve_forever()
