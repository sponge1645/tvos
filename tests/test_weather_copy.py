from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/HomeData.java').read_text()
harness='package io.tvos;public class WeatherCopyHarness {\n static void check(boolean ok){if(!ok)throw new AssertionError();}\n'+block(source,'    static String condition(')+'public static void main(String[] args){\n for(String code:new String[]{"en","ru"}){AppLanguage.select(code);boolean ru=code.equals("ru");\n check(condition(-1,true).isEmpty());check(condition(0,true).equals(ru?"Ясно":"Clear"));\n check(condition(1,false).equals(ru?"Малооблачно":"Mostly Clear"));\n check(condition(65,true).equals(ru?"Сильный дождь":"Heavy Rain"));\n check(condition(95,true).equals(ru?"Гроза":"Thunderstorms"));}\n System.out.println("PASS: production weather copy, WMO and app language");}}\n'
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);write(root);(root/'WeatherCopyHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.WeatherCopyHarness'],check=True)
