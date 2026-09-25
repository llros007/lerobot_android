from dataclasses import dataclass

from ..configs import CameraConfig

__all__ = ["MjpegCameraConfig"]


@CameraConfig.register_subclass("mjpeg")
@dataclass
class MjpegCameraConfig(CameraConfig):
    """HTTP multipart MJPEG camera. `read()` returns JPEG bytes, not a decoded image.

    The phone bridge already emits the camera's own JPEG. Decoding happens later,
    inside the dataset encoder, so the 30 Hz control loop does not pay for it.
    """

    url: str
    warmup_s: float = 2.0
