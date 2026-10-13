import { common } from '@kit.AbilityKit';
import { utf8Decode } from '../core/Archive';
interface GlyphLayer { path:string; cutout:boolean; }
interface GlyphTable { glyphs:Record<string,GlyphLayer[]>; }
@Component
export struct VehicleArtwork {
 @Prop @Watch('paint') model:string = 'ZD8';
 @Prop @Watch('paint') plate:string = '';
 private canvas:CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
 private glyphs:Record<string,GlyphLayer[]> = {};
 private matrix:number[]=[];
 aboutToAppear():void{this.load();}
 private async load():Promise<void>{try{const context=this.getUIContext().getHostContext() as common.UIAbilityContext;this.glyphs=(JSON.parse(utf8Decode(await context.resourceManager.getRawFileContent('plate/glyphs.json'))) as GlyphTable).glyphs;this.paint();}catch(_){}}
 private point(x:number,y:number):number[]{const m=this.matrix,xn=x/440,yn=y/140,z=m[6]*xn+m[7]*yn+1;return [(m[0]*xn+m[1]*yn+m[2])/z,(m[3]*xn+m[4]*yn+m[5])/z];}
 private move(x:number,y:number):void{const p=this.point(x,y);this.canvas.moveTo(p[0],p[1]);}
 private line(x:number,y:number):void{const p=this.point(x,y);this.canvas.lineTo(p[0],p[1]);}
 private roundRect(x:number,y:number,w:number,h:number,r:number):void{const c=this.canvas;c.beginPath();for(let corner=0;corner<4;corner++){const cx=x+(corner===0||corner===3?r:w-r),cy=y+(corner<2?r:h-r);for(let i=0;i<=6;i++){const a=(-180+corner*90+i*15)*Math.PI/180,px=cx+r*Math.cos(a),py=cy+r*Math.sin(a);if(corner===0&&i===0)this.move(px,py);else this.line(px,py);}}c.closePath();}
 private glyph(char:string,left:number):void{const c=this.canvas;for(const layer of this.glyphs[char]||[]){const tokens=layer.path.match(/[MLCZ]|[-+]?(?:\d*\.)?\d+/g)||[];let i=0;c.beginPath();while(i<tokens.length){const command=tokens[i++];if(command==='Z'){c.closePath();continue;}if(command==='M'||command==='L'){const x=Number(tokens[i++])+left,y=Number(tokens[i++])+25;if(command==='M')this.move(x,y);else this.line(x,y);}else if(command==='C'){const a=this.point(Number(tokens[i++])+left,Number(tokens[i++])+25),b=this.point(Number(tokens[i++])+left,Number(tokens[i++])+25),d=this.point(Number(tokens[i++])+left,Number(tokens[i++])+25);c.bezierCurveTo(a[0],a[1],b[0],b[1],d[0],d[1]);}else return;}c.fillStyle=layer.cutout?'#0052A8':'#FFFFFF';c.fill();}}
 private paint():void{
  const c=this.canvas,w=c.width,h=c.height;if(w<=0||h<=0)return;c.clearRect(0,0,w,h);const chars=Array.from(this.plate.replace(/[·•.\s-]/g,''));if(chars.length!==7||!Object.keys(this.glyphs).length)return;
  const dw=Math.min(w,h*1.5),dh=dw/1.5,left=(w-dw)/2,top=(h-dh)/2;
  const q=this.model==='ZC6'?[.763096,.607513,.886630,.596958,.888194,.666154,.763878,.676709]:[.768657,.587020,.890448,.580752,.892239,.649708,.770448,.655976];
  const p=q.map((v:number,i:number)=>i%2===0?left+v*dw:top+v*dh);const dx1=p[2]-p[4],dx2=p[6]-p[4],dx3=p[0]-p[2]+p[4]-p[6],dy1=p[3]-p[5],dy2=p[7]-p[5],dy3=p[1]-p[3]+p[5]-p[7],det=dx1*dy2-dx2*dy1,g=(dx3*dy2-dx2*dy3)/det,j=(dx1*dy3-dx3*dy1)/det;
  this.matrix=[p[2]-p[0]+g*p[2],p[6]-p[0]+j*p[6],p[0],p[3]-p[1]+g*p[3],p[7]-p[1]+j*p[7],p[1],g,j];
  this.roundRect(-7,-7,454,156,10);c.fillStyle='#9B04070B';c.fill();this.roundRect(0,0,440,140,10);c.fillStyle='#0052A8';c.fill();this.roundRect(5,5,430,130,6.2);c.strokeStyle='#FFFFFF';c.lineWidth=dw*.00067;c.stroke();
  for(const x of [115,325])for(const y of [12.5,127.5]){this.roundRect(x-7.5,y-4,15,8,4);c.fillStyle='#CAD3DD';c.fill();}
  chars.forEach((char:string,i:number)=>this.glyph(char,i===0?15:i===1?72:151+(i-2)*57));c.beginPath();for(let i=0;i<=16;i++){const a=i*Math.PI/8;if(i===0)this.move(134+5*Math.cos(a),70+5*Math.sin(a));else this.line(134+5*Math.cos(a),70+5*Math.sin(a));}c.closePath();c.fillStyle='#FFFFFF';c.fill();
 }
 build(){Stack(){Image(this.model==='ZC6'?$r('app.media.brz_zc6_hero'):$r('app.media.brz_zd8_hero')).width('100%').height('100%').objectFit(ImageFit.Contain);Canvas(this.canvas).width('100%').height('100%').onReady(()=>this.paint()).onAreaChange(()=>this.paint())}.width('100%').height(228)}
}
