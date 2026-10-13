import { readable } from '../core/Ui';
import { INK, MUTED, hhmm } from '../core/AndroidPresentation';
interface StatItem {title:string;value:string;hint:string;}
@Component
export struct StatisticsCard {
  @Prop title:string='';
  @Prop color:string='#368FB5';
  @Prop values:number[]=[];
  @Prop target:string='';
  @Prop note:string='';
  onOpen:(target:string)=>void=(_:string)=>{};
  private items():StatItem[] {
    const known=this.values.length>=3,distance=this.values[0],seconds=this.values[1],fuel=this.values[2];
    return [{title:'里程',value:known?(distance/1000).toFixed(1)+' km':'—',hint:''},
      {title:'驾驶时长',value:known?hhmm(seconds):'—',hint:''},
      {title:'平均油耗',value:known&&distance>0?(fuel/distance*100).toFixed(1)+' L/100km':'—',hint:known?'消耗 '+(fuel/1000).toFixed(2)+' L':'等待数据'}];
  }
  build(){
    Row(){
      Rect().width(3).height(66).fill(this.color).radius(3)
      Column({space:8}){
        Row(){Text(this.title).fontFamily('GarageSans').fontSize(12).fontWeight(FontWeight.Medium).fontColor(readable(this.color)).layoutWeight(1);if(this.target){Text('›').fontFamily('GarageSans').fontSize(22).fontColor(readable(this.color)).width(28).textAlign(TextAlign.Center)}}.width('100%')
        Row({space:6}){
          ForEach(this.items(),(item:StatItem)=>{
            Column(){
              Text(item.title).fontFamily('GarageSans').fontSize(10).fontWeight(FontWeight.Medium).fontColor(MUTED).maxLines(1).width('100%').textAlign(TextAlign.Center)
              Text(item.value).fontFamily('GarageSans').fontSize(13).minFontSize(9).maxFontSize(13).heightAdaptivePolicy(TextHeightAdaptivePolicy.MIN_FONT_SIZE_FIRST).maxLines(1).fontWeight(FontWeight.Medium).fontColor(readable(this.color)).width('100%').textAlign(TextAlign.Center).margin({top:4})
              Text(item.hint||' ').fontFamily('GarageSans').fontSize(9).minFontSize(8).maxFontSize(9).heightAdaptivePolicy(TextHeightAdaptivePolicy.MIN_FONT_SIZE_FIRST).maxLines(1).fontColor(MUTED).width('100%').textAlign(TextAlign.Center).margin({top:2})
            }.layoutWeight(1).padding({left:6,right:6,top:9,bottom:9}).backgroundColor('#F7F8FB').borderRadius(12)
          },(item:StatItem)=>item.title+'|'+item.value+'|'+item.hint+'|'+this.color)
        }.width('100%')
        if(this.note){Text(this.note).fontFamily('GarageSans').fontSize(11).fontColor(MUTED).width('100%')}
      }.layoutWeight(1).padding({left:13,right:13,top:12,bottom:13})
    }.width('100%').backgroundColor('#FFFFFF').borderRadius(22).shadow({radius:2,color:'#12000000',offsetY:1}).margin({top:10}).onClick(()=>{if(this.target)this.onOpen(this.target);})
  }
}
