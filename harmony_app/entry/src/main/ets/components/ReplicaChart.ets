import { Metric } from '../core/Garage';
import { MonthCost, COLORS, DAILY_COLORS } from '../core/AndroidPresentation';
import { readable } from '../core/Ui';
interface ChartLabel {text:string;x:number;y:number;size:number;color:string;align:string;bold:boolean;}
@Component
export struct ReplicaChart {
 @Prop @Watch('draw') points: Metric[] = [];
 @Prop @Watch('draw') months: MonthCost[] = [];
 @Prop @Watch('draw') average: number = 0;
 @Prop @Watch('draw') kind: string = 'fuel';
 @State private labels:ChartLabel[]=[];
 private pending:ChartLabel[]=[];
 private labelFont:string='10vp sans-serif';
 private labelColor:string='#616B79';
 private labelAlign:string='center';
 private label(value:string,x:number,y:number):void {this.pending.push({text:value,x,y,size:Number(this.labelFont.match(/([0-9]+)vp/)?.[1]||10),color:this.labelColor,align:this.labelAlign,bold:this.labelFont.includes('bold')});}
 private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
 private roundedBar(x:number,y:number,w:number,h:number,r:number):void {
  if(h<=0)return;const c=this.context,radius=Math.min(r,w/2,h/2);c.beginPath();c.moveTo(x+radius,y);c.lineTo(x+w-radius,y);c.arcTo(x+w,y,x+w,y+radius,radius);c.lineTo(x+w,y+h-radius);c.arcTo(x+w,y+h,x+w-radius,y+h,radius);c.lineTo(x+radius,y+h);c.arcTo(x,y+h,x,y+h-radius,radius);c.lineTo(x,y+radius);c.arcTo(x,y,x+radius,y,radius);c.closePath();c.fill();
 }
 private description():string {
  if(this.kind==='cost')return '最近 '+this.months.length+' 个月用车开销；'+this.months.map((m:MonthCost)=>m.label+'，'+m.total.toFixed(2)+' 元').join('；');
  if(this.kind==='daily')return '年度日常分类；'+this.points.map((p:Metric)=>p.label+'，'+p.value.toFixed(2)+' 元').join('；');
  return '平均油耗变化；'+this.points.length+' 个有效区间'+(this.average>0?'；历史均值 '+this.average.toFixed(2)+' 升每百公里':'');
 }
 private draw():void {this.pending=[];try{this.drawGraphics();}finally{this.labels=this.pending;}}
 private drawGraphics(): void {
  const c=this.context,w=c.width,h=c.height;if(w<=0||h<=0)return;c.clearRect(0,0,w,h);c.font=this.labelFont='10vp sans-serif';c.textAlign=this.labelAlign='center';
  if(this.kind==='daily'){
   const total=this.points.reduce((n:number,p:Metric)=>n+p.value,0),x=w/2,y=h/2-2,r=Math.min(w,h)/2-25;c.lineWidth=24;c.strokeStyle='#E8ECF1';c.beginPath();c.arc(x,y,r,0,Math.PI*2);c.stroke();let angle=-Math.PI/2;this.points.forEach((p:Metric,i:number)=>{if(p.value>0&&total>0){const end=angle+p.value/total*Math.PI*2;c.strokeStyle=DAILY_COLORS[i];c.beginPath();c.arc(x,y,r,angle,end);c.stroke();angle=end;}});c.font=this.labelFont='bold 20vp sans-serif';c.fillStyle=this.labelColor='#212733';this.label(total>=10000?'¥'+(total/10000).toFixed(1)+'万':'¥'+total.toFixed(0),x,y+2);c.font=this.labelFont='10vp sans-serif';c.fillStyle=this.labelColor='#6D7784';this.label(total>0?'年度日常':'暂无支出',x,y+21);return;
  }
  if(this.kind==='cost'){
   const top=26,bottom=h-31,height=bottom-top,max=Math.max(1,...this.months.map((m:MonthCost)=>m.total)),slot=w/Math.max(1,this.months.length),bw=Math.max(8,Math.min(18,slot*.52));
   c.lineWidth=1;c.strokeStyle='#E7EAF0';for(let i=0;i<4;i++){c.beginPath();c.moveTo(5,top+height*i/3);c.lineTo(w-5,top+height*i/3);c.stroke();}
   if(!this.months.some((m:MonthCost)=>m.total>0)){c.font=this.labelFont='12vp sans-serif';c.fillStyle=this.labelColor='#616B79';this.label('暂无月度开销',w/2,top+height/2);}
   this.months.forEach((m:MonthCost,i:number)=>{const x=i*slot+(slot-bw)/2;c.fillStyle=this.labelColor=i===this.months.length-1?'#E2E5EE':'#F1F3F7';this.roundedBar(x,top,bw,height,5);let y=bottom;[m.fuel,m.maintenance,m.daily].forEach((v:number,k:number)=>{const bar=height*v/max;c.fillStyle=this.labelColor=COLORS[k];this.roundedBar(x,y-bar,bw,bar,4);y-=bar;});c.font=this.labelFont='bold 10vp sans-serif';c.fillStyle=this.labelColor=i===this.months.length-1?COLORS[3]:m.total>0?'#4F5664':'#616B79';this.label(m.total>=10000?(m.total/10000).toFixed(1)+'w':m.total>=1000?(m.total/1000).toFixed(1)+'k':m.total.toFixed(0),x+bw/2,Math.max(9,y-6));c.font=this.labelFont='10vp sans-serif';c.fillStyle=this.labelColor=i===this.months.length-1?COLORS[3]:'#6D7784';this.label(Number(m.label.slice(-2))+'月',i*slot+slot/2,h-9);});return;
  }
  if(this.kind==='fuel'&&!this.points.length&&this.average<=0){c.fillStyle=this.labelColor='#616B79';this.label('至少录入两次且里程递增后生成曲线',w/2,h/2);return;}
  const left=42,right=w-12,top=20,bottom=h-30;
  const values=this.kind==='fuel'?this.points.map((p:Metric)=>p.value).concat(this.average>0?[this.average]:[]):this.months.map((m:MonthCost)=>m.total);
  const min=this.kind==='fuel'?Math.max(0,Math.floor((Math.min(...values)-1)/2)*2):0,max=this.kind==='fuel'?Math.max(min+4,Math.ceil((Math.max(...values)+1)/2)*2):Math.max(100,Math.ceil(Math.max(...values)/100)*100);
  c.lineWidth=1;for(let i=0;i<=4;i++){const y=bottom-(bottom-top)*i/4;c.strokeStyle='#E2E5EA';c.beginPath();c.moveTo(left,y);c.lineTo(right,y);c.stroke();c.fillStyle=this.labelColor='#616B79';c.textAlign=this.labelAlign='right';this.label((min+(max-min)*i/4).toFixed(this.kind==='fuel'?1:0),left-7,y+4);}
  if(this.kind==='fuel'){
   const xs=this.points.map((p:Metric,i:number)=>this.points.length===1?(left+right)/2:left+(right-left)*i/(this.points.length-1));
   const ys=this.points.map((p:Metric)=>bottom-(p.value-min)/(max-min)*(bottom-top));
   if(xs.length){c.beginPath();xs.forEach((x:number,i:number)=>{if(i===0)c.moveTo(x,ys[i]);else c.lineTo(x,ys[i]);});c.lineTo(xs[xs.length-1],bottom);c.lineTo(xs[0],bottom);c.closePath();c.fillStyle=this.labelColor='rgba(220,49,86,0.11)';c.fill();
   c.beginPath();xs.forEach((x:number,i:number)=>{if(i===0)c.moveTo(x,ys[i]);else c.lineTo(x,ys[i]);});c.strokeStyle='#DC3156';c.lineWidth=3;c.lineCap='round';c.lineJoin='round';c.stroke();xs.forEach((x:number,i:number)=>{c.beginPath();c.arc(x,ys[i],4,0,Math.PI*2);c.fillStyle=this.labelColor='#DC3156';c.fill();});}
   if(this.average>0){const y=bottom-(this.average-min)/(max-min)*(bottom-top);c.setLineDash([7,5]);c.strokeStyle='#2660A4';c.lineWidth=1.5;c.beginPath();c.moveTo(left,y);c.lineTo(right,y);c.stroke();c.setLineDash([]);c.fillStyle=this.labelColor='#2660A4';c.textAlign=this.labelAlign='right';this.label('历史均值 '+this.average.toFixed(2),right,y-5);}
   if(this.points.length){c.fillStyle=this.labelColor='#6D7784';c.textAlign=this.labelAlign='left';this.label(this.points[0].label.slice(-5).replace('-','/'),left,h-7);c.textAlign=this.labelAlign='right';this.label(this.points[this.points.length-1].label.slice(-5).replace('-','/'),right,h-7);}
  }else{
   const span=(right-left)/Math.max(1,this.months.length);this.months.forEach((m:MonthCost,i:number)=>{let y=bottom;[m.fuel,m.maintenance,m.daily].forEach((value:number,k:number)=>{const height=value/max*(bottom-top);c.fillStyle=this.labelColor=COLORS[k];c.fillRect(left+i*span+span*.17,y-height,span*.66,height);y-=height;});c.fillStyle=this.labelColor='#6D7784';c.textAlign=this.labelAlign='center';this.label(String(Number(m.label.slice(-2))),left+(i+.5)*span,h-7);});
  }
 }
 build(){Stack({alignContent:Alignment.TopStart}){Canvas(this.context).width('100%').height(this.kind==='daily'?184:220).onReady(()=>this.draw()).onAreaChange(()=>this.draw()).accessibilityText(this.description());ForEach(this.labels,(label:ChartLabel)=>{Text(label.text).fontFamily('GarageSans').fontSize(label.size).fontColor(label.color).fontWeight(label.bold?FontWeight.Bold:FontWeight.Normal).maxLines(1).position({x:label.x,y:label.y}).translate({x:label.align==='right'?'-100%':label.align==='center'?'-50%':0,y:'-80%'}).hitTestBehavior(HitTestMode.None)},(label:ChartLabel)=>label.text+'|'+label.x+'|'+label.y)}.width('100%').height(this.kind==='daily'?184:220)}
}
