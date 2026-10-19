#!/usr/bin/env python3
"""Create private deploy configuration once; never print secrets."""
import argparse, os, secrets
from pathlib import Path
from urllib.parse import urlparse
p=argparse.ArgumentParser();p.add_argument('--app-domain');p.add_argument('--admin-domain');p.add_argument('--local',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parent.parent; dest=root/'.env'
if dest.exists():raise SystemExit('.env artıq mövcuddur; avtomatik üzərinə yazılmır.')
if a.local:
 app='http://localhost';admin='http://localhost:18081';origins='http://localhost:18080,http://localhost:18081'
else:
 if not a.app_domain or not a.admin_domain:raise SystemExit('--app-domain və --admin-domain tələb olunur (və ya --local).')
 for value in (a.app_domain,a.admin_domain):
  if not __import__('re').fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]+\.[A-Za-z]{2,}',value):raise SystemExit('Yalnız domen adı daxil edin, protokol və yol olmadan.')
 if a.app_domain==a.admin_domain:raise SystemExit('Mobil və admin üçün iki ayrı domen/subdomen lazımdır.')
 app=a.app_domain;admin=a.admin_domain;origins=f'https://{app},https://{admin}'
def pin():
 while True:
  v=f'{secrets.randbelow(1000000):06d}'
  if v!='123456':return v
values={'APP_ADDRESS':':80' if a.local else app,'ADMIN_ADDRESS':':8088' if a.local else admin,'CORS_ORIGINS':origins,'DATABASE_PASSWORD':secrets.token_hex(24),'SIM_KEY':secrets.token_hex(32),'KYC_MEDIA_KEY':secrets.token_hex(32),'DEMO_PIN':pin(),'ADMIN_PIN':pin(),'BIND_IP':'127.0.0.1' if a.local else '0.0.0.0','HTTP_PORT':'18080' if a.local else '80','HTTPS_PORT':'18443' if a.local else '443','LOCAL_ADMIN_PORT':'18081' if a.local else '8088'}
fd=os.open(dest,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:f.write('\n'.join(f'{k}={v}' for k,v in values.items())+'\n')
print('Şəxsi .env yaradıldı (0600). PIN-lər yalnız həmin fayldadır. Deploy: ./scripts/deploy.sh')
