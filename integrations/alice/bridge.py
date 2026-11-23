"""Windows local bridge: authenticated SSH IPC -> pinned Pult HTTPS API.

Run with --config path.json. Credentials are Windows DPAPI, never sent to station.
"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import http.client
import json
import logging
from pathlib import Path
import socket
import ssl
import subprocess
import time

from policy import Policy
from protocol import cancel, receive, sound, state


def protect(data, decrypt=False):
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_byte))]
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    output = Blob()
    method = ctypes.windll.crypt32.CryptUnprotectData if decrypt else ctypes.windll.crypt32.CryptProtectData
    if not method(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise ctypes.WinError()
    try: return ctypes.string_at(output.data, output.size)
    finally: ctypes.windll.kernel32.LocalFree(output.data)


class Client:
    def __init__(self, credentials, port=47591):
        self.credentials, self.port = credentials, port

    def request(self, path, data=None):
        # Trust is a SHA-256 certificate pin, checked before sending credentials.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = http.client.HTTPSConnection('127.0.0.1', self.port, context=context, timeout=4)
        try:
            connection.connect()
            fingerprint = hashlib.sha256(connection.sock.getpeercert(True)).hexdigest().upper()
            if fingerprint != self.credentials['fingerprint'].upper(): raise ValueError('Pult certificate changed')
            headers = {'Authorization': 'Bearer ' + self.credentials['token']}
            body = None
            if data is not None:
                headers['Content-Type'] = 'application/json'
                body = json.dumps(data).encode()
            connection.request('POST' if data is not None else 'GET', path, body, headers)
            response = connection.getresponse()
            result = json.loads(response.read(16384))
            if response.status != 200: raise RuntimeError('Pult rejected request: ' + str(response.status))
            return result
        finally: connection.close()

    def execute(self, command):
        result = self.request('/api/command', command)
        if not result.get('accepted'): raise RuntimeError('Command not accepted')


class Listener:
    def __init__(self, execute, clock=time.monotonic):
        self.clock = clock
        self.policy = Policy(execute, clock)
        self.seen = set()
        self.order = []
        self.listening = None
        self.pending_sound = None

    def feed(self, packet, send):
        try: decoded = state(packet)
        except (ValueError, KeyError, TypeError, UnicodeError): return
        if decoded is None: return
        phase, identifier, text = decoded
        now = self.clock()
        if self.pending_sound:
            name, since = self.pending_sound
            if phase == 1 or now - since > 5:
                self.pending_sound = None
                self.policy.reset()
            elif phase == 0:
                self.pending_sound = None
                send(sound(name))
                if name.startswith('ask_'): self.policy.arm()
        if phase == 1:
            self.listening = (identifier, now) if identifier else None
            return
        if phase != 3 or text is None or not self.listening: return
        expected, since = self.listening
        if identifier != expected or now - since > 20: return
        self.listening = None
        if identifier in self.seen: return
        self.seen.add(identifier); self.order.append(identifier)
        if len(self.order) > 512: self.seen.remove(self.order.pop(0))
        response = self.policy.handle(text, identifier)
        if response is None: return
        send(cancel(identifier))
        self.pending_sound = (response, now)
        logging.info('Voice request handled: %s', response)  # No transcripts or credentials.


def tunnel(config):
    with socket.socket() as reserve:
        reserve.bind(('127.0.0.1', 0)); port = reserve.getsockname()[1]
    args = ['ssh', '-T', '-N', '-i', config['ssh_key'], '-p', str(config['ssh_port']),
            '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
            '-o', 'UserKnownHostsFile="' + config['known_hosts'] + '"', '-o', 'ConnectTimeout=5',
            '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=2',
            '-L', f'127.0.0.1:{port}:127.0.0.1:9875', config['ssh_target']]
    process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        deadline = time.monotonic() + 8
        while True:
            if process.poll() is not None: raise ConnectionError('SSH tunnel failed')
            try: stream = socket.create_connection(('127.0.0.1', port), timeout=1); break
            except OSError:
                if time.monotonic() > deadline: raise
                time.sleep(.1)
        stream.settimeout(45)
        return process, stream
    except BaseException:
        process.terminate(); process.wait(timeout=5)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--check', action='store_true', help='Check authentication without any commands')
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding='utf-8-sig'))
    logging.basicConfig(filename=config['log'], level=logging.INFO, format='%(asctime)s %(message)s')
    credentials = json.loads(protect(Path(config['credentials']).read_bytes(), decrypt=True))
    client = Client(credentials)
    if args.check:
        status = client.request('/api/status')
        print('Pult authenticated; protocol', status['protocol'])
        return
    # Single instance per user via a local file lock, no network control listener.
    import msvcrt
    with open(config['log'] + '.lock', 'a+b') as lock:
        if lock.seek(0, 2) == 0:
            lock.write(b'0'); lock.flush()
        lock.seek(0)
        try: msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError: return
        while True:
            process = stream = None
            try:
                process, stream = tunnel(config)
                listener = Listener(client.execute)  # Reconnect cancels all pending confirmations.
                logging.info('Station connected')
                while True: listener.feed(receive(stream), stream.sendall)
            except (OSError, ValueError, RuntimeError):
                logging.info('Connection unavailable; retrying without replaying commands')
            finally:
                if stream: stream.close()
                if process:
                    process.terminate()
                    try: process.wait(timeout=5)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
            time.sleep(3)


if __name__ == '__main__': main()
