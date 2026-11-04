"""Explicit publisher title/year corrections are narrow and preserve real metadata."""
from pathlib import Path
import subprocess, tempfile
with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp)
    for name in ("WatchingSources", "ResumeArtworkIdentity"):
        (root/(name+".java")).write_text(Path("app/src/io/tvos/watching/"+name+".java").read_text())
    (root/"IdentityHarness.java").write_text('''import io.tvos.watching.ResumeArtworkIdentity;public class IdentityHarness {
      static void check(boolean b){if(!b)throw new AssertionError();}
      public static void main(String[] args){
        check(ResumeArtworkIdentity.year("watch.kino","Приятель / Buddy", "").equals("2026"));
        check(ResumeArtworkIdentity.year("watch.kino.mod","Сумерки/Twilight", "").equals("2008"));
        check(ResumeArtworkIdentity.year("watch.kino","Приятель / Buddy", "2013").equals("2013"));
        check(ResumeArtworkIdentity.year("top.rootu.lampa","Приятель / Buddy", "").isEmpty());
        check(ResumeArtworkIdentity.year("watch.kino","Buddy", "").isEmpty());
        check(ResumeArtworkIdentity.year("watch.kino","Сумерки. Сага. Затмение", "").isEmpty());
        System.out.println("PASS: exact KinoPub corrections, explicit year priority and unrelated labels preserved");
      }}''')
    subprocess.run(["java","com.sun.tools.javac.Main","-Xlint:all","-Werror","-d",tmp,*map(str,root.glob("*.java"))],check=True)
    subprocess.run(["java","-cp",tmp,"IdentityHarness"],check=True)
