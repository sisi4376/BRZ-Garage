import { common } from '@kit.AbilityKit';
import { webview } from '@kit.ArkWeb';
import { util } from '@kit.ArkTS';
import { VehicleArtwork } from './VehicleArtwork';
import { utf8Decode, utf8Encode } from '../core/Archive';

interface GlyphLayer { path: string; cutout: boolean; }
interface GlyphTable { glyphs: Record<string, GlyphLayer[]>; }
interface VehiclePayload { model: string; color: string; finish: string; plate: string; visible: boolean; plateUrl: string; }
class VehicleHost {
  onReady: () => void = () => {};
  onLoaded: () => void = () => {};
  onFailed: () => void = () => {};
  ready(): void { this.onReady(); }
  loaded(): void { this.onLoaded(); }
  failed(): void { this.onFailed(); }
}
@Component
export struct Vehicle3D {
  @Prop @Watch('sendState') model: string = 'ZD8';
  @Prop @Watch('sendState') plate: string = '';
  @Prop @Watch('sendState') color: string = '#155dcc';
  @Prop @Watch('sendState') finish: string = 'gloss';
  onAppearance: (color: string, finish: string) => void = () => {};
  @State private loaded: boolean = false;
  @State private failed: boolean = false;
  @State private palette: boolean = false;
  @State private mounted: boolean = true;
  private ready: boolean = false;
  private timeout: number = -1;
  private controller: webview.WebviewController = new webview.WebviewController();
  private host: VehicleHost = new VehicleHost();
  private alive: boolean = false;
  aboutToAppear(): void {
    this.alive = true;
    this.host.onReady = () => { this.ready = true; this.sendState(); };
    this.host.onLoaded = () => { if (this.alive) { this.loaded = true; this.failed = false; clearTimeout(this.timeout); } };
    this.host.onFailed = () => { if (this.alive) this.failed = true; };
    this.timeout = setTimeout(() => { if (!this.loaded) this.failed = true; }, 45000);
  }
  aboutToDisappear(): void { this.alive = false; clearTimeout(this.timeout); this.run('window.BrzVehicle?.setActive(false)'); }
  private run(script: string): void { if (!this.ready) return; this.controller.runJavaScript(script).catch(() => { this.failed = true; }); }
  private async sendState(): Promise<void> {
    if (!this.ready || !this.alive) return;
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    let plateUrl = '';
    if (this.plate) {
      const glyphs = (JSON.parse(utf8Decode(await context.resourceManager.getRawFileContent('plate/glyphs.json'))) as GlyphTable).glyphs;
      const chars = Array.from(this.plate.replace(/[·•.\s-]/g, ''));
      if (chars.length === 7 && chars.every((c: string) => glyphs[c] !== undefined)) {
        let svg = '<svg xmlns="http://www.w3.org/2000/svg" width="2640" height="840" viewBox="0 0 440 140"><rect width="440" height="140" rx="10" fill="#0052A8"/><rect x="5" y="5" width="430" height="130" rx="6.2" fill="none" stroke="white" stroke-width="1"/><circle cx="134" cy="70" r="5" fill="white"/>';
        chars.forEach((c: string, i: number) => { const x = i === 0 ? 15 : i === 1 ? 72 : 151 + (i - 2) * 57;
          glyphs[c].forEach((layer: GlyphLayer) => svg += '<path transform="translate('+x+',25)" fill="'+(layer.cutout?'#0052A8':'white')+'" d="'+layer.path+'"/>'); });
        svg += '</svg>'; plateUrl = 'data:image/svg+xml;base64,' + new util.Base64Helper().encodeToStringSync(utf8Encode(svg));
      }
    }
    if (!this.alive) return;
    const value: VehiclePayload = { model: this.model.toLowerCase(), color: this.color, finish: this.finish, plate: this.plate, visible: !!this.plate, plateUrl };
    this.run('window.BrzVehicle?.apply(' + JSON.stringify(value) + ')');
  }
  private intercept(url: string): WebResourceResponse {
    const response = new WebResourceResponse();
    response.setResponseCode(403); response.setReasonMessage('Blocked'); response.setResponseData('');
    const prefix = 'https://brz.local/vehicle-3d/';
    if (!url.startsWith(prefix)) return response;
    const name = url.substring(prefix.length).split('?')[0];
    if (!/^[a-zA-Z0-9_./-]+$/.test(name) || name.includes('..')) return response;
    response.setResponseIsReady(false);
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    context.resourceManager.getRawFileContent('vehicle-3d/'+name).then((bytes: Uint8Array) => {
      response.setResponseCode(200); response.setReasonMessage('OK');
      response.setResponseMimeType(name.endsWith('.html')?'text/html':name.endsWith('.js')||name.endsWith('.mjs')?'text/javascript':name.endsWith('.json')?'application/json':'application/octet-stream');
      response.setResponseEncoding('utf-8'); response.setResponseData(bytes.slice().buffer as ArrayBuffer); response.setResponseIsReady(true);
    }).catch(() => { response.setResponseCode(404); response.setReasonMessage('Not Found'); response.setResponseIsReady(true); });
    return response;
  }
  private retry(): void {
    this.failed = false; this.loaded = false; this.ready = false; this.mounted = false; clearTimeout(this.timeout);
    setTimeout(() => { if (this.alive) { this.controller = new webview.WebviewController(); this.mounted = true; this.timeout = setTimeout(() => { if (!this.loaded) this.failed = true; }, 45000); } }, 30);
  }
  build() {
    Column() {
      Stack() {
        if (!this.loaded || this.failed) { VehicleArtwork({ model: this.model, plate: this.plate }) }
        if (this.mounted) {
          Web({ src: 'https://brz.local/vehicle-3d/app.html', controller: this.controller })
            .javaScriptAccess(true).domStorageAccess(false).fileAccess(false).onlineImageAccess(false)
            .backgroundColor('#00000000').width('100%').height(228).opacity(this.loaded&&!this.failed?1:0)
            .javaScriptProxy({ object: this.host, name: 'BrzHost', methodList: ['ready','loaded','failed'], controller: this.controller })
            .onInterceptRequest((event) => this.intercept(event.request.getRequestUrl()))
            .onLoadIntercept((event) => event.data.getRequestUrl() !== 'https://brz.local/vehicle-3d/app.html')
            .onRenderExited(() => { this.failed = true; })
            .onVisibleAreaChange([0,1], (visible: boolean) => this.run('window.BrzVehicle?.setActive('+visible+')'))
        }
        if (!this.loaded || this.failed) { Text(this.failed?'3D 暂不可用 · 点击重试':'正在加载 3D 车辆…').fontSize(11).fontColor('#CEDBEA').onClick(() => this.retry()) }
      }.width('100%').height(228)
      Row() {
        ForEach(['前侧','后侧','车牌'], (name: string, i: number) => { Text(name).fontSize(12).fontColor('#D8E5F5').layoutWeight(1).textAlign(TextAlign.Center).padding(8).onClick(() => this.run('window.BrzVehicle?.view('+JSON.stringify(['front','rear','plate'][i])+')')) })
        Text('车色').fontSize(12).fontColor('#D8E5F5').layoutWeight(1).textAlign(TextAlign.Center).padding(8).onClick(() => this.palette = !this.palette)
      }.width('100%')
      if (this.palette) {
        Row({space:8}) { ForEach(['#155dcc','#e7e9e8','#b91930','#565d66','#141a22','#a4b1bf'], (color: string) => { Circle().fill(color).width(28).height(28).border({width:1,color:'#D8E5F5'}).onClick(() => this.onAppearance(color,this.finish)) }) }.justifyContent(FlexAlign.Center).width('100%').padding(6)
        Row() { Text(this.finish==='gloss'?'漆面：亮面':'漆面：哑光').fontSize(12).fontColor('#D8E5F5').padding(8).onClick(() => this.onAppearance(this.color,this.finish==='gloss'?'satin':'gloss')); Text('模型来源与许可').fontSize(12).fontColor('#D8E5F5').padding(8).onClick(() => this.getUIContext().showAlertDialog({title:'3D 模型署名',message:'Subaru BRZ ZD8 / ZC6\nMona x Supercars / GT Cars: Hyperspeed\nhttps://sketchfab.com/Car2022\nCC BY 4.0\nhttps://creativecommons.org/licenses/by/4.0/\n修改：清理辅助几何、合并 GLB、替换车漆、添加前后车牌。\nThree.js 0.160.1 · MIT\n原始许可与模型来源随应用内置。',confirm:{value:'关闭',action:()=>{}}})) }
      }
    }.width('100%')
  }
}
