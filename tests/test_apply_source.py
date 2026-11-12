"""Apply a minimal snapshot over an old checkout: retired files disappear, backups/config/key survive."""
from pathlib import Path
import subprocess,tempfile
with tempfile.TemporaryDirectory(prefix='tvos-apply-source-') as tmp:
 root=Path(tmp);source=root/'source';target=root/'target'
 for base in [source,target]:
  (base/'app/src/io/tvos').mkdir(parents=True);(base/'tools').mkdir()
  (base/'build.sh').write_text('#!/bin/sh\n');(base/'release.sh').write_text('#!/bin/sh\n')
 (source/'tools/apply-source.sh').write_text(Path('tools/apply-source.sh').read_text())
 (source/'app/src/io/tvos/HomeView.java').write_text('new source')
 moved=[('settings',name) for name in ['SettingsActions','SettingsOrderEditor','SettingsDockEditor','SettingsBackground','SettingsPopupBackdrop']]+[('home',name) for name in ['HomeLayout','HomeReveal']]+[('home/dock','HomeDockEditor')]+[('artwork',name) for name in ['PosterCache','RoundedImages']]
 for folder in ['settings','home','home/dock','artwork']:(source/('app/src/io/tvos/'+folder)).mkdir(parents=True,exist_ok=True)
 for folder,name in moved:(source/('app/src/io/tvos/'+folder+'/'+name+'.java')).write_text('new '+name)
 retired=['app/src/io/tvos/RemoteKeys.java','tests/test_mi_remote.py','tools/test-volume-hold.py','tests/test_volume_hold_prototype.py']+['app/src/io/tvos/'+name+'.java' for folder,name in moved]+['app/assets/fonts/sf_display_regular.otf','app/assets/avatars/robot.webp','app/src/io/tvos/Russian.java','app/src/io/tvos/Glyphs.java','app/res/drawable/coin_btc.xml','app/res/drawable/coin_eth.xml','app/res/drawable/coin_sol.xml','app/res/drawable/coin_usdt.xml','app/assets/symbols/bitcoin.svg','app/assets/symbols/bitcoin.png','app/assets/settings/light.webp','app/assets/settings/dark.webp','app/src/io/tvos/ScreensaverWindow.java']
 retired += ['app/src/io/tvos/'+name+'.java' for name in ['HomeSearchInput','HomeSearchGlass','HomeSearchState']]
 retired += ['tests/'+name+'.py' for name in ['test_home_search_input','test_home_search_glass','test_home_search_lifecycle','test_search_heading']]
 retired += ['docs/AUDIT-1.1.2.md', 'docs/FINAL-AUDIT.md', 'docs/REFACTORING-AUDIT.md', 'docs/DEVELOPMENT-NOTES.md']
 retired += ['app/src/io/tvos/watching/ResumeArtworkIdentity.java', 'tests/test_resume_artwork_identity.py']
 retired += ["app/src/io/tvos/"+name+".java" for name in ["VolumeKeyRepeat","RemoteInputMethod","RemoteKeyboardBridge"]]
 retired += ["app/src/io/tvos/remote/KeyboardHandoff.java","app/res/xml/remote_input_method.xml","app/res/values/settings_remote_input_strings.xml","app/res/values-ru/settings_remote_input_strings.xml"]
 for name in retired:
  p=target/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('retired')
 (target/'config.properties').write_text('personal config')
 (target/'signing.local.json').write_text('private signing config')
 (target/'legacy.keystore').write_bytes(b'test-signing-key')
 (target/'app/custom.txt').write_text('custom')
 subprocess.run(['bash',str(source/'tools/apply-source.sh'),str(target)],check=True,capture_output=True,text=True)
 assert all(not (target/name).exists() for name in retired)
 assert all((target/('app/src/io/tvos/'+folder+'/'+name+'.java')).read_text()=='new '+name for folder,name in moved)
 assert (target/'app/src/io/tvos/HomeView.java').read_text()=='new source'
 assert (target/'config.properties').read_text()=='personal config'
 assert (target/'signing.local.json').read_text()=='private signing config'
 assert (target/'legacy.keystore').read_bytes()==b'test-signing-key'
 assert (target/'app/custom.txt').read_text()=='custom'
 backup=next((target/'patch-backups').glob('github-updater-*'))
 assert all((backup/('app/src/io/tvos/'+name+'.java')).read_text()=='retired' for folder,name in moved)
 assert (backup/'app/src/io/tvos/Glyphs.java').read_text()=='retired'
 assert all((backup/name).read_text()=='retired' for name in ['docs/AUDIT-1.1.2.md', 'docs/FINAL-AUDIT.md', 'docs/REFACTORING-AUDIT.md', 'docs/DEVELOPMENT-NOTES.md'])
 print('PASS: source application removes retired files, backs up the previous app and preserves config/key/custom files')
