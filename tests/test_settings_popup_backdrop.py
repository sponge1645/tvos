"""Real backdrop cache and blur, with deterministic bitmap/Canvas adapters."""
from pathlib import Path
import subprocess, tempfile
stubs = {
'android/graphics/Paint.java': 'package android.graphics;public class Paint{public static final int FILTER_BITMAP_FLAG=2;public Paint(int f){}}',
'android/graphics/RectF.java': 'package android.graphics;public class RectF{public void set(float a,float b,float c,float d){}}',
'android/graphics/Path.java': 'package android.graphics;public class Path{public enum Direction{CW}public void reset(){}public void addRoundRect(RectF r,float a,float b,Direction d){}}',
'android/graphics/Bitmap.java': '''package android.graphics;public class Bitmap{public enum Config{ARGB_8888}public static int maxPixels;int[] data;int w;public static Bitmap createBitmap(int w,int h,Config c){maxPixels=Math.max(maxPixels,w*h);Bitmap b=new Bitmap();b.w=w;b.data=new int[w*h];return b;}public void getPixels(int[] p,int o,int s,int x,int y,int w,int h){System.arraycopy(data,0,p,0,p.length);}public void setPixels(int[] p,int o,int s,int x,int y,int w,int h){System.arraycopy(p,0,data,0,p.length);}}''',
'android/graphics/Canvas.java': '''package android.graphics;public class Canvas{public int depth,draws;public Canvas(){}public Canvas(Bitmap b){java.util.Arrays.fill(b.data,0xFF56789A);}public int save(){return depth++;}public void restoreToCount(int n){depth=n;}public void scale(float x,float y){}public void clipPath(Path p){}public void drawBitmap(Bitmap b,Object src,RectF r,Paint p){draws++;}}''',
'android/util/Log.java': 'package android.util;public class Log{public static int w(String t,String m,Throwable e){return 0;}}',
'io/tvos/BackdropHarness.java': '''package io.tvos;import android.graphics.*;import java.lang.reflect.*;import java.util.*;public class BackdropHarness{
static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
public static void main(String[] args)throws Exception{
SettingsPopupBackdrop b=new SettingsPopupBackdrop();int[] calls={0};SettingsPopupBackdrop.Source source=c->calls[0]++;
b.prepare(3840,2160,false,source);b.prepare(3840,2160,false,source);check(calls[0]==1&&Bitmap.maxPixels<=320*180,"one bounded snapshot per session");
Canvas c=new Canvas();b.draw(c,new RectF(),72);check(c.draws==1&&c.depth==0,"clipped draw restores canvas");b.clear();b.draw(c,new RectF(),72);check(c.draws==1,"clear releases image");
b.prepare(1920,1080,false,source);b.prepare(1920,1080,true,source);b.prepare(1280,720,true,source);check(calls[0]==4,"recapture after clear, theme and resize");
Method blur=SettingsPopupBackdrop.class.getDeclaredMethod("blur",int[].class,int[].class,int.class,int.class,boolean.class);blur.setAccessible(true);
for(int w:new int[]{1,2,8,25})for(int h:new int[]{1,3,20})for(boolean horizontal:new boolean[]{false,true}){int[] in=new int[w*h],out=new int[w*h];Arrays.fill(in,0xFF56789A);blur.invoke(null,in,out,w,h,horizontal);check(Arrays.equals(in,out),"uniform colors stay constant including edges and tiny images");}
int[] edge=new int[25],out=new int[25];for(int i=0;i<25;i++)edge[i]=i<12?0xFF000000:0xFFFFFFFF;blur.invoke(null,edge,out,25,1,true);check((out[11]&255)>0&&(out[12]&255)<255,"actual blur softens edge");for(int i=1;i<25;i++)check((out[i]&255)>=(out[i-1]&255),"blur stays monotonic through boundary");
System.out.println("PASS: actual popup backdrop snapshot reuse, bounded 4K allocation, cleanup, theme/resize, rounded clip and blur edges");}}
'''
}
with tempfile.TemporaryDirectory(prefix='tvos-popup-backdrop-') as tmp:
 root=Path(tmp)
 stubs['io/tvos/SettingsPopupBackdrop.java']=Path('app/src/io/tvos/SettingsPopupBackdrop.java').read_text()
 for name,text in stubs.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.BackdropHarness'],check=True)
