# 双麦听诊 · AC7916AB 安卓应用 V0.1（工程验证版）

## 这版能做什么

- 不再使用 nRF Connect 的复杂通用界面，扫描列表只显示名称中含 AC79 / WL82 / JL- 的设备。
- 点击设备即连接，自动请求 MTU 200、自动发现 AE3A/AE30 服务、自动订阅 AE3C 和 AE02 的通知。
- 解析当前固件真正使用的 **84 字节 MC V1** 数据包，显示两路 RMS / peak / 32点波形。
- 检查丢包（根据16位包序号计算）与无效数据包数量。
- “ABCD 回传测试”：通过 AE01 写入 `41 42 43 44`，在 AE02 显示返回情况。
- 保存从 **AE3C 真正收到的数据** 为 CSV（Android 系统文件选择器，不需要读写存储权限）。
- 演示模式可离线查看效果，**明确标注为模拟，不能混淆为实测数据**。
- 不用账号，不连接服务器，不上传云端。

### 此版本还做不到

- MIC1/MIC3 未焊接前无法验证真实拾音。
- **不能实时播放音频，也不能录 WAV**：当前杰理固件只发每路32个波形抽样点，以及 RMS/peak，不是连续 PCM/ADPCM 音频数据。
- 如果 AE3C 持续为 0 包，App 会明确显示连接/订阅正常，但采集回调或固件发送流程仍需排查；换 App 并不能修复芯片端未发送的情况。
- 暂不实现健康指标、疾病诊断或临床判断。

## 手机、开发环境

- Android 8.0（API 26）以上，建议 Android 12 以上。
- 开发电脑安装 Android Studio、Android SDK Platform 35、JDK 17。
- Gradle 插件 8.7.3，Gradle 8.9。
- 安卓 12+ 的附近设备权限需要授权；安卓 8–11 需授予定位权限，通常还需打开系统定位服务才能扫描到 BLE。

## 从源码编译安装

1. 将项目 ZIP **完整解压**（不要在压缩包内直接运行）。
2. 用 Android Studio 的 **Open** 打开解压后的 `AC7916_Android_App` 文件夹。
3. 等待 Gradle Sync 完成；若提示缺少 Gradle Wrapper，请使用下方说明先生成 Wrapper。
4. 用手机 USB 数据线连接电脑，手机启用开发者选项和 USB 调试。
5. Android Studio 右上角点击 ▶ Run，即可直接安装到手机。
6. 或执行 **Build > Build Bundle(s) / APK(s) > Build APK(s)**，生成 `app/build/outputs/apk/debug/app-debug.apk`，再复制到安卓手机安装。

**注意：当前提供的是可编辑的 Android Studio 源码工程，不是已编译签名的 APK。当前环境没有 Android SDK/Gradle，无法在这里完成真机编译验证。**

### 如果 Android Studio 提示缺少 Gradle Wrapper

本 ZIP 含 `gradle-wrapper.properties`，但不附带 Gradle 官方的 `gradle-wrapper.jar`。可以选择：

- 在电脑安装 Gradle 8.9，并在项目目录运行 `gradle wrapper --gradle-version 8.9`，生成 `gradlew.bat`、`gradlew` 和官方 `gradle-wrapper.jar`，随后重新同步。
- 或先在 Android Studio 新建一个标准 **Empty Views Activity (Java)** 项目，再把本项目的 `app/src/main` 文件和 `MicPacket.java`、`WaveformView.java`、`BleClient.java`、`MainActivity.java` 放入新工程相同包路径中，并按本工程的 `AndroidManifest.xml` 配置权限。

## 使用步骤

1. 给 AC7916AB 正常供电，确保已经**退出 USB 下载模式**。
2. 打开「双麦听诊」，允许附近设备权限；点击「扫描设备」。
3. 点击 `JL-AC79XX-E70A(BLE)` 之类的设备名。
4. App 自动连接、请求 MTU、订阅 AE3C 和 AE02，不需要手动找服务或点击 0x2902。
5. 点击「ABCD 回传测试」；如固件与之前一样，会显示 `✓ AE01→AE02 ABCD 回传成功`。
6. 如果 AE3C 正常发送 84 字节 MC 包，波形和两路 RMS 会自动显示；否则看「AE3C 真实数据包」计数是否为0，并检查固件采集回调。
7. 需要留存原始诊断包时，点击「保存 AE3C 数据 CSV」，选择手机目录和文件名；结束时再次点击同一按钮。

## 固件对应关系

- AE30 服务 / AE01 Write Without Response / AE02 Notify：原 SDK 回环测试。
- AE3A 服务 / AE3B Write Without Response：控制通道，当前固件无 PING 应答实现。
- AE3A 服务 / AE3C Notify：84 字节 MC V1 双麦采集**摘要**。需要实际 MTU >=87。
- `AE3C` 未收到数据 ≠ BLE 本身无法通信：可先做 AE01→AE02 ABCD 回传测试。
- 音频顺序 **slot0、slot1 不能只凭 ADC 索引认定等于 MIC1、MIC3**。焊接后应分别刺激麦克风并标定。

## CSV 内容

每行：时间戳、包序号、采样率、每通道原始帧数、通道ADC编号、两路 RMS/peak、slot0 的32个有符号抽样点、slot1 的32个有符号抽样点。

**CSV 是诊断波形摘要，不是原始 PCM，也不能恢复成临床听诊音频。**

## 已做验证

- `MicPacket` 协议解析的本地 Java 单元/自检测试已通过（16位小端解析、RMS/peak、signed 8-bit 波形、无效包判定、CSV列数）。
- UI 与 Android BLE 设备交互需要在你的安卓手机上构建和实机测试。

## 后续版本 V0.2

固件升级为支持连续 PCM 或 ADPCM 后，再新增实时播放（AudioTrack）、WAV录音、心音/肺音滤波、降噪前后对比；不要把当前84字节MC包误当作音频进行播放。
