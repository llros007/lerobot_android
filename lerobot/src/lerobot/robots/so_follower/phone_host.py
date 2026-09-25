"""LeKiwi-style client for the phone arm host.

The phone owns the Feetech bus and runs the 30 Hz loop. This side only pushes
the latest goal and reads the latest position, so the record loop is not
blocked on Wi-Fi RTT.
"""

from __future__ import annotations

import json
import logging
import socket
import threading
import time
from contextlib import contextmanager

from lerobot.motors import Motor, MotorCalibration, MotorNormMode
from lerobot.motors.feetech import FeetechMotorsBus

logger = logging.getLogger(__name__)

MOTOR_NAMES = (
    "shoulder_pan",
    "shoulder_lift",
    "elbow_flex",
    "wrist_flex",
    "wrist_roll",
    "gripper",
)


class PhoneHostBus:
    def __init__(
        self,
        port: str,
        motors: dict[str, Motor],
        calibration: dict[str, MotorCalibration] | None,
    ):
        host = port.split("://", 1)[1]
        if ":" in host:
            host = host.rsplit(":", 1)[0]
        self.host = host
        self.cmd_port = 9101
        self.obs_port = 9102
        self.motors = motors
        self.calibration = calibration
        self._codec = FeetechMotorsBus(port="/dev/null", motors=motors, calibration=calibration)
        self._raw = [0] * 6
        self._vel = [0, 0]
        self._stamp = 0.0
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._cmd: socket.socket | None = None
        self._connected = False
        self._stale_warn_at = 0.0

    @property
    def is_connected(self) -> bool:
        return self._connected

    def connect(self, handshake: bool = True) -> None:
        del handshake
        self._stop.clear()
        self._thread = threading.Thread(target=self._obs_loop, name="phone-arm-obs", daemon=True)
        self._thread.start()
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline:
            with self._lock:
                if self._stamp > 0:
                    break
            time.sleep(0.02)
        else:
            raise ConnectionError(
                f"No arm observation from {self.host}:{self.obs_port} within 3s. "
                "Is the phone bridge running with the follower plugged in?"
            )
        self._cmd = socket.create_connection((self.host, self.cmd_port), timeout=3)
        self._cmd.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._connected = True
        logger.info("Phone arm host %s cmd=%s obs=%s", self.host, self.cmd_port, self.obs_port)

    def disconnect(self, disable_torque: bool = True) -> None:
        if disable_torque and self._cmd is not None:
            try:
                self._cmd.sendall(b'{"torque":0}\n')
            except OSError:
                pass
        self._stop.set()
        self._connected = False
        if self._cmd is not None:
            self._cmd.close()
            self._cmd = None

    @property
    def is_calibrated(self) -> bool:
        return bool(self.calibration)

    def _named(self, data_name: str, raw: dict[int, int], motors, *, normalize: bool) -> dict:
        age_ms = (time.monotonic() - self._stamp) * 1000 if self._stamp else 1e9
        # A short gap is the PC reader thread losing the CPU to AV1, not a dead phone.
        # Reuse the last sample. Only a multi-second gap means the link is down.
        if age_ms > 500:
            if age_ms > 2000:
                raise TimeoutError(f"phone arm observation is {age_ms:.0f}ms old")
            now = time.monotonic()
            if now - self._stale_warn_at > 1.0:
                self._stale_warn_at = now
                logger.warning("phone arm observation is %.0fms old, reusing it", age_ms)
        decoded = self._codec._decode_sign(data_name, raw)
        if normalize and data_name in self._codec.normalized_data:
            decoded = self._codec._normalize(decoded)
        named = {self._codec._id_to_name(i): v for i, v in decoded.items()}
        if motors:
            want = set(motors)
            named = {k: v for k, v in named.items() if k in want}
        return named

    def sync_read(self, data_name: str, motors=None, *, normalize: bool = True, num_retry: int = 0):
        del num_retry
        with self._lock:
            if data_name == "Present_Position":
                raw = {i + 1: self._raw[i] for i in range(6)}
            elif data_name == "Present_Velocity":
                raw = {7: self._vel[0], 8: self._vel[1]}
            else:
                raise NotImplementedError(f"phone host only caches position and velocity, not {data_name}")
        return self._named(data_name, raw, motors, normalize=normalize)

    def _send(self, payload: dict) -> None:
        sock = self._cmd
        if sock is None:
            raise ConnectionError("phone arm command socket is closed")
        sock.sendall((json.dumps(payload) + "\n").encode())

    def sync_write(self, data_name: str, values, *, normalize: bool = True, num_retry: int = 0) -> None:
        del num_retry
        ids_values = {}
        for key, val in values.items():
            name = key.removesuffix(".pos") if isinstance(key, str) else self._codec._id_to_name(key)
            if name not in self.motors and isinstance(key, str):
                name = key
            motor_id = self.motors[name].id
            ids_values[motor_id] = float(val)
        if data_name == "Goal_Position":
            if normalize:
                ids_values = self._codec._unnormalize(ids_values)
            encoded = self._codec._encode_sign(data_name, {i: int(v) for i, v in ids_values.items()})
            self._send({"pos": [int(encoded[i]) for i in range(1, 7)]})
            return
        if data_name == "Goal_Velocity":
            encoded = self._codec._encode_sign(data_name, {i: int(v) for i, v in ids_values.items()})
            self._send({"vel": [int(encoded.get(7, 0)), int(encoded.get(8, 0))]})
            return
        logger.debug("ignore phone host write %s", data_name)

    def disable_torque(self, *args, **kwargs) -> None:
        del args, kwargs
        sock = self._cmd
        if sock is not None:
            try:
                sock.sendall(b'{"torque":0}\n')
            except OSError:
                pass

    def configure_motors(self) -> None:
        return None

    def write(self, *args, **kwargs) -> None:
        del args, kwargs
        return None

    @contextmanager
    def torque_disabled(self):
        yield

    def _obs_loop(self) -> None:
        while not self._stop.is_set():
            try:
                sock = socket.create_connection((self.host, self.obs_port), timeout=3)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.settimeout(1.0)
                buf = b""
                while not self._stop.is_set():
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    buf += chunk
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        if not line.strip():
                            continue
                        msg = json.loads(line)
                        pos = msg.get("pos")
                        if not isinstance(pos, list) or len(pos) < 6:
                            continue
                        vel = msg.get("vel")
                        with self._lock:
                            self._raw = [int(pos[i]) for i in range(6)]
                            if isinstance(vel, list) and len(vel) >= 2:
                                self._vel = [int(vel[0]), int(vel[1])]
                            self._stamp = time.monotonic()
                        if len(buf) > 65536:
                            buf = buf[-4096:]
                sock.close()
            except Exception as exc:
                if self._stop.is_set():
                    return
                logger.warning("phone arm obs: %s", exc)
                time.sleep(0.2)


def is_phone_host_port(port: str) -> bool:
    return port.startswith("host://")


def make_follower_bus(port: str, motors: dict[str, Motor], calibration):
    if is_phone_host_port(port):
        return PhoneHostBus(port, motors, calibration)
    return FeetechMotorsBus(port=port, motors=motors, calibration=calibration)
