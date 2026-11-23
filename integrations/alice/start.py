"""Windowless task entry point; config.json lives next to this file."""
from pathlib import Path
import sys
import traceback

root = Path(__file__).resolve().parent
with (root / 'startup-error.log').open('a', encoding='utf-8') as error:
    sys.stderr = error
    try:
        from bridge import main
        sys.argv = [str(root / 'bridge.py'), '--config', str(root / 'config.json')]
        main()
    except Exception:
        traceback.print_exc()
        raise SystemExit(1)
