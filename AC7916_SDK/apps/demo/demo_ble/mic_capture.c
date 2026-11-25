#include "system/includes.h"
#include "app_config.h"
#include "server/audio_server.h"
#include "server/server_core.h"
#include "mic_capture.h"

/* Implemented in apps/common/ble/le_trans_data.c. Uses the existing AE3C Notify characteristic. */
extern int ble_send_audio_notify(const u8 *data, u16 len);

#define MIC_SAMPLE_RATE             16000
#define MIC_CAPTURE_GAIN            CONFIG_AISP_MIC_ADC_GAIN
#define MIC_CAPTURE_CHANNELS        2
#define MIC_WAVE_POINTS             32
#define MIC_PACKET_HEADER_LEN       20
#define MIC_PACKET_LEN              (MIC_PACKET_HEADER_LEN + MIC_WAVE_POINTS * MIC_CAPTURE_CHANNELS)
#define MIC_ENCODER_BUF_LEN         (8 * 1024)

struct mic_capture_context {
    struct server *enc_server;
    volatile u8 running;
    u16 sequence;
    u32 sent_packets;
    u32 dropped_packets;
    volatile u32 pcm_callback_count;
    volatile int enc_open_status; /* 0=not tried; 1=opened; -1=server_open failed; -2=enc request failed */
};

static struct mic_capture_context mic_ctx;
static u8 mic_packet[MIC_PACKET_LEN];

static void put_le16(u8 *p, u16 v)
{
    p[0] = (u8)(v & 0xff);
    p[1] = (u8)(v >> 8);
}

/* Integer square root, avoiding floating point in the encoder callback. */
static u32 isqrt_u64(unsigned long long x)
{
    unsigned long long res = 0;
    unsigned long long bit = 1ULL << 62;

    while (bit > x) {
        bit >>= 2;
    }

    while (bit) {
        if (x >= res + bit) {
            x -= res + bit;
            res = (res >> 1) + bit;
        } else {
            res >>= 1;
        }
        bit >>= 2;
    }
    return (u32)res;
}

static u32 sample_abs(s16 v)
{
    return (v < 0) ? (u32)(-(s32)v) : (u32)v;
}

/*
 * Audio Server PCM callback.
 * PCM is configured as 16-bit, 2-channel, interleaved data.
 * The two selected ADC streams are kept as slot 0 and slot 1; their ADC indexes
 * are included in every packet so the phone can identify the board mapping.
 */
static int mic_audio_vfs_fwrite(void *file, void *data, u32 len)
{
    const s16 *pcm = (const s16 *)data;
    u32 frames;
    u32 i;
    unsigned long long sum0 = 0;
    unsigned long long sum1 = 0;
    u32 peak0 = 0;
    u32 peak1 = 0;
    u16 rms0, rms1;
    int ret;

    if (!mic_ctx.running || !data || len < 4) {
        return (int)len;
    }

    /* One stereo frame is two signed 16-bit samples. */
    frames = len / 4;
    if (!frames) {
        return (int)len;
    }

    mic_ctx.pcm_callback_count++;

    for (i = 0; i < frames; i++) {
        s16 ch0 = pcm[i * 2];
        s16 ch1 = pcm[i * 2 + 1];
        u32 a0 = sample_abs(ch0);
        u32 a1 = sample_abs(ch1);

        sum0 += (unsigned long long)((s32)ch0 * (s32)ch0);
        sum1 += (unsigned long long)((s32)ch1 * (s32)ch1);
        if (a0 > peak0) peak0 = a0;
        if (a1 > peak1) peak1 = a1;
    }

    rms0 = (u16)isqrt_u64(sum0 / frames);
    rms1 = (u16)isqrt_u64(sum1 / frames);

    /* Packet layout (little-endian):
     * 0..1  'M','C'; 2 version; 3 channel count;
     * 4..5  sequence; 6..7 sample rate; 8..9 samples/channel;
     * 10 ADC index for slot0; 11 ADC index for slot1;
     * 12..13 RMS0; 14..15 RMS1; 16..17 peak0; 18..19 peak1;
     * 20..51 32 signed 8-bit waveform points for slot0;
     * 52..83 32 signed 8-bit waveform points for slot1.
     */
    mic_packet[0] = 'M';
    mic_packet[1] = 'C';
    mic_packet[2] = 1;
    mic_packet[3] = MIC_CAPTURE_CHANNELS;
    put_le16(&mic_packet[4], mic_ctx.sequence++);
    put_le16(&mic_packet[6], MIC_SAMPLE_RATE);
    put_le16(&mic_packet[8], (u16)frames);
    mic_packet[10] = CONFIG_AISP_MIC0_ADC_CHANNEL;
    mic_packet[11] = CONFIG_AISP_MIC1_ADC_CHANNEL;
    put_le16(&mic_packet[12], rms0);
    put_le16(&mic_packet[14], rms1);
    put_le16(&mic_packet[16], (u16)peak0);
    put_le16(&mic_packet[18], (u16)peak1);

    for (i = 0; i < MIC_WAVE_POINTS; i++) {
        u32 sample_index = (u32)(((unsigned long long)i * frames) / MIC_WAVE_POINTS);
        s16 ch0 = pcm[sample_index * 2];
        s16 ch1 = pcm[sample_index * 2 + 1];
        mic_packet[MIC_PACKET_HEADER_LEN + i] = (u8)(s8)(ch0 >> 8);
        mic_packet[MIC_PACKET_HEADER_LEN + MIC_WAVE_POINTS + i] = (u8)(s8)(ch1 >> 8);
    }

    ret = ble_send_audio_notify(mic_packet, MIC_PACKET_LEN);
    if (ret == 0) {
        mic_ctx.sent_packets++;
    } else {
        /* No CCC / disconnected / BLE queue full: drop this telemetry frame; never block audio capture. */
        mic_ctx.dropped_packets++;
    }

    return (int)len;
}

static int mic_audio_vfs_fread(void *file, void *data, u32 len)
{
    return 0;
}

static int mic_audio_vfs_fclose(void *file)
{
    return 0;
}

static int mic_audio_vfs_flen(void *file)
{
    return 0;
}

static const struct audio_vfs_ops mic_audio_vfs_ops = {
    .fwrite = mic_audio_vfs_fwrite,
    .fread  = mic_audio_vfs_fread,
    .fclose = mic_audio_vfs_fclose,
    .flen   = mic_audio_vfs_flen,
};

static void mic_audio_event_handler(void *priv, int argc, int *argv)
{
    if (!argv) {
        return;
    }

    if (argv[0] == AUDIO_SERVER_EVENT_ERR) {
        printf("mic audio server error, event=%d\n", argv[0]);
        mic_capture_stop();
    } else if (argv[0] == AUDIO_SERVER_EVENT_END) {
        puts("mic audio server end\n");
        mic_capture_stop();
    }
}

int mic_capture_stop(void)
{
    union audio_req req = {0};

    if (!mic_ctx.enc_server) {
        mic_ctx.running = 0;
        return 0;
    }

    mic_ctx.running = 0;
    req.enc.cmd = AUDIO_ENC_CLOSE;
    server_request(mic_ctx.enc_server, AUDIO_REQ_ENC, &req);
    server_close(mic_ctx.enc_server);
    mic_ctx.enc_server = NULL;
    puts("dual mic capture stopped\n");
    return 0;
}

int mic_capture_start(void)
{
    int err;
    union audio_req req = {0};

#ifndef CONFIG_ALL_ADC_CHANNEL_OPEN_ENABLE
#error "Define CONFIG_ALL_ADC_CHANNEL_OPEN_ENABLE for dual-MIC ADC capture"
#endif

    if (mic_ctx.enc_server) {
        return 0;
    }

    puts("---------- dual MIC1/MIC3 capture start ----------\n");
    printf("ADC map: MIC0=%d, MIC1=%d, rate=%d, gain=%d\n",
           CONFIG_AISP_MIC0_ADC_CHANNEL,
           CONFIG_AISP_MIC1_ADC_CHANNEL,
           MIC_SAMPLE_RATE,
           MIC_CAPTURE_GAIN);

    mic_ctx.enc_server = server_open("audio_server", "enc");
    if (!mic_ctx.enc_server) {
        mic_ctx.enc_open_status = -1;
        puts("open audio_server enc failed\n");
        return -1;
    }

    server_register_event_handler(mic_ctx.enc_server, NULL, mic_audio_event_handler);

    req.enc.cmd = AUDIO_ENC_OPEN;
    req.enc.channel = MIC_CAPTURE_CHANNELS;
    req.enc.channel_bit_map = BIT(CONFIG_AISP_MIC0_ADC_CHANNEL) |
                              BIT(CONFIG_AISP_MIC1_ADC_CHANNEL);
    req.enc.volume = MIC_CAPTURE_GAIN;
    req.enc.output_buf = NULL;
    req.enc.output_buf_len = MIC_ENCODER_BUF_LEN;
    req.enc.sample_rate = MIC_SAMPLE_RATE;
    req.enc.format = "pcm";
    req.enc.frame_size = MIC_SAMPLE_RATE / 100 * 4 * MIC_CAPTURE_CHANNELS;
    req.enc.sample_source = "mic";
    req.enc.vfs_ops = &mic_audio_vfs_ops;
    req.enc.msec = 0; /* continuous capture */

    mic_ctx.running = 1;
    err = server_request(mic_ctx.enc_server, AUDIO_REQ_ENC, &req);
    if (err) {
        mic_ctx.enc_open_status = -2;
        printf("AUDIO_ENC_OPEN failed: %d\n", err);
        mic_ctx.running = 0;
        server_close(mic_ctx.enc_server);
        mic_ctx.enc_server = NULL;
        return -1;
    }

    mic_ctx.enc_open_status = 1;
    printf("dual MIC capture opened: channel_map=0x%02x, frame_size=%d\n",
           req.enc.channel_bit_map, req.enc.frame_size);
    return 0;
}

/* Temporary diagnostic, called by BLE AE01 -> AE3C test (not audio).
 * App V0.1 displays only the length for non-MC packets.
 * 9: server_open failed; 10: AUDIO_ENC_OPEN failed;
 * 11: server_open/request passed, zero PCM callbacks;
 * 12: callbacks ran but no successful 84-byte send;
 * 13: callbacks and at least one send returned success;
 * 14: capture never started / state unknown.
 */
u8 mic_capture_diag_notification_len(void)
{
    if (mic_ctx.enc_open_status == -1) return 9;
    if (mic_ctx.enc_open_status == -2) return 10;
    if (mic_ctx.enc_open_status == 1) {
        if (mic_ctx.pcm_callback_count == 0) return 11;
        if (mic_ctx.sent_packets == 0) return 12;
        return 13;
    }
    return 14;
}
