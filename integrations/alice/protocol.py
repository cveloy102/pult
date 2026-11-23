"""Minimal bounded Quasar protobuf framing; unknown fields are skipped."""
import struct
import uuid


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result) + bytes([value])


def integer(key, value): return varint(key * 8) + varint(value)
def blob(key, data):
    if isinstance(data, str): data = data.encode()
    return varint(key * 8 + 2) + varint(len(data)) + data


def fields(data):
    pos, result = 0, {}
    def read():
        nonlocal pos
        value = 0
        for shift in range(0, 70, 7):
            if pos >= len(data): raise ValueError('Truncated protobuf')
            byte = data[pos]; pos += 1
            if shift == 63 and byte > 1: raise ValueError('Overflow')
            value |= (byte & 127) << shift
            if byte < 128: return value
        raise ValueError('Invalid varint')
    while pos < len(data):
        tag = read(); key, wire = tag >> 3, tag & 7
        if not key or key in result: raise ValueError('Invalid or duplicate field')
        if wire == 0: value = read()
        elif wire in (1, 2, 5):
            size = read() if wire == 2 else 8 if wire == 1 else 4
            if size > len(data) - pos: raise ValueError('Truncated field')
            value = data[pos:pos + size]; pos += size
        else: raise ValueError('Unsupported wire type')
        result[key] = value
    return result


def state(data):
    env = fields(data)
    if env.get(253) != 3: return None
    obj = fields(env[252])
    identifier = obj.get(7, b'').decode('ascii')
    if identifier: uuid.UUID(identifier)
    text = None
    if obj.get(1) == 3 and 15 in obj:
        request = fields(obj[15])
        if request.get(2) == 1 and request.get(4) == 1:
            raw = request.get(1, b'')
            if len(raw) <= 1024: text = raw.decode('utf-8')
    return obj.get(1), identifier, text


def remote(capability, key, method):
    body = blob(7, capability) + blob(key, method)
    packet = blob(252, body) + integer(253, 210)
    return struct.pack('<I', len(packet)) + packet


def cancel(identifier):
    return remote('AliceCapability', 8, integer(1, 6) + blob(10, identifier))


def sound(name):
    if name not in ('ask_shutdown', 'ask_restart', 'ask_timer_shutdown', 'ask_timer_restart',
                    'done', 'error', 'cancelled', 'no_confirmation'): raise ValueError('Unknown sound')
    method = integer(1, 0) + blob(2, '/data/opt/pult-alice/' + name + '.wav') + integer(3, 1)
    method += blob(4, integer(1, 0) + integer(2, 1))
    return remote('AudioFilePlayerCapability', 12, method)


def exact(sock, size):
    result = bytearray()
    while len(result) < size:
        part = sock.recv(size - len(result))
        if not part: raise ConnectionError('IPC disconnected')
        result.extend(part)
    return result


def receive(sock):
    size, = struct.unpack('<I', exact(sock, 4))
    if not 0 < size <= 524288: raise ValueError('Invalid frame size')
    return exact(sock, size)
