export interface NotificationOptions { inspection: boolean; insurance: boolean; maintenance: boolean; }
export function defaultNotificationOptions(): NotificationOptions { return {inspection:true,insurance:true,maintenance:true}; }
export function allowsReminder(options: NotificationOptions, key: string): boolean {
  switch (key.split('|')[0]) {
    case 'inspection': return options.inspection;
    case 'insurance': return options.insurance;
    case 'maintenance': return options.maintenance;
    default: return false;
  }
}
