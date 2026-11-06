"""BRZ OBD Studio: native Windows capture and replay application."""
import asyncio
from collections import deque
import contextlib
from datetime import datetime
import io
import json
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace

from PySide6.QtCore import Qt, QThread, Signal, QTimer, QPointF
from PySide6.QtGui import QColor, QFont, QPainter, QPen, QPainterPath, QIcon, QPixmap
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QFrame, QLabel,
    QPushButton, QComboBox, QLineEdit, QFileDialog, QVBoxLayout, QHBoxLayout,
    QGridLayout, QTabWidget, QCheckBox, QTableWidget, QTableWidgetItem,
    QHeaderView, QPlainTextEdit, QMessageBox, QProgressBar)

from obd_recorder import capture, ReplayData, ReplayElm, body, normalize
from obd_ble_simulator import serve
from obd_telemetry import decode, TripMetrics

HOME = Path(sys.executable).resolve().parent if getattr(sys, 'frozen', False) else Path(__file__).resolve().parents[1]
ACCENT = '#50dec2'
STYLE = '''
QMainWindow, QWidget { background:#0c1422; color:#e7eef8; font-family:'Microsoft YaHei UI'; font-size:13px; }
QFrame#panel { background:#131f30; border:1px solid #25344b; border-radius:12px; }
QFrame#panel QLabel { background:transparent; border:none; }
QLabel#muted { color:#8b9cb5; }
QLabel#title { font-size:27px; font-weight:700; }
QLabel#value { font-size:34px; font-weight:700; color:#50dec2; }
QLabel#metric { font-size:24px; font-weight:600; color:#e7eef8; }
QLabel#badge { color:#50dec2; background:#14322f; border-radius:14px; padding:7px 16px; }
QPushButton { background:#23334b; border:1px solid #344760; border-radius:7px; padding:9px 14px; font-weight:600; }
QPushButton:hover { background:#304763; border-color:#6d8aa8; }
QPushButton#primary { background:#50dec2; border:none; color:#09231e; }
QPushButton#primary:hover { background:#7bebd5; }
QPushButton#danger { background:#39242f; border-color:#704253; color:#ffb5b5; }
QPushButton:disabled { background:#1a2637; color:#55657a; border-color:#233044; }
QLineEdit,QComboBox { background:#0c1626; border:1px solid #344760; border-radius:6px; padding:7px; min-height:24px; selection-background-color:#246f68; }
QComboBox QAbstractItemView { background:#18263a; selection-background-color:#2e5a63; }
QTabWidget::pane { border:0; }
QTabBar::tab { background:#131f30; color:#90a3bd; padding:11px 20px; border-bottom:2px solid transparent; }
QTabBar::tab:selected { color:#50dec2; border-bottom-color:#50dec2; }
QTableWidget { background:#111d2e; alternate-background-color:#152337; border:0; gridline-color:#223249; selection-background-color:#25535b; }
QHeaderView::section { background:#1c2b40; color:#9eb2cc; padding:9px; border:0; }
QPlainTextEdit { background:#0b1320; color:#93a9c5; border:1px solid #233249; border-radius:8px; padding:7px; font-family:Consolas,'Microsoft YaHei UI'; font-size:12px; }
QProgressBar { border:0; background:#203047; border-radius:3px; max-height:6px; }
QProgressBar::chunk { background:#50dec2; border-radius:3px; }
QCheckBox { spacing:8px; }
QToolTip { color:#eef4fa; background:#24364e; border:1px solid #55718f; }
'''


class LogSink(io.TextIOBase):
    def __init__(self, signal):
        self.signal, self.pending = signal, ''

    def write(self, value):
        self.pending += value
        while '\n' in self.pending:
            line, self.pending = self.pending.split('\n', 1)
            if line.strip():
                self.signal.emit(line)
        return len(value)

    def flush(self):
        pass


class Job(QThread):
    event = Signal(object)
    log = Signal(str)
    failed = Signal(str)

    def __init__(self, mode, args, parent=None):
        super().__init__(parent)
        self.mode, self.args = mode, args
        self.cancelled = threading.Event()
        self.loop = self.task = None
        self.byte_count = 0

    def report(self, event):
        if event['kind'] == 'rx':
            self.byte_count += len(event['hex']) // 2
        elif event['kind'] not in ('tx',):
            self.event.emit(dict(event, byte_count=self.byte_count))

    async def execute(self):
        if self.mode == 'scan':
            from bleak import BleakScanner
            devices = await BleakScanner.discover(timeout=8, return_adv=True)
            rows = [(device.address, adv.local_name or device.name or '未命名设备', adv.rssi)
                    for device, adv in devices.values()]
            self.event.emit(dict(kind='devices', devices=sorted(rows, key=lambda row: -row[2])))
        elif self.mode == 'record':
            await capture(self.args, self.report)
        else:
            data = ReplayData(self.args.file)
            self.event.emit(dict(kind='replay_info', duration=data.duration, pids=len(data.samples)))
            await serve(self.args, lambda: ReplayElm(data, self.args.speed, self.args.loop), self.report)

    def run(self):
        # Each job owns its asyncio loop on the Bluetooth worker thread (MTA).
        try:
            with contextlib.redirect_stdout(LogSink(self.log)), contextlib.redirect_stderr(LogSink(self.log)):
                with asyncio.Runner() as runner:
                    self.loop = runner.get_loop()
                    self.task = self.loop.create_task(self.execute())
                    if self.cancelled.is_set():
                        self.task.cancel()
                    runner.run(self.await_task())
        except asyncio.CancelledError:
            self.log.emit('已停止，录制文件已保存。' if self.mode == 'record' else '已停止。')
        except Exception as exc:
            self.failed.emit(f'{type(exc).__name__}: {exc}')
        finally:
            self.loop = self.task = None

    async def await_task(self):
        return await self.task

    def stop(self):
        if self.cancelled.is_set():
            return
        self.cancelled.set()
        loop, task = self.loop, self.task
        if loop and task:
            with contextlib.suppress(RuntimeError):
                loop.call_soon_threadsafe(task.cancel)


class Trend(QWidget):
    def __init__(self):
        super().__init__()
        self.points = deque(maxlen=2000)
        self.setMinimumHeight(160)

    def add(self, value):
        now = time.monotonic()
        self.points.append((now, value))
        while self.points and now - self.points[0][0] > 60:
            self.points.popleft()
        self.update()

    def paintEvent(self, event):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        left, top, width, height = 50, 16, self.width() - 70, self.height() - 45
        p.setFont(QFont('Segoe UI', 9))
        for i in range(5):
            y = top + height * i / 4
            p.setPen(QColor('#23334a'))
            p.drawLine(QPointF(left, y), QPointF(left + width, y))
            p.setPen(QColor('#758ba7'))
            p.drawText(3, int(y + 5), str(8000 - i * 2000))
        p.drawText(left, self.height() - 7, '−60 s')
        p.drawText(self.width() - 50, self.height() - 7, '现在')
        if not self.points:
            p.setPen(QColor('#70849e'))
            p.drawText(self.rect(), Qt.AlignCenter, '等待转速数据 · 最近 60 秒')
            return
        now = time.monotonic()
        path = QPainterPath()
        started = False
        for stamp, value in self.points:
            if now - stamp > 60:
                continue
            x = left + width * (1 - (now - stamp) / 60)
            y = top + height * (1 - min(8000, max(0, value)) / 8000)
            if not started:
                path.moveTo(x, y)
                started = True
            else:
                path.lineTo(x, y)
        p.setPen(QPen(QColor(ACCENT), 2.2))
        p.drawPath(path)


def label(text, kind=None):
    item = QLabel(text)
    if kind:
        item.setObjectName(kind)
    return item


def panel():
    frame = QFrame()
    frame.setObjectName('panel')
    return frame


class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle('BRZ OBD Studio · 数据采集与回放')
        self.resize(1220, 860)
        self.setMinimumSize(1050, 760)
        self.job = None
        self.closing = self.had_error = False
        self.started = None
        self.count = self.byte_count = 0
        self.rows, self.last_values = {}, {}
        self.trip = TripMetrics()
        self.trip_epoch = None
        self.folder = HOME / 'OBD-Recordings'
        self.last_recording = None
        self.build_ui()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(250)

    def build_ui(self):
        root = QWidget()
        self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        layout.setContentsMargins(24, 20, 24, 20)
        layout.setSpacing(16)
        heading = QHBoxLayout()
        title_box = QVBoxLayout()
        title_box.addWidget(label('BRZ  /  OBD STUDIO', 'title'))
        title_box.addWidget(label('实车数据采集  →  本地录制  →  仪表蓝牙回放', 'muted'))
        heading.addLayout(title_box)
        heading.addStretch()
        self.badge = label('●  就绪', 'badge')
        heading.addWidget(self.badge)
        layout.addLayout(heading)
        body_layout = QHBoxLayout()
        body_layout.setSpacing(18)
        side = panel()
        side.setFixedWidth(320)
        side_layout = QVBoxLayout(side)
        side_layout.setContentsMargins(17, 18, 17, 18)
        side_layout.setSpacing(13)
        self.tabs = QTabWidget()
        self.tabs.addTab(self.capture_tab(), '采集')
        self.tabs.addTab(self.replay_tab(), '回放')
        side_layout.addWidget(self.tabs)
        side_layout.addStretch()
        self.state_detail = label('先扫描设备，选择车上的 ELM327。', 'muted')
        self.state_detail.setWordWrap(True)
        side_layout.addWidget(self.state_detail)
        self.progress = QProgressBar()
        self.progress.setTextVisible(False)
        self.progress.setRange(0, 1)
        self.progress.setValue(0)
        side_layout.addWidget(self.progress)
        self.stop_button = QPushButton('停止任务')
        self.stop_button.setObjectName('danger')
        self.stop_button.setEnabled(False)
        self.stop_button.clicked.connect(self.stop)
        side_layout.addWidget(self.stop_button)
        hint = label('采集前请让手机和仪表断开 ELM。\n回放时，在仪表上选择这台电脑。', 'muted')
        hint.setWordWrap(True)
        side_layout.addWidget(hint)
        body_layout.addWidget(side)
        right = QVBoxLayout()
        right.setSpacing(14)
        cards = QHBoxLayout()
        self.cards = {}
        for command, title, unit in [('010C', '发动机转速', 'rpm'), ('010D', '车速', 'km/h'),
                                      ('0105', '冷却液温度', '°C'), ('015C', '机油温度', '°C')]:
            card = panel()
            box = QVBoxLayout(card)
            box.setContentsMargins(16, 12, 16, 12)
            box.addWidget(label(title, 'muted'))
            value = label('—', 'value')
            box.addWidget(value)
            box.addWidget(label(unit, 'muted'))
            self.cards[command] = value
            cards.addWidget(card)
        right.addLayout(cards)
        metrics = panel()
        metric_grid = QGridLayout(metrics)
        metric_grid.setContentsMargins(14, 10, 14, 10)
        metric_grid.setHorizontalSpacing(16)
        self.metric_values, self.metric_units = {}, {}
        for index, (key, title, unit) in enumerate([
                ('instant', '瞬时油耗', 'L/100km'),
                ('average_l100km', '有效采样平均油耗', 'L/100km'),
                ('distance_km', '本次里程 · 计算', 'km'),
                ('fuel_l', '本次耗油 · 计算', 'L'),
                ('fuel_rate_lph', '燃油流量', 'L/h'),
                ('012F', '燃油余量 · ECU', '%'),
                ('01A6', '总里程 · ECU', 'km'),
                ('0142', '电压 · ECU', 'V')]):
            box = QVBoxLayout()
            box.setSpacing(1)
            box.addWidget(label(title, 'muted'))
            value = label('—', 'metric')
            units = label(unit, 'muted')
            self.metric_values[key], self.metric_units[key] = value, units
            box.addWidget(value)
            box.addWidget(units)
            metric_grid.addLayout(box, index // 4, index % 4)
        right.addWidget(metrics)
        self.fuel_note = label('燃油来源：等待数据 · 累计仅覆盖有效采样时段', 'muted')
        self.fuel_note.setWordWrap(True)
        right.addWidget(self.fuel_note)
        graph = panel()
        graph_layout = QVBoxLayout(graph)
        graph_layout.addWidget(label('发动机转速  /  LIVE TREND', 'muted'))
        self.trend = Trend()
        graph_layout.addWidget(self.trend)
        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(['请求', '信号', '当前值', '原始响应', '更新'])
        self.table.setAlternatingRowColors(True)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.verticalHeader().hide()
        self.table.horizontalHeader().setSectionResizeMode(3, QHeaderView.Stretch)
        for col, width in ((0, 70), (1, 115), (2, 95), (4, 75)):
            self.table.setColumnWidth(col, width)
        detail_tabs = QTabWidget()
        detail_tabs.addTab(graph, '转速曲线')
        detail_tabs.addTab(self.table, '原始响应')
        right.addWidget(detail_tabs, 1)
        self.stats = label('00:00:00    ·    0 条响应    ·    0.0 KB 接收', 'muted')
        right.addWidget(self.stats)
        body_layout.addLayout(right, 1)
        layout.addLayout(body_layout, 1)
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(400)
        self.log.setFixedHeight(105)
        layout.addWidget(self.log)
        self.write_log('欢迎使用 OBD Studio。原始收发数据保存为 JSONL，支持离车回放。')

    def capture_tab(self):
        tab = QWidget()
        box = QVBoxLayout(tab)
        box.setContentsMargins(0, 16, 0, 0)
        box.setSpacing(12)
        self.scan_button = QPushButton('扫描蓝牙设备')
        self.scan_button.clicked.connect(self.scan)
        box.addWidget(self.scan_button)
        box.addWidget(label('ELM327 设备', 'muted'))
        self.device = QComboBox()
        self.device.setMinimumContentsLength(10)
        self.device.setSizeAdjustPolicy(QComboBox.AdjustToMinimumContentsLengthWithIcon)
        self.device.addItem('请先扫描设备', None)
        box.addWidget(self.device)
        self.address = QLineEdit()
        self.address.setPlaceholderText('或输入蓝牙地址 AA:BB:CC:DD:EE:FF')
        self.address.setToolTip('扫描不到时可以填写已知的 BLE 地址；填写后优先使用此地址。')
        box.addWidget(self.address)
        box.addWidget(label('采集范围', 'muted'))
        self.profile = QComboBox()
        for name, value in [('全部支持的标准 PID', 'all'), ('仪表常用 PID · 更快更新', 'gauge'), ('ZC6 · 包含 2101 私有数据', 'zc6')]:
            self.profile.addItem(name, value)
        box.addWidget(self.profile)
        self.folder_label = label('保存至：' + self.folder.name, 'muted')
        self.folder_label.setToolTip(str(self.folder))
        self.folder_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        box.addWidget(self.folder_label)
        self.folder_button = QPushButton('选择保存文件夹')
        self.folder_button.clicked.connect(self.choose_folder)
        box.addWidget(self.folder_button)
        self.record_button = QPushButton('●  开始录制')
        self.record_button.setObjectName('primary')
        self.record_button.clicked.connect(self.record)
        box.addWidget(self.record_button)
        note = label('记录全部收到的原始字节。标准 PID 轮询不等于整车 CAN 抓包。', 'muted')
        note.setWordWrap(True)
        box.addWidget(note)
        return tab

    def replay_tab(self):
        tab = QWidget()
        box = QVBoxLayout(tab)
        box.setContentsMargins(0, 16, 0, 0)
        box.setSpacing(13)
        box.addWidget(label('录制文件', 'muted'))
        self.replay_path = QLineEdit()
        self.replay_path.setPlaceholderText('选择 .jsonl 文件')
        box.addWidget(self.replay_path)
        self.file_button = QPushButton('浏览录制文件…')
        self.file_button.clicked.connect(self.choose_replay)
        box.addWidget(self.file_button)
        box.addWidget(label('播放速度', 'muted'))
        self.speed = QComboBox()
        for value in (.25, .5, 1, 2, 4):
            self.speed.addItem(f'{value:g} ×', value)
        self.speed.setCurrentIndex(2)
        box.addWidget(self.speed)
        self.loop_check = QCheckBox('循环回放')
        box.addWidget(self.loop_check)
        self.replay_button = QPushButton('▶  启动仪表回放')
        self.replay_button.setObjectName('primary')
        self.replay_button.clicked.connect(self.replay)
        box.addWidget(self.replay_button)
        text = label('电脑将模拟 ELM327。仪表连接后，第一次请求实时数据时开始计时。\n\n未录到的请求返回 NO DATA。', 'muted')
        text.setWordWrap(True)
        box.addWidget(text)
        return tab

    def write_log(self, text):
        self.log.appendPlainText(datetime.now().strftime('%H:%M:%S') + '  ' + text)

    def choose_folder(self):
        folder = QFileDialog.getExistingDirectory(self, '保存录制数据', str(self.folder))
        if folder:
            self.folder = Path(folder)
            self.folder_label.setText('保存至：' + self.folder.name)
            self.folder_label.setToolTip(folder)

    def choose_replay(self):
        path, _ = QFileDialog.getOpenFileName(self, '选择录制文件', str(self.folder), 'OBD 录制 (*.jsonl)')
        if path:
            self.replay_path.setText(path)

    def launch(self, mode, args):
        if self.job:
            return
        self.had_error = False
        self.set_busy(True)
        self.badge.setText({'scan': '●  扫描中', 'record': '●  连接中', 'replay': '●  准备回放'}[mode])
        self.state_detail.setText({'scan': '正在扫描附近 BLE 设备，约需 8 秒。',
            'record': '正在连接 ELM 并初始化，请稍候。', 'replay': '正在加载录制文件并启动蓝牙广播。'}[mode])
        if mode != 'scan':
            self.started = time.monotonic()
            self.count = self.byte_count = 0
            self.rows.clear()
            self.last_values.clear()
            self.trip = TripMetrics()
            self.trip_epoch = None
            self.table.setRowCount(0)
            self.trend.points.clear()
            for value in self.cards.values():
                value.setText('—')
            for value in self.metric_values.values():
                value.setText('—')
            self.fuel_note.setText('燃油来源：等待数据 · 累计仅覆盖有效采样时段')
        self.job = Job(mode, args, self)
        self.job.event.connect(self.on_event)
        self.job.log.connect(self.write_log)
        self.job.failed.connect(self.on_error)
        self.job.finished.connect(self.finished)
        self.job.start()

    def set_busy(self, busy):
        for widget in (self.scan_button, self.record_button, self.replay_button, self.device,
                       self.address, self.profile, self.folder_button, self.replay_path,
                       self.file_button, self.speed, self.loop_check):
            widget.setEnabled(not busy)
        self.stop_button.setEnabled(busy)
        self.progress.setRange(0, 0 if busy else 1)

    def scan(self):
        self.launch('scan', None)

    def record(self):
        address = self.address.text().strip() or self.device.currentData()
        if not address:
            QMessageBox.information(self, '选择设备', '请先扫描并选择 ELM327，或填写蓝牙地址。')
            return
        import re
        if not re.fullmatch(r'[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}', address):
            QMessageBox.information(self, '蓝牙地址格式', '请使用 AA:BB:CC:DD:EE:FF 格式。')
            return
        output = self.folder / (datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.jsonl')
        self.last_recording = output
        args = SimpleNamespace(address=address, output=output, profile=self.profile.currentData(),
                               duration=0, interval=.03, extra=[], verbose=False)
        self.launch('record', args)

    def replay(self):
        path = Path(self.replay_path.text().strip().strip('"'))
        if not path.is_file():
            QMessageBox.information(self, '选择文件', '请选择有效的 JSONL 录制文件。')
            return
        self.launch('replay', SimpleNamespace(file=path, speed=self.speed.currentData(),
            loop=self.loop_check.isChecked(), check=False, duration=0, verbose=False))

    def stop(self):
        if self.job:
            self.stop_button.setEnabled(False)
            self.badge.setText('●  停止中')
            self.state_detail.setText('正在断开蓝牙并完成文件保存…')
            self.job.stop()

    def on_error(self, error):
        self.had_error = True
        self.badge.setText('●  任务失败')
        self.state_detail.setText('操作未完成，请查看下方错误信息。已接收的录制数据会保留。')
        self.write_log('错误：' + error)

    def finished(self):
        job = self.job
        self.job = None
        self.set_busy(False)
        self.started = None
        if job.mode == 'record' and self.last_recording and self.last_recording.exists():
            self.replay_path.setText(str(self.last_recording))
            self.write_log('录制文件：' + str(self.last_recording))
            summary_path = self.last_recording.with_suffix('.summary.json')
            try:
                summary_path.write_text(json.dumps(dict(schema=1, coverage='valid_samples_only',
                    **self.trip.snapshot()), ensure_ascii=False, indent=2), encoding='utf-8')
                self.write_log('本次统计：' + str(summary_path))
            except OSError as exc:
                self.write_log('统计摘要保存失败（原始录制已保存）：' + str(exc))
        if not self.had_error:
            self.badge.setText('●  就绪')
            self.state_detail.setText('任务已结束。可以开始新的采集或回放。')
        job.deleteLater()
        if self.closing:
            self.close()

    def on_event(self, event):
        kind = event['kind']
        if kind == 'devices':
            self.device.clear()
            for address, name, rssi in event['devices']:
                self.device.addItem(f'{name} · {address[-8:]} · {rssi}', address)
                self.device.setItemData(self.device.count() - 1, address, Qt.ToolTipRole)
            if not event['devices']:
                self.device.addItem('未发现设备，请重试', None)
            self.write_log(f"扫描完成，发现 {len(event['devices'])} 个 BLE 设备。")
        elif kind == 'poll_plan':
            self.badge.setText('●  正在录制')
            self.state_detail.setText(f"正在轮询 {len(event['commands'])} 个请求。停止后可直接切换回放。")
        elif kind == 'ready':
            self.badge.setText('●  等待仪表')
            self.state_detail.setText('蓝牙广播已开启。在仪表 BLE SCAN 页面选择此电脑。')
        elif kind == 'clients':
            self.badge.setText('●  仪表已连接' if event['count'] else '●  等待仪表')
        elif kind == 'replay_info':
            self.write_log(f"录制信息：{event['duration']:.1f} 秒，{event['pids']} 种请求。")
        elif kind in ('reply', 'replay_reply'):
            if kind == 'reply' and event['phase'] != 'poll':
                return
            if event['command'].upper().startswith('AT'):
                return
            self.count += 1
            if kind == 'replay_reply':
                self.byte_count += len(event['hex']) // 2
            else:
                self.byte_count = event.get('byte_count', self.byte_count)
            self.update_response(event)

    def update_response(self, event):
        command = normalize(event['command'])
        response = body(bytes.fromhex(event['hex']), command).strip()
        value = decode(command, response) if event['complete'] else None
        timeline = event.get('timeline')
        if event['kind'] == 'replay_reply':
            if timeline is not None:
                epoch = (event.get('client'), timeline['cycle'])
                if epoch != self.trip_epoch:
                    self.trip = TripMetrics()
                    self.trip_epoch = epoch
                stamp = timeline['sampled_at']
                if stamp is None or timeline['t'] - stamp >= TripMetrics.FRESH_SECONDS:
                    value = None
                self.trip.update(command, value[1] if value else None, timeline['t'], stamp)
        else:
            self.trip.update(command, value[1] if value else None, event.get('t', time.monotonic()))
        self.render_metrics()
        if command not in self.rows:
            row = self.table.rowCount()
            self.rows[command] = row
            self.table.insertRow(row)
            for col in range(5):
                self.table.setItem(row, col, QTableWidgetItem())
        row = self.rows[command]
        now = time.monotonic()
        fields = [command, value[0] if value else '原始数据',
                  f'{value[1]:.1f} {value[2]}' if value else ('—' if event['complete'] else '响应不完整'),
                  response.replace('\r', ' | '), datetime.now().strftime('%H:%M:%S')]
        for col, text in enumerate(fields):
            self.table.item(row, col).setText(text)
            self.table.item(row, col).setToolTip(text)
        if command in self.cards:
            self.cards[command].setText(f'{value[1]:.0f}' if value else '—')
            self.last_values[command] = now
        if command in self.metric_values:
            self.metric_values[command].setText(f'{value[1]:.1f}' if value else '—')
            self.last_values[command] = now
        if command == '010C' and value:
            self.trend.add(value[1])

    def render_metrics(self):
        stats = self.trip.snapshot()
        for key in ('instant', 'average_l100km', 'distance_km', 'fuel_l', 'fuel_rate_lph'):
            value = stats[key]
            decimals = 3 if key == 'fuel_l' else 2 if key == 'distance_km' else 1
            self.metric_values[key].setText(f'{value:.{decimals}f}' if value is not None else '—')
        self.metric_units['instant'].setText(stats['instant_unit'])
        suffix = ' · 累计含 MAF 估算' if stats['includes_maf_estimate'] else ''
        self.fuel_note.setText(f"燃油来源：{stats['fuel_source']}{suffix} · 有效里程采样 {stats['distance_seconds']:.0f}s / 燃油采样 {stats['fuel_seconds']:.0f}s")

    def tick(self):
        if self.started is not None:
            elapsed = int(time.monotonic() - self.started)
            direction = '发送' if self.job and self.job.mode == 'replay' else '接收'
            self.stats.setText(f'{elapsed // 3600:02}:{elapsed // 60 % 60:02}:{elapsed % 60:02}    ·    '
                               f'{self.count:,} 条响应    ·    {self.byte_count / 1024:.1f} KB {direction}')
        for command, timestamp in self.last_values.items():
            if time.monotonic() - timestamp > 15:
                target = self.cards.get(command) or self.metric_values.get(command)
                if target:
                    target.setText('—')
        # Timers never integrate fuel/distance: delayed GUI events must not add travel.
        if self.last_values and time.monotonic() - max(self.last_values.values()) > 5:
            self.metric_values['instant'].setText('—')
            self.metric_values['fuel_rate_lph'].setText('—')
        self.trend.update()

    def closeEvent(self, event):
        if self.job:
            self.closing = True
            self.stop()
            event.ignore()
        else:
            event.accept()


def main():
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    app.setStyleSheet(STYLE)
    app.setApplicationName('BRZ OBD Studio')
    icon_path = Path(getattr(sys, '_MEIPASS', HOME / '.tmp' / 'obd-studio-build')) / 'studio.ico'
    if icon_path.exists():
        app.setWindowIcon(QIcon(str(icon_path)))
    window = Window()
    window.show()
    if '--smoke-test' in sys.argv:
        # Packaging smoke test: imports native Bluetooth modules without radio operations.
        import bleak
        from obd_ble_simulator import load_winrt
        load_winrt()
        output = Path(sys.argv[sys.argv.index('--smoke-test') + 1])
        def finish():
            window.grab().save(str(output))
            output.with_suffix('.json').write_text(json.dumps({'ok': True, 'frozen': bool(getattr(sys, 'frozen', False))}), encoding='utf-8')
            window.close()
        QTimer.singleShot(1200, finish)
    sys.exit(app.exec())


if __name__ == '__main__':
    main()
