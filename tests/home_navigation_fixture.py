"""Compile the production router together with the actual HomeView bridge in host harnesses."""
from pathlib import Path

def bridge(home, owner):
    start = home.index('    // Navigation bridge:')
    end = home.index('    // End navigation bridge.', start)
    return home[start:end].replace('HomeView.this', owner + '.this')

def router(owner):
    source = Path('app/src/io/tvos/HomeNavigation.java').read_text()
    return (source.replace('package io.tvos;\n', '')
            .replace('import android.graphics.RectF;\n', '')
            .replace('HomeAccessibility.', owner + '.')
            .replace('RectF', owner + '.RectF'))
