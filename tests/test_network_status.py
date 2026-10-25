"""Run real menu network state and callback lifecycle without making network requests."""
from pathlib import Path
import runpy, subprocess, tempfile
stubs=runpy.run_path('tests/test_local_network.py',run_name='network_stubs')['stubs'].copy()
stubs.pop('io/tvos/NetworkHarness.java')
stubs['android/content/Context.java']=stubs['android/content/Context.java'].replace('public Object service;', 'public Context getApplicationContext(){return this;}public Object service;')
stubs['android/os/Looper.java']='package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}'
stubs['android/os/Handler.java']='''package android.os;import java.util.*;public class Handler {
 public static List<Runnable> pending=new ArrayList<>();public Handler(Looper l){}
 public void removeCallbacks(Runnable r){pending.removeIf(x->x==r);}public void postDelayed(Runnable r,long t){pending.add(r);}
 public static void drain(){List<Runnable> copy=new ArrayList<>(pending);pending.clear();for(Runnable r:copy)r.run();}
}'''
stubs['android/net/NetworkRequest.java']='package android.net;public class NetworkRequest {public static class Builder {public Builder clearCapabilities(){return this;}public NetworkRequest build(){return new NetworkRequest();}}}'
manager=stubs['android/net/ConnectivityManager.java']
stubs['android/net/ConnectivityManager.java']=manager[:-1]+'''
 public int registrations,unregistrations;public List<NetworkCallback> callbacks=new ArrayList<>();
 public static class NetworkCallback {public void onAvailable(Network n){}public void onLost(Network n){}public void onCapabilitiesChanged(Network n,NetworkCapabilities c){}public void onLinkPropertiesChanged(Network n,LinkProperties p){}}
 public void registerNetworkCallback(NetworkRequest r,NetworkCallback cb,android.os.Handler h){registrations++;callbacks.add(cb);}
 public void registerDefaultNetworkCallback(NetworkCallback cb,android.os.Handler h){registrations++;callbacks.add(cb);}
 public void unregisterNetworkCallback(NetworkCallback cb){unregistrations++;callbacks.remove(cb);}
}'''
stubs['io/tvos/NetworkStatus.java']=Path('app/src/io/tvos/NetworkStatus.java').read_text()
stubs['io/tvos/StatusHarness.java']='''package io.tvos;
import android.content.Context;import android.net.*;import android.os.Handler;
public class StatusHarness {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Network add(ConnectivityManager m,int transport,boolean validated){
  Network n=new Network();NetworkCapabilities c=new NetworkCapabilities();c.transports.add(transport);c.validated=validated;
  m.networks.add(n);m.caps.put(n,c);return n;
 }
 public static void main(String[] args){
  ConnectivityManager m=new ConnectivityManager();Network wifi=add(m,1,true);m.active=wifi;
  check(NetworkStatus.detect(m)==1,"validated Wi-Fi (including IPv6-only) without address queries");
  m.caps.get(wifi).validated=false;check(NetworkStatus.detect(m)==0,"connected router/captive portal without validated internet");
  m.caps.get(wifi).validated=true;Network eth=add(m,3,true);m.active=eth;
  check(NetworkStatus.detect(m)==2,"active ethernet preferred over Wi-Fi");
  Network vpn=add(m,4,true);m.active=vpn;m.caps.get(vpn).transports.add(1);
  check(NetworkStatus.detect(m)==2,"VPN advertising Wi-Fi cannot replace physical ethernet");
  m.networks.remove(eth);check(NetworkStatus.detect(m)==1,"Wi-Fi under VPN");
  m.caps.get(vpn).validated=false;check(NetworkStatus.detect(m)==0,"default network unvalidated despite validated physical LAN");
  m.caps.get(vpn).validated=true;m.networks.remove(wifi);check(NetworkStatus.detect(m)==0,"VPN alone is not physical LAN");
  m.active=null;check(NetworkStatus.detect(m)==0,"no default network");check(NetworkStatus.detect(null)==0,"no service");
  m.denied=true;check(NetworkStatus.detect(m)==0,"permission denied");m.denied=false;
  m.networks.add(wifi);m.active=wifi;Context c=new Context();c.service=m;int[] changes={0};
  NetworkStatus s=new NetworkStatus(c,()->changes[0]++);s.start();s.start();
  check(s.state()==1&&changes[0]==1&&m.registrations==2,"initial refresh, no duplicate registration");
  ConnectivityManager.NetworkCallback cb=m.callbacks.get(0);m.caps.get(wifi).validated=false;
  cb.onCapabilitiesChanged(wifi,m.caps.get(wifi));cb.onLost(wifi);cb.onAvailable(wifi);
  check(Handler.pending.size()==1,"coalesce connectivity events");Handler.drain();
  check(s.state()==0&&changes[0]==2,"internet loss redraws status");
  cb.onAvailable(wifi);Handler.drain();check(changes[0]==2,"unchanged state does not redraw");
  cb.onAvailable(wifi);s.stop();s.stop();cb.onAvailable(wifi);Handler.drain();
  check(m.unregistrations==2&&Handler.pending.isEmpty()&&changes[0]==2,"pause cancels events and releases callbacks once");
  m.caps.get(wifi).validated=true;s.start();check(s.state()==1&&changes[0]==3&&m.registrations==4,"resume takes fresh snapshot");s.stop();
  System.out.println("PASS: three menu states, validation, dual transport, VPN, IPv6-only LAN, callback coalescing, pause/resume cleanup");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory)
 for name,s in stubs.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.StatusHarness'],check=True)
