package io.tvos.remote;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure raw-input state. No manufacturer names, Android callbacks or audio-stream assumptions. */
public final class RawVolumeState {
    public interface Sink { void step(int direction); void learned(String token, int direction, String device, int scan); }
    private static final Pattern DEVICE = Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
    private static final Pattern NAME = Pattern.compile("\\s*name:\\s*\"([^\"]+)\".*");
    private static final Pattern KEY = Pattern.compile(".*(/dev/input/event\\d+):\\s+0001\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})\\s*");
    private final Map<String,String> names = new HashMap<>();
    private final Map<String,Integer> mapped = new HashMap<>();
    private final Sink sink;
    private String pendingDevice="", held="", candidate="", token="", completed="";
    private int direction, learning, candidateScan;
    private String candidateDevice="";
    private long next, limit, lease;
    public RawVolumeState(Sink sink) { this.sink=sink; }
    private static String id(String device,int scan) { return device+"\u0000"+scan; }
    public synchronized void map(String device,int scan,int dir) {
        if (!device.isEmpty() && device.length()<=256 && scan>0 && scan<=65535 && (dir==1||dir==-1)) mapped.put(id(device,scan),dir);
    }
    public synchronized void learn(int dir,String session,long now) {
        if ((dir!=1&&dir!=-1)||session==null||!session.matches("[a-f0-9-]{36}")) { learning=0;candidate="";return; }
        if (!session.equals(token)||learning!=dir) { candidate=""; cancel(); }
        token=session;learning=dir;lease=now+1500;
    }
    public synchronized void line(String line,long now) {
        Matcher m=DEVICE.matcher(line);
        if(m.matches()){pendingDevice=m.group(1);return;}
        m=NAME.matcher(line);
        if(m.matches()&&!pendingDevice.isEmpty()){names.put(pendingDevice,m.group(1));pendingDevice="";return;}
        m=KEY.matcher(line);if(!m.matches())return;
        String device=names.get(m.group(1));if(device==null)return;
        int scan=Integer.parseInt(m.group(2),16);long value=Long.parseLong(m.group(3),16);
        if(value>2)return;
        String key=id(device,scan);
        if(learning!=0&&now<lease&&!token.equals(completed)) {
            cancel();
            if(value==1&&candidate.isEmpty()){candidate=key;candidateScan=scan;candidateDevice=device;}
            else if(value==0&&key.equals(candidate)) {
                completed=token;map(device,candidateScan,learning);
                sink.learned(token,learning,candidateDevice,candidateScan);candidate="";
            }
            return;
        }
        Integer dir=mapped.get(key);
        if(dir==null){if(scan==115)dir=1;else if(scan==114)dir=-1;}
        if(value==0){if(key.equals(held))cancel();return;}
        if(value==2){if(key.equals(held))cancel();return;} // native hardware autorepeat owns this press
        if(dir==null){cancel();return;}
        if(key.equals(held))return;
        held=key;direction=dir;next=now+600;limit=now+30000;
    }
    public synchronized void tick(long now) {
        if(held.isEmpty())return;
        if(now>=limit){cancel();return;}
        if(now>=next){next=now+80;sink.step(direction);}
    }
    public synchronized long delayMillis(){return held.isEmpty()?200:20;}
    public synchronized void cancel(){held="";direction=0;}
}
