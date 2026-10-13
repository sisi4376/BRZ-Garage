import { CalendarWeek } from './AndroidPresentation';
// Preserve decorative Android accents, but use a darker tone for small text and filled controls.
function channels(color: string): number[] {
  const value = parseInt(color.substring(1), 16);
  return [value >> 16, value >> 8 & 255, value & 255];
}
function luminance(color: string): number {
  const c = channels(color).map((n: number) => n / 255).map((n: number) => n <= .04045 ? n / 12.92 : Math.pow((n + .055) / 1.055, 2.4));
  return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
}
export function contrast(foreground: string, background: string): number {
  const a = luminance(foreground), b = luminance(background);
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}
export function readable(color: string, background: string = '#E8EBF1'): string {
  const c = channels(color); let result = color;
  for (let step = 1; contrast(result, background) < 4.5 && step <= 100; step++) {
    result = '#' + c.map((n: number) => Math.round(n * (1 - step / 100)).toString(16).padStart(2, '0')).join('');
  }
  return result;
}
export function darken(color: string): string {
  return '#' + channels(color).map((n: number) => Math.round(n * .72).toString(16).padStart(2, '0')).join('');
}
export function preciseDuration(seconds: number): string {
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, Math.floor(seconds) % 60].map((n: number) => String(n).padStart(2, '0')).join(':');
}
export function isLowFuel(percent:number|null):boolean {return percent!==null&&Number.isFinite(percent)&&percent<25;}
export function calendarHit(weeks: CalendarWeek[], x: number, y: number): string {
  if (x < 24 || y < 20) return '';
  const week = Math.floor((x - 24) / 15), day = Math.floor((y - 20) / 15);
  return week >= 0 && week < weeks.length && day >= 0 && day < 7 ? weeks[week].cells[day].date : '';
}
