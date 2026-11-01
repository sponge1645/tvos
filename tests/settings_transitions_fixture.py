"""Recording Android boundaries for the production settings transition component."""
from pathlib import Path

def write(root):
 sources={
 'android/graphics/Paint.java':'''package android.graphics;public class Paint {private int alpha=255;public int getAlpha(){return alpha;}public void setAlpha(int n){alpha=n;}}''',
 'android/graphics/Canvas.java':'''package android.graphics;import java.util.*;public class Canvas {
 public int depth;public List<Float> translations=new ArrayList<>();public List<Integer> alphas=new ArrayList<>();public List<float[]> clips=new ArrayList<>();
 public int save(){return ++depth;}public void restore(){if(--depth<0)throw new AssertionError();}public void restoreToCount(int n){depth=n-1;if(depth<0)throw new AssertionError();}
 public void clipRect(float l,float t,float r,float b){clips.add(new float[]{l,t,r,b});}public void translate(float x,float y){if(y!=0)throw new AssertionError();translations.add(x);}
 public int saveLayerAlpha(float l,float t,float r,float b,int a){alphas.add(a);clips.add(new float[]{l,t,r,b});return save();}}''',
 'io/tvos/settings/SettingsTextRenderer.java':'''package io.tvos.settings;import android.graphics.*;import java.util.*;public class SettingsTextRenderer {
 public List<String> labels=new ArrayList<>();public List<Integer> alphas=new ArrayList<>();public float x,y,tracking;
 public void drawTracked(Canvas c,String s,float x,float y,Paint p,float tracking){labels.add(s);alphas.add(p.getAlpha());this.x=x;this.y=y;this.tracking=tracking;}
 public void clear(){labels.clear();alphas.clear();}}''',
 'io/tvos/settings/SettingsTransitions.java':Path('app/src/io/tvos/settings/SettingsTransitions.java').read_text()}
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
