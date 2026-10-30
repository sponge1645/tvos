"""Actual preloaded navigation sound routing, readiness, mute preference and stream cleanup."""
from pathlib import Path
import subprocess,tempfile,wave
files={
'io/tvos/Click.java':Path('app/src/io/tvos/Click.java').read_text(),
'io/tvos/AppLog.java':'package io.tvos;class AppLog {static void w(String s){}}',
'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public boolean enabled=true;public Context getApplicationContext(){return this;}public Context getSharedPreferences(String s,int i){return this;}public boolean getBoolean(String s,boolean d){return enabled;}public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}}''',
'android/content/res/AssetFileDescriptor.java':'package android.content.res;public class AssetFileDescriptor implements AutoCloseable {public java.io.FileDescriptor getFileDescriptor(){return new java.io.FileDescriptor();}public long getStartOffset(){return 0;}public long getLength(){return 100;}public void close(){}}',
'android/content/res/AssetManager.java':'package android.content.res;public class AssetManager {public static java.util.List<String> opened=new java.util.ArrayList<>();public AssetFileDescriptor openFd(String s)throws java.io.IOException{opened.add(s);return new AssetFileDescriptor();}}',
'android/media/AudioAttributes.java':'package android.media;public class AudioAttributes {public static final int USAGE_ASSISTANCE_SONIFICATION=1,CONTENT_TYPE_SONIFICATION=2;public static class Builder {public Builder setUsage(int i){return this;}public Builder setContentType(int i){return this;}public AudioAttributes build(){return new AudioAttributes();}}}',
'android/media/SoundPool.java':'''package android.media;public class SoundPool {public interface OnLoadCompleteListener {void onLoadComplete(SoundPool p,int id,int status);}public static SoundPool last;public OnLoadCompleteListener listener;public int loads,plays,lastSound,stops;public boolean released;public static class Builder {public Builder setMaxStreams(int n){return this;}public Builder setAudioAttributes(AudioAttributes a){return this;}public SoundPool build(){return last=new SoundPool();}}public void setOnLoadCompleteListener(OnLoadCompleteListener l){listener=l;}public int load(java.io.FileDescriptor f,long o,long n,int p){return ++loads;}public int play(int id,float l,float r,int p,int repeat,float rate){plays++;lastSound=id;return plays;}public void stop(int id){stops++;}public void release(){released=true;}}''',
'io/tvos/SoundHarness.java':'''package io.tvos;import android.media.SoundPool;public class SoundHarness {static void check(boolean b,String s){if(!b)throw new AssertionError(s);}public static void main(String[] args){android.content.Context c=new android.content.Context();Click click=new Click(c);SoundPool p=SoundPool.last;check(p.loads==4&&android.content.res.AssetManager.opened.equals(java.util.List.of("sounds/move_button.wav","sounds/move_app.wav","sounds/open.wav","sounds/back.wav")),"four preloaded assets");click.move();check(p.plays==0,"no play before asynchronous load completion");for(int i=1;i<=4;i++)p.listener.onLoadComplete(p,i,0);click.move();check(p.lastSound==1,"button focus sample");click.app();check(p.lastSound==2&&p.stops==1,"Dock focus sample replaces prior movement");click.open();check(p.lastSound==3&&p.stops==2,"open uses action sample and stops movement");click.back();check(p.lastSound==4&&p.stops==3,"back has a distinct sample");c.enabled=false;click.move();click.open();click.back();check(p.plays==4,"existing mute preference applies to every event");click.release();click.move();check(p.released&&p.listener==null&&p.plays==4,"release detaches callbacks and prevents playback");System.out.println("PASS: navigation samples, readiness, preference and bounded overlapping streams");}}'''}
for p in Path('app/assets/sounds').glob('*.wav'):
 with wave.open(str(p)) as w:
  assert w.getsampwidth()==2 and w.getnchannels()==2 and w.getframerate()==48000
  assert w.getnframes()*w.getsampwidth()*w.getnchannels()<1000000
assert '-0 wav' in Path('build.sh').read_text()
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,s in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.SoundHarness'],check=True,timeout=10)
