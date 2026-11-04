#!/usr/bin/env python3
from pathlib import Path
import sys

surface = Path(sys.argv[1]) / 'SDLSurface.java'
text = surface.read_text()
old = '''        if (skip) {
           Log.v("SDL", "Skip .. Surface is not ready.");'''
new = '''        // Android 16 can ignore requested orientation on large displays. The
        // actual surface is ready; waiting for a landscape resize would stall SDL.
        if (skip && Build.VERSION.SDK_INT >= 36) {
            Log.v("SDL", "Accept actual surface orientation on Android 16+");
            skip = false;
        }

        if (skip) {
           Log.v("SDL", "Skip .. Surface is not ready.");'''
if 'Accept actual surface orientation on Android 16+' not in text:
    assert old in text
    surface.write_text(text.replace(old, new))
