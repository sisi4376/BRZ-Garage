const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../test-output');
const U=require(root+'/core/Ui'),P=require(root+'/core/AndroidPresentation');
test('small accent text and filled controls remain readable on every shared light surface',()=>{
 for(const color of [...P.COLORS,...P.DAILY_COLORS,'#E08426','#2B8B6F','#7E8694']){
  for(const surface of ['#FFFFFF','#F7F8FB','#E8EBF1',P.soften(color)])assert.ok(U.contrast(U.readable(color),surface)>=4.5,color+' on '+surface);
 }
 assert.equal(U.readable('#181D27'),'#181D27');
});
test('summary and detail duration matches Android including seconds and durations exceeding one day',()=>{
 assert.equal(U.preciseDuration(59),'00:00:59');assert.equal(U.preciseDuration(3661),'01:01:01');assert.equal(U.preciseDuration(90061),'25:01:01');
});
test('low fuel indication follows Android 25 percent boundary and ignores unknown sensor readings',()=>{
 assert.equal(U.isLowFuel(24.99),true);assert.equal(U.isLowFuel(25),false);assert.equal(U.isLowFuel(null),false);assert.equal(U.isLowFuel(NaN),false);
});
test('calendar hit testing ignores labels and out-of-year cells and selects exact dates',()=>{
 const weeks=P.calendar([],2025);
 assert.equal(U.calendarHit(weeks,1,21),'');assert.equal(U.calendarHit(weeks,25,19),'');
 assert.equal(U.calendarHit(weeks,25,21),'');assert.equal(U.calendarHit(weeks,25,51),'2025-01-01');
 assert.equal(U.calendarHit(weeks,24+weeks.length*15+1,21),'');
});
test('calendar month labels follow the first valid date in each week and details retain seconds',()=>{
 const weeks=P.calendar([{flags:1,start_epoch_s:new Date(2025,0,1,12).getTime()/1000,distance_m:1000,duration_s:61,fuel_ml:100}],2025);
 let previous='';for(const week of weeks){const first=week.cells.find(c=>c.date);assert.equal(week.label,first&&first.month!==previous?first.month:'');if(first)previous=first.month;}
 assert.ok(weeks.flatMap(w=>w.cells).find(c=>c.date==='2025-01-01').detail.includes('00:01:01'));
});
test('fuel interval labels identify exact records even when two fills share a date',()=>{
 const G=require(root+'/core/Garage');
 const records=[100,200,300].map((km,i)=>({transfer_id:'fill-'+i,draft:0,full_tank:1,litres:10,odometer_km:km,date_epoch_day:20000}));
 const points=G.fuelAnalysis(records);assert.deepEqual(points.map(p=>p.recordId),['fill-1','fill-2']);assert.equal(points[0].value,10);
});
function chart(){
 const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
 let source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/components/ReplicaChart.ets'),'utf8').split(' build(){')[0]+'\n}';
 source=source.replace('@Component','').replace('export struct ReplicaChart','export class ReplicaChart').replace(/@Prop @Watch\('draw'\)/g,'').replace(/@State /g,'');
 const log=[];const canvas=new Proxy({width:280,height:220},{get:(target,key)=>key in target?target[key]:(...args)=>{for(const a of args)if(typeof a==='number')assert.ok(Number.isFinite(a),key+' contains invalid coordinates');log.push([key,...args]);}});
 const exports={};vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText,{exports,require:id=>id.endsWith('Ui')?U:id.endsWith('AndroidPresentation')?P:{},CanvasRenderingContext2D:class{constructor(){return canvas;}},RenderingContextSettings:class{},Math});
 return {view:new exports.ReplicaChart(),log};
}
test('production statistics cards distinguish unknown from zero and rebuild updated metrics',()=>{
 const ts=require((process.env.DEVECO_HOME||'C:/Program Files/Huawei/DevEco Studio')+'/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
 let source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/components/StatisticsCard.ets'),'utf8').split('  build(){')[0]+'\n}';
 source=source.replace('@Component','').replace('export struct StatisticsCard','export class StatisticsCard').replace(/@Prop /g,'');
 const exports={};vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.CommonJS}}).outputText,{exports,require:id=>id.endsWith('Ui')?U:P});
 const card=new exports.StatisticsCard();assert.equal(card.items()[0].value,'—');card.values=[0,0,0];assert.equal(card.items()[0].value,'0.0 km');assert.equal(card.items()[2].hint,'消耗 0.00 L');
 card.values=[10000,3600,800];assert.equal(card.items()[0].value,'10.0 km');assert.equal(card.items()[2].value,'8.0 L/100km');
});
test('production fuel chart renders an average-only cache without indexing nonexistent points',()=>{
 const {view,log}=chart();view.average=8.93;view.draw();assert.ok(view.labels.some(x=>x.text==='历史均值 8.93'));
 assert.ok(!view.labels.some(x=>x.text.includes('至少录入')));
});
test('production charts handle empty, long Unicode labels, single points and rounded stacked bars',()=>{
 const {view,log}=chart();view.draw();view.points=[{label:'2026-10-08',value:8.5,detail:''}];view.draw();
 view.kind='cost';view.months=[{label:'2026-10',fuel:300,maintenance:100,daily:50,total:450}];view.draw();
 assert.ok(log.some(x=>x[0]==='arcTo'));assert.ok(view.labels.some(x=>x.text==='10月'));
 view.kind='daily';view.points=[{label:'车辆洗护🧼',value:42,detail:''}];view.draw();
 assert.ok(view.description().includes('车辆洗护🧼'));
});

test('production trip detail keeps local date and exact time visible on separate lines',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../entry/src/main/ets/pages/Index.ets'),'utf8');
 const formatBody=source.match(/private detailedTime\(seconds:number\):string \{([^\n]+)\}/)[1];
 const formatter=vm.runInNewContext('(seconds)=>{'+formatBody.replace(/:number/g,'')+'}',{Date,timeText:P.timeText});
 const start=new Date(2026,9,8,23,58,47),end=new Date(2026,9,9,0,12,9);
 const values=[formatter(start.getTime()/1000),formatter(end.getTime()/1000)];
 assert.deepEqual(values,['2026-10-08\n23:58:47','2026-10-09\n00:12:09']);
 // Execute the actual Text(value) modifier chain, so one-line clipping regresses this test.
 const metric=source.slice(source.indexOf('@Builder private metricBox('),source.indexOf('@Builder private metricPair('));
 const chain=metric.match(/Text\(value\)[^;]+;/)[0];
 for(const value of [...values,'8.2 L/100km']){
  const attributes={};let view;
  view=new Proxy({},{get:(_,key)=>(arg)=>{attributes[key]=arg;return view;}});
  vm.runInNewContext(chain,{value,color:'#181D27',readable:U.readable,FontWeight:{Medium:500},TextHeightAdaptivePolicy:{MIN_FONT_SIZE_FIRST:0},Text:()=>view});
  assert.equal(attributes.maxLines,value.includes('\n')?2:1);
  assert.ok(attributes.maxLines>=value.split('\n').length);
 }
});
