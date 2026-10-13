"""Read-only comparison against original workspace source-file baseline."""
import os,json,hashlib,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
original=Path(os.environ.get('BRZ_ANDROID_REFERENCE_ROOT','C:/Users/18739/Documents/ChatGPT/BRZ_OBD'))
baseline=json.loads((root/'tests/reference/original-files.sha256.json').read_text())
changed=[name for name,digest in baseline.items() if not (original/name).is_file() or hashlib.sha256((original/name).read_bytes()).hexdigest()!=digest]
print(json.dumps({'checked':len(baseline),'changed':changed},ensure_ascii=True,indent=2))
sys.exit(1 if changed else 0)
