# Copyright 2026 The HuggingFace Inc. team. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from dataclasses import dataclass, field

from lerobot.cameras import CameraConfig

from ..config import RobotConfig


@RobotConfig.register_subclass("bi_xlerobot")
@dataclass
class BiXLerobotConfig(RobotConfig):
    """Dual-arm XLeRobot 0.4.0 dual-wheel layout on two Feetech buses.

    Bus1 (port1): left arm 1–6 + head 7–8
    Bus2 (port2): right arm 1–6 + left/right wheels 7–8
    """

    port1: str = "/dev/ttyACM0"
    port2: str = "/dev/ttyACM1"

    disable_torque_on_disconnect: bool = True
    max_relative_target: float | dict[str, float] | None = None
    cameras: dict[str, CameraConfig] = field(default_factory=dict)
    use_degrees: bool = True
    num_read_retries: int = 2

    wheel_radius: float = 0.05
    wheelbase: float = 0.25
    left_wheel_sign: int = -1
    right_wheel_sign: int = 1

    teleop_keys: dict[str, str] = field(
        default_factory=lambda: {
            "forward": "i",
            "backward": "k",
            "rotate_left": "u",
            "rotate_right": "o",
            "speed_up": "n",
            "speed_down": "m",
            "quit": "b",
        }
    )
