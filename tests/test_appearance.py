"""Settings and home share sunrise/sunset boundaries and explicit theme overrides."""
from pathlib import Path
import os
import subprocess
import tempfile


def method(filename, signature):
    source = Path('app/src/io/tvos', filename + '.java').read_text()
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


harness = '''package io.tvos;
public class AppearanceHarness {
 static class Prefs {static String mode="auto";static String theme(){return mode;}}
 static class Shaders {
''' + method('Shaders', '    static long timeOfDay(') + '''
 }
 static class HomeData {
  static final HomeData instance=new HomeData();
  static HomeData get(Object context){return instance;}
  static class Weather {long sunrise,sunset;}
  final Weather weather=new Weather();
''' + method('HomeData', '    boolean isNight(') + '''
 }
 boolean lightAppearance;
 Object getContext(){return this;}
''' + method('SettingsActivity', '        private void updateAppearance(') + method(
    'SettingsActivity', '        private boolean lightSettings(') + '''
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static long time(int h,int m){return 86_400_000L+(h*60L+m)*60_000L;}
 void expect(int h,int m,boolean light){
  long now=time(h,m);updateAppearance(now);
  check(lightSettings()==light,"settings appearance at "+h+":"+m);
  check(lightSettings()!=HomeData.instance.isNight(now),"home/settings agreement");
 }
 public static void main(String[] args){
  java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
  AppearanceHarness h=new AppearanceHarness();
  HomeData.instance.weather.sunrise=time(8,10);
  HomeData.instance.weather.sunset=time(17,20);
  h.expect(7,30,false);h.expect(8,9,false);h.expect(8,10,true);
  h.expect(17,19,true);h.expect(17,20,false);h.expect(18,0,false);
  h.expect(23,59,false);h.expect(0,0,false);
  Prefs.mode="light";h.expect(23,0,true);
  Prefs.mode="dark";h.expect(12,0,false);
  Prefs.mode="auto";HomeData.instance.weather.sunrise=0;HomeData.instance.weather.sunset=0;
  h.expect(5,59,false);h.expect(6,0,true);h.expect(17,59,true);h.expect(18,0,false);
  HomeData.instance.weather.sunset=time(16,30);h.expect(17,0,false);
  System.out.println("PASS: shared automatic theme, exact sun boundaries, midnight, overrides, missing/updated weather");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-appearance-') as directory:
    path = Path(directory) / 'AppearanceHarness.java'
    path.write_text(harness)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory, str(path)], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.AppearanceHarness'], check=True)
