"""Exercise focus-owned QR crossfade and listener cancellation with the actual panel renderer."""
from pathlib import Path
import tempfile,subprocess
from settings_list_renderer_fixture import graphics
from settings_strings_fixture import sources as strings
f=graphics();f.update(strings())
f['android/graphics/Canvas.java']=f['android/graphics/Canvas.java'].replace('public void drawRect(float', 'public void drawRoundRect(float l,float t,float r,float b,float rx,float ry,Paint p){drawRoundRect(new RectF(l,t,r,b),rx,ry,p);}public void drawRect(float')
f['android/os/SystemClock.java']='package android.os;public class SystemClock {public static long now=1000;public static long uptimeMillis(){return now;}}'
f['io/tvos/settings/SettingsLogoRenderer.java']='package io.tvos.settings;public class SettingsLogoRenderer {public int calls;public void draw(android.graphics.Canvas c,float k,boolean light,android.graphics.Typeface t){calls++;}}'
f['io/tvos/ICloudSetup.java']='''package io.tvos;class ICloudSetup {interface Listener{void ready(String s,android.graphics.Path p,int n);void message(int r);void saved();}static Listener listener;static int opened,closed;ICloudSetup(android.content.Context c,Listener l){listener=l;opened++;l.ready("http://192.168.1.2:1234/icloud",new android.graphics.Path(),29);}void close(){closed++;}}'''
f['io/tvos/ICloudSetupPanel.java']=Path('app/src/io/tvos/ICloudSetupPanel.java').read_text()
f['io/tvos/PanelHarness.java']='''package io.tvos;import android.graphics.*;import android.os.SystemClock;class PanelHarness {static void check(boolean b,String s){if(!b)throw new AssertionError(s);}static boolean qr(Canvas c){return c.entries.stream().anyMatch(e->e.kind.equals("path"));}public static void main(String[] args){
 io.tvos.settings.SettingsLogoRenderer logo=new io.tvos.settings.SettingsLogoRenderer();int[] wakes={0};ICloudSetupPanel panel=new ICloudSetupPanel(new android.content.Context(),()->wakes[0]++);
 Canvas c=new Canvas();panel.draw(c,1,true,false,false,logo);check(ICloudSetup.opened==0&&logo.calls==1,"no local listener before focus");
 panel.draw(new Canvas(),1,true,true,false,logo);check(ICloudSetup.opened==1&&wakes[0]>0,"focus starts one session and schedules fade");
 SystemClock.now+=120;c=new Canvas();int calls=logo.calls;panel.draw(c,1,true,true,false,logo);check(qr(c)&&logo.calls==calls+1,"logo and QR coexist during crossfade");
 SystemClock.now+=240;c=new Canvas();panel.draw(c,1,true,true,false,logo);check(qr(c)&&ICloudSetup.opened==1,"steady focus keeps one QR session");
 ICloudSetup.Listener old=ICloudSetup.listener;SystemClock.now+=120;panel.draw(new Canvas(),1,true,false,false,logo);check(ICloudSetup.closed==1,"leaving focus closes socket immediately");int before=wakes[0];old.saved();check(wakes[0]==before,"late session events ignored");
 SystemClock.now+=240;c=new Canvas();panel.draw(c,1,true,false,false,logo);check(!qr(c),"QR gone after fade");
 panel.draw(new Canvas(),1,false,true,true,logo);check(ICloudSetup.opened==2,"return opens fresh short form");panel.pause();before=wakes[0];ICloudSetup.listener.message(R.string.icloud_expired);check(ICloudSetup.closed==2&&wakes[0]==before,"pause closes session and rejects stale events");
 System.out.println("PASS: QR focus lifecycle, crossfade, single listener, cancellation, stale events and reduced motion");}}
'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,text in f.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.PanelHarness'],check=True)
