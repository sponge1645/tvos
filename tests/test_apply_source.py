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
 retired=['app/src/io/tvos/Russian.java','app/src/io/tvos/Glyphs.java','app/res/drawable/coin_btc.xml','app/res/drawable/coin_eth.xml','app/res/drawable/coin_sol.xml','app/res/drawable/coin_usdt.xml','app/assets/symbols/bitcoin.svg','app/assets/symbols/bitcoin.png','app/assets/settings/light.webp','app/assets/settings/dark.webp','app/src/io/tvos/ScreensaverWindow.java']
 for name in retired:
  p=target/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('retired')
 (target/'config.properties').write_text('personal config')
 (target/'freemytv.keystore').write_bytes(b'test-signing-key')
 (target/'app/custom.txt').write_text('custom')
 subprocess.run(['bash',str(source/'tools/apply-source.sh'),str(target)],check=True,capture_output=True,text=True)
 assert all(not (target/name).exists() for name in retired)
 assert (target/'app/src/io/tvos/HomeView.java').read_text()=='new source'
 assert (target/'config.properties').read_text()=='personal config'
 assert (target/'freemytv.keystore').read_bytes()==b'test-signing-key'
 assert (target/'app/custom.txt').read_text()=='custom'
 backup=next((target/'patch-backups').glob('github-updater-*'))
 assert (backup/'app/src/io/tvos/Glyphs.java').read_text()=='retired'
 print('PASS: source application removes retired files, backs up the previous app and preserves config/key/custom files')
