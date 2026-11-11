"""GUI lifecycle and signal display contracts; no Bluetooth hardware required."""
import asyncio
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from PySide6.QtWidgets import QApplication
from PySide6.QtTest import QTest
from obd_recorder_gui import Job, Window, decode

APP = QApplication.instance() or QApplication([])


class StudioTests(unittest.TestCase):
    def test_small_windows_keep_stop_visible_and_contents_reachable(self):
        window = Window()
        window.show()
        try:
            for width, height in [(1220, 688), (1024, 600), (800, 500), (640, 420)]:
                window.resize(width, height)
                QTest.qWait(30)
                window.reflow_cards()
                APP.processEvents()
                self.assertEqual(window.width(), width)
                self.assertEqual(window.height(), height)
                stop = window.stop_button
                self.assertTrue(window.rect().contains(stop.mapTo(window, stop.rect().bottomRight())))
                self.assertEqual(window.data_scroll.horizontalScrollBar().maximum(), 0)
                window.side_scroll.ensureWidgetVisible(window.record_button)
                APP.processEvents()
                viewport = window.side_scroll.viewport()
                self.assertTrue(viewport.rect().contains(window.record_button.mapTo(viewport, window.record_button.rect().center())))
            self.assertEqual(window._columns, 2)
            window.log_toggle.setChecked(True)
            APP.processEvents()
            self.assertLessEqual(window.height(), 420)
        finally:
            window.close()

    def test_replay_metrics_use_source_time_and_reset_on_loop(self):
        window = Window()
        try:
            for t in range(61):
                for command, response in [('010D', b'41 0D 3C\r>'), ('015E', b'41 5E 00 78\r>')]:
                    window.on_event(dict(kind='replay_reply', command=command, hex=response.hex(),
                        complete=True, client='gauge', timeline=dict(t=t, sampled_at=t, cycle=0)))
            self.assertEqual(window.metric_values['distance_km'].text(), '1.00')
            self.assertEqual(window.metric_values['fuel_l'].text(), '0.100')
            self.assertEqual(window.metric_values['average_l100km'].text(), '10.0')
            window.on_event(dict(kind='replay_reply', command='010D', hex=b'41 0D 3C\r>'.hex(),
                complete=True, client='gauge', timeline=dict(t=0, sampled_at=0, cycle=1)))
            self.assertEqual(window.metric_values['distance_km'].text(), '0.00')
            self.assertEqual(window.metric_values['fuel_l'].text(), '—')
        finally:
            window.close()

    def test_decoding_rejects_partial_and_no_data(self):
        self.assertEqual(decode('010C', '41 0C 1F 40')[1], 2000)
        self.assertEqual(decode('0105', '41 05 28')[1], 0)
        self.assertIsNone(decode('010C', 'NO DATA'))
        self.assertIsNone(decode('010C', '41 0C 1F'))
        self.assertIsNone(decode('010C', '41 0D 32'))

    def test_display_clears_failed_value_and_stale_value(self):
        window = Window()
        try:
            event = dict(kind='reply', command='010C', complete=True, phase='poll',
                         hex=b'41 0C 1F 40\r>'.hex(), byte_count=14)
            window.on_event(event)
            self.assertEqual(window.cards['010C'].text(), '2000')
            self.assertEqual(window.count, 1)
            window.last_values['010C'] -= 16
            window.tick()
            self.assertEqual(window.cards['010C'].text(), '—')
            window.on_event(dict(event, hex=b'NO DATA\r>'.hex()))
            self.assertEqual(window.cards['010C'].text(), '—')
            self.assertEqual(window.table.rowCount(), 1)
        finally:
            window.close()

    def test_stop_waits_for_async_cleanup(self):
        seen = []
        class SlowJob(Job):
            async def execute(self):
                seen.append('started')
                try:
                    await asyncio.sleep(60)
                finally:
                    await asyncio.sleep(.02)
                    seen.append('saved')
        job = SlowJob('record', None)
        job.start()
        for _ in range(100):
            QTest.qWait(10)
            if seen:
                break
        job.stop()
        self.assertTrue(job.wait(3000))
        self.assertEqual(seen, ['started', 'saved'])

    def test_stop_before_thread_starts(self):
        class NeverJob(Job):
            async def execute(self):
                raise AssertionError('Cancelled job must not start')
        job = NeverJob('scan', None)
        errors = []
        job.failed.connect(errors.append)
        job.stop()
        job.start()
        self.assertTrue(job.wait(3000))
        APP.processEvents()
        self.assertFalse(errors)


if __name__ == '__main__':
    unittest.main()
