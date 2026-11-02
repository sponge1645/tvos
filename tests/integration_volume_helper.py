"""Check the actual built APK contains the exact bounded Android-9-compatible helper."""
from pathlib import Path
import io
import zipfile
apk=Path('tvOS.apk')
staged=Path('out/assets/volume-hold.jar').read_bytes()
with zipfile.ZipFile(apk) as archive:
    assert archive.namelist().count('assets/volume-hold.jar')==1
    packed=archive.read('assets/volume-hold.jar')
assert packed==staged and 0<len(packed)<=65536
with zipfile.ZipFile(io.BytesIO(packed)) as helper:
    assert [n for n in helper.namelist() if n.endswith('.dex')]==['classes.dex']
    dex=helper.read('classes.dex')
    assert dex.startswith(b'dex\n') and 35<=int(dex[4:7])<=39
print('PASS: signed APK contains exact staged helper, bounded transfer size and Android 9-compatible DEX version')
