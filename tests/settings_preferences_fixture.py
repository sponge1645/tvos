"""SharedPreferences boundary for exercising the real preference mutation adapter."""
from pathlib import Path

def write(root, prefs_owner=None):
 sources={
 'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {
 public final Map<String,Object> data=new HashMap<>();public int writes;
 public boolean contains(String k){return data.containsKey(k);}public boolean getBoolean(String k,boolean d){return data.containsKey(k)?(Boolean)data.get(k):d;}
 public int getInt(String k,int d){return data.containsKey(k)?(Integer)data.get(k):d;}public String getString(String k,String d){return data.containsKey(k)?(String)data.get(k):d;}
 public SharedPreferences edit(){return this;}public SharedPreferences putBoolean(String k,boolean v){data.put(k,v);return this;}public SharedPreferences putInt(String k,int v){data.put(k,v);return this;}public SharedPreferences putString(String k,String v){data.put(k,v);return this;}public void apply(){writes++;}}''',
 'io/tvos/SettingsPreferenceEditor.java':Path('app/src/io/tvos/SettingsPreferenceEditor.java').read_text()}
 if prefs_owner:
  sources['io/tvos/SettingsPreferenceEditor.java']=sources['io/tvos/SettingsPreferenceEditor.java'].replace('Prefs.',prefs_owner+'.')
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
