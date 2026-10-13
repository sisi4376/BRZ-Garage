import { Row, displayTripId } from './Models';
export function recordDetails(row: Row): string {
  const lines: string[] = [];
  if (row.device_id) lines.push('仪表：' + row.device_id);
  if (row.trip_id !== undefined) lines.push('行程：#' + displayTripId(Number(row.trip_id)));
  if (row.interval_id !== undefined) lines.push('区间：#' + row.interval_id);
  if (row.name) lines.push('名称：' + row.name);
  if (row.title) lines.push('标题：' + row.title);
  if (row.date_epoch_day !== undefined) lines.push('日期：' + new Date(Number(row.date_epoch_day) * 86400000).toISOString().substring(0, 10));
  const validTime = (Number(row.flags) & 1) !== 0 || row.flags === undefined;
  if (row.start_epoch_s !== undefined) lines.push('开始：' + (validTime && Number(row.start_epoch_s) > 0 ? new Date(Number(row.start_epoch_s) * 1000).toLocaleString() : '时间未校准'));
  if (row.end_epoch_s !== undefined) lines.push('结束：' + (validTime && Number(row.end_epoch_s) > 0 ? new Date(Number(row.end_epoch_s) * 1000).toLocaleString() : '时间未校准'));
  if (row.distance_m !== undefined) lines.push('里程：' + (Number(row.distance_m) / 1000).toFixed(2) + ' km');
  if (row.duration_s !== undefined) lines.push('时长：' + Math.round(Number(row.duration_s) / 60) + ' 分钟');
  if (row.fuel_ml !== undefined) lines.push('燃油：' + (Number(row.fuel_ml) / 1000).toFixed(3) + ' L');
  if (row.avg_l100_x100 !== undefined) lines.push('平均油耗：' + (Number(row.avg_l100_x100) / 100).toFixed(2) + ' L/100km');
  if (row.max_speed_kmh !== undefined) lines.push('最高车速：' + (row.max_speed_kmh === null ? '暂无数据' : row.max_speed_kmh + ' km/h'));
  if (row.max_rpm !== undefined) lines.push('最高转速：' + (row.max_rpm === null ? '暂无数据' : row.max_rpm + ' rpm'));
  if (row.max_accel_x100 !== undefined && row.max_accel_x100 !== null) lines.push('最大加速度：' + (Number(row.max_accel_x100) / 100).toFixed(2) + ' m/s²');
  if (row.max_decel_x100 !== undefined && row.max_decel_x100 !== null) lines.push('最大减速度：' + (Number(row.max_decel_x100) / 100).toFixed(2) + ' m/s²');
  if (row.odometer_km !== undefined && row.odometer_km !== null) lines.push('里程表：' + row.odometer_km + ' km');
  if (row.odometer_x10_km !== undefined) lines.push('里程表：' + (Number(row.odometer_x10_km) / 10).toFixed(1) + ' km');
  if (row.litres !== undefined) lines.push('加油量：' + row.litres + ' L');
  if (row.detected_added_ml !== undefined) lines.push('识别加油量：' + (Number(row.detected_added_ml) / 1000).toFixed(1) + ' L');
  if (row.cost !== undefined || row.amount !== undefined) lines.push('金额：' + Number(row.cost ?? row.amount).toFixed(2));
  if (row.full_tank !== undefined) lines.push('加满：' + (row.full_tank === 1 ? '是' : '否'));
  if (row.draft !== undefined) lines.push('状态：' + (row.draft === 1 ? '草稿' : '已保存'));
  if (row.category !== undefined) lines.push('类型：' + (row.category === 1 ? '保养' : '其他费用'));
  if (row.maintenance_type !== undefined && row.maintenance_type !== null) lines.push('保养类别：' + ['', 'A\u4fdd', 'B\u4fdd', '\u8f6e\u80ce\u66f4\u6362', '\u5176\u4ed6\u7ef4\u4fee'][Number(row.maintenance_type)]);
  if (row.note) lines.push('备注：' + row.note);
  if (row.time_revised === 1) lines.push('时间已手动修正');
  if (row.data_revised === 1) lines.push('行程数据已手动修正');
  if (row.poll_requested !== undefined) {
    const names = ['发动机转速', '车速', '进气温度', '冷却液温度', '机油温度 ZD8', '控制模块电压', '发动机负荷', '节气门位置', '燃油系统状态', '指令当量比', '进气流量', '油箱液位', '燃油消耗率', '挡位', '机油温度 ZC6', '基础 PID', '燃油 PID', '挡位 PID'];
    lines.push('OBD 轮询自检');
    names.forEach((name: string, bit: number) => lines.push(name + '：' +
      ((Number(row.poll_received) & (1 << bit)) !== 0 ? '已收到响应' : (Number(row.poll_requested) & (1 << bit)) !== 0 ? '已请求，未收到响应' : Number(row.poll_requested) === 0 && Number(row.poll_received) === 0 ? '未记录' : '未请求')));
  }
  return lines.join('\n');
}
