#!/usr/bin/env python3
"""Read-only deployment checks plus reversible sandbox operations; secrets stay local."""
import json,os,sys,uuid,urllib.request,urllib.error
from pathlib import Path
from urllib.parse import urlparse
root=Path(__file__).resolve().parent.parent
env=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if line and not line.startswith('#'))
local=env['APP_ADDRESS']==':80'
app=f"http://localhost:{env['HTTP_PORT']}" if local else 'https://'+env['APP_ADDRESS']
admin=f"http://localhost:{env['LOCAL_ADMIN_PORT']}" if local else 'https://'+env['ADMIN_ADDRESS']
def send(path,body=None,token='',device='deploy-check',base=app):
 headers={'Content-Type':'application/json','X-Device-Id':device,'Idempotency-Key':str(uuid.uuid4())}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(base+'/api/v1/'+path,headers=headers,data=json.dumps(body).encode() if body is not None else None)
 with urllib.request.urlopen(req,timeout=20) as r:return json.load(r)
def page(url):
 with urllib.request.urlopen(url,timeout=20) as r:return r.status,r.read().decode()
assert send('health')['status']=='UP';assert send('health/ready')['database']=='UP'
assert page(app)[0]==200;assert page(admin)[0]==200
for path in ['/vasp/portfolio','/cards/reveal','/sim/admin']:
 # Provider endpoints must never be reverse proxied. They may resolve to the SPA fallback.
 req=urllib.request.Request(app+path,data=b'{}',headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=10) as r:assert r.headers.get('Content-Type','').startswith('text/html')
 except urllib.error.HTTPError as e:assert e.code in (404,405)
u=send('auth/login',{'phone':'+994501000001','pin':env['DEMO_PIN'],'deviceId':'deploy-check'})['accessToken']
a=send('auth/login',{'phone':'+994501000005','pin':env['ADMIN_PIN'],'deviceId':'deploy-check'},base=admin)['accessToken']
assert send('dashboard',token=u)['user']['role']=='USER'
assert send('admin/overview',token=a,base=admin)['reconciliation']['differenceMinor']==0
try:send('admin/overview',token=u)
except urllib.error.HTTPError as e:assert e.code==403
else:raise AssertionError('USER accessed admin')
session=send('card-transfers/card-session',{},u)
assert page(app+session['formPath'])[0]==200
assert send('auth/logout',{},u)['loggedOut']
try:send('wallet',token=u)
except urllib.error.HTTPError as e:assert e.code==401
else:raise AssertionError('Logout did not revoke session')
send('auth/logout',{},a,base=admin)
print('PASS: web/admin, PostgreSQL readiness, auth/roles/logout, hosted card form, provider isolation and ledger reconciliation.')
