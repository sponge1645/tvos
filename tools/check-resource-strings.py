"""Compare compiled UI copy with the source XML, including Android quoting/whitespace."""
import argparse,json,re,subprocess,sys
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tests'))
from settings_strings_fixture import tables

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--aapt2',required=True)
parser.add_argument('apk',type=Path)
args=parser.parse_args()
dump=subprocess.check_output([args.aapt2,'dump','resources',str(args.apk)],text=True)
actual=[{},{}]
for block in re.finditer(r'    resource \S+ string/((?:home|settings|runtime|weather)_\w+)\n(.*?)(?=    resource |\Z)',dump,re.S):
 for match in re.finditer(r'      \((ru)?\) ("(?:\\.|[^"\\])*")',block[2]):
  value=json.loads(match[2].replace('\n      ','\\n'))
  language=int(bool(match[1]))
  assert block[1] not in actual[language],('duplicate compiled locale',block[1],language)
  actual[language][block[1]]=value
expected=tables()
for language in [0,1]:
 assert actual[language].keys()==expected[language].keys(),('compiled key mismatch',language,actual[language].keys()^expected[language].keys())
 for key,value in expected[language].items():
  assert actual[language][key]==value,(key,language,repr(actual[language][key]),repr(value))
print(f'PASS: {sum(map(len,actual))} compiled RU/EN strings match XML, including spaces, line breaks and escaping')
