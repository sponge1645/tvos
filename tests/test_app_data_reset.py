"""Application data reset removes nested state without following links or deleting native code."""
from pathlib import Path
import subprocess,tempfile
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);source=root/'io/tvos';source.mkdir(parents=True)
 (source/'AppDataReset.java').write_text(Path('app/src/io/tvos/AppDataReset.java').read_text())
 (source/'ResetTest.java').write_text('''package io.tvos;import java.nio.file.*;public class ResetTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] a)throws Exception {
  Path root=Paths.get(a[0]),data=root.resolve("data"),outside=root.resolve("outside");Files.createDirectories(data.resolve("files/nested"));Files.createDirectories(data.resolve("shared_prefs"));Files.createDirectories(data.resolve("lib"));Files.createDirectories(outside);
  Files.write(outside.resolve("keep"),new byte[]{1});Files.write(data.resolve("files/nested/cache"),new byte[]{1});Files.write(data.resolve("shared_prefs/settings.xml"),new byte[]{1});Files.write(data.resolve("lib/native.so"),new byte[]{1});Files.write(data.resolve(AppDataReset.MARKER),new byte[]{1});Files.createSymbolicLink(data.resolve("external-link"),outside);
  check(AppDataReset.clear(data.toFile()),"reset succeeds");check(!Files.exists(data.resolve("files"))&&!Files.exists(data.resolve("shared_prefs")),"preferences and nested data erased");check(Files.exists(outside.resolve("keep")),"symlink targets preserved");check(Files.exists(data.resolve("lib/native.so"))&&Files.exists(data.resolve(AppDataReset.MARKER)),"native code and active-reset guard preserved");check(AppDataReset.clear(data.toFile()),"repeat reset is safe");
  System.out.println("PASS: reset removes application state, preserves code/guard and never follows links");
 }}''')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,source.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ResetTest',tmp],check=True)
