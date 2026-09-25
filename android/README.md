# LeNeo 设备桥 (IP Devices)

把 **Android 内置摄像头**、**USB UVC 摄像头**、**USB 转串口** 用网络转发给同一台手机上的 Termux `proot-distro ubuntu-22`，或局域网里的 ROS2 / LeRobot。

设计参考：

- 内置摄像头 HTTP MJPEG：[DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera)
- USB UVC：[ernestp/AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera)（AUSBC，原项目 [jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera)）
- USB 串口：[mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) + [SimpleUsbTerminal](https://github.com/kai-morich/SimpleUsbTerminal) 的前台服务思路

## 功能

前台服务常驻后提供：

| 设备 | 协议 | 默认地址 |
| --- | --- | --- |
| 后置摄像头 | MJPEG HTTP | `http://<IP>:8080/cam/back/mjpeg` |
| 前置摄像头 | MJPEG HTTP | `http://<IP>:8080/cam/front/mjpeg` |
| USB UVC 摄像头 | MJPEG HTTP | `http://<IP>:8080/uvc/mjpeg` |
| 抓拍 | JPEG | `.../snapshot` |
| 第一路 USB 串口 | 手机 30 Hz 主机，电脑用 `host://<IP>` | 命令 9101，状态 9102 |
| 其余 USB 串口 | 原始 TCP 双向 | `tcp://<IP>:9000` 起 |
| 设备列表 | JSON | `http://<IP>:8080/info.json` |
| 网页预览 | HTML | `http://<IP>:8080/` |

同一台手机的 Termux 把 `<IP>` 换成 `127.0.0.1` 即可。

兼容 android-ip-camera 习惯路径：`/video/mjpeg`。

## 编译安装

本机需要 **Android Studio Ladybug+**（JDK 17）和 Android SDK。

1. 用 Android Studio 打开本目录
2. 等待 Gradle 同步（第一次会从 JitPack 拉 AUSBC / usb-serial，较慢）
3. 连上手机，点 Run；或命令行：

```bat
gradlew.bat assembleDebug
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`

手机需要：

- 打开 **OTG / USB 主机**
- 授予 **相机**、**通知** 权限
- 建议点 App 里的「忽略电池优化」
- USB 摄像头 / 串口插入后点「刷新设备」，弹出 USB 权限点允许

LeRobot 的单臂遥操和录制写在仓库根目录的 `README.md`。相机地址以 `http://<IP>:8080/info.json` 为准。

## 注意

1. **不要对同一颗 USB 摄像头同时开 Camera2 和 AUSBC**。部分手机系统会把 UVC 枚举成 `external` 摄像头，会出现在「内置摄像头」列表里；这时用 `/cam/<id>/mjpeg` 即可，不必再开 `/uvc/...`。
2. USB 摄像头和 USB 串口如果接在同一个 OTG 口，请用 **USB Hub**。
3. MJPEG 走的是 JPEG 帧，延迟通常几十毫秒级，适合 LeRobot 采集；带宽大约 1–8 Mbps，看分辨率。
4. 第一路串口由手机按 30 Hz 读写舵机，电脑使用 `host://<IP>`。多出来的串口仍是原始字节 TCP，不是 RFC2217。
5. 长时间插电请把充电上限设到 80%（如果手机支持），避免电池鼓包。见 [android-ip-camera 的警告](https://github.com/DigitallyRefined/android-ip-camera)。

## 项目结构

```
app/src/main/java/com/leneo/ipdevices/
  BridgeService.kt          前台服务
  camera/BuiltinCameraManager.kt   Camera2 内置摄像头
  camera/UsbUvcCameraManager.kt    AUSBC USB 摄像头
  serial/UsbSerialManager.kt       usb-serial-for-android + TCP
  net/HttpBridgeServer.kt          MJPEG / JSON HTTP
```
