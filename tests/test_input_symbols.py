"""Execute production HDMI symbol mapping, including Android TV system/custom names."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/HomeApps.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
a=source.index('    private static final String[][] DEVICE_WORDS');b=source.index('    /** Prefer a recognized',a)
body=source[a:b]+method('    static String inputSymbol(')+method('    static String deviceSymbol(')
harness='''import java.util.*;import java.util.regex.*;public class InputSymbolsHarness {
'''+body+'''
 static void check(String actual,String expected){if(!actual.equals(expected))throw new AssertionError(actual+" != "+expected);}
 public static void main(String[] args){
  for(String name:new String[]{"Игровая приставка","HDMI 2 — ИГРОВАЯ ПРИСТАВКА","Игровая консоль","PlayStation 5","Xbox"})check(deviceSymbol(name),"gamepad");
  for(String name:new String[]{"HDMI AUX","AUX","hdmi_aux","HDMI 1 (AUX)","Yamaha receiver"})check(deviceSymbol(name),"speaker");
  for(String name:new String[]{"HDMI 1","FAUX","auxiliary",null,""})check(deviceSymbol(name),"hdmi");
  check(inputSymbol("HDMI 1","Игровая приставка"),"gamepad");check(inputSymbol("HDMI 2","HDMI AUX"),"speaker");
  check(inputSymbol("PlayStation 5","HDMI AUX"),"gamepad");check(inputSymbol("Soundbar","Игровая приставка"),"speaker");
  check(inputSymbol("Streaming box","HDMI AUX"),"play");check(inputSymbol("HDMI 1","HDMI 1"),"hdmi");
  System.out.println("PASS: HDMI system/custom symbol selection, Russian console aliases, AUX boundaries and existing devices");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-input-symbols-') as tmp:
 p=Path(tmp)/'InputSymbolsHarness.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'InputSymbolsHarness'],check=True)
