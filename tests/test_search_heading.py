"""Exercise heading transitions before dialog layout and through rapid input reopenings."""
from pathlib import Path
import os
import subprocess
import tempfile

source = Path('app/src/io/tvos/HomeView.java').read_text()

def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

body = ''.join(method(s) for s in [
    '    private void setSearchInputOpen(',
    '    private boolean tickSearchHeading(',
])
harness = '''package io.tvos;
public class SearchHeadingHarness {
 boolean searchMode, heroDirty;
 final Spring searchHeadingOpacity = new Spring(0.42f, 1f, 0);
 void wake() {}
 static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
''' + body + '''
 public static void main(String[] args) {
  SearchHeadingHarness h = new SearchHeadingHarness();
  h.setSearchInputOpen(true); h.searchMode = true;
  for (int i = 0; i < 120; i++) {
   h.tickSearchHeading(1f / 60);
   check(h.searchHeadingOpacity.value == 0, "no flash while dialog layout is delayed");
  }
  h.setSearchInputOpen(false);
  h.tickSearchHeading(1f / 60);
  check(h.searchHeadingOpacity.value > 0 && h.searchHeadingOpacity.value < 0.5f,
        "dismissal starts a fade instead of jumping to full opacity");
  float previous = h.searchHeadingOpacity.value;
  for (int i = 0; i < 120; i++) {
   h.tickSearchHeading(1f / 60);
   check(h.searchHeadingOpacity.value >= previous, "fade-in is monotonic");
   previous = h.searchHeadingOpacity.value;
  }
  check(h.searchHeadingOpacity.value == 1, "closed capsule leaves heading visible");
  h.setSearchInputOpen(true);
  check(h.searchHeadingOpacity.value == 1, "reopening preserves starting opacity");
  h.tickSearchHeading(1f / 60);
  check(h.searchHeadingOpacity.value > 0 && h.searchHeadingOpacity.value < 1,
        "reopening fades heading out");
  previous = h.searchHeadingOpacity.value;
  h.setSearchInputOpen(false);
  check(h.searchHeadingOpacity.value == previous, "rapid reversal has no opacity jump");
  for (int i = 0; i < 120; i++) h.tickSearchHeading(1f / 60);
  h.searchMode = false; h.tickSearchHeading(1f / 60);
  check(h.searchHeadingOpacity.value == 0, "leaving search clears stale opacity");
  h.setSearchInputOpen(true); h.searchMode = true;
  h.tickSearchHeading(1f / 60);
  check(h.searchHeadingOpacity.value == 0, "entry from another section remains hidden");
  System.out.println("PASS: delayed search input layout, smooth heading fades, rapid reversal, re-entry");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-search-heading-') as directory:
    root = Path(directory)
    (root / 'SearchHeadingHarness.java').write_text(harness)
    (root / 'Spring.java').write_text(Path('app/src/io/tvos/Spring.java').read_text())
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory,
                    str(root / 'Spring.java'), str(root / 'SearchHeadingHarness.java')], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.SearchHeadingHarness'], check=True)
