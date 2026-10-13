@Component
export struct PlateView {
 @Prop value: string = '';
 private letters():string[]{return Array.from(this.value.replace(/[·•.\s-]/g,''));}
 build(){Stack({alignContent:Alignment.TopStart}){
  Image($rawfile('plate/base.svg')).width('100%').height('100%')
  if(this.value){ForEach(this.letters(),(char:string,i:number)=>{Image($rawfile('plate/'+char.codePointAt(0)+'.svg')).width('10.22727%').height('64.28571%').position({x:((i===0?15:i===1?72:151+(i-2)*57)/440*100)+'%',y:'17.85714%'})},(char:string,i:number)=>String(i)+char)}
  else{Text('输入车牌号后生成预览').fontSize(16).fontColor('#AAFFFFFF').width('100%').height('100%').textAlign(TextAlign.Center)}
 }.width('100%').aspectRatio(440/140)}
}
