import unittest
from policy import Policy, parse, duration
from protocol import blob, integer, fields, state
from bridge import Listener

ID1 = '00000000-0000-4000-8000-000000000001'
ID2 = '00000000-0000-4000-8000-000000000002'


def packet(phase, identifier=ID1, text=None, final=1):
    data = integer(1, phase) + blob(7, identifier)
    if text is not None: data += blob(15, blob(1, text) + integer(2, 1) + integer(4, final))
    return blob(252, data) + integer(253, 3)


class Checks(unittest.TestCase):
    def setUp(self):
        self.now, self.calls = 10, []
        self.policy = Policy(self.calls.append, lambda: self.now)

    def test_aliases(self):
        for target in ('пк', 'компьютер'):
            self.assertEqual(parse('Алиса, выключи ' + target + ', пожалуйста!').action, 'shutdown')
            self.assertEqual(parse('перезагрузи ' + target).action, 'restart')
            self.assertEqual(parse('заблокируй ' + target).action, 'lock')
            self.assertEqual(parse('поставь на паузу ' + target).action, 'playPause')
            self.assertEqual(parse('сделай ' + target + ' громче').value, 5)

    def test_volume(self):
        for suffix in ('пк', 'компьютера', 'на пк', 'на компьютере'):
            self.assertEqual(parse('поставь громкость ' + suffix + ' на пятьдесят пять процентов').value, 55)
            self.assertEqual(parse('поставь громкость ' + suffix + ' 0').value, 0)
        self.assertIsNone(parse('поставь громкость пк -5'))
        self.assertIsNone(parse('поставь громкость пк 101'))

    def test_reject(self):
        for phrase in ('не выключай пк', 'не выключи пк', 'скажи выключи пк', 'как выключи пк',
                       'выключи пк и музыку', 'выключи пк соседа', 'выключи пк завтра',
                       'запусти calc на пк', 'shutdown /s', 'поставь таймер выключения пк на -5 минут'):
            self.assertIsNone(parse(phrase), phrase)

    def test_duration(self):
        for text, expected in [('один час тридцать минут', 5400), ('24 часа', 86400),
                               ('30 минут', 1800), ('одну минуту', 60), ('5 секунд', 5)]:
            self.assertEqual(duration(text), expected)
        for text in ('25 часов', '0 секунд', '-5 минут', '5 минут 2 часа', '5 минут и музыку', '5 минут 5 минут'):
            self.assertIsNone(duration(text))

    def test_timers(self):
        for target in ('пк', 'компьютера'):
            timer = parse('поставь таймер выключения ' + target + ' на 30 минут')
            self.assertEqual((timer.timerAction, timer.durationSeconds), ('shutdown', 1800))
            self.assertNotIn('replace', timer.command(ID1))
        self.assertEqual(parse('заблокируй пк через 5 минут').timerAction, 'lock')
        self.assertEqual(parse('отмени таймер пк').action, 'cancelTimer')

    def test_confirmation_requires_prompt(self):
        self.assertEqual(self.policy.handle('выключи пк', ID1), 'ask_shutdown')
        self.assertEqual(self.calls, [])
        self.assertEqual(self.policy.handle('подтверди выключение пк', ID2), 'no_confirmation')
        self.assertEqual(self.calls, [])

    def test_confirm_once(self):
        self.policy.handle('выключи пк', ID1); self.policy.arm()
        self.assertEqual(self.policy.handle('подтверди выключение пк', ID2), 'done')
        self.assertEqual(self.calls[0]['action'], 'shutdown')
        self.assertEqual(self.policy.handle('подтверди выключение пк', ID2), 'no_confirmation')
        self.assertEqual(len(self.calls), 1)

    def test_timeout(self):
        self.policy.handle('перезагрузи пк', ID1); self.policy.arm(); self.now += 31
        self.assertEqual(self.policy.handle('подтверди перезагрузку пк', ID2), 'no_confirmation')
        self.assertEqual(self.calls, [])

    def test_wrong_confirmation(self):
        self.policy.handle('перезагрузи пк', ID1); self.policy.arm()
        self.assertEqual(self.policy.handle('подтверди выключение пк', ID2), 'no_confirmation')
        self.assertEqual(self.calls, [])

    def test_other_request_cancels(self):
        for text in ('нет', 'отмена', 'который час', 'да'):
            self.policy.handle('выключи пк', ID1); self.policy.arm()
            self.policy.handle(text, ID2)
            self.assertEqual(self.policy.handle('подтверди выключение пк', ID2), 'no_confirmation')
        self.assertEqual(self.calls, [])

    def test_timer_confirmation(self):
        self.assertEqual(self.policy.handle('выключи пк через 30 минут', ID1), 'ask_timer_shutdown')
        self.policy.arm()
        self.assertEqual(self.policy.handle('подтверди таймер выключения пк', ID2), 'done')
        self.assertEqual(self.calls[0]['durationSeconds'], 1800)

    def test_failure_consumes_confirmation(self):
        def fail(command): raise OSError('lost response')
        self.policy.execute = fail
        self.policy.handle('выключи пк', ID1); self.policy.arm()
        self.assertEqual(self.policy.handle('подтверди выключение пк', ID2), 'error')
        self.assertIsNone(self.policy.pending)

    def test_final_fresh_request_and_dedup(self):
        listener = Listener(self.calls.append, lambda: self.now)
        sent = []
        listener.feed(packet(3, text='заблокируй пк'), sent.append)
        listener.feed(packet(1), sent.append)
        listener.feed(packet(3, text='заблокируй пк', final=0), sent.append)
        listener.feed(packet(3, ID2, 'заблокируй пк'), sent.append)
        self.assertEqual(self.calls, [])
        listener.feed(packet(3, text='заблокируй пк'), sent.append)
        listener.feed(packet(3, text='заблокируй пк'), sent.append)
        listener.feed(packet(1), sent.append)
        listener.feed(packet(3, text='заблокируй пк'), sent.append)
        self.assertEqual(len(self.calls), 1)

    def test_prompt_before_confirmation(self):
        listener = Listener(self.calls.append, lambda: self.now); sent = []
        listener.feed(packet(1), sent.append)
        listener.feed(packet(3, text='выключи пк'), sent.append)
        self.assertEqual(self.calls, [])
        listener.feed(packet(0, ''), sent.append)
        self.assertGreater(listener.policy.armed_until, self.now)
        listener.feed(packet(1, ID2), sent.append)
        listener.feed(packet(3, ID2, 'подтверди выключение пк'), sent.append)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(len(sent), 3)

    def test_cancel_prompt_on_interruption(self):
        listener = Listener(self.calls.append, lambda: self.now); sent = []
        listener.feed(packet(1), sent.append)
        listener.feed(packet(3, text='выключи пк'), sent.append)
        listener.feed(packet(1, ID2), sent.append)
        listener.feed(packet(3, ID2, 'подтверди выключение пк'), sent.append)
        self.assertEqual(self.calls, [])

    def test_malformed(self):
        listener = Listener(self.calls.append)
        for malformed in (b'\xff', b'\0', blob(252, b'bad') + integer(253, 3), packet(1, 'wrong')):
            listener.feed(malformed, lambda _: None)
        self.assertEqual(self.calls, [])
        with self.assertRaises(ValueError): fields(integer(1, 1) + integer(1, 2))


if __name__ == '__main__': unittest.main()
