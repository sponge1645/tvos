"""Exercise the production LAN selector with VPN and physical network snapshots."""
from pathlib import Path
import tempfile, subprocess
stubs={
'android/content/Context.java':'''package android.content;public class Context {public static final String CONNECTIVITY_SERVICE="connectivity";public Object service;public Object getSystemService(String n){return service;}}''',
'android/os/SystemClock.java':'''package android.os;public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}''',
'android/net/Network.java':'''package android.net;public class Network {}''',
'android/net/NetworkCapabilities.java':'''package android.net;import java.util.*;public class NetworkCapabilities {public static final int TRANSPORT_WIFI=1,TRANSPORT_ETHERNET=3,TRANSPORT_VPN=4,NET_CAPABILITY_VALIDATED=16;public Set<Integer> transports=new HashSet<>();public boolean validated;public boolean hasTransport(int t){return transports.contains(t);}public boolean hasCapability(int c){return validated;}}''',
'android/net/LinkAddress.java':'''package android.net;import java.net.*;public class LinkAddress {InetAddress address;public LinkAddress(String a)throws Exception {address=InetAddress.getByName(a);}public InetAddress getAddress(){return address;}}''',
'android/net/LinkProperties.java':'''package android.net;import java.util.*;public class LinkProperties {public List<LinkAddress> addresses=new ArrayList<>();public List<LinkAddress> getLinkAddresses(){return addresses;}}''',
'android/net/ConnectivityManager.java':'''package android.net;import java.util.*;public class ConnectivityManager {public Network active,bad;public List<Network> networks=new ArrayList<>();public Map<Network,NetworkCapabilities> caps=new HashMap<>();public Map<Network,LinkProperties> links=new HashMap<>();public int reads;public boolean denied;public Network getActiveNetwork(){reads++;if(denied)throw new SecurityException();return active;}public Network[] getAllNetworks(){return networks.toArray(new Network[0]);}public NetworkCapabilities getNetworkCapabilities(Network n){if(n==bad)throw new IllegalStateException();return caps.get(n);}public LinkProperties getLinkProperties(Network n){return links.get(n);}}''',
'io/tvos/NetworkHarness.java':'''package io.tvos;
import android.net.*;import android.content.Context;import android.os.SystemClock;
public class NetworkHarness {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Network add(ConnectivityManager m,int transport,String ip)throws Exception {
  Network n=new Network();NetworkCapabilities c=new NetworkCapabilities();c.transports.add(transport);c.validated=true;
  LinkProperties l=new LinkProperties();if(ip!=null)l.addresses.add(new LinkAddress(ip));m.networks.add(n);m.caps.put(n,c);m.links.put(n,l);return n;
 }
 public static void main(String[] args)throws Exception {
  ConnectivityManager m=new ConnectivityManager();Network vpn=add(m,4,"198.18.0.1");m.caps.get(vpn).transports.add(3);m.active=vpn;
  Network eth=add(m,3,"192.168.0.111");
  check(LocalNetwork.query(m).address.equals("192.168.0.111")&&LocalNetwork.query(m).ethernet,"VPN advertising ethernet never replaces LAN");
  m.active=eth;check(LocalNetwork.query(m).address.equals("192.168.0.111"),"VPN off preserves same IP");
  m.networks.remove(eth);Network wifi=add(m,1,"192.168.0.112");m.active=vpn;m.caps.get(vpn).transports.add(1);
  check(LocalNetwork.query(m).address.equals("192.168.0.112")&&!LocalNetwork.query(m).ethernet,"Wi-Fi under VPN");
  m.networks.add(eth);m.active=wifi;check(LocalNetwork.query(m).address.endsWith("112"),"active physical Wi-Fi beats ethernet");
  m.active=vpn;check(LocalNetwork.query(m).ethernet,"ethernet wins equal physical candidates behind VPN");
  m.caps.get(eth).validated=false;check(!LocalNetwork.query(m).ethernet,"validated Wi-Fi beats offline ethernet");
  m.networks.remove(wifi);check(LocalNetwork.query(m).ethernet,"LAN without internet still displays local IP");
  m.links.get(eth).addresses.clear();m.links.get(eth).addresses.add(new LinkAddress("169.254.1.2"));
  m.links.get(eth).addresses.add(new LinkAddress("127.0.0.1"));m.links.get(eth).addresses.add(new LinkAddress("0.0.0.0"));
  m.links.get(eth).addresses.add(new LinkAddress("::1"));check(LocalNetwork.query(m)==null,"no loopback/link-local/IPv6 or tunnel fallback");
  m.links.get(eth).addresses.add(new LinkAddress("192.168.0.111"));m.bad=vpn;
  check(LocalNetwork.query(m).ethernet,"one network failing does not hide physical LAN");
  m.links.put(eth,null);check(LocalNetwork.query(m)==null,"disconnected link is skipped");
  check(LocalNetwork.query(null)==null,"absent manager");m.denied=true;check(LocalNetwork.query(m)==null,"permission error");m.denied=false;
  m.links.put(eth,new LinkProperties());m.links.get(eth).addresses.add(new LinkAddress("192.168.0.111"));
  Context c=new Context();c.service=m;int initial=m.reads;SystemClock.now=0;LocalNetwork.read(c);LocalNetwork.read(c);
  check(m.reads==initial+1,"no per-frame binder polling");m.networks.clear();SystemClock.now=1000;
  check(LocalNetwork.read(c)==null,"cache refresh removes disconnected IP");
  System.out.println("PASS: Ethernet/Wi-Fi with VPN on/off, multi-transport VPN rejection, active preference, LAN without internet, address filtering, disconnect races, permission errors, query cache");
 }
}'''}
if __name__ == '__main__':
 with tempfile.TemporaryDirectory() as d:
  root=Path(d);stubs['io/tvos/LocalNetwork.java']=Path('app/src/io/tvos/LocalNetwork.java').read_text()
  for name,s in stubs.items():
   p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
  subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
  subprocess.run(['java','-cp',d,'io.tvos.NetworkHarness'],check=True)
