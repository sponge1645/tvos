"""Logo timeout, late-logo crossfade, reduced motion and per-title deadline isolation."""
from pathlib import Path
import subprocess,tempfile
h='''package io.tvos;class RevealTest {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[] args){
 SearchTitleReveal r=new SearchTitleReveal();r.open(1000);
 r.update(3999,false,false);check(r.textAlpha()==0&&r.nextDelay(3999)==1,"no title before three seconds");
 r.update(4000,false,false);check(r.textAlpha()==0&&r.nextDelay(4000)==16,"timeout begins fade, not a flash");
 r.update(4150,false,false);check(Math.abs(r.textAlpha()-.5f)<.001,"smooth midpoint");
 r.update(4300,false,false);check(r.textAlpha()==1&&r.nextDelay(4300)==0,"settled title stops scheduling");
 r.update(4500,true,false);r.update(4650,true,false);check(r.textAlpha()==.5f&&r.logoAlpha()==.5f,"late logo crossfades with text");
 r.update(4800,true,false);check(r.textAlpha()==0&&r.logoAlpha()==1&&r.nextDelay(4800)==0,"logo replaces fallback");
 r.open(6000);r.update(6100,true,false);r.update(6400,true,false);check(r.logoAlpha()==1&&r.textAlpha()==0,"early logo suppresses text");
 r.open(7000);r.update(7100,false,false);check(r.logoAlpha()==0&&r.textAlpha()==0&&r.nextDelay(7100)==2900,"new card clears old reveal/deadline");
 r.update(10000,false,true);check(r.textAlpha()==1&&r.logoAlpha()==0&&r.nextDelay(10000)==0,"reduced motion respects deadline and skips fade");
 r.update(11000,true,true);check(r.textAlpha()==0&&r.logoAlpha()==1,"reduced-motion late logo");
 System.out.println("PASS: three-second deadline, smooth text reveal, early/late logos, per-title reset and reduced motion");
 }}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'RevealTest.java';p.write_text(h)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,'app/src/io/tvos/SearchTitleReveal.java',str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RevealTest'],check=True)
