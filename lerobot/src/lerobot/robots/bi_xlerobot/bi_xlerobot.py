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

"""Dual-arm XLeRobot 0.4.0: left+head / right+wheels on two buses."""

from __future__ import annotations

import logging
import time
from functools import cached_property
from itertools import chain

import numpy as np

from lerobot.cameras import make_cameras_from_configs
from lerobot.lerobot_types import RobotAction, RobotObservation
from lerobot.motors import Motor, MotorCalibration, MotorNormMode
from lerobot.motors.feetech import FeetechMotorsBus, OperatingMode
from lerobot.utils.decorators import check_if_already_connected, check_if_not_connected

from ..robot import Robot
from ..utils import ensure_safe_goal_position
from .config_bi_xlerobot import BiXLerobotConfig

logger = logging.getLogger(__name__)


class BiXLerobot(Robot):
    """Left arm + head on bus1, right arm + differential wheels on bus2."""

    config_class = BiXLerobotConfig
    name = "bi_xlerobot"

    def __init__(self, config: BiXLerobotConfig):
        super().__init__(config)
        self.config = config
        self.teleop_keys = config.teleop_keys
        self.speed_levels = [
            {"linear": 0.1, "angular": 30},
            {"linear": 0.2, "angular": 60},
            {"linear": 0.3, "angular": 90},
        ]
        self.speed_index = 0
        norm = MotorNormMode.DEGREES if config.use_degrees else MotorNormMode.RANGE_M100_100

        cal1 = {
            k: v
            for k, v in self.calibration.items()
            if k.startswith(("left_arm", "head_"))
        } or self.calibration
        cal2 = {
            k: v
            for k, v in self.calibration.items()
            if k.startswith(("right_arm", "base_"))
        } or self.calibration

        self.bus1 = FeetechMotorsBus(
            port=config.port1,
            motors={
                "left_arm_shoulder_pan": Motor(1, "sts3215", norm),
                "left_arm_shoulder_lift": Motor(2, "sts3215", norm),
                "left_arm_elbow_flex": Motor(3, "sts3215", norm),
                "left_arm_wrist_flex": Motor(4, "sts3215", norm),
                "left_arm_wrist_roll": Motor(5, "sts3215", norm),
                "left_arm_gripper": Motor(6, "sts3215", MotorNormMode.RANGE_0_100),
                "head_motor_1": Motor(7, "sts3215", norm),
                "head_motor_2": Motor(8, "sts3215", norm),
            },
            calibration=cal1,
        )
        self.bus2 = FeetechMotorsBus(
            port=config.port2,
            motors={
                "right_arm_shoulder_pan": Motor(1, "sts3215", norm),
                "right_arm_shoulder_lift": Motor(2, "sts3215", norm),
                "right_arm_elbow_flex": Motor(3, "sts3215", norm),
                "right_arm_wrist_flex": Motor(4, "sts3215", norm),
                "right_arm_wrist_roll": Motor(5, "sts3215", norm),
                "right_arm_gripper": Motor(6, "sts3215", MotorNormMode.RANGE_0_100),
                "base_left_wheel": Motor(7, "sts3215", MotorNormMode.RANGE_M100_100),
                "base_right_wheel": Motor(8, "sts3215", MotorNormMode.RANGE_M100_100),
            },
            calibration=cal2,
        )
        self.left_arm_motors = [m for m in self.bus1.motors if m.startswith("left_arm")]
        self.head_motors = [m for m in self.bus1.motors if m.startswith("head")]
        self.right_arm_motors = [m for m in self.bus2.motors if m.startswith("right_arm")]
        self.base_motors = [m for m in self.bus2.motors if m.startswith("base")]
        self.cameras = make_cameras_from_configs(config.cameras)

    @property
    def _state_ft(self) -> dict[str, type]:
        keys = (
            [f"{m}.pos" for m in self.left_arm_motors]
            + [f"{m}.pos" for m in self.right_arm_motors]
            + [f"{m}.pos" for m in self.head_motors]
            + ["x.vel", "theta.vel"]
        )
        return dict.fromkeys(keys, float)

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
        return (
            self.bus1.is_connected
            and self.bus2.is_connected
            and all(cam.is_connected for cam in self.cameras.values())
        )

    @check_if_already_connected
    def connect(self, calibrate: bool = True) -> None:
        self.bus1.connect()
        self.bus2.connect()
        self.bus1.calibration = {k: v for k, v in self.calibration.items() if k in self.bus1.motors}
        self.bus2.calibration = {k: v for k, v in self.calibration.items() if k in self.bus2.motors}
        if not self.is_calibrated and calibrate:
            logger.info(
                "Mismatch between calibration values in the motor and the calibration file "
                "or no calibration file found"
            )
            self.calibrate()
        for cam in self.cameras.values():
            cam.connect()
        self.configure()
        logger.info(f"{self} connected.")

    @property
    def is_calibrated(self) -> bool:
        return self.bus1.is_calibrated and self.bus2.is_calibrated

    def calibrate(self) -> None:
        if self.calibration:
            user_input = input(
                f"Press ENTER to use provided calibration file associated with the id {self.id}, "
                "or type 'c' and press ENTER to run calibration: "
            )
            if user_input.strip().lower() != "c":
                self.bus1.write_calibration({k: v for k, v in self.calibration.items() if k in self.bus1.motors})
                self.bus2.write_calibration({k: v for k, v in self.calibration.items() if k in self.bus2.motors})
                return

        logger.info(f"\nRunning calibration of {self}")
        left = self.left_arm_motors + self.head_motors
        self.bus1.disable_torque()
        for name in left:
            self.bus1.write("Operating_Mode", name, OperatingMode.POSITION.value)
        input("Move left arm and head to mid-range and press ENTER....")
        homing1 = self.bus1.set_half_turn_homings(left)
        print("Sweep left arm + head (except wrist_roll). Press ENTER to stop...")
        full1 = [m for m in left if "wrist_roll" in m]
        unknown1 = [m for m in left if m not in full1]
        mins1, maxes1 = self.bus1.record_ranges_of_motion(unknown1)
        for name in full1:
            mins1[name] = 0
            maxes1[name] = 4095
        cal_left = {
            name: MotorCalibration(
                id=motor.id,
                drive_mode=0,
                homing_offset=homing1[name],
                range_min=mins1[name],
                range_max=maxes1[name],
            )
            for name, motor in self.bus1.motors.items()
        }
        self.bus1.write_calibration(cal_left)

        self.bus2.disable_torque(self.right_arm_motors)
        for name in self.right_arm_motors:
            self.bus2.write("Operating_Mode", name, OperatingMode.POSITION.value)
        input("Move right arm to mid-range and press ENTER....")
        homing2 = self.bus2.set_half_turn_homings(self.right_arm_motors)
        homing2.update(dict.fromkeys(self.base_motors, 0))
        print("Sweep right arm (except wrist_roll / wheels). Press ENTER to stop...")
        full2 = [m for m in self.right_arm_motors if "wrist_roll" in m] + self.base_motors
        unknown2 = [m for m in self.right_arm_motors if m not in full2]
        mins2, maxes2 = self.bus2.record_ranges_of_motion(unknown2)
        for name in full2:
            mins2[name] = 0
            maxes2[name] = 4095
        cal_right = {
            name: MotorCalibration(
                id=motor.id,
                drive_mode=0,
                homing_offset=homing2[name],
                range_min=mins2[name],
                range_max=maxes2[name],
            )
            for name, motor in self.bus2.motors.items()
        }
        self.bus2.write_calibration(cal_right)
        self.calibration = {**cal_left, **cal_right}
        self._save_calibration()
        print("Calibration saved to", self.calibration_fpath)

    def configure(self) -> None:
        self.bus1.disable_torque()
        self.bus2.disable_torque()
        self.bus1.configure_motors()
        self.bus2.configure_motors()
        for bus, names in (
            (self.bus1, self.left_arm_motors + self.head_motors),
            (self.bus2, self.right_arm_motors),
        ):
            for name in names:
                bus.write("Operating_Mode", name, OperatingMode.POSITION.value)
                bus.write("P_Coefficient", name, 16)
                bus.write("I_Coefficient", name, 0)
                bus.write("D_Coefficient", name, 32)
                if name.endswith("gripper"):
                    bus.write("Max_Torque_Limit", name, 500)
                    bus.write("Protection_Current", name, 250)
                    bus.write("Overload_Torque", name, 25)
        for name in self.base_motors:
            self.bus2.write("Operating_Mode", name, OperatingMode.VELOCITY.value)
        self.bus1.enable_torque()
        self.bus2.enable_torque()

    def setup_motors(self) -> None:
        for motor in chain(reversed(self.left_arm_motors), reversed(self.head_motors)):
            input(f"Connect bus1 to '{motor}' only and press enter.")
            self.bus1.setup_motor(motor)
            print(f"'{motor}' id={self.bus1.motors[motor].id}")
        for motor in chain(reversed(self.right_arm_motors), reversed(self.base_motors)):
            input(f"Connect bus2 to '{motor}' only and press enter.")
            self.bus2.setup_motor(motor)
            print(f"'{motor}' id={self.bus2.motors[motor].id}")

    @staticmethod
    def _degps_to_raw(degps: float) -> int:
        return int(np.clip(round(degps * (4096.0 / 360.0)), -0x8000, 0x7FFF))

    @staticmethod
    def _raw_to_degps(raw_speed: int) -> float:
        return raw_speed / (4096.0 / 360.0)

    def _body_to_wheel_raw(self, x: float, theta: float, max_raw: int = 3000) -> dict[str, int]:
        r = self.config.wheel_radius
        L = self.config.wheelbase
        theta_rad = theta * (np.pi / 180.0)
        left = (x - theta_rad * L / 2) / r * (180.0 / np.pi)
        right = (x + theta_rad * L / 2) / r * (180.0 / np.pi)
        peak = max(abs(left), abs(right), 1e-9) * (4096.0 / 360.0)
        if peak > max_raw:
            scale = max_raw / peak
            left *= scale
            right *= scale
        return {
            "base_left_wheel": self._degps_to_raw(left) * int(self.config.left_wheel_sign),
            "base_right_wheel": self._degps_to_raw(right) * int(self.config.right_wheel_sign),
        }

    def _wheel_raw_to_body(self, left_raw: int, right_raw: int) -> dict[str, float]:
        r = self.config.wheel_radius
        L = self.config.wheelbase
        left_raw = int(left_raw) * int(self.config.left_wheel_sign)
        right_raw = int(right_raw) * int(self.config.right_wheel_sign)
        left = self._raw_to_degps(left_raw) * (np.pi / 180.0) * r
        right = self._raw_to_degps(right_raw) * (np.pi / 180.0) * r
        return {
            "x.vel": float((left + right) / 2),
            "theta.vel": float(((right - left) / L) * (180.0 / np.pi)),
        }

    def _from_keyboard_to_base_action(self, pressed_keys) -> dict[str, float]:
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
        left = self.bus1.sync_read(
            "Present_Position", self.left_arm_motors, num_retry=self.config.num_read_retries
        )
        head = self.bus1.sync_read(
            "Present_Position", self.head_motors, num_retry=self.config.num_read_retries
        )
        right = self.bus2.sync_read(
            "Present_Position", self.right_arm_motors, num_retry=self.config.num_read_retries
        )
        wheels = self.bus2.sync_read(
            "Present_Velocity", self.base_motors, num_retry=self.config.num_read_retries
        )
        base = self._wheel_raw_to_body(wheels["base_left_wheel"], wheels["base_right_wheel"])
        obs: RobotObservation = {
            **{f"{k}.pos": v for k, v in left.items()},
            **{f"{k}.pos": v for k, v in right.items()},
            **{f"{k}.pos": v for k, v in head.items()},
            **base,
        }
        logger.debug(f"{self} read state: {(time.perf_counter() - start) * 1e3:.1f}ms")
        for cam_key, cam in self.cameras.items():
            obs[cam_key] = cam.read_latest()
        return obs

    @check_if_not_connected
    def send_action(self, action: RobotAction) -> RobotAction:
        # bi_so_leader emits left_shoulder_pan.pos; motors are left_arm_shoulder_pan.
        action = self._prefix_arm_action(action)
        pos = {k.removesuffix(".pos"): v for k, v in action.items() if k.endswith(".pos")}
        x_vel = float(action.get("x.vel", 0.0))
        theta_vel = float(action.get("theta.vel", 0.0))
        wheels = self._body_to_wheel_raw(x_vel, theta_vel)

        left_goal = {k: v for k, v in pos.items() if k in self.bus1.motors}
        right_goal = {k: v for k, v in pos.items() if k in self.right_arm_motors}

        if self.config.max_relative_target is not None:
            cap = self.config.max_relative_target
            if left_goal:
                present = self.bus1.sync_read(
                    "Present_Position", list(left_goal), num_retry=self.config.num_read_retries
                )
                left_goal = ensure_safe_goal_position(
                    {k: (left_goal[k], present[k]) for k in left_goal},
                    cap if isinstance(cap, float) else {k: cap[k] for k in left_goal if k in cap},
                )
            if right_goal:
                present = self.bus2.sync_read(
                    "Present_Position", list(right_goal), num_retry=self.config.num_read_retries
                )
                right_goal = ensure_safe_goal_position(
                    {k: (right_goal[k], present[k]) for k in right_goal},
                    cap if isinstance(cap, float) else {k: cap[k] for k in right_goal if k in cap},
                )

        if left_goal:
            self.bus1.sync_write("Goal_Position", left_goal)
        if right_goal:
            self.bus2.sync_write("Goal_Position", right_goal)
        self.bus2.sync_write("Goal_Velocity", wheels)

        sent = {f"{k}.pos": v for k, v in {**left_goal, **right_goal}.items()}
        sent["x.vel"] = x_vel
        sent["theta.vel"] = theta_vel
        return sent

    @staticmethod
    def _prefix_arm_action(action: RobotAction) -> RobotAction:
        """Map bi_so_leader keys onto left_arm_* / right_arm_* motor names."""
        out: RobotAction = {}
        for key, value in action.items():
            if key.endswith(".vel") or "_arm_" in key or key.startswith("head_") or key.startswith("base_"):
                out[key] = value
                continue
            if key.startswith("left_") and key.endswith(".pos") and not key.startswith("left_arm_"):
                out[f"left_arm_{key.removeprefix('left_')}"] = value
            elif key.startswith("right_") and key.endswith(".pos") and not key.startswith("right_arm_"):
                out[f"right_arm_{key.removeprefix('right_')}"] = value
            else:
                out[key] = value
        return out

    def stop_base(self) -> None:
        self.bus2.sync_write("Goal_Velocity", dict.fromkeys(self.base_motors, 0), num_retry=5)
        logger.info("Base motors stopped")

    @check_if_not_connected
    def disconnect(self) -> None:
        self.stop_base()
        self.bus1.disconnect(self.config.disable_torque_on_disconnect)
        self.bus2.disconnect(self.config.disable_torque_on_disconnect)
        for cam in self.cameras.values():
            cam.disconnect()
        logger.info(f"{self} disconnected.")
