"""Production TypeScript tests + real Android Kotlin codec interoperability.
Only writes inside harmony_app/test-output. Requires local DevEco and Android test jars.
"""
import os, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(os.environ.get('BRZ_ANDROID_REFERENCE_ROOT', 'C:/Users/18739/Documents/ChatGPT/BRZ_OBD'))
DEVECO = Path(os.environ.get('DEVECO_HOME', 'C:/Program Files/Huawei/DevEco Studio'))
OUT=ROOT/'test-output'
OUT.mkdir(exist_ok=True)
node=DEVECO/'tools/node/node.exe'
tsc=DEVECO/'sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript/bin/tsc'
def run(args):
    result=subprocess.run(list(map(str,args)),cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',errors='replace')
    print(result.stdout.encode(sys.stdout.encoding or 'utf-8',errors='replace').decode(sys.stdout.encoding or 'utf-8'),end='')
    if result.returncode: raise SystemExit(result.returncode)
run([node,tsc,'--target','ES2020','--module','commonjs','--strict','--skipLibCheck','--outDir',OUT/'core',*sorted((ROOT/'entry/src/main/ets/core').glob('*.ts'))])
maven=SOURCE/'.toolchains/local-maven'
jars=[]
for artifact in ['kotlin-compiler-embeddable/2.2.10','kotlin-stdlib/2.2.10','kotlin-script-runtime/2.2.10','kotlin-reflect/1.6.10','kotlin-daemon-embeddable/2.2.10']:
    jars.extend((maven/'org/jetbrains/kotlin'/artifact).glob('*.jar'))
jars.extend((maven/'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0').glob('*.jar'))
jars += [SOURCE/'.toolchains/gradle9.5/lib/annotations-24.0.1.jar',SOURCE/'tmp/json-20240303.jar']
cp=os.pathsep.join(map(str,jars))
java=DEVECO/'jbr/bin/java.exe'
run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',OUT/'kotlin',SOURCE/'android_app/app/src/main/java/com/brz/gauge/trips/TransferArchive.kt',SOURCE/'android_app/app/src/main/java/com/brz/gauge/trips/TransferSchema.kt',ROOT/'tests/FixtureMain.kt'])
runtime=str(OUT/'kotlin')+os.pathsep+cp
run([java,'-cp',runtime,'com.brz.gauge.trips.FixtureMainKt',OUT])
os.environ['BRZ_TRANSFER_FIXTURE_DIR'] = str(SOURCE/'tmp/transfer-fresh-phone')
run([node,'--test',*sorted((ROOT/'tests').glob('*.test.cjs'))])
run([java,'-cp',runtime,'com.brz.gauge.trips.FixtureMainKt',OUT,'verify'])

run([sys.executable,ROOT/'tools/check_android_parity.py'])
