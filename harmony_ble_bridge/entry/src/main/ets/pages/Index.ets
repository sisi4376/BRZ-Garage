import { partnerWakeManager } from '../bridge/PartnerWakeManager';
import { PartnerWakeSnapshot } from '../bridge/PartnerWakeTypes';

@Entry
@Component
struct Index {
  @State private supported: boolean = false;
  @State private registered: boolean = false;
  @State private scanning: boolean = false;
  @State private registering: boolean = false;
  @State private enabling: boolean = false;
  @State private status: string = '正在读取系统状态…';
  @State private deviceAddress: string = '';
  @State private error: string = '';
  @State private diagnostics: string = '';

  aboutToAppear(): void {
    partnerWakeManager.setListener((snapshot: PartnerWakeSnapshot) => {
      this.supported = snapshot.supported;
      this.registered = snapshot.registered;
      this.scanning = snapshot.scanning;
      this.registering = snapshot.registering;
      this.status = snapshot.status;
      this.deviceAddress = snapshot.deviceAddress;
      this.error = snapshot.error;
      this.diagnostics = snapshot.diagnostics;
    });
    partnerWakeManager.refresh();
  }

  aboutToDisappear(): void {
    partnerWakeManager.setListener(undefined);
  }

  build() {
    Scroll() {
      Column({ space: 18 }) {
        Row() {
          Column({ space: 5 }) {
            Text('BRZ Garage 授时桥')
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
              .fontColor('#111827')
            Text('Android 未连接时原生兜底授时')
              .fontSize(14)
              .fontColor('#64748B')
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)

          Text(this.registered ? '已启用' : ((this.scanning || this.registering) ? '注册中' : '未启用'))
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.registered ? '#047857' : '#64748B')
            .backgroundColor(this.registered ? '#D1FAE5' : '#E2E8F0')
            .borderRadius(20)
            .padding({ left: 12, right: 12, top: 7, bottom: 7 })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        Column({ space: 18 }) {
          Row() {
            Column({ space: 6 }) {
              Text('仪表上电自动授时')
                .fontSize(18)
                .fontWeight(FontWeight.Medium)
                .fontColor('#111827')
              Text('一次注册，之后由鸿蒙系统发现仪表')
                .fontSize(13)
                .fontColor('#64748B')
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)

            Toggle({
              type: ToggleType.Switch,
              isOn: this.registered || this.scanning || this.registering || this.enabling
            })
              .selectedColor('#0EA5E9')
              // Always allow the first attempt so the user can grant Bluetooth
              // permission and see the actual compatibility error. While the
              // one-time scan is running, keep the switch visibly on.
              .enabled(!this.enabling && !this.scanning && !this.registering)
              .onChange(async (isOn: boolean) => {
                if (this.enabling || this.scanning || this.registering || isOn === this.registered) return;
                this.enabling = true;
                try {
                  if (isOn) {
                    await partnerWakeManager.enableFromUser();
                  } else {
                    await partnerWakeManager.disableFromUser();
                  }
                } finally {
                  this.enabling = false;
                }
              })
          }
          .width('100%')

          Divider().color('#E2E8F0')

          Row({ space: 12 }) {
            Circle({ width: 12, height: 12 })
              .backgroundColor(this.registered ? '#10B981' : (this.scanning ? '#38BDF8' : '#94A3B8'))
            Column({ space: 4 }) {
              Text(this.status)
                .fontSize(16)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.error.length > 0 ? '#B91C1C' : '#1E293B')
              if (this.deviceAddress.length > 0) {
                Text(this.deviceAddress)
                  .fontSize(13)
                  .fontColor('#64748B')
              }
            }
            .alignItems(HorizontalAlign.Start)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          if (this.diagnostics.length > 0) {
            Text(`连接诊断 · 2.1.5\n${this.diagnostics}`)
              .fontSize(12)
              .lineHeight(19)
              .fontColor('#64748B')
              .width('100%')
          }
        }
        .width('100%')
        .padding(20)
        .backgroundColor('#FFFFFF')
        .borderRadius(22)
        .shadow({ radius: 18, color: '#140F172A', offsetX: 0, offsetY: 6 })

        Column({ space: 12 }) {
          Text('不影响仪表数据采集')
            .fontSize(17)
            .fontWeight(FontWeight.Medium)
            .fontColor('#0F172A')
            .width('100%')
          Text('Android 连接拥有最高优先级。系统发现仪表后先等待 8 秒；只有 Android 没有接管时才短暂连接，向专用特征写入一次时间并立即断开。不读取行程、不订阅实时数据、不修改设置，也不运行常驻扫描。')
            .fontSize(13)
            .lineHeight(21)
            .fontColor('#64748B')
            .width('100%')
        }
        .width('100%')
        .padding(20)
        .backgroundColor('#FFFFFF')
        .borderRadius(22)

        Text('需要仪表固件 3.3.1 或更高版本。首次注册请暂时关闭 Android 的自动连接，让仪表恢复广播；授时桥会发起鸿蒙原生配对，请确认系统弹窗。注册成功后恢复 Android 自动连接即可。')
          .fontSize(13)
          .lineHeight(21)
          .fontColor('#64748B')
          .padding({ left: 4, right: 4 })
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 28, bottom: 36 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F4F7FB')
  }
}
