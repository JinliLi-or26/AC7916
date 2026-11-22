package com.ac7916.stethoscope;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Android handset UI for the currently-flashed 84-byte MC BLE telemetry firmware. */
public class MainActivity extends Activity implements BleClient.Listener {
    private static final int PERMISSION_REQUEST=41, CREATE_CSV=42;
    private final int BACK=Color.rgb(11,21,36), CARD=Color.rgb(22,37,55), SUB=Color.rgb(143,167,186);
    private final int TEAL=Color.rgb(72,216,194), WHITE=Color.rgb(232,242,252), BLUE=Color.rgb(117,167,255);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BleClient ble;
    private TextView state, mtuText, rxText, dropText, lastText, mic0, mic1, phaseText, logView, recordButton;
    private WaveformView waveform;
    private LinearLayout deviceList;
    private Button scanBtn, disconnectBtn, demoBtn;
    private long count=0, lost=0, malformed=0, beginMs=0, lastMs=0, lastUiMs=0;
    private int previousSeq=-1, mtu=23;
    private boolean connected=false, notifyReady=false, demo=false, scanPlaceholder=false;
    private long demoCount=0, savedRows=0;
    private BufferedWriter writer;
    private final StringBuilder log=new StringBuilder();
    private final Runnable demoStep=new Runnable() {
        @Override public void run(){
            if(!demo)return;
            byte[] b=new byte[84];b[0]='M';b[1]='C';b[2]=1;b[3]=2;
            int seq=(int)(demoCount++ & 65535), rate=16000, frames=320;
            put16(b,4,seq);put16(b,6,rate);put16(b,8,frames);b[10]=1;b[11]=0;
            put16(b,12,500+(int)(350*Math.abs(Math.sin(seq*.11))));
            put16(b,14,120+(int)(180*Math.abs(Math.sin(seq*.19))));
            put16(b,16,1500);put16(b,18,900);
            for(int i=0;i<32;i++) {
                b[20+i]=(byte)(Math.sin(seq*.23+i*.35)*Math.exp(-Math.pow((i-9.)/6.,2))*110);
                b[52+i]=(byte)(Math.sin(seq*.17+i*.6)*Math.exp(-Math.pow((i-16.)/9.,2))*60);
            }
            onMcPacket(b,true);
            handler.postDelayed(this,110);
        }
    };
    private static void put16(byte[] b,int i,int v){b[i]=(byte)v;b[i+1]=(byte)(v>>>8);}

    @Override public void onCreate(Bundle stateBundle){
        super.onCreate(stateBundle);
        getWindow().setStatusBarColor(BACK);getWindow().setNavigationBarColor(BACK);
        ble=new BleClient(this,this);
        createScreen();
        appendLog("应用已启动。当前固件仅提供波形摘要，尚不支持实时音频播放。");
    }
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String s,int sp,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setLineSpacing(dp(2),1f);return v;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void gap(LinearLayout parent,int height){ View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    private void card(LinearLayout root,LinearLayout inside){
        inside.setBackground(bg(CARD,18)); inside.setPadding(dp(16),dp(15),dp(16),dp(15));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(14);root.addView(inside,lp);
    }
    private Button button(String name,int color){
        Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextSize(14);b.setTextColor(color==TEAL?BACK:WHITE);
        b.setBackground(bg(color,12));b.setPadding(dp(6),0,dp(6),0);b.setMinHeight(dp(46));
        return b;
    }
    private void addRowButton(LinearLayout row,Button b){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);
        if(row.getChildCount()>0)lp.leftMargin=dp(8);
        row.addView(b,lp);
    }
    private void createScreen(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BACK);
        LinearLayout root=vertical();root.setPadding(dp(18),dp(26),dp(18),dp(28));
        scroll.addView(root);setContentView(scroll);
        TextView header=text("双麦听诊",29,WHITE,true);root.addView(header);
        TextView headSub=text("AC7916AB  ·  BLE 诊断台  ·  V0.1",12,SUB,false);root.addView(headSub);
        gap(root,9);
        TextView note=text("当前版本：BLE连接 / 84字节双麦数据 / 波形 / CSV记录",13,TEAL,false);root.addView(note);

        LinearLayout connection=vertical();card(root,connection);
        connection.addView(text("设备连接",18,WHITE,true));gap(connection,7);
        state=text("● 未连接",15,SUB,true);connection.addView(state);
        phaseText=text("自动识别 JL-AC79XX 蓝牙设备",12,SUB,false);connection.addView(phaseText);
        gap(connection,12);
        LinearLayout buttons=horizontal();connection.addView(buttons);
        scanBtn=button("扫描设备",TEAL);scanBtn.setOnClickListener(v->requestAndScan());addRowButton(buttons,scanBtn);
        disconnectBtn=button("断开连接",Color.rgb(59,78,98));disconnectBtn.setOnClickListener(v->ble.disconnect());disconnectBtn.setEnabled(false);addRowButton(buttons,disconnectBtn);
        deviceList=vertical();connection.addView(deviceList);

        LinearLayout link=vertical();card(root,link);
        link.addView(text("链路状态",18,WHITE,true));gap(link,10);
        mtuText=text("MTU：23（等待协商）",13,SUB,false);link.addView(mtuText);
        rxText=text("AE3C 数据包：0",14,WHITE,true);link.addView(rxText);
        dropText=text("序号缺口：0   解析异常：0",12,SUB,false);link.addView(dropText);
        lastText=text("最近数据：尚未收到",12,SUB,false);link.addView(lastText);
        gap(link,8);
        TextView explain=text("提示：已连接 ≠ 有麦克风数据。如果 AE01 回传成功而 AE3C=0，应检查固件音频回调。",12,SUB,false);link.addView(explain);

        LinearLayout signal=vertical();card(root,signal);
        signal.addView(text("双通道采集监视",18,WHITE,true));
        TextView statusTag=text("信号槽位与物理 MIC1/MIC3 尚需逐路实测确认",11,SUB,false);signal.addView(statusTag);gap(signal,10);
        mic0=text("通道 A（ADC ?）：RMS —  |  峰值 —",15,TEAL,true);signal.addView(mic0);
        mic1=text("通道 B（ADC ?）：RMS —  |  峰值 —",15,BLUE,true);signal.addView(mic1);
        gap(signal,7);
        waveform=new WaveformView(this);signal.addView(waveform,new LinearLayout.LayoutParams(-1,dp(225)));
        TextView sampling=text("每通道仅32个抽样点；这是测试波形，不是连续PCM录音。",11,SUB,false);signal.addView(sampling);

        LinearLayout tools=vertical();card(root,tools);
        tools.addView(text("快捷测试",18,WHITE,true));gap(tools,10);
        LinearLayout toolRow=horizontal();tools.addView(toolRow);
        Button echo=button("ABCD 回传测试",Color.rgb(43,91,111));echo.setOnClickListener(v->ble.sendEchoTest());addRowButton(toolRow,echo);
        demoBtn=button("演示数据",Color.rgb(43,91,111));demoBtn.setOnClickListener(v->toggleDemo());addRowButton(toolRow,demoBtn);
        gap(tools,9);
        LinearLayout saveRow=horizontal();tools.addView(saveRow);
        Button save=button("保存 AE3C 数据 CSV",Color.rgb(52,78,95));save.setOnClickListener(v->toggleSaving());addRowButton(saveRow,save);
        recordButton=text("未记录 · 保存的是摘要采样，非音频 WAV",12,SUB,false);tools.addView(recordButton);
        gap(tools,12);
        TextView audioTitle=text("实时播放 / WAV 录音",15,SUB,true);tools.addView(audioTitle);
        TextView audioHelp=text("暂不可用：现有固件只有每包32点抽样，不能还原16 kHz完整音频。后续升级连续PCM/ADPCM固件后再开启。",12,SUB,false);tools.addView(audioHelp);

        LinearLayout logCard=vertical();card(root,logCard);
        logCard.addView(text("运行日志",18,WHITE,true));gap(logCard,7);
        logView=text("",12,SUB,false);logView.setTypeface(Typeface.MONOSPACE);logCard.addView(logView);
        gap(root,15);
        root.addView(text("本地数据 · 无账号 · 不上传云端 · 仅供工程验证，非医疗诊断",11,SUB,false));
    }
    private void appendLog(String value){
        String time=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date());
        String newLine=time+"  "+value+"\n";
        log.insert(0,newLine);
        if(log.length()>2800)log.setLength(2800);
        if(logView!=null)logView.setText(log.toString());
    }
    private boolean granted(){
        if(Build.VERSION.SDK_INT>=31){
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestAndScan(){
        if(!granted()){
            if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},PERMISSION_REQUEST);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},PERMISSION_REQUEST);
            return;
        }
        if(!ble.bluetoothAvailable()){
            BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter!=null){
                Intent enable=new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enable,23);
            }else appendLog("此设备没有可用的蓝牙适配器");
            return;
        }
        deviceList.removeAllViews();scanPlaceholder=true;
        TextView tip=text("扫描 12 秒，请确保开发板正常运行 BLE 广播...",12,SUB,false);
        deviceList.addView(tip);
        ble.startScan();
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(req,permissions,grants);
        if(req==PERMISSION_REQUEST){if(granted())requestAndScan();else appendLog("未获得蓝牙权限，无法扫描");}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==23 && resultCode==RESULT_OK)requestAndScan();
        if(requestCode==CREATE_CSV && resultCode==RESULT_OK && data!=null && data.getData()!=null){
            try{
                writer=new BufferedWriter(new OutputStreamWriter(getContentResolver().openOutputStream(data.getData()),StandardCharsets.UTF_8),16384);
                writer.write(MicPacket.csvHeader());writer.flush();savedRows=0;
                recordButton.setText("● 正在记录 AE3C 数据到 CSV");recordButton.setTextColor(TEAL);
                appendLog("CSV 已开始；仅保存真实 AE3C 数据包");
            }catch(Exception e){writer=null;appendLog("创建文件失败："+e.getMessage());}
        }
    }
    private void toggleSaving(){
        if(writer!=null){closeWriter();return;}
        if(demo){appendLog("演示模式不能保存为真实采集，请先结束演示");return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/csv");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE,"AC7916_MC_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".csv");
        startActivityForResult(i,CREATE_CSV);
    }
    private void closeWriter(){
        if(writer==null)return;
        try{writer.flush();writer.close();appendLog("CSV已保存："+savedRows+" 条真实MC数据包");}
        catch(Exception e){appendLog("文件关闭异常："+e.getMessage());}
        writer=null;recordButton.setText("未记录 · 点击按钮可重新保存CSV");recordButton.setTextColor(SUB);
    }
    private void toggleDemo(){
        demo=!demo;demoBtn.setText(demo?"停止演示":"演示数据");
        if(demo){appendLog("开启模拟演示：不是设备数据");phaseText.setText("⚠ 演示模式 · 数据为本地生成");handler.post(demoStep);}
        else{handler.removeCallbacks(demoStep);waveform.clearData();mic0.setText("通道 A：等待真实数据");mic1.setText("通道 B：等待真实数据");appendLog("演示已停止，已清除模拟波形");phaseText.setText(connected?"BLE 连接中，等待真实通知":"设备未连接");}
    }
    @Override public void found(BluetoothDevice device,String name){
        if(scanPlaceholder){deviceList.removeAllViews();scanPlaceholder=false;}
        TextView row=text("连接  "+name+"\n"+device.getAddress(),13,WHITE,true);
        row.setBackground(bg(Color.rgb(40,61,79),12));row.setPadding(dp(12),dp(13),dp(12),dp(13));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);deviceList.addView(row,lp);
        row.setOnClickListener(v->{if(demo)toggleDemo();resetStats();ble.connect(device);});
    }
    private void resetStats(){count=lost=malformed=0;previousSeq=-1;lastMs=beginMs=lastUiMs=0;waveform.clearData();updateStats();}
    @Override public void connected(boolean c){
        connected=c;state.setText(c?"● 已连接":"● 未连接");state.setTextColor(c?TEAL:SUB);
        disconnectBtn.setEnabled(c);scanBtn.setEnabled(!c);
        if(!c && !demo){phaseText.setText("等待连接设备");notifyReady=false;}
        if(c)appendLog("GATT连接成功，自动协商MTU并订阅AE3C/AE02");
    }
    @Override public void mtu(int m){mtu=m;mtuText.setText("MTU："+mtu+(mtu>=87?" ✓ 可容纳84字节":" ⚠ 不足以发送84字节"));}
    @Override public void notificationReady(boolean ready){
        notifyReady=ready;
        if(ready){
            appendLog("AE3C Notify已开启，等待真实数据");
            handler.postDelayed(()->{
                if(connected && notifyReady && count==0 && !demo){
                    phaseText.setText("AE3C已订阅但未收到数据；请排查固件音频回调");
                    appendLog("5秒内无AE3C数据：连接/MTU正常不代表ADC采集成功");
                }
            },5000);
        }
    }
    @Override public void status(String message){appendLog(message);if(!demo)phaseText.setText(message);}
    @Override public void echoData(byte[] d){
        if(d.length==4 && d[0]=='A'&&d[1]=='B'&&d[2]=='C'&&d[3]=='D')appendLog("✓ AE01→AE02 ABCD 回传成功");
        else appendLog("收到 AE02："+d.length+" 字节");
    }
    @Override public void micData(byte[] data){
        if(demo)return; // demo cannot be mistaken for real data
        onMcPacket(data,false);
    }
    private void onMcPacket(byte[] data,boolean synthetic){
        MicPacket packet=MicPacket.parse(data);
        if(packet==null){
            if(!synthetic){malformed++;appendLog("AE3C收到"+data.length+"字节，但不是有效84字节MC v1包");updateStats();}
            return;
        }
        long now=System.currentTimeMillis();
        if(!synthetic){
            if(previousSeq>=0){int delta=(packet.sequence-previousSeq)&0xFFFF;if(delta>1 && delta<32768)lost+=delta-1;}
            previousSeq=packet.sequence;count++;
            if(beginMs==0)beginMs=now;
            lastMs=now;
            if(writer!=null){
                try{writer.write(packet.csvLine(now));savedRows++;if(savedRows%25==0)writer.flush();}
                catch(Exception e){appendLog("CSV写入失败："+e.getMessage());closeWriter();}
            }
        }
        waveform.setData(packet);
        if(now-lastUiMs>=120){
            mic0.setText("通道 A（ADC"+packet.adc0+"）：RMS "+packet.rms0+"  |  峰值 "+packet.peak0);
            mic1.setText("通道 B（ADC"+packet.adc1+"）：RMS "+packet.rms1+"  |  峰值 "+packet.peak1);
            if(synthetic)lastText.setText("⚠ 演示波形 · 非设备实测");
            else lastText.setText("最近数据："+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(now))+
                              "  |  Seq "+packet.sequence+"  |  "+packet.sampleRate+"Hz");
            updateStats();lastUiMs=now;
        }
    }
    private void updateStats(){
        rxText.setText("AE3C 真实数据包："+count);
        dropText.setText("序号缺口："+lost+"   解析异常："+malformed+
                         ((beginMs>0&&lastMs>beginMs)?"   平均 "+String.format(Locale.US,"%.1f",count*1000.0/(lastMs-beginMs))+"包/秒":""));
    }
    @Override public void onDestroy(){
        demo=false;handler.removeCallbacks(demoStep);closeWriter();ble.close();super.onDestroy();
    }
}
