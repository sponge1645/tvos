"""Use the real XML translations when JVM UI tests replace Android resource lookup."""
from pathlib import Path
import json,xml.etree.ElementTree as ET

def decode(value):
 # Android outer quotes preserve significant whitespace; escaped quotes are text.
 if value.startswith('"') and value.endswith('"'):value=value[1:-1]
 return value.replace('\\n','\n').replace("\\'","'").replace('\\"','"').replace('\\\\','\\')

def tables():
 out=[]
 for folder in ['values','values-ru']:
  values={}
  for path in sorted((Path('app/res')/folder).glob('*_strings.xml')):
   root=ET.parse(path).getroot()
   for e in root.findall('string'):
    key=e.attrib['name'];assert key not in values,(folder,key)
    values[key]=decode(e.text or '')
  out.append(values)
 assert out[0].keys()==out[1].keys()
 return out

def sources():
 en,ru=tables();keys=sorted(en)
 ids={key:i+1 for i,key in enumerate(keys)}
 r='package io.tvos;final class R {static final class string {'+''.join('static final int '+key+'='+str(value)+';' for key,value in ids.items())+'}}'
 getter='package io.tvos;final class AppStrings {static String get(int id,boolean ru){switch(id){'+''.join('case '+str(ids[key])+':return ru?'+json.dumps(ru[key],ensure_ascii=False)+':'+json.dumps(en[key],ensure_ascii=False)+';' for key in keys)+'default:throw new IllegalArgumentException();}}}'
 plural_tables=[]
 for folder in ['values','values-ru']:
  root=ET.parse(Path('app/res')/folder/'settings_strings.xml').getroot()
  plural_tables.append({e.attrib['name']:{i.attrib['quantity']:decode(i.text or '') for i in e.findall('item')} for e in root.findall('plurals')})
 assert plural_tables[0].keys()==plural_tables[1].keys()
 plural_ids={key:i+len(ids)+1 for i,key in enumerate(sorted(plural_tables[0]))}
 r=r[:-1]+'static final class plurals {'+''.join('static final int '+key+'='+str(value)+';' for key,value in plural_ids.items())+'}}'
 # JVM resource adapter only; Android owns plural selection in the application.
 body='static String quantity(int id,boolean ru,int n){String category=ru?(n%100>=11&&n%100<=14?"many":n%10==1?"one":n%10>=2&&n%10<=4?"few":"many"):"other";switch(id){'
 for key,id in plural_ids.items():
  body+='case '+str(id)+':if(ru){switch(category){'
  body+=''.join('case '+json.dumps(q)+':return '+json.dumps(v,ensure_ascii=False)+';' for q,v in plural_tables[1][key].items())
  body+='default:return '+json.dumps(plural_tables[1][key]['other'],ensure_ascii=False)+';}}return '+json.dumps(plural_tables[0][key]['other'],ensure_ascii=False)+';'
 body+='default:throw new IllegalArgumentException();}}'
 getter=getter[:-1]+body+'}'
 getter=getter[:-1]+'''static String get(int id){return get(id,AppLanguage.current());}
static String get(int id,AppLanguage language){return get(id,language==AppLanguage.RUSSIAN);}
static String quantity(int id,int n){return quantity(id,AppLanguage.current(),n);}
static String quantity(int id,AppLanguage language,int n){return quantity(id,language==AppLanguage.RUSSIAN,n);}
}'''
 return {'io/tvos/R.java':r,'io/tvos/AppStrings.java':getter,
         'io/tvos/AppLanguage.java':Path('app/src/io/tvos/AppLanguage.java').read_text(),
         'io/tvos/Russian.java':Path('app/src/io/tvos/Russian.java').read_text()}

def write(root, package=True):
 for name,text in sources().items():
  if not package:name=Path(name).name;text=text.replace('package io.tvos;','')
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
