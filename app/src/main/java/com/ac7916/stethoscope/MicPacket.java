package com.ac7916.stethoscope;

import java.util.Locale;

/** Wire protocol from the user's AC7916AB_dual_mic_ble_patch.zip / mic_capture.c. */
public final class MicPacket {
    public static final int SIZE = 84;
    public final int sequence, sampleRate, frames, adc0, adc1, rms0, rms1, peak0, peak1;
    public final int[] wave0 = new int[32], wave1 = new int[32];

    private MicPacket(int sequence, int sampleRate, int frames, int adc0, int adc1,
                      int rms0, int rms1, int peak0, int peak1) {
        this.sequence=sequence; this.sampleRate=sampleRate; this.frames=frames;
        this.adc0=adc0; this.adc1=adc1; this.rms0=rms0; this.rms1=rms1;
        this.peak0=peak0; this.peak1=peak1;
    }
    private static int u8(byte[] d, int i) { return d[i] & 0xFF; }
    private static int u16(byte[] d, int i) { return u8(d,i) | (u8(d,i+1)<<8); }

    public static MicPacket parse(byte[] data) {
        if (data == null || data.length != SIZE || data[0]!='M' || data[1]!='C' ||
            u8(data,2)!=1 || u8(data,3)!=2) return null;
        int rate=u16(data,6), frames=u16(data,8);
        if (rate<1000 || frames==0) return null;
        MicPacket p=new MicPacket(u16(data,4), rate, frames, u8(data,10), u8(data,11),
              u16(data,12), u16(data,14), u16(data,16), u16(data,18));
        for (int i=0;i<32;i++) {
            p.wave0[i]=data[20+i]; // signed 8-bit as in C `(s8)(ch0 >> 8)`
            p.wave1[i]=data[52+i];
        }
        return p;
    }
    public String csvLine(long timestampMillis) {
        StringBuilder sb=new StringBuilder(320);
        sb.append(timestampMillis).append(',').append(sequence).append(',').append(sampleRate)
          .append(',').append(frames).append(',').append(adc0).append(',').append(adc1)
          .append(',').append(rms0).append(',').append(rms1)
          .append(',').append(peak0).append(',').append(peak1);
        for (int x:wave0) sb.append(',').append(x);
        for (int x:wave1) sb.append(',').append(x);
        return sb.append('\n').toString();
    }
    public static String csvHeader() {
        StringBuilder s=new StringBuilder("timestamp_ms,sequence,sample_rate,frames,adc_slot0,adc_slot1,rms_slot0,rms_slot1,peak_slot0,peak_slot1");
        for(int i=0;i<32;i++) s.append(String.format(Locale.US,",slot0_%02d",i));
        for(int i=0;i<32;i++) s.append(String.format(Locale.US,",slot1_%02d",i));
        return s.append('\n').toString();
    }
}
