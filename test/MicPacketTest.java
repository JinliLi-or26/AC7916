import com.ac7916.stethoscope.MicPacket;
public class MicPacketTest {
    static void check(boolean yes) { if (!yes) throw new AssertionError(); }
    public static void main(String[] args) {
        byte[] d=new byte[84]; d[0]='M'; d[1]='C'; d[2]=1;d[3]=2;
        d[4]=(byte)0xFE;d[5]=(byte)0xFF; d[6]=(byte)0x80;d[7]=0x3e;d[8]=0x40;d[9]=0x01;
        d[10]=1;d[11]=0;d[12]=0x45;d[13]=0x03;d[14]=0x12;d[16]=(byte)0xff;d[17]=0x7f;
        d[20]=(byte)0x80;d[52]=0x7f;
        MicPacket p=MicPacket.parse(d);
        check(p!=null && p.sequence==65534 && p.sampleRate==16000 && p.frames==320);
        check(p.adc0==1 && p.adc1==0 && p.rms0==837 && p.rms1==18 && p.peak0==32767);
        check(p.wave0[0]==-128 && p.wave1[0]==127);
        check(p.csvLine(123).split(",").length==74);
        d[0]=0;check(MicPacket.parse(d)==null);
        check(MicPacket.parse(new byte[83])==null);
        System.out.println("MicPacket parser tests passed");
    }
}
