"""Pair a separate, revocable Alice client using the code shown by Pult."""
import argparse
import getpass
import json
from pathlib import Path
import re
from bridge import Client, protect

parser = argparse.ArgumentParser()
parser.add_argument('--fingerprint', required=True, help='Full SHA-256 fingerprint shown by Pult')
parser.add_argument('--credentials', required=True, help='Output Windows DPAPI file')
args = parser.parse_args()
pin = args.fingerprint.replace(':', '').replace(' ', '').upper()
if not re.fullmatch('[0-9A-F]{64}', pin): parser.error('Expected full SHA-256 fingerprint')
client = Client({'fingerprint': pin, 'token': ''})
result = client.request('/api/pair', {'code': getpass.getpass('Код из Пульта: '), 'name': 'Алиса — локальная колонка'})
path = Path(args.credentials)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(protect(json.dumps({'fingerprint': pin, 'token': result['token']}).encode()))
print('Алиса привязана. Доступ можно отозвать в Пульте.')
