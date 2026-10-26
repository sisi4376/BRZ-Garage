import { CalendarWeek, CalendarCell } from '../core/AndroidPresentation';
import { calendarHit } from '../core/Ui';
@Component
export struct CalendarHeatmap {
  @Prop @Watch('refreshWeeks') weeks: CalendarWeek[] = [];
  @State private selected: string = '';
  onSelect: (detail: string) => void = (_: string) => {};
  private canvas: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private refreshWeeks(): void {
    if (this.selected) {
      let found = false;
      for (const week of this.weeks) {
        const cell = week.cells.find((value: CalendarCell) => value.date === this.selected);
        if (cell) { this.onSelect(cell.detail); found = true; break; }
      }
      if (!found) { this.selected = ''; this.onSelect('点击日期方格查看当天统计'); }
    }
    this.draw();
  }
  private rounded(x: number, y: number, size: number, radius: number): void {
    const c=this.canvas;c.beginPath();c.moveTo(x+radius,y);c.lineTo(x+size-radius,y);c.arcTo(x+size,y,x+size,y+radius,radius);
    c.lineTo(x+size,y+size-radius);c.arcTo(x+size,y+size,x+size-radius,y+size,radius);c.lineTo(x+radius,y+size);
    c.arcTo(x,y+size,x,y+size-radius,radius);c.lineTo(x,y+radius);c.arcTo(x,y,x+radius,y,radius);c.closePath();
  }
  private draw(): void {
    const c=this.canvas;if(c.width<=0)return;c.clearRect(0,0,c.width,c.height);

    this.weeks.forEach((week: CalendarWeek,index: number)=>{
      // Paint month labels across columns rather than clipping them to a 12 vp cell.

      week.cells.forEach((cell: CalendarCell,day: number)=>{
        if(!cell.date)return;const x=24+index*15,y=20+day*15;this.rounded(x,y,12,2.5);c.fillStyle=cell.color;c.fill();
        if(cell.date===this.selected){this.rounded(x-1,y-1,14,3);c.lineWidth=2;c.strokeStyle='#DC3156';c.stroke();}
      });
    });
  }
  build(){
    Stack({alignContent:Alignment.TopStart}){
    Canvas(this.canvas).width(24+this.weeks.length*15+8).height(133).onReady(()=>this.draw()).onAreaChange(()=>this.draw())
      .accessibilityText('每日驾驶里程日历；'+(this.selected||'点击日期查看当天统计'))
      .onClick((event: ClickEvent)=>{const date=calendarHit(this.weeks,event.x,event.y);if(date){this.selected=date;for(const week of this.weeks){const cell=week.cells.find((c: CalendarCell)=>c.date===date);if(cell){this.onSelect(cell.detail);break;}}this.draw();}})
    ForEach(['一','','三','','五','','日'],(name:string,day:number)=>{if(name){Text(name).fontFamily('GarageSans').fontSize(10).fontColor('#616B79').position({x:2,y:20+day*15}).hitTestBehavior(HitTestMode.None)}})
    ForEach(this.weeks,(week:CalendarWeek,index:number)=>{if(week.label){Text(week.label).fontFamily('GarageSans').fontSize(10).fontColor('#616B79').position({x:24+index*15,y:0}).hitTestBehavior(HitTestMode.None)}},(week:CalendarWeek)=>week.id+'|'+week.label)
    }.width(24+this.weeks.length*15+8).height(133)
  }
}
