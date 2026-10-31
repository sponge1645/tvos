"""Build display identity stays separate from numeric OTA and installation ordering."""
from pathlib import Path
import subprocess, tempfile
harness='''import io.tvos.version.SoftwareBuild;
public class BuildHarness {
 static void check(String v,String expected){if(!expected.equals(SoftwareBuild.display(v)))throw new AssertionError(v);}
 public static void main(String[] args){
 check("1.0","1.0 (1A117)");check("1.1.1","1.1.1 (1B117)");
 check("1.1.2","1.1.2 (1B117)");check("2.0","2.0 (2A117)");
 check("2.25.0","2.25.0 (2Z117)");check("2.26.0","2.26.0 (2AA117)");
 check("bad","bad");if(SoftwareBuild.display(null)!=null)throw new AssertionError("null");
 java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar"));check("1.1.1","1.1.1 (1B117)");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'BuildHarness.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,'app/src/io/tvos/version/SoftwareBuild.java',str(p)],check=True)
 subprocess.run(['java','-cp',d,'BuildHarness'],check=True)
print('PASS: major/minor mapping, patch stability, alphabet rollover, locale-independent build display')
