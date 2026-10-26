"""Prevent deleted language shims and new Russian UI literals from returning."""
from pathlib import Path
import re,subprocess,tempfile
from settings_strings_fixture import write
allowed={'AppLanguage.java':{'Русский'},'SettingsActivity.java':{'ТВ'},'Recs.java':{'ы','и','а','ов'},'PosterText.java':{' г.',' г'}}
for p in Path('app/src/io/tvos').glob('*.java'):
 text=p.read_text()
 assert not re.search(r'Ui\.(?:ru|en|es|de|tr)\b',text),p
 assert 'fromRussian(' not in text,p
 tokens=re.finditer(r'("(?:[^"\\]|\\.)*")|//[^\n]*|/\*.*?\*/',text,re.S)
 for token in tokens:
  value=token[1]
  if value and re.search('[А-Яа-яЁё]',value):assert value[1:-1] in allowed.get(p.name,set()),(p,value)
ui=Path('app/src/io/tvos/Ui.java').read_text()
assert 'static String tr(' not in ui
assert 'volatile boolean' not in ui
harness='''package io.tvos;public class MessageCompatibilityHarness {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
 AppLanguage.select("ru");
 check(AppLanguage.current().legacyText("Settings").equals("Настройки"));
 check(AppLanguage.current().legacyText("Couldn't check tvOS: debugging is off on the TV (port 5555 closed)").equals("Не удалось проверить tvOS: отладка на телевизоре выключена (порт 5555 закрыт)"));
 check(AppLanguage.current().legacyText("unknown provider detail").equals("unknown provider detail"));
 AppLanguage.select("en");check(AppLanguage.current().legacyText("Settings").equals("Settings"));
 System.out.println("PASS: deleted shim guard, XML-only translated copy and older ADB/notice decoding");
 }}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);write(root);(root/'MessageCompatibilityHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.MessageCompatibilityHarness'],check=True)
