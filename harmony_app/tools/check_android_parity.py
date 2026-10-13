"""Read-only drift check. A changed Android baseline requires a new parity review, not an automatic baseline refresh."""
import os,json,hashlib,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
android=Path(os.environ.get('BRZ_ANDROID_REFERENCE_ROOT','C:/Users/18739/Documents/ChatGPT/BRZ_OBD'))
baseline=json.loads((root/'tests/reference/android-current-parity.json').read_text(encoding='utf-8'))
changed=[name for name,digest in baseline['files'].items() if not (android/name).is_file() or hashlib.sha256((android/name).read_bytes()).hexdigest()!=digest]
if changed:
 print('Android changed since the reviewed '+baseline['android']+' baseline; review Harmony parity for:')
 print('\n'.join(changed));sys.exit(1)
print('PASS Android '+baseline['android']+' reviewed source baseline ('+str(len(baseline['files']))+' files)')
