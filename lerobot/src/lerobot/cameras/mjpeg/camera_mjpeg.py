"""Read an HTTP MJPEG stream and hand the JPEG bytes through without decoding."""

from __future__ import annotations

import logging
import socket
import time
from threading import Event, Lock, Thread
from urllib.parse import urlparse

from lerobot.utils.decorators import check_if_already_connected, check_if_not_connected
from lerobot.utils.errors import DeviceNotConnectedError

from ..camera import Camera
from .configuration_mjpeg import MjpegCameraConfig

logger = logging.getLogger(__name__)


class MjpegCamera(Camera):
    def __init__(self, config: MjpegCameraConfig):
        super().__init__(config)
        self.config = config
        self.url = config.url
        self.warmup_s = config.warmup_s
        self._thread: Thread | None = None
        self._stop = Event()
        self._lock = Lock()
        self._latest: bytes | None = None
        self._seq = 0
        self._stamp = 0.0
        self._new = Event()
        self._stale_warn_at = 0.0

    def __str__(self) -> str:
        return f"{self.__class__.__name__}({self.url})"

    @staticmethod
    def find_cameras() -> list[dict]:
        # Network MJPEG endpoints are configured explicitly; nothing to scan.
        return []

    @property
    def is_connected(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    @check_if_already_connected
    def connect(self, warmup: bool = True) -> None:
        self._stop.clear()
        self._thread = Thread(target=self._loop, name=f"mjpeg-{self.url}", daemon=True)
        self._thread.start()
        if not warmup:
            return
        deadline = time.monotonic() + self.warmup_s
        while time.monotonic() < deadline:
            with self._lock:
                if self._latest is not None:
                    return
            time.sleep(0.02)
        raise ConnectionError(f"{self} produced no JPEG within {self.warmup_s}s")

    @check_if_not_connected
    def disconnect(self) -> None:
        self._stop.set()
        thread = self._thread
        self._thread = None
        if thread is not None:
            thread.join(timeout=2)

    def read(self) -> bytes:
        return self.async_read()

    def read_latest(self, max_age_ms: int = 500) -> bytes:
        if not self.is_connected:
            raise DeviceNotConnectedError(f"{self} is not connected.")
        with self._lock:
            frame = self._latest
            stamp = self._stamp
        if frame is None:
            raise TimeoutError(f"{self} has no frame yet")
        age_ms = (time.monotonic() - stamp) * 1000
        if age_ms > max_age_ms:
            if age_ms > max(max_age_ms * 4, 2000):
                raise TimeoutError(f"{self} latest frame is {age_ms:.0f}ms old")
            now = time.monotonic()
            if now - self._stale_warn_at > 1.0:
                self._stale_warn_at = now
                logger.warning("%s latest frame is %.0fms old, reusing it", self, age_ms)
        return frame

    def async_read(self, timeout_ms: float = 200) -> bytes:
        if not self.is_connected:
            raise DeviceNotConnectedError(f"{self} is not connected.")
        with self._lock:
            seen = self._seq
        if not self._new.wait(timeout_ms / 1000):
            raise TimeoutError(f"{self} async_read timeout after {timeout_ms}ms")
        self._new.clear()
        with self._lock:
            frame = self._latest
            if frame is None or self._seq == seen and self._seq == 0:
                raise TimeoutError(f"{self} async_read got no frame")
            return frame

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self._read_connection()
            except Exception as exc:
                if self._stop.is_set():
                    return
                logger.warning("%s stream error: %s", self, exc)
                time.sleep(0.2)

    def _read_connection(self) -> None:
        parsed = urlparse(self.url)
        if parsed.scheme not in ("http", ""):
            raise ValueError(f"MjpegCamera only supports http URLs, got {self.url}")
        host = parsed.hostname
        if not host:
            raise ValueError(f"MjpegCamera URL has no host: {self.url}")
        port = parsed.port or 80
        path = parsed.path or "/"
        if parsed.query:
            path = f"{path}?{parsed.query}"
        sock = socket.create_connection((host, port), timeout=15)
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # Phone UVC opens on the first viewer and can take several seconds
            # before the HTTP headers and the first JPEG arrive.
            sock.settimeout(15)
            request = (
                f"GET {path} HTTP/1.0\r\nHost: {host}:{port}\r\nConnection: close\r\n\r\n"
            ).encode()
            sock.sendall(request)
            buf = bytearray()
            while b"\r\n\r\n" not in buf:
                chunk = sock.recv(4096)
                if not chunk:
                    raise ConnectionError("stream closed before headers")
                buf += chunk
                if len(buf) > 65536:
                    raise ConnectionError("headers too large")
            header, rest = buf.split(b"\r\n\r\n", 1)
            if b" 200 " not in header.split(b"\r\n", 1)[0]:
                raise ConnectionError(header.split(b"\r\n", 1)[0].decode("latin1", "replace"))
            pending = bytes(rest)
            while not self._stop.is_set():
                frame, pending = _next_jpeg(pending)
                if frame is None:
                    chunk = sock.recv(65536)
                    if not chunk:
                        raise ConnectionError("stream closed")
                    pending += chunk
                    continue
                with self._lock:
                    self._latest = frame
                    self._seq += 1
                    self._stamp = time.monotonic()
                self._new.set()
        finally:
            sock.close()


def _next_jpeg(buf: bytes) -> tuple[bytes | None, bytes]:
    """Pull one JPEG out of a multipart MJPEG buffer. Returns (frame, rest)."""
    marker = buf.find(b"\xff\xd8")
    if marker < 0:
        return None, buf[-1:] if buf.endswith(b"\xff") else b""
    length = _content_length_before(buf, marker)
    if length is not None:
        # Camera2 JPEGs embed a thumbnail whose FFD9 is not the end of the frame.
        # Wait for Content-Length bytes instead of cutting there.
        if marker + length > len(buf):
            return None, buf
        return buf[marker : marker + length], buf[marker + length :]
    end = _jpeg_end(buf, marker)
    if end is None:
        return None, buf[marker:]
    return buf[marker:end], buf[end:]


def _jpeg_end(buf: bytes, start: int) -> int | None:
    """Return the index just past this JPEG, skipping markers inside APP segments."""
    i = start + 2
    n = len(buf)
    while i + 1 < n:
        if buf[i] != 0xFF:
            i += 1
            continue
        while i < n and buf[i] == 0xFF:
            i += 1
        if i >= n:
            return None
        marker = buf[i]
        i += 1
        if marker == 0xD9:
            return i
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            continue
        if i + 2 > n:
            return None
        seglen = int.from_bytes(buf[i : i + 2], "big")
        if seglen < 2:
            return None
        if marker == 0xDA:
            j = i + seglen
            while j + 1 < n:
                if buf[j] == 0xFF and buf[j + 1] not in (0x00, 0xFF) and not 0xD0 <= buf[j + 1] <= 0xD7:
                    if buf[j + 1] == 0xD9:
                        return j + 2
                    break
                j += 1
            return None
        if i + seglen > n:
            return None
        i += seglen
    return None


def _content_length_before(buf: bytes, jpeg_at: int) -> int | None:
    window = buf[max(0, jpeg_at - 512) : jpeg_at].lower()
    key = b"content-length:"
    idx = window.rfind(key)
    if idx < 0:
        return None
    line = window[idx + len(key) :].split(b"\r\n", 1)[0].strip()
    try:
        return int(line)
    except ValueError:
        return None
