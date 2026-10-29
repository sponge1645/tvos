"""Prevent deleted language shims and new Russian UI literals from returning."""
from pathlib import Path
import re,subprocess,tempfile
from settings_strings_fixture import write
# П is a non-displayed capital-height reference for sizing the search SVG.
allowed={'DockBackdrop.java':{'истори','смотр','нов','фильм','рекоменд'},'HomeSearchInput.java':{'П'},'HomeApps.java':{'игровая приставка','игровая консоль'},'AppLanguage.java':{'Русский'},'SettingsDeviceDialogs.java':{'ТВ'},'SettingsValues.java':{'ТВ'},'Recs.java':{'ы','и','а','ов'},'PosterText.java':{' г.',' г'}}
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
assert not Path('app/src/io/tvos/Russian.java').exists()
assert 'legacyText(' not in Path('app/src/io/tvos/AppLanguage.java').read_text()
print('PASS: localization contracts, removed shims and historical decoder')
