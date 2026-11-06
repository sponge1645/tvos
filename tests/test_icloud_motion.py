"""Actual photo frame scheduling and transition duration, without a display runtime."""
from pathlib import Path
from tmdb_metadata_fixture import block
import subprocess,tempfile
s=Path('app/src/io/tvos/DreamView.java').read_text()
methods=block(s,'    @Override public void doFrame(')+block(s,'    private long fadeDuration(')
h='''interface Frame {void doFrame(long time);}public class MotionHarness implements Frame {
 static class Settings {String source="icloud",style="kenburns";boolean reduced;String getString(String k,String d){return k.equals("video_dream_source")?source:style;}boolean getBoolean(String k,boolean d){return reduced;}}
 final Settings settings=new Settings();boolean running=true;Object incoming;long lastFrame;int frames;static final long FADE_MS=3000;
 void invalidate(){frames++;}static class Choreographer {static Choreographer getInstance(){return new Choreographer();}void postFrameCallback(Frame f){}}
 METHODS
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}public static void main(String[] args){MotionHarness h=new MotionHarness();h.doFrame(16_666_667);h.doFrame(33_333_334);check(h.frames==2,"Ken Burns updates on each display frame");check(h.fadeDuration()==1000,"iCloud transitions last one second");h.settings.style="classic";h.frames=0;h.lastFrame=0;h.doFrame(16_666_667);check(h.frames==0,"idle classic photo retains frame limiter");h.incoming=new Object();h.doFrame(33_333_334);check(h.frames==1,"crossfade updates on display frame");h.settings.source="google";h.lastFrame=0;h.frames=0;h.doFrame(16_666_667);check(h.frames==0&&h.fadeDuration()==3000,"other sources retain established timing");h.running=false;h.doFrame(100_000_000);check(h.frames==0,"stopped dream schedules no drawing");System.out.println("PASS: iCloud display-frame motion, one-second transitions, idle limiter and other-source timing");}}
'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'MotionHarness.java';p.write_text(h)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,str(p)],check=True)
 subprocess.run(['java','-cp',d,'MotionHarness'],check=True)
