#!/usr/bin/env python3
"""Run isolated integration checks against packaged API and simulator."""
import socket, shutil, concurrent.futures, re, urllib.parse, hashlib, json, os, pathlib, subprocess, tempfile, time, unittest, urllib.error, urllib.request, uuid
ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:18080/api/v1/'
SIM_BASE = 'http://127.0.0.1:18081'
class Client:
    def __init__(self, phone, device='demo-device', pin='123456'):
        self.device, self.token = device, ''
        self.token = self.send('auth/login', {'phone':phone,'pin':pin,'deviceId':device})['accessToken']
    def send(self,path,body=None,key=None,proof=None,status=200):
        headers={'Content-Type':'application/json','Authorization':'Bearer '+self.token,'X-Device-Id':self.device,'Idempotency-Key':key or str(uuid.uuid4())}
        if proof: headers['X-SCA-Proof']=proof
        req=urllib.request.Request(BASE+path,data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None,headers=headers)
        try:
            with urllib.request.urlopen(req,timeout=20) as r: code,result=r.status,json.load(r)
        except urllib.error.HTTPError as e: code,result=e.code,json.load(e)
        assert (code in status if isinstance(status,tuple) else code==status),(path,code,result)
        return result
    def sca(self,path,body):
        return self.send('auth/sca',{'pin':'123456','payload':{'action':path,'data':body}})['proof']
    def confirmed(self,path,body):
        return self.send(path,body,proof=self.sca(path,body))
    def balance(self): return self.send('wallet')['availableBalanceMinor']
    def tbody(self,phone,amount,note='',**extra):
        """A P2P transfer body with the single-use recipient token the server issues when the recipient is looked up."""
        token=self.send('transfers/resolve?phone='+urllib.parse.quote(phone))['recipientToken']
        return {'phone':phone,'amountMinor':amount,'note':note,**extra,'recipientToken':token}
    def qr_body(self,payload,amount,source='WALLET',**extra):
        """A QR payment body with the single-use payment token the server issues when the QR is parsed."""
        token=self.send('payments/qr/parse',{'qrPayload':payload})['paymentToken']
        return {'qrPayload':payload,'amountMinor':amount,'fundingSource':source,'paymentToken':token,**extra}
class Checks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.a=Client('+994501000001');cls.b=Client('+994501000002');cls.admin=Client('+994501000005');cls.merchant=Client('+994501000007');cls.compliance=Client('+994501000006')
    def test_01_auth_and_role_boundaries(self):
        self.a.send('admin/overview',status=403)
        self.merchant.send('admin/overview',status=403)
        self.compliance.send('admin/feature-flags/crypto.enabled',{'value':True},status=403)
        req=urllib.request.Request(BASE+'wallet')
        with self.assertRaises(urllib.error.HTTPError) as e: urllib.request.urlopen(req)
        self.assertEqual(e.exception.code,401)
    def test_02_integer_amounts(self):
        for a in (0,-5,1.5,100000001):
            self.a.send('topups',{'amountMinor':a,'source':'CARD'},status=422 if isinstance(a,int) else 400)
    def test_03_concurrent_idempotency(self):
        before=self.a.balance();key=str(uuid.uuid4());body={'amountMinor':1234,'source':'CARD'}
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            replies=list(pool.map(lambda _:self.a.send('topups',body,key=key),range(6)))
        self.assertEqual(len({r['id'] for r in replies}),1);self.assertEqual(self.a.balance(),before+1234)
        self.a.send('topups',{'amountMinor':1235,'source':'CARD'},key=key,status=422)
    def test_04_transfer_cancellation_and_completion(self):
        before=self.a.balance();other=self.b.balance()
        t=self.a.send('transfers',self.a.tbody('+994501000002',1000,'pizza'))
        self.assertEqual(self.a.balance(),before-1000)
        self.b.send('transfers/'+t['id'],status=403)
        self.a.send('transfers/'+t['id']+'/cancel',{})
        self.assertEqual(self.a.balance(),before)
        t=self.a.send('transfers',self.a.tbody('+994501000002',1000,'pizza'))
        time.sleep(5.7)
        self.assertEqual(self.a.send('transfers/'+t['id'])['status'],'COMPLETED')
        self.assertEqual(self.a.balance(),before-1000);self.assertEqual(self.b.balance(),other+1000)
    def test_05_no_overspend(self):
        available=self.b.balance()
        results=[]
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            for result in pool.map(lambda _:self.b.send('transfers',self.b.tbody('+994501000001',6000,'test'),status=(200,422)),range(5)): results.append(result)
        self.assertEqual(sum('id' in r for r in results),4)
        self.assertEqual(sum(r.get('code')=='INSUFFICIENT_FUNDS' for r in results),1)
        self.assertGreaterEqual(self.b.balance(),0)
        for t in results:
            if 'id' in t:self.b.send('transfers/'+t['id']+'/cancel',{})
        self.assertEqual(self.b.balance(),available)
    def test_06_qr_crc_replay_refund(self):
        i=self.merchant.send('merchant/qr/dynamic',{'amountMinor':1500});p=i['qrPayload']
        self.a.send('payments/qr/parse',{'qrPayload':p[:-1]+('0' if p[-1]!='0' else '1')},status=422)
        late=self.b.qr_body(p,1500)
        before=self.a.balance();pay=self.a.send('payments/qr',self.a.qr_body(p,1500))
        self.assertEqual(self.a.balance(),before-1500)
        self.b.send('payments/qr',late,status=422)
        self.merchant.send('merchant/payments/'+pay['id']+'/refund',{});self.assertEqual(self.a.balance(),before)
    def test_07_sca_payload_binding_and_replay(self):
        path='transfers';body=self.a.tbody('+994501000002',7000,'test')
        self.a.send(path,body,status=403);proof=self.a.sca(path,body)
        self.a.send(path,{**body,'amountMinor':8000},proof=proof,status=422)
        t=self.a.send(path,body,proof=proof)
        self.a.send(path,body,proof=proof,status=422)
        self.a.send('transfers/'+t['id']+'/cancel',{})
    def test_08_fraud_hold_release(self):
        new=Client('+994501000001','new-device-for-fraud');before=new.balance()
        t=new.send('transfers',new.tbody('+994501000003',30000,'test'))
        self.assertEqual(t['status'],'UNDER_REVIEW');self.assertEqual(new.balance(),before-30000)
        case=next(f for f in self.admin.send('admin/fraud/cases') if f['transferId']==t['id'])
        self.admin.send('admin/fraud/cases/'+case['id']+'/reject',{});self.assertEqual(new.balance(),before)
    def test_09_credit_consent_offers_disbursement(self):
        body={'amountMinor':80000,'termMonths':12,'purpose':'Noutbuk','consent':True}
        self.a.send('loan-applications',{**body,'consent':False},status=422)
        app=self.a.send('loan-applications',body);offers=self.a.send('loan-applications/'+app['id']+'/offers')
        self.assertEqual(len(offers),2);self.assertEqual(sum(o['approved'] for o in offers),1)
        o=next(o for o in offers if o['approved']);before=self.a.balance()
        path='loan-offers/'+o['id']+'/accept';self.a.confirmed(path,{})
        self.assertEqual(self.a.balance(),before+80000)
        schedule=self.a.send('partner-loans/'+self.a.send('dashboard')['partnerLoans'][0]['id']+'/schedule')
        self.assertEqual(len(schedule),12);self.assertEqual(sum(i['amountMinor'] for i in schedule),o['totalPayableMinor'])
        loan_id=self.a.send('dashboard')['partnerLoans'][0]['id']
        self.a.confirmed('partner-loans/'+loan_id+'/installments/'+schedule[0]['id']+'/pay',{})
        self.assertEqual(self.a.balance(),before+80000-schedule[0]['amountMinor'])
        self.a.send(path,{},status=422)
    def test_10_bnpl_limit_and_repayment(self):
        i=self.merchant.send('merchant/qr/dynamic',{'amountMinor':4500})
        self.a.confirmed('payments/qr',self.a.qr_body(i['qrPayload'],4500,'BNPL'))
        i=self.merchant.send('merchant/qr/dynamic',{'amountMinor':1000})
        b=self.a.qr_body(i['qrPayload'],1000,'BNPL')
        self.a.send('payments/qr',b,proof=self.a.sca('payments/qr',b),status=422)
        loan=next(l for l in self.a.send('bnpl/loans') if l['status']=='ACTIVE')
        self.a.confirmed('bnpl/loans/'+loan['id']+'/repay',{})
        self.assertEqual(self.a.send('bnpl/eligibility')['availableMinor'],5000)
    def test_11_crypto_gate_trade_and_qr(self):
        self.a.send('crypto/portfolio',status=403)
        self.admin.send('admin/feature-flags/crypto.enabled',{'value':True})
        initial=self.a.send('crypto/portfolio')['balanceMicro'];before=self.a.balance()
        q=self.a.send('crypto/quotes',{'asset':'USDT','side':'BUY','amountMinor':1700})
        self.a.confirmed('crypto/orders',{'quoteId':q['id'],'riskAccepted':True})
        self.assertEqual(self.a.balance(),before-1700);self.assertEqual(self.a.send('crypto/portfolio')['balanceMicro'],initial+10000000)
        self.a.send('crypto/orders',{'quoteId':q['id'],'riskAccepted':True},status=422)
        i=self.merchant.send('merchant/qr/dynamic',{'amountMinor':1700})
        self.a.confirmed('payments/qr',self.a.qr_body(i['qrPayload'],1700,'CRYPTO',riskAccepted=True))
        self.assertEqual(self.a.send('crypto/portfolio')['balanceMicro'],initial)
    def test_11b_expired_crypto_quote(self):
        before=self.a.balance()
        q=self.a.send('crypto/quotes',{'asset':'USDT','side':'SELL','amountMinor':170})
        time.sleep(30.2)
        self.a.send('crypto/orders',{'quoteId':q['id'],'riskAccepted':True},status=422)
        self.assertEqual(self.a.balance(),before)
    def test_13_reconciliation(self):
        r=self.admin.send('admin/reconciliation/run',{})
        self.assertEqual(r['status'],'MATCHED');self.assertEqual(r['differenceMinor'],0)
    def test_13b_split_progress_and_requests(self):
        split=self.a.send('splits',{'totalMinor':6000,'mode':'EQUAL','note':'pizza','participants':[{'phone':'+994501000002'},{'phone':'+994501000003'}]})
        view=self.a.send('splits/'+split['id'])
        self.assertEqual(view['paidMinor'],2000);self.assertEqual(view['paidCount'],1)
        self.b.send('splits/'+split['id'],status=403)
        req=next(r for r in self.b.send('payment-requests') if r.get('splitId')==split['id'])
        self.b.confirmed('payment-requests/'+req['id']+'/pay',{})
        view=self.a.send('splits/'+split['id'])
        self.assertEqual(view['paidMinor'],4000);self.assertEqual(view['paidCount'],2)
    def demo_card(self,pan):
        session=self.a.send('card-transfers/card-session',{})
        self.b.send('card-sessions/'+session['id'],status=403)
        form_url=SIM_BASE+session['formPath']
        html=urllib.request.urlopen(form_url).read().decode()
        nonce=re.search(r'name="nonce" value="([^"]+)"',html).group(1)
        invalid=urllib.request.Request(form_url,data=urllib.parse.urlencode({'pan':'4242424242424241','nonce':nonce}).encode())
        with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(invalid)
        self.assertEqual(error.exception.code,422)
        req=urllib.request.Request(form_url,data=urllib.parse.urlencode({'pan':pan,'nonce':nonce}).encode())
        with urllib.request.urlopen(req) as reply:self.assertEqual(reply.status,200)
        card=self.a.send('card-sessions/'+session['id'])
        self.assertNotIn(pan,json.dumps(card))
        self.assertEqual(card['last4'],pan[-4:])
        return session
    def wait_card(self,id,status):
        for _ in range(80):
            t=self.a.send('card-transfers/'+id)
            if t['status']==status:return t
            time.sleep(.15)
        self.fail('Card transfer did not reach '+status+': '+str(t))
    def test_15_card_transfer_approval_and_fee(self):
        session=self.demo_card('4242424242424242')
        quote=self.a.send('card-transfers/fee-quote',{'sessionId':session['id'],'amountMinor':2000})
        self.assertEqual(quote['feeMinor'],30);self.assertEqual(quote['totalMinor'],2030)
        body={'quoteId':quote['id'],'amountMinor':2000}
        self.a.send('card-transfers',{**body,'pan':'4242424242424242'},status=422)
        self.a.send('card-transfers',body,status=403)
        self.a.send('card-transfers',{**body,'amountMinor':2100},status=422)
        before=self.a.balance();key=str(uuid.uuid4());proof=self.a.sca('card-transfers',body)
        t=self.a.send('card-transfers',body,key=key,proof=proof)
        again=self.a.send('card-transfers',body,key=key,proof=proof)
        self.assertEqual(t['id'],again['id']);self.assertEqual(self.a.balance(),before-2030)
        done=self.wait_card(t['id'],'COMPLETED')
        self.assertEqual(self.a.balance(),before-2030);self.assertNotIn('cardToken',done)
        self.a.send('card-transfers',body,status=422)
        self.assertEqual(self.admin.send('admin/reconciliation/run',{})['differenceMinor'],0)
    def test_16_card_transfer_decline_releases_hold(self):
        session=self.demo_card('4000000000000002')
        q=self.a.send('card-transfers/fee-quote',{'sessionId':session['id'],'amountMinor':1000})
        before=self.a.balance();t=self.a.confirmed('card-transfers',{'quoteId':q['id'],'amountMinor':1000})
        self.assertEqual(self.a.balance(),before-1030)
        self.wait_card(t['id'],'FAILED');self.assertEqual(self.a.balance(),before)
    def test_17_card_transfer_unknown_is_not_refunded_early(self):
        session=self.demo_card('4000000000009995')
        q=self.a.send('card-transfers/fee-quote',{'sessionId':session['id'],'amountMinor':1500})
        before=self.a.balance();t=self.a.confirmed('card-transfers',{'quoteId':q['id'],'amountMinor':1500})
        self.wait_card(t['id'],'UNDER_REVIEW')
        self.assertEqual(self.a.balance(),before-1530)
        path='admin/card-transfers/'+t['id']+'/resolve'
        self.compliance.send(path,{'decision':'DECLINED'},status=403)
        self.admin.send(path,{'decision':'DECLINED'})
        self.wait_card(t['id'],'FAILED');self.assertEqual(self.a.balance(),before)
    def test_18_card_transfer_saved_card_and_own_withdrawal(self):
        session=self.demo_card('4169738855551234')
        q=self.a.send('card-transfers/fee-quote',{'sessionId':session['id'],'amountMinor':1000,'purpose':'SEND'})
        self.assertEqual(q['bankName'],'Kapital Bank');self.assertEqual(q['brand'],'VISA');self.assertEqual(q['feeMinor'],30)
        t=self.a.confirmed('card-transfers',{'quoteId':q['id'],'amountMinor':1000,'saveAs':'Kapital Maaş'})
        self.wait_card(t['id'],'COMPLETED')
        cards=self.a.send('recipient-cards')
        saved=next(c for c in cards if c['last4']=='1234')
        self.assertEqual(saved['nickname'],'Kapital Maaş')
        q_own=self.a.send('card-transfers/fee-quote',{'cardId':saved['id'],'amountMinor':1200,'purpose':'WITHDRAW_OWN'})
        self.assertEqual(q_own['feeMinor'],0);self.assertEqual(q_own['totalMinor'],1200)
        before=self.a.balance()
        t_own=self.a.confirmed('card-transfers',{'quoteId':q_own['id'],'amountMinor':1200})
        self.wait_card(t_own['id'],'COMPLETED');self.assertEqual(self.a.balance(),before-1200)
        self.a.send('recipient-cards/'+saved['id']+'/delete',{})
        self.assertEqual(self.admin.send('admin/reconciliation/run',{})['differenceMinor'],0)
    def test_19_ekyc_fin_liveness_sima_and_rejections(self):
        import base64
        selfie_bytes=(pathlib.Path(__file__).resolve().parent/'fixtures'/'selfie.jpg').read_bytes()
        selfie='data:image/jpeg;base64,'+base64.b64encode(selfie_bytes).decode()
        otp=self.a.send('auth/otp',{'phone':'+994509998877'})
        self.assertEqual(len(otp['livenessActions']),3)
        base_reg={'otpId':otp['otpId'],'otp':otp['demoCode'],'name':'Elvin','surname':'Kərimov','birthDate':'2002-05-10','pin':'4821','deviceId':'demo-device','termsAccepted':True,'dataConsent':True,'fin':'7AZ5544','idSeries':'AZE12345678','facesCount':1,'simaSigned':True,'livenessMode':'MEDIAPIPE_WEB','livenessActions':otp['livenessActions'],'selfie':selfie}
        # phone-first entry and the separate SMS step
        self.assertTrue(self.a.send('auth/identify',{'phone':'+994501000001'})['registered'])
        self.assertFalse(self.a.send('auth/identify',{'phone':'+994509998877'})['registered'])
        self.a.send('auth/identify',{'phone':'abc'},status=422)
        self.a.send('auth/register',base_reg,status=422)
        self.a.send('auth/otp/verify',{'otpId':otp['otpId'],'otp':'12345x'},status=422)
        self.assertTrue(self.a.send('auth/otp/verify',{'otpId':otp['otpId'],'otp':otp['demoCode']})['verified'])
        locked=self.a.send('auth/otp',{'phone':'+994509998844'})
        wrong=str((int(locked['demoCode'])+1)%1000000).zfill(6)
        for attempt in range(4):self.a.send('auth/otp/verify',{'otpId':locked['otpId'],'otp':wrong},status=422)
        self.a.send('auth/otp/verify',{'otpId':locked['otpId'],'otp':wrong},status=429)
        self.a.send('auth/otp/verify',{'otpId':locked['otpId'],'otp':locked['demoCode']},status=422)
        # every required KYC input is enforced
        for patch in ({'pin':'123456'},{'pin':'12'},{'surname':''},{'name':'1'},{'idSeries':'XX123'},{'fin':'123'},{'selfie':''},{'selfie':'data:image/jpeg;base64,'+base64.b64encode(b'not an image'*400).decode()},{'livenessActions':['BLINK']},{'livenessMode':'NONE'},{'facesCount':2},{'simaSigned':False},{'fin':'17YASLI'},{'fin':'LOWFACE'}):
            self.a.send('auth/register',{**base_reg,**patch},status=422)
        without_kyc={k:v for k,v in base_reg.items() if k not in ('facesCount','simaSigned')}
        self.a.send('auth/register',without_kyc,status=422)
        reg=self.a.send('auth/register',base_reg)
        self.assertIn('accessToken',reg)
        self.assertEqual(reg['user']['name'],'Elvin Kərimov')
        self.assertEqual(reg['user']['pinLength'],4)
        self.assertEqual(self.a.send('auth/identify',{'phone':'+994509998877'}),{'registered':True,'pinLength':4})
        self.assertEqual(self.a.send('auth/identify',{'phone':'+994501000001'})['pinLength'],6)
        self.assertEqual(self.a.send('auth/login',{'phone':'+994509998877','pin':'4821','deviceId':'demo-device'})['user']['pinLength'],4)
        self.a.send('auth/login',{'phone':'+994509998877','pin':'1234','deviceId':'demo-device'},status=401)
        ov=self.admin.send('admin/overview')
        kyc=[k for k in ov['kycRecords'] if k.get('finMasked')=='7••••44']
        self.assertTrue(kyc and kyc[0]['status']=='DEMO_VERIFIED' and kyc[0]['liveness']=='CLIENT_REPORTED' and kyc[0]['mediaStored'] is True and kyc[0]['idSeriesMasked']=='A••••••••78' and 'fin' not in kyc[0] and 'idSeries' not in kyc[0])
        # the same FIN or ID series cannot open a second account
        otp2=self.a.send('auth/otp',{'phone':'+994509998866'})
        self.a.send('auth/otp/verify',{'otpId':otp2['otpId'],'otp':otp2['demoCode']})
        again={**base_reg,'otpId':otp2['otpId'],'otp':otp2['demoCode'],'livenessActions':otp2['livenessActions']}
        self.a.send('auth/register',again,status=422)
        self.a.send('auth/register',{**again,'fin':'7AZ5545'},status=422)
        # compliance can read the stored photo back; ordinary users cannot
        photo=self.admin.send('admin/kyc/'+reg['user']['id']+'/selfie')
        self.assertEqual(base64.b64decode(photo['dataBase64']),selfie_bytes)
        self.assertEqual(photo['sha256'],hashlib.sha256(selfie_bytes).hexdigest())
        self.a.send('admin/kyc/'+reg['user']['id']+'/selfie',status=403)
    def test_20_terminal_code_trusted_beneficiaries_and_hashed_contacts(self):
        before=self.a.balance()
        tc=self.a.send('topups/terminal-code',{'amountMinor':1500})
        self.assertEqual(len(tc['code']),8)
        self.a.send('topups/terminal-redeem',{'code':tc['code']})
        self.assertEqual(self.a.balance(),before+1500)
        self.a.send('topups/terminal-redeem',{'code':tc['code']},status=422)
        matched=self.a.send('contacts/match',{'phoneHashes':[hashlib.sha256(p.encode()).hexdigest() for p in ['+994501000002','+994509990000']]})
        self.assertTrue(any(m['phone']=='+994501000002' and len(m['phoneHash'])==64 for m in matched['matches']))
        tb=self.a.confirmed('trusted-beneficiaries',{'phone':'+994501000003','nickname':'Leyla'})
        t=self.a.send('transfers',self.a.tbody('+994501000003',7000,'Etibarlı alıcıya'))
        self.assertTrue(t['trustedBeneficiary'])
        self.a.send('transfers/'+t['id']+'/cancel',{})
        self.a.send('trusted-beneficiaries/'+tb['id']+'/delete',{})
    def test_22_crypto_deposit_aml_withdrawal_and_budget_alerts(self):
        self.admin.send('admin/feature-flags/crypto.enabled',{'value':True})
        addr=self.a.send('crypto/deposit-address')
        self.assertTrue(addr['address'].startswith('TKampus'))
        dep_ok=self.a.send('crypto/deposits',{'amountMicro':15000000,'fromAddress':'T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb'})
        self.assertEqual(dep_ok['amlStatus'],'CREDITED')
        dep_aml=self.a.send('crypto/deposits',{'amountMicro':20000000,'fromAddress':'TBLACKLIST999SANCTION'})
        self.assertEqual(dep_aml['amlStatus'],'FROZEN_FOR_AML')
        wd=self.a.confirmed('crypto/withdrawals',{'amountMicro':5000000,'toAddress':'TN3W4H6rK2ce4vX9YnFQHwKENnHjoxb3m9','beneficiaryName':'Murad Əliyev'})
        self.assertEqual(wd['amlStatus'],'COMPLETED')
        sp=self.a.send('splits',{'totalMinor':3000,'mode':'CUSTOM','participants':[{'phone':'+994501000002','amountMinor':1200}],'note':'Xüsusi hesab'})
        self.assertEqual(sp['organizerShareMinor'],1800)
        self.a.send('budgets',{'category':'Nəqliyyat','limitMinor':1000})
        txs=self.a.send('transactions')
        out_tx=next(t for t in txs if t['direction']=='OUT')
        updated=self.a.send('transactions/'+out_tx['id']+'/category',{'category':'Nəqliyyat'})
        self.assertEqual(updated['category'],'Nəqliyyat')
        self.assertEqual(self.admin.send('admin/reconciliation/run',{})['differenceMinor'],0)
    def test_23_contacts_do_not_enumerate_users(self):
        self.assertEqual(self.a.send('contacts/match',{'phoneHashes':[]})['matches'],[])
        self.a.send('contacts/match',{},status=422)
        self.a.send('contacts/match',{'phones':['+994501000002']},status=422)
        self.a.send('contacts/match',{'phoneHashes':['not-a-hash']},status=422)
    def test_25_bitcoin_preview_cannot_bypass_crypto_gate(self):
        self.admin.send('admin/feature-flags/crypto.enabled',{'value':False})
        before=self.a.balance()
        self.a.send('topups',{'source':'BITCOIN','amountMinor':1000},status=422)
        self.a.send('crypto/portfolio',status=403)
        self.assertEqual(self.a.balance(),before)
        self.admin.send('admin/feature-flags/crypto.enabled',{'value':True})
    def test_26_logout_revokes_session(self):
        client=Client('+994501000002','logout-device')
        client.send('auth/logout',{})
        client.send('wallet',status=401)
    def test_27_new_account_is_not_automatically_bnpl_eligible(self):
        self.assertIn('recentIncomingCount',self.a.send('bnpl/eligibility'))
        self.assertTrue(self.a.send('health/ready')['database']=='UP')
    def test_28_provider_retries_do_not_duplicate_crypto(self):
        def provider(path,body):
            req=urllib.request.Request(SIM_BASE+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','X-Api-Key':'kampus-local-simulator-key'})
            with urllib.request.urlopen(req,timeout=10) as r:return json.load(r)
        uid='provider-retry-'+str(uuid.uuid4())
        initial=provider('/vasp/portfolio',{'userId':uid})['balanceMicro']
        deposit={'userId':uid,'operationId':str(uuid.uuid4()),'amountMicro':1000000,'fromAddress':'TDEMO'}
        first=provider('/vasp/deposit',deposit);second=provider('/vasp/deposit',deposit)
        self.assertEqual(first,second)
        with self.assertRaises(urllib.error.HTTPError) as conflict:provider('/vasp/deposit',{**deposit,'amountMicro':2000000})
        self.assertEqual(conflict.exception.code,409)
        self.assertEqual(provider('/vasp/portfolio',{'userId':uid})['balanceMicro'],initial+1000000)
        withdrawal={'userId':uid,'operationId':str(uuid.uuid4()),'amountMicro':1000000,'toAddress':'TDEMO','beneficiaryName':'Demo'}
        self.assertEqual(provider('/vasp/withdraw',withdrawal),provider('/vasp/withdraw',withdrawal))
        self.assertEqual(provider('/vasp/portfolio',{'userId':uid})['balanceMicro'],initial)
    def test_14_pin_lockout(self):
        for _ in range(3):
            self.a.send('auth/login',{'phone':'+994501000004','pin':'000000','deviceId':'demo-device'},status=401)
        self.a.send('auth/login',{'phone':'+994501000004','pin':'123456','deviceId':'demo-device'},status=423)

    def selfie_data(self):
        import base64
        return 'data:image/jpeg;base64,'+base64.b64encode((pathlib.Path(__file__).resolve().parent/'fixtures'/'selfie.jpg').read_bytes()).decode()
    def link_bank(self,client,bank,months=2,limit=50_000,cards_only=True):
        """The whole customer journey: request, bank notification, pick cards and terms, preview the document, sign with SİMA."""
        consent=client.send('open-banking/consents',{'bankId':bank})
        self.assertEqual((consent['status'],consent['step']),('AWAITING_AUTHORIZATION','NOTIFIED'))
        self.assertTrue(consent['notification']['body'])
        chosen=[c['id'] for c in consent['offeredCards'] if c['type']=='CARD' or not cards_only]
        selected=client.send('open-banking/consents/'+consent['id']+'/select',{'accountIds':chosen,'months':months,'monthlyLimitMinor':limit})
        preview=client.send('open-banking/consents/'+consent['id']+'/document')
        self.assertFalse(preview['signed'])
        signed=client.send('open-banking/consents/'+consent['id']+'/sign',{'selfie':self.selfie_data(),'livenessMode':'MEDIAPIPE_WEB','livenessActions':selected['signActions'],'facesCount':1})
        return consent,signed
    def test_29_open_banking_consent_journey_and_aggregation(self):
        import base64
        wallet_before=self.a.balance()
        banks=self.a.send('open-banking/banks')['banks']
        self.assertEqual({b['id'] for b in banks},{'KAPITAL','ABB','PASHA','RABITA'})
        self.a.send('open-banking/consents',{'bankId':'NOPE'},status=404)
        # 1-2: the bank finds the customer and notifies them in its app
        consent=self.a.send('open-banking/consents',{'bankId':'KAPITAL'})
        self.assertEqual((consent['status'],consent['step']),('AWAITING_AUTHORIZATION','NOTIFIED'))
        self.assertEqual({c['type'] for c in consent['offeredCards']},{'CARD','CURRENT'});self.assertEqual(len([c for c in consent['offeredCards'] if c['type']=='CARD']),2)
        self.assertIn('Kampus',consent['notification']['body'])
        cid=consent['id']
        # nothing is shared before the customer finishes, and nobody else may read or drive this consent
        self.b.send('open-banking/consents/'+cid,status=403)
        self.b.send('open-banking/consents/'+cid+'/select',{'accountIds':[],'months':2,'monthlyLimitMinor':50000},status=403)
        self.assertEqual(self.a.send('open-banking/consents/'+cid+'/sync',{})['synced'],False)
        self.a.send('open-banking/consents/'+cid+'/document',status=422)
        self.a.send('open-banking/consents/'+cid+'/sign',{'selfie':self.selfie_data(),'livenessMode':'MEDIAPIPE_WEB','livenessActions':[],'facesCount':1},status=422)
        # 3-4: terms are validated before anything is stored
        card=next(c['id'] for c in consent['offeredCards'] if c['type']=='CARD')
        for bad in [{'accountIds':[],'months':2,'monthlyLimitMinor':50000},{'accountIds':['nope'],'months':2,'monthlyLimitMinor':50000},
                    {'accountIds':[card],'months':4,'monthlyLimitMinor':50000},{'accountIds':[card],'months':2,'monthlyLimitMinor':5},{'accountIds':[card],'months':2,'monthlyLimitMinor':99999999}]:
            self.a.send('open-banking/consents/'+cid+'/select',bad,status=422)
        selected=self.a.send('open-banking/consents/'+cid+'/select',{'accountIds':[card],'months':2,'monthlyLimitMinor':50000})
        self.assertEqual(selected['step'],'SELECTED');self.assertEqual(len(selected['signActions']),3)
        # 5: the document is a real PDF and an on-screen preview of the same content
        preview=self.a.send('open-banking/consents/'+cid+'/document')
        self.assertFalse(preview['signed'])
        self.assertTrue(base64.b64decode(preview['dataBase64']).startswith(b'%PDF'))
        self.assertIn('2 ay',json.dumps(preview['document'],ensure_ascii=False));self.assertIn('500.00 AZN',json.dumps(preview['document'],ensure_ascii=False))
        # 6: signing needs the camera check the server asked for
        face={'selfie':self.selfie_data(),'livenessMode':'MEDIAPIPE_WEB','facesCount':1}
        self.a.send('open-banking/consents/'+cid+'/sign',{**face,'livenessActions':['SMILE']},status=422)
        self.a.send('open-banking/consents/'+cid+'/sign',{**face,'livenessActions':selected['signActions'],'facesCount':2},status=422)
        self.assertEqual(self.a.send('open-banking/consents/'+cid)['status'],'AWAITING_AUTHORIZATION')
        first=self.a.send('open-banking/consents/'+cid+'/sign',{**face,'livenessActions':selected['signActions']})
        self.assertTrue(first['synced'] and first['added']>20)
        signed=first['consent']
        self.assertEqual((signed['status'],signed['step']),('AUTHORIZED','SIGNED'))
        self.assertTrue(signed['contractId'].startswith('CNT-'))
        self.assertAlmostEqual(signed['expiresAt']/1000,time.time()+2*30*86400,delta=300)
        again_doc=self.a.send('open-banking/consents/'+cid+'/document')
        self.assertTrue(again_doc['signed'] and again_doc['filename'].endswith('-imzali.pdf'))
        self.assertIn(signed['contractId'],json.dumps(again_doc['document'],ensure_ascii=False))
        self.a.send('open-banking/consents/'+cid+'/select',{'accountIds':[card],'months':2,'monthlyLimitMinor':50000},status=422)
        overview=self.a.send('open-banking/overview')
        mine=[x for x in overview['accounts'] if x['consentId']==cid]
        self.assertEqual([x['type'] for x in mine],['CARD'],'only the picked card is shared')
        self.assertGreater(overview['totalBalanceMinor'],0)
        txs=self.a.send('open-banking/transactions')
        self.assertEqual(len(txs),first['added'])
        self.assertTrue(all(t['source']=='OPEN_BANKING' and t['bankId']=='KAPITAL' for t in txs))
        categories={t['category'] for t in txs}
        self.assertTrue({'Yemək','Nəqliyyat'}<=categories,categories)
        # a second sync is idempotent
        self.assertEqual(self.a.send('open-banking/consents/'+cid+'/sync',{})['added'],0)
        self.assertEqual(len(self.a.send('open-banking/transactions')),len(txs))
        # external spending joins the analytics, but never the ledger
        dash=self.a.send('dashboard')
        month=dash['analytics']['month']
        external=sum(t['amountMinor'] for t in txs if t['direction']=='OUT' and t['createdAt'].startswith(month))
        self.assertEqual(dash['analytics']['externalMinor'],external)
        self.assertGreaterEqual(dash['analytics']['totalMinor'],external)
        self.assertEqual(dash['openBanking']['totalBalanceMinor'],overview['totalBalanceMinor'])
        self.assertEqual(self.a.balance(),wallet_before)
        self.assertEqual(self.admin.send('admin/reconciliation/run',{})['differenceMinor'],0)
        self.assertFalse(any(t.get('source')=='OPEN_BANKING' for t in dash['transactions']))
        # transfers between the customer's own accounts are labelled and never counted as spending
        abb,_=self.link_bank(self.a,'ABB',cards_only=False)
        self.assertEqual(self.a.send('open-banking/consents/'+abb['id'])['status'],'AUTHORIZED')
        every=self.a.send('open-banking/transactions')
        own=[t for t in every if t['category']=='Daxili köçürmə']
        self.assertTrue(any(t['direction']=='OUT' for t in own) and any(t['direction']=='IN' for t in own))
        spent=sum(t['amountMinor'] for t in every if t['direction']=='OUT' and t['category']!='Daxili köçürmə' and t['createdAt'].startswith(month))
        self.assertEqual(self.a.send('dashboard')['analytics']['externalMinor'],spent)
        self.assertEqual(self.a.send('open-banking/consents/'+abb['id']+'/revoke',{})['status'],'REVOKED')
        # the customer can recategorise an external transaction; others cannot
        target=next(t for t in txs if t['direction']=='OUT')
        self.b.send('transactions/'+target['id']+'/category',{'category':'Əyləncə'},status=403)
        self.a.send('transactions/'+target['id']+'/category',{'category':'Əyləncə'})
        self.assertEqual(next(t for t in self.a.send('open-banking/transactions') if t['id']==target['id'])['category'],'Əyləncə')
        # a request the customer abandons shares nothing
        second=self.a.send('open-banking/consents',{'bankId':'PASHA'})
        self.assertEqual(self.a.send('open-banking/consents/'+second['id']+'/revoke',{})['status'],'REVOKED')
        self.assertEqual(self.a.send('open-banking/consents/'+second['id']+'/sync',{})['synced'],False)
        self.assertFalse(any(x['bankId']=='PASHA' for x in self.a.send('open-banking/overview')['accounts']))
        # linking an already linked bank is refused; revoking deletes what was fetched and tells the bank
        self.a.send('open-banking/consents',{'bankId':'KAPITAL'},status=409)
        self.b.send('open-banking/consents/'+cid+'/revoke',{},status=403)
        self.assertEqual(self.a.send('open-banking/consents/'+cid+'/revoke',{})['status'],'REVOKED')
        after=self.a.send('open-banking/overview')
        self.assertFalse(any(c['id']==cid for c in after['consents']))
        self.assertEqual([t for t in self.a.send('open-banking/transactions') if t['consentId']==cid],[])
        self.assertNotIn('simConsentId',json.dumps(self.a.send('open-banking/overview')));self.assertNotIn('externalId',json.dumps(self.a.send('open-banking/overview')))
        # the same bank can be linked again after revocation
        self.assertEqual(self.a.send('open-banking/consents',{'bankId':'KAPITAL'})['step'],'NOTIFIED')
        self.assertEqual(self.a.balance(),wallet_before)
    def test_29b_the_bank_only_knows_its_own_customers(self):
        # the seeded demo people bank everywhere; a stranger's FİN decides, so some banks answer "not a customer"
        stranger=Client('+994509998877',pin='4821')  # registered in test_19 with a FİN the demo banks do not know
        outcomes=[]
        for bank in ['KAPITAL','ABB','PASHA','RABITA']:
            r=stranger.send('open-banking/consents',{'bankId':bank},status=(200,404))
            outcomes.append(r.get('code','OK'))
            if r.get('code'):self.assertEqual(r['code'],'NOT_BANK_CUSTOMER')
        self.assertTrue(set(outcomes)<={'OK','NOT_BANK_CUSTOMER'},outcomes)
    def test_33_payments_and_transfers_can_use_a_linked_card(self):
        consent,_=self.link_bank(self.a,'PASHA',months=3,limit=20_000)  # 200 AZN a month
        card_id=max((x for x in self.a.send('open-banking/overview')['accounts'] if x['consentId']==consent['id'] and x['type']=='CARD'),key=lambda x:x['balanceMinor'])['id']
        def card():
            return next(x for x in self.a.send('open-banking/overview')['accounts'] if x['id']==card_id)
        wallet=self.a.balance();start=card()['balanceMinor']
        # top-up from the card needs a PIN, and moves money from the card into the wallet
        body={'amountMinor':3000,'source':'BANK_CARD','accountId':card_id}
        self.assertEqual(self.a.send('topups',body,status=403)['code'],'SCA_REQUIRED')
        self.a.confirmed('topups',body)
        self.assertEqual(self.a.balance(),wallet+3000);self.assertEqual(card()['balanceMinor'],start-3000)
        # a QR payment from the card leaves the wallet as it was
        i=self.merchant.send('merchant/qr/dynamic',{'amountMinor':1200})
        pay=self.a.confirmed('payments/qr',self.a.qr_body(i['qrPayload'],1200,'BANK_CARD',accountId=card_id))
        self.assertEqual(pay['fundingSource'],'BANK_CARD');self.assertEqual(self.a.balance(),wallet+3000)
        self.assertEqual(card()['balanceMinor'],start-4200)
        # so does a P2P transfer (cancelled inside the 5 second window the money stays in the wallet)
        t=self.a.confirmed('transfers',self.a.tbody('+994501000002',2500,'from card',fundingAccountId=card_id))
        self.assertEqual(t['fundedBy'],'PAŞA Bank '+card()['masked'])
        self.assertEqual(card()['balanceMinor'],start-6700)
        self.a.send('transfers/'+t['id']+'/cancel',{})
        # cancelling inside the window puts the money back on the card it came from, not into the wallet
        self.assertEqual(self.a.balance(),wallet+3000);self.assertEqual(card()['balanceMinor'],start-4200)
        # the bank enforces the monthly limit the customer signed: 42 of 200 AZN used, 170 more does not fit
        over={'amountMinor':17000,'source':'BANK_CARD','accountId':card_id}
        refused=self.a.send('topups',over,proof=self.a.sca('topups',over),status=422)
        self.assertEqual(refused['code'],'CARD_LIMIT');self.assertIn('200.00 AZN',refused['detail'])
        self.assertEqual(self.a.balance(),wallet+3000);self.assertEqual(card()['balanceMinor'],start-4200)
        # a card belongs to its owner, and the wallet is the default when no card is named
        stolen={'amountMinor':1000,'source':'BANK_CARD','accountId':card_id}
        self.b.send('topups',stolen,proof=self.b.sca('topups',stolen),status=403)
        plain=self.a.confirmed('transfers',self.a.tbody('+994501000002',100,'wallet'))
        self.assertEqual(plain['fundedBy'],'');self.a.send('transfers/'+plain['id']+'/cancel',{})
        # the bank-side bookings arrive on their own (background sync), as the customer's own movements and not as spending; the returned one is gone
        booked=[]
        for _ in range(12):
            booked=[x for x in self.a.send('open-banking/transactions') if x['name'].startswith('Kampus:')]
            if len(booked)>=2:break
            time.sleep(1)
        self.assertEqual(len(booked),2,booked);self.assertTrue(all(x['category']=='Daxili köçürmə' for x in booked))
        self.assertEqual(self.admin.send('admin/reconciliation/run',{})['differenceMinor'],0)
        # revoking stops further payments
        self.a.send('open-banking/consents/'+consent['id']+'/revoke',{})
        gone={'amountMinor':1000,'source':'BANK_CARD','accountId':card_id}
        self.a.send('topups',gone,proof=self.a.sca('topups',gone),status=404)
    def test_30_large_payments_need_a_fresh_face_check(self):
        import base64
        selfie='data:image/jpeg;base64,'+base64.b64encode((pathlib.Path(__file__).resolve().parent/'fixtures'/'selfie.jpg').read_bytes()).decode()
        self.a.send('topups',{'amountMinor':60000,'source':'CARD'})
        before=self.a.balance()
        body=self.a.tbody('+994501000002',55000,'kyc step-up')
        # a PIN is required, even for a recipient the customer already trusts
        self.a.send('transfers',body,status=403)
        # a small payment still needs only the PIN
        small=self.a.send('auth/sca',{'pin':'123456','payload':{'action':'transfers','data':{**body,'amountMinor':100}}})
        self.assertFalse(small['kycRequired'])
        self.assertNotIn('kycActions',small)
        # a large one asks for a face check with three server-chosen actions
        sca=self.a.send('auth/sca',{'pin':'123456','payload':{'action':'transfers','data':body}})
        self.assertTrue(sca['kycRequired'])
        self.assertEqual(len(sca['kycActions']),3)
        proof=sca['proof']
        # the PIN alone does not move the money
        self.a.send('transfers',body,proof=proof,status=403)
        kyc={'proof':proof,'selfie':selfie,'livenessMode':'MEDIAPIPE_WEB','livenessActions':sca['kycActions'],'facesCount':1}
        for patch in ({'livenessActions':['BLINK']},{'facesCount':2},{'livenessMode':'MANUAL'},{'selfie':''}):
            self.a.send('auth/sca/kyc',{**kyc,**patch},status=422)
        self.b.send('auth/sca/kyc',kyc,status=403)   # nobody else can complete someone's check
        self.assertTrue(self.a.send('auth/sca/kyc',kyc)['kycDone'])
        self.a.send('auth/sca/kyc',kyc,status=422)   # and it cannot be completed twice
        # the proof stays bound to the amount and recipient that were confirmed
        self.a.send('transfers',{**body,'amountMinor':54000},proof=proof,status=422)
        done=self.a.send('transfers',body,proof=proof)
        self.assertIn(done['status'],('SCHEDULED','UNDER_REVIEW'))
        self.assertEqual(self.a.balance(),before-55000)
        # one confirmation, one payment
        self.a.send('transfers',body,proof=proof,status=422)
        # a face check is not offered for a proof that never needed one
        self.a.send('auth/sca/kyc',{**kyc,'proof':small['proof']},status=422)
        # a risky device does not skip the check: this transfer would go to manual review, and still needs PIN + face first
        if self.a.balance()<60000:self.a.send('topups',{'amountMinor':60000,'source':'CARD'})
        risky=None
        phone=Client('+994501000001',device='new-phone-kyc')
        risky=phone.tbody('+994501000002',56000,'kyc on a new device')
        phone.send('transfers',risky,status=403)
        risky_sca=phone.send('auth/sca',{'pin':'123456','payload':{'action':'transfers','data':risky}})
        self.assertTrue(risky_sca['kycRequired'])
        phone.send('transfers',risky,proof=risky_sca['proof'],status=403)
        phone.send('auth/sca/kyc',{'proof':risky_sca['proof'],'selfie':selfie,'livenessMode':'MEDIAPIPE_WEB','livenessActions':risky_sca['kycActions'],'facesCount':1})
        queued=phone.send('transfers',risky,proof=risky_sca['proof'])
        self.assertEqual(queued['status'],'UNDER_REVIEW')
        # large card transfers and QR payments go through the same gate
        qr_total=self.a.send('auth/sca',{'pin':'123456','payload':{'action':'card-transfers','data':{'amountMinor':70000,'totalMinor':70350}}})
        self.assertTrue(qr_total['kycRequired'])
    def test_31_qr_payment_tokens_are_single_use_scoped_and_bound(self):
        static=self.merchant.send('merchant/qr/static')['qrPayload']
        dynamic=self.merchant.send('merchant/qr/dynamic',{'amountMinor':300})['qrPayload']
        # no token, a made-up token and somebody else's token are all refused
        self.a.send('payments/qr',{'qrPayload':static,'amountMinor':100,'fundingSource':'WALLET'},status=422)
        self.a.send('payments/qr',{'qrPayload':static,'amountMinor':100,'fundingSource':'WALLET','paymentToken':'pt_made_up'},status=422)
        theirs=self.b.qr_body(static,100)
        self.a.send('payments/qr',theirs,status=422)
        # a token is bound to the QR it was issued for
        other=self.a.qr_body(dynamic,300)
        self.a.send('payments/qr',{**other,'qrPayload':static,'amountMinor':100},status=422)
        # the payment spends it: the same token cannot pay twice, and the token is not stored in the clear
        before=self.a.balance();body=self.a.qr_body(static,100)
        self.assertTrue(body['paymentToken'].startswith('pt_'))
        self.a.send('payments/qr',body)
        self.assertEqual(self.a.balance(),before-100)
        self.assertEqual(self.a.send('payments/qr',body,status=409)['code'],'TOKEN_USED')
        self.assertEqual(self.a.balance(),before-100)
        # a refused payment does not burn the token (the whole request rolls back)
        self.a.send('payments/qr',{**other,'amountMinor':301},status=422)
        self.a.send('payments/qr',other)
        self.assertEqual(self.a.balance(),before-400)
    def test_32_national_ids_are_stored_as_tokens(self):
        records=self.admin.send('admin/overview')['kycRecords']
        self.assertTrue(records)
        for k in records:
            self.assertNotIn('fin',k);self.assertNotIn('idSeries',k)
        seeded=next(k for k in records if k['userId']=='u1')
        self.assertTrue(seeded['finToken'].startswith('tok_fin_'))
        self.assertIn('•',seeded['finMasked']);self.assertNotIn('7AZ1234',json.dumps(records))

def wait(url):
    for _ in range(150):
        try:
            with urllib.request.urlopen(url,timeout=1):return
        except Exception:time.sleep(.2)
    raise RuntimeError('Service did not start: '+url)
if __name__=='__main__':
    with socket.socket() as api_socket,socket.socket() as sim_socket:
        api_socket.bind(('127.0.0.1',0));sim_socket.bind(('127.0.0.1',0))
        api_port=api_socket.getsockname()[1];sim_port=sim_socket.getsockname()[1]
    BASE=f'http://127.0.0.1:{api_port}/api/v1/'
    SIM_BASE=f'http://127.0.0.1:{sim_port}'
    with tempfile.TemporaryDirectory(prefix='kampus-tests-') as tmp:
        api_jar=pathlib.Path(tmp)/'api.jar';sim_jar=pathlib.Path(tmp)/'sim.jar'
        shutil.copy2(ROOT/'services/api/target/kampus-api-0.1.0.jar',api_jar)
        shutil.copy2(ROOT/'services/sim/target/kampus-sim-0.1.0.jar',sim_jar)
        env={**os.environ,'DATABASE_URL':'jdbc:h2:mem:kampus_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1','SIM_URL':SIM_BASE,'PORT':str(api_port)}
        logs=open(pathlib.Path(tmp)/'services.log','w')
        sim=subprocess.Popen(['java','-jar',str(sim_jar),'--server.port='+str(sim_port)],cwd=tmp,env=env,stdout=logs,stderr=logs)
        api=subprocess.Popen(['java','-jar',str(api_jar),'--kampus.oct-review-after-ms=2500','--kampus.ob-sync-ms=1500','--kampus.ob-sync-initial-ms=1000'],cwd=tmp,env=env,stdout=logs,stderr=logs)
        try:
            wait(SIM_BASE+'/health');wait(BASE+'health');time.sleep(.7)
            result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Checks))
            if not result.wasSuccessful():
                logs.flush();print((pathlib.Path(tmp)/'services.log').read_text()[-5000:])
            raise SystemExit(not result.wasSuccessful())
        finally:
            api.terminate();sim.terminate();api.wait(timeout=10);sim.wait(timeout=10);logs.close()
