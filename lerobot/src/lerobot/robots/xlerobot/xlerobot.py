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

"""Single-arm XLeRobot: SO arm + differential 2-wheel base on one bus (like LeKiwi)."""

from __future__ import annotations

import logging
import time
from functools import cached_property
from itertools import chain
from typing import Any

import numpy as np

from lerobot.cameras import make_cameras_from_configs
from lerobot.lerobot_types import RobotAction, RobotObservation
from lerobot.motors import Motor, MotorCalibration, MotorNormMode
from lerobot.motors.feetech import FeetechMotorsBus, OperatingMode
from lerobot.robots.so_follower.phone_host import PhoneHostBus, is_phone_host_port
from lerobot.utils.decorators import check_if_already_connected, check_if_not_connected

from ..robot import Robot
from ..utils import ensure_safe_goal_position
from .config_xlerobot import XLerobotConfig

logger = logging.getLogger(__name__)


class XLerobot(Robot):
    """One follower arm (IDs 1–6) and two drive wheels (IDs 7–8) on a single bus."""

    config_class = XLerobotConfig
    name = "xlerobot"

    def __init__(self, config: XLerobotConfig):
        super().__init__(config)
        self.config = config
        self.teleop_keys = config.teleop_keys
        self.speed_levels = [
            {"linear": 0.1, "angular": 30},
            {"linear": 0.2, "angular": 60},
            {"linear": 0.3, "angular": 90},
        ]
        self.speed_index = 0
        norm_mode_body = MotorNormMode.DEGREES if config.use_degrees else MotorNormMode.RANGE_M100_100
        motors = {
            "arm_shoulder_pan": Motor(1, "sts3215", norm_mode_body),
            "arm_shoulder_lift": Motor(2, "sts3215", norm_mode_body),
            "arm_elbow_flex": Motor(3, "sts3215", norm_mode_body),
            "arm_wrist_flex": Motor(4, "sts3215", norm_mode_body),
            "arm_wrist_roll": Motor(5, "sts3215", norm_mode_body),
            "arm_gripper": Motor(6, "sts3215", MotorNormMode.RANGE_0_100),
            "base_left_wheel": Motor(7, "sts3215", MotorNormMode.RANGE_M100_100),
            "base_right_wheel": Motor(8, "sts3215", MotorNormMode.RANGE_M100_100),
        }
        self._phone_host = is_phone_host_port(self.config.port)
        if self._phone_host:
            self.bus = PhoneHostBus(self.config.port, motors, self.calibration)
        else:
            self.bus = FeetechMotorsBus(
                port=self.config.port,
                motors=motors,
                calibration=self.calibration,
            )
        self.arm_motors = [m for m in self.bus.motors if m.startswith("arm")]
        self.base_motors = [m for m in self.bus.motors if m.startswith("base")]
        self._wheel_cmd: dict[str, int] | None = None
        self.cameras = make_cameras_from_configs(config.cameras)

    @property
    def _state_ft(self) -> dict[str, type]:
        return dict.fromkeys(
            (
                "arm_shoulder_pan.pos",
                "arm_shoulder_lift.pos",
                "arm_elbow_flex.pos",
                "arm_wrist_flex.pos",
                "arm_wrist_roll.pos",
                "arm_gripper.pos",
                "x.vel",
                "theta.vel",
            ),
            float,
        )

    @property
    def _cameras_ft(self) -> dict[str, tuple]:
        return {
            cam: (self.config.cameras[cam].height, self.config.cameras[cam].width, 3)
            for cam in self.cameras
        }

    @cached_property
    def observation_features(self) -> dict[str, type | tuple]:
        return {**self._state_ft, **self._cameras_ft}

    @cached_property
    def action_features(self) -> dict[str, type]:
        return self._state_ft

    @property
    def is_connected(self) -> bool:
        return self.bus.is_connected and all(cam.is_connected for cam in self.cameras.values())

    @check_if_already_connected
    def connect(self, calibrate: bool = True) -> None:
        self.bus.connect()
        if self._phone_host and not self.is_calibrated:
            raise RuntimeError(
                f"Phone host for {self.id} needs a calibration file on this PC. "
                "Calibrate once over a direct serial port, then use host://"
            )
        if not self.is_calibrated and calibrate:
            logger.info(
                "Mismatch between calibration values in the motor and the calibration file "
                "or no calibration file found"
            )
            self.calibrate()
        for cam in self.cameras.values():
            cam.connect()
        # The phone already configures the bus and runs the 30 Hz loop.
        if not self._phone_host:
            self.configure()
        logger.info(f"{self} connected.")

    @property
    def is_calibrated(self) -> bool:
        return self.bus.is_calibrated

    def calibrate(self) -> None:
        if self.calibration:
            user_input = input(
                f"Press ENTER to use provided calibration file associated with the id {self.id}, "
                "or type 'c' and press ENTER to run calibration: "
            )
            if user_input.strip().lower() != "c":
                logger.info(f"Writing calibration file associated with the id {self.id} to the motors")
                self.bus.write_calibration(self.calibration)
                return

        logger.info(f"\nRunning calibration of {self}")
        self.bus.disable_torque(self.arm_motors)
        for name in self.arm_motors:
            self.bus.write("Operating_Mode", name, OperatingMode.POSITION.value)

        input("Move arm to the middle of its range of motion and press ENTER....")
        homing_offsets = self.bus.set_half_turn_homings(self.arm_motors)
        homing_offsets.update(dict.fromkeys(self.base_motors, 0))

        full_turn = [m for m in self.arm_motors if "wrist_roll" in m] + self.base_motors
        unknown = [m for m in self.arm_motors if m not in full_turn]
        print(
            f"Move all arm joints except '{full_turn}' through their ranges.\n"
            "Recording positions. Press ENTER to stop..."
        )
        range_mins, range_maxes = self.bus.record_ranges_of_motion(unknown)
        for name in full_turn:
            range_mins[name] = 0
            range_maxes[name] = 4095

        self.calibration = {}
        for name, motor in self.bus.motors.items():
            self.calibration[name] = MotorCalibration(
                id=motor.id,
                drive_mode=0,
                homing_offset=homing_offsets[name],
                range_min=range_mins[name],
                range_max=range_maxes[name],
            )
        self.bus.write_calibration(self.calibration)
        self._save_calibration()
        print("Calibration saved to", self.calibration_fpath)

    def configure(self) -> None:
        self.bus.disable_torque()
        self.bus.configure_motors()
        for name in self.arm_motors:
            self.bus.write("Operating_Mode", name, OperatingMode.POSITION.value)
            self.bus.write("P_Coefficient", name, 16)
            self.bus.write("I_Coefficient", name, 0)
            self.bus.write("D_Coefficient", name, 32)
            if name == "arm_gripper":
                self.bus.write("Max_Torque_Limit", name, 500)
                self.bus.write("Protection_Current", name, 250)
                self.bus.write("Overload_Torque", name, 25)
        for name in self.base_motors:
            self.bus.write("Operating_Mode", name, OperatingMode.VELOCITY.value)
        self.bus.enable_torque()

    def setup_motors(self) -> None:
        for motor in chain(reversed(self.arm_motors), reversed(self.base_motors)):
            input(f"Connect the controller board to the '{motor}' motor only and press enter.")
            self.bus.setup_motor(motor)
            print(f"'{motor}' motor id set to {self.bus.motors[motor].id}")

    @staticmethod
    def _degps_to_raw(degps: float) -> int:
        steps_per_deg = 4096.0 / 360.0
        speed_int = int(round(degps * steps_per_deg))
        return int(np.clip(speed_int, -0x8000, 0x7FFF))

    @staticmethod
    def _raw_to_degps(raw_speed: int) -> float:
        return raw_speed / (4096.0 / 360.0)

    def _body_to_wheel_raw(
        self,
        x: float,
        theta: float,
        *,
        wheel_radius: float | None = None,
        wheelbase: float | None = None,
        max_raw: int = 3000,
    ) -> dict[str, int]:
        wheel_radius = self.config.wheel_radius if wheel_radius is None else wheel_radius
        wheelbase = self.config.wheelbase if wheelbase is None else wheelbase
        theta_rad = theta * (np.pi / 180.0)
        left = (x - theta_rad * wheelbase / 2) / wheel_radius
        right = (x + theta_rad * wheelbase / 2) / wheel_radius
        left_degps = left * (180.0 / np.pi)
        right_degps = right * (180.0 / np.pi)
        steps_per_deg = 4096.0 / 360.0
        peak = max(abs(left_degps) * steps_per_deg, abs(right_degps) * steps_per_deg, 1e-9)
        if peak > max_raw:
            scale = max_raw / peak
            left_degps *= scale
            right_degps *= scale
        return {
            "base_left_wheel": self._degps_to_raw(left_degps) * int(self.config.left_wheel_sign),
            "base_right_wheel": self._degps_to_raw(right_degps) * int(self.config.right_wheel_sign),
        }

    def _wheel_raw_to_body(
        self,
        left_wheel_speed: int,
        right_wheel_speed: int,
        *,
        wheel_radius: float | None = None,
        wheelbase: float | None = None,
    ) -> dict[str, float]:
        wheel_radius = self.config.wheel_radius if wheel_radius is None else wheel_radius
        wheelbase = self.config.wheelbase if wheelbase is None else wheelbase
        left_wheel_speed = int(left_wheel_speed) * int(self.config.left_wheel_sign)
        right_wheel_speed = int(right_wheel_speed) * int(self.config.right_wheel_sign)
        left = self._raw_to_degps(left_wheel_speed) * (np.pi / 180.0) * wheel_radius
        right = self._raw_to_degps(right_wheel_speed) * (np.pi / 180.0) * wheel_radius
        x_vel = (left + right) / 2
        theta_vel = ((right - left) / wheelbase) * (180.0 / np.pi)
        return {"x.vel": float(x_vel), "theta.vel": float(theta_vel)}

    def _from_keyboard_to_base_action(self, pressed_keys: np.ndarray | set | list) -> dict[str, float]:
        if self.teleop_keys["speed_up"] in pressed_keys:
            self.speed_index = min(self.speed_index + 1, 2)
        if self.teleop_keys["speed_down"] in pressed_keys:
            self.speed_index = max(self.speed_index - 1, 0)
        speed = self.speed_levels[self.speed_index]
        x_cmd = 0.0
        theta_cmd = 0.0
        if self.teleop_keys["forward"] in pressed_keys:
            x_cmd += speed["linear"]
        if self.teleop_keys["backward"] in pressed_keys:
            x_cmd -= speed["linear"]
        if self.teleop_keys["rotate_left"] in pressed_keys:
            theta_cmd += speed["angular"]
        if self.teleop_keys["rotate_right"] in pressed_keys:
            theta_cmd -= speed["angular"]
        return {"x.vel": x_cmd, "theta.vel": theta_cmd}

    @check_if_not_connected
    def get_observation(self) -> RobotObservation:
        start = time.perf_counter()
        arm_pos = self.bus.sync_read(
            "Present_Position", self.arm_motors, num_retry=self.config.num_read_retries
        )
        # Raw socket: a stopped base does not need another Wi-Fi read.
        # Phone host already caches wheel speed, so reading it does not wait.
        if (
            not self._phone_host
            and self._wheel_cmd is not None
            and all(v == 0 for v in self._wheel_cmd.values())
        ):
            base_vel = {"x.vel": 0.0, "theta.vel": 0.0}
        else:
            base_wheel_vel = self.bus.sync_read(
                "Present_Velocity", self.base_motors, num_retry=self.config.num_read_retries
            )
            base_vel = self._wheel_raw_to_body(
                base_wheel_vel["base_left_wheel"],
                base_wheel_vel["base_right_wheel"],
            )
        obs_dict: RobotObservation = {**{f"{k}.pos": v for k, v in arm_pos.items()}, **base_vel}
        logger.debug(f"{self} read state: {(time.perf_counter() - start) * 1e3:.1f}ms")
        for cam_key, cam in self.cameras.items():
            start = time.perf_counter()
            obs_dict[cam_key] = cam.read_latest()
            logger.debug(f"{self} read {cam_key}: {(time.perf_counter() - start) * 1e3:.1f}ms")
        return obs_dict

    @check_if_not_connected
    def send_action(self, action: RobotAction) -> RobotAction:
        # SO leader emits shoulder_pan.pos; LeKiwi/XLeRobot motors are arm_shoulder_pan.
        action = self._prefix_arm_action(action)
        arm_goal_pos = {k: v for k, v in action.items() if k.endswith(".pos")}
        base_goal_vel = {k: v for k, v in action.items() if k.endswith(".vel")}
        # Missing base keys (arm-only teleop) mean stop the wheels.
        x_vel = float(base_goal_vel.get("x.vel", 0.0))
        theta_vel = float(base_goal_vel.get("theta.vel", 0.0))
        base_wheel_goal_vel = self._body_to_wheel_raw(x_vel, theta_vel)

        if self.config.max_relative_target is not None and arm_goal_pos:
            present_pos = self.bus.sync_read(
                "Present_Position", self.arm_motors, num_retry=self.config.num_read_retries
            )
            goal_present = {
                key.removesuffix(".pos"): (g_pos, present_pos[key.removesuffix(".pos")])
                for key, g_pos in arm_goal_pos.items()
            }
            safe = ensure_safe_goal_position(goal_present, self.config.max_relative_target)
            arm_goal_pos = {f"{k}.pos": v for k, v in safe.items()}

        arm_raw = {k.removesuffix(".pos"): v for k, v in arm_goal_pos.items()}
        if arm_raw:
            self.bus.sync_write("Goal_Position", arm_raw)
        if base_wheel_goal_vel != self._wheel_cmd:
            self.bus.sync_write("Goal_Velocity", base_wheel_goal_vel)
            self._wheel_cmd = dict(base_wheel_goal_vel)
        return {**arm_goal_pos, "x.vel": x_vel, "theta.vel": theta_vel}

    @staticmethod
    def _prefix_arm_action(action: RobotAction) -> RobotAction:
        """Map SO-leader keys (shoulder_pan.pos) onto arm_* motor names."""
        out: RobotAction = {}
        for key, value in action.items():
            if key.endswith(".vel") or key.startswith("arm_") or key.startswith("base_"):
                out[key] = value
                continue
            if key.endswith(".pos") and not key.startswith("arm_"):
                out[f"arm_{key}"] = value
            else:
                out[key] = value
        return out

    def stop_base(self) -> None:
        stopped = dict.fromkeys(self.base_motors, 0)
        self.bus.sync_write("Goal_Velocity", stopped, num_retry=5)
        self._wheel_cmd = stopped
        logger.info("Base motors stopped")

    @check_if_not_connected
    def disconnect(self) -> None:
        self.stop_base()
        self.bus.disconnect(self.config.disable_torque_on_disconnect)
        for cam in self.cameras.values():
            cam.disconnect()
        logger.info(f"{self} disconnected.")
