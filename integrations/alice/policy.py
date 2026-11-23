"""Exact Russian voice commands. No model, shell, or arbitrary API dispatch."""
import re
import time
import uuid
from dataclasses import dataclass


def normalize(text):
    text = text.lower().replace('ё', 'е').strip().rstrip(' .!,')
    text = re.sub(r'\s+', ' ', text)
    text = re.sub(r'^алиса[, ]+', '', text)
    return re.sub(r'[, ]+пожалуйста$', '', text).strip()


ONES = dict(zip('один одна одну два две три четыре пять шесть семь восемь девять десять одиннадцать двенадцать тринадцать четырнадцать пятнадцать шестнадцать семнадцать восемнадцать девятнадцать'.split(),
                [1, 1, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]))
TENS = dict(zip('двадцать тридцать сорок пятьдесят шестьдесят семьдесят восемьдесят девяносто'.split(), range(20, 100, 10)))


def number(text):
    if re.fullmatch(r'[0-9]{1,5}', text):
        return int(text)
    if text == 'ноль': return 0
    if text == 'сто': return 100
    if text in ONES: return ONES[text]
    parts = text.split()
    if len(parts) == 1: return TENS.get(text)
    if len(parts) == 2 and parts[0] in TENS and 1 <= ONES.get(parts[1], 0) <= 9:
        return TENS[parts[0]] + ONES[parts[1]]
    return None


def duration(text):
    # Units must appear once, in descending order. Entire text must be consumed.
    pattern = r'(.+?) (час|часа|часов|минута|минуту|минуты|минут|секунда|секунду|секунды|секунд)(?: |$)'
    pos, total, previous = 0, 0, 3601
    for match in re.finditer(pattern, text):
        if match.start() != pos: return None
        amount = number(match[1])
        unit = 3600 if match[2].startswith('час') else 60 if match[2].startswith('минут') else 1
        if amount is None or amount <= 0 or unit >= previous: return None
        total += amount * unit
        previous, pos = unit, match.end()
    return total if pos == len(text) and 1 <= total <= 86400 else None


@dataclass(frozen=True)
class Intent:
    action: str
    value: int | None = None
    timerAction: str | None = None
    durationSeconds: int | None = None

    @property
    def confirmation(self):
        action = self.timerAction if self.action == 'timer' else self.action
        if action not in ('shutdown', 'restart'): return None
        return ('timer_' if self.action == 'timer' else '') + action

    def command(self, request_id):
        result = {'requestId': str(uuid.uuid5(uuid.NAMESPACE_URL, 'pult-alice:' + request_id)), 'action': self.action}
        for key in ('value', 'timerAction', 'durationSeconds'):
            if getattr(self, key) is not None: result[key] = getattr(self, key)
        return result


def parse(text):
    text = normalize(text)
    timer = re.fullmatch(r'(?:поставь|установи) таймер (выключения|перезагрузки|блокировки) (?:компьютера|пк) на (.+)', text)
    if timer:
        seconds = duration(timer[2])
        if seconds is not None:
            return Intent('timer', timerAction={'выключения': 'shutdown', 'перезагрузки': 'restart', 'блокировки': 'lock'}[timer[1]], durationSeconds=seconds)
        return None
    power = re.fullmatch(r'(выключи|перезагрузи|заблокируй) (?:компьютер|пк)(?: через (.+))?', text)
    if power:
        action = {'выключи': 'shutdown', 'перезагрузи': 'restart', 'заблокируй': 'lock'}[power[1]]
        if power[2] is None: return Intent(action)
        seconds = duration(power[2])
        if seconds is not None: return Intent('timer', timerAction=action, durationSeconds=seconds)
        return None
    if re.fullmatch(r'отмени таймер (?:компьютера|пк|на компьютере|на пк)', text): return Intent('cancelTimer')
    if re.fullmatch(r'(?:сделай (?:компьютер|пк) громче|увеличь громкость (?:компьютера|пк))', text): return Intent('volumeStep', value=5)
    if re.fullmatch(r'(?:сделай (?:компьютер|пк) тише|уменьши громкость (?:компьютера|пк))', text): return Intent('volumeStep', value=-5)
    if re.fullmatch(r'(?:выключи|убери) звук на (?:компьютере|пк)', text): return Intent('volume', value=0)
    if re.fullmatch(r'(?:поставь на паузу|продолжи воспроизведение на) (?:пк|компьютер|компьютере)', text): return Intent('playPause')
    volume = re.fullmatch(r'(?:установи|поставь) громкость (?:компьютера|пк|на компьютере|на пк) (?:на )?(.+?)(?: процент| процента| процентов)?', text)
    if volume:
        value = number(volume[1])
        if value is not None and 0 <= value <= 100: return Intent('volume', value=value)
    return None


CONFIRM = {
    'подтверди выключение компьютера': 'shutdown',
    'подтверди перезагрузку компьютера': 'restart',
    'подтверди таймер выключения компьютера': 'timer_shutdown',
    'подтверди таймер перезагрузки компьютера': 'timer_restart',
}


class Policy:
    def __init__(self, execute, clock=time.monotonic):
        self.execute, self.clock = execute, clock
        self.pending = None
        self.armed_until = 0

    def reset(self):
        self.pending, self.armed_until = None, 0

    def arm(self):
        if self.pending: self.armed_until = self.clock() + 30

    def handle(self, text, request_id):
        text = normalize(text)
        text = re.sub(r' пк$', ' компьютера', text) if text.startswith('подтверди ') else text
        if text in ('отмена', 'не надо', 'нет', 'отмени команду компьютера'):
            had = self.pending is not None
            self.reset()
            return 'cancelled' if had else None
        if text in CONFIRM:
            pending = self.pending
            valid = pending and pending.confirmation == CONFIRM[text] and self.clock() < self.armed_until
            self.reset()  # Consume before dispatch, including failure or repeated confirmation.
            if not valid: return 'no_confirmation'
            return self.dispatch(pending, request_id)
        self.reset()  # A different request invalidates the outstanding confirmation.
        intent = parse(text)
        if intent is None: return None
        if intent.confirmation:
            self.pending = intent
            return 'ask_' + intent.confirmation
        return self.dispatch(intent, request_id)

    def dispatch(self, intent, request_id):
        try:
            self.execute(intent.command(request_id))
            return 'done'
        except Exception:
            return 'error'  # Never automatically retry a command with an uncertain outcome.
