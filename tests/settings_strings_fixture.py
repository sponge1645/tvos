"""Use the real XML translations when JVM settings tests replace Android resource lookup."""
from pathlib import Path
import json,xml.etree.ElementTree as ET

def tables():
 out=[]
 for folder in ['values','values-ru']:
  root=ET.parse(Path('app/res')/folder/'settings_strings.xml').getroot()
  out.append({e.attrib['name']:(e.text or '').replace('\\n','\n').replace("\\'","'").replace('\\"','"').replace('\\\\','\\') for e in root.findall('string')})
 assert out[0].keys()==out[1].keys()
 return out

def sources():
 en,ru=tables();keys=sorted(en)
 ids={key:i+1 for i,key in enumerate(keys)}
 r='package io.tvos;final class R {static final class string {'+''.join('static final int '+key+'='+str(value)+';' for key,value in ids.items())+'}}'
 getter='package io.tvos;final class AppStrings {static String get(int id,boolean ru){switch(id){'+''.join('case '+str(ids[key])+':return ru?'+json.dumps(ru[key],ensure_ascii=False)+':'+json.dumps(en[key],ensure_ascii=False)+';' for key in keys)+'default:throw new IllegalArgumentException();}}}'
 return {'io/tvos/R.java':r,'io/tvos/AppStrings.java':getter}

def write(root):
 for name,text in sources().items():
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
