# 当前 AC7916AB 固件 AE3C / MC V1 数据格式

来源：项目双麦补丁 `apps/demo/demo_ble/mic_capture.c` 中 `mic_audio_vfs_fwrite()`。

- GATT 服务 `0xAE3A`，数据特征 `0xAE3C`，`Notify`。
- 需要 GATT MTU >= 87；App 会请求 200，但以手机最终协商值为准。
- 每个 MC 数据包正好 **84 字节**，小端序。

| 偏移 | 长度 | 类型 | 含义 |
|---:|---:|---|---|
| 0 | 2 | ASCII | `M` `C` (hex `4D 43`) |
| 2 | 1 | u8 | 版本 `1` |
| 3 | 1 | u8 | 通道数 `2` |
| 4 | 2 | u16 LE | 包序号，65535后回绕 |
| 6 | 2 | u16 LE | 采样率，现为16000 |
| 8 | 2 | u16 LE | 该PCM回调内每通道帧数 |
| 10 | 1 | u8 | slot0 绑定ADC通道索引 |
| 11 | 1 | u8 | slot1 绑定ADC通道索引 |
| 12 | 2 | u16 LE | slot0 RMS |
| 14 | 2 | u16 LE | slot1 RMS |
| 16 | 2 | u16 LE | slot0 peak |
| 18 | 2 | u16 LE | slot1 peak |
| 20 | 32 | int8 ×32 | slot0 波形抽样（PCM右移8位） |
| 52 | 32 | int8 ×32 | slot1 波形抽样 |

> 必须分别对实际 MIC1 与 MIC3 施加声学刺激，才能映射 slot0/1 到物理麦克风；不能仅凭 `CONFIG_AISP_MIC0_ADC_CHANNEL=1` 和 `CONFIG_AISP_MIC1_ADC_CHANNEL=0` 推断。

## 为什么当前不能实现播放和WAV录音

采样率16kHz双路原始PCM意味着每秒每通道16000个16位采样点，而本包每次只保留32个8位下采样点。没有其余样本，因此无法高保真重建声音。后续固件必须提供连续 PCM 或音频压缩码流。

## 当前已确认的回环测试

AE01 写入 `41 42 43 44`（ASCII `ABCD`）→ AE02 Notify 返回相同4字节；本 App 有专用按钮。

AE3B 原固件仅打印接收到的数据，不会对 `PING` 自动回 `AE3C_OK`，因此当前版本 App **没有**把该 PING 功能当成已存在的功能。
