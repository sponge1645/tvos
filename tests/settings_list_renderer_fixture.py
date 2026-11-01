"""Record row text boundaries while exercising the real list and capsule renderers."""
from pathlib import Path

def add(sources):
 sources['io/tvos/settings/SettingsListRenderer.java']=Path('app/src/io/tvos/settings/SettingsListRenderer.java').read_text()
 sources['io/tvos/settings/SettingsTextRenderer.java']='''package io.tvos.settings;import android.graphics.*;public class SettingsTextRenderer {
 public int shelfBegins;public boolean selected;public String fitted;public void beginShelf(Paint p){shelfBegins++;}
 public void drawTracked(Canvas c,String s,float x,float y,Paint p,float tracking){c.add("detail",s,x,y,0,0,p);}
 public void drawRowLabel(Canvas c,String s,RectF r,float k,float base,Paint p){c.add("label",s,r.left,base,r.right,r.bottom,p);}
 public void drawRowValue(Canvas c,String s,RectF r,float k,float base,float inset,Paint p){c.add("value",s,r.right-inset*k,base,0,0,p);}
 public void drawPickerLabel(Canvas c,String s,RectF r,float k,float base,boolean selected,Paint p){this.selected=selected;c.add("picker",s,r.left,base,0,0,p);}
 public void drawShelfLabel(Canvas c,String s,RectF r,float k,float base,Paint p){c.add("shelf",s,r.left,base,0,0,p);}
 public String fitRowValue(String label,String value,RectF r,float k,Paint lp,Paint vp){fitted=label+":"+value;return value;}
}'''
 return sources

def graphics():
 from home_renderers_fixture import files
 sources=add(files())
 sources['android/graphics/Shader.java']=sources['android/graphics/Shader.java'].replace('public enum','public void setLocalMatrix(Matrix m){}public enum')
 sources['android/graphics/Matrix.java']=sources['android/graphics/Matrix.java'].replace('public void setScale','public void setSinCos(float a,float b){}public void postScale(float a,float b){}public void setScale')
 sources['android/graphics/Paint.java']=sources['android/graphics/Paint.java'].replace('public enum Cap', 'public enum Join {ROUND}public void setStrokeJoin(Join j){}public enum Cap')
 sources['io/tvos/settings/SettingsRowRenderer.java']=Path('app/src/io/tvos/settings/SettingsRowRenderer.java').read_text()
 return sources


def geometry_sources():
 return {name: body for name, body in graphics().items() if name.startswith('android/') or name.startswith('io/tvos/settings/')}
