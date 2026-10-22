# Kampus yoxlama nəticələri

19 oktyabr 2026, lokal sandbox və deploy hazırlığı.

## Mövcud işlərin yoxlanması

Antigravity-dən sonra mövcud 24 izolə inteqrasiya testi yenidən işlədildi və keçdi. Əlavə edilmiş terminal, yadda saxlanan kartlar, etibarlı alıcılar, kripto mock depozit/çıxarışı, kart nəzarətləri və büdcə axınları saxlanıldı.

Git tarixçəsi yoxdur. Dəyişikliklər söhbətdəki əvvəlki vəziyyət, hazırkı kod və təkrar testlər üzərindən qiymətləndirilib; sətir səviyyəsində müəllif atribusiyası aparılmır.

## Aradan qaldırılan problemlər

- Kart reveal cavabındakı tam PAN və CVV idempotency DB keşinə yazılırdı. İndi yalnız həssas cavab marker-i saxlanır, eyni açarla təkrar reveal 410 qaytarır və yeni PIN təsdiqi tələb edir. Startup köhnə reveal keşlərini də təmizləyir.
- Boş kontakt sorğusu bütün tələbələri qaytarırdı. İndi yalnız düzgün SHA-256 heşləri qəbul olunur; boş siyahı boş nəticə qaytarır. Mobil cihaz heşləri göndərməzdən əvvəl özü hesablayır.
- Kart əməliyyatının anomaliya təsdiqi PIN tələb etmirdi və başqa məbləğə də tətbiq edilə bilirdi. İndi PIN, satıcı, kart və konkret məbləğ yoxlanır; icazə birdəfəlikdir.
- Qara siyahı yalnız adminə baxış növbəsi yaradırdı. İndi göndəriş bloklanır, növbədəki köçürmənin alanı sonradan bloklanarsa rezerv açılır.
- Kredit panelində ümidsiz borc nisbəti sabit sıfır, hədd isə 5% idi. İndi TT üzrə 1% kapital həddi və gecikmiş/silinmiş borc qeydlərindən hesablama işləyir.
- ML Kit, canlılıqdan keçmə və AES media saxlanması kodda qurulmadan hazır kimi işarələnmişdi. İndi qeydiyyat və admin bunu demo simulyasiyası kimi göstərir.
- Statik TRA faizi ölçülmüş göstərici kimi təqdim olunurdu. İndi hesablamanın qurulmadığı və TRA istisnasının aktiv olmadığı göstərilir.
- Bitcoin dizayn ekranı crypto flag-dan yan keçərək AZN balans artırırdı. Bu yol bağlanıb; ekran USDT pilotuna yönləndirir.
- Mobil animasiyalardakı ESLint xətaları qaydaları söndürmədən düzəldildi.
- Bağlantı bərpa olunandan sonra əvvəlki server xətası ekranda qalırdı. Uğurlu dashboard yenilənməsi indi həmin bağlantı mesajını təmizləyir; digər əməliyyat xətaları saxlanılır.
- Paralel test işə salmaları eyni portları paylaşırdı. Hər run indi ayrıca portlar, müvəqqəti DB, simulator vəziyyəti və paket nüsxəsi istifadə edir.

## Keçən avtomatik yoxlamalar

- 30 izolə HTTP inteqrasiya testi.
- 15 JUnit testi: 4 ledger/QR/məbləğ, 3 kredit siyasəti, 1 həssas reveal, 7 avtomatik borc/uyğunluq/scoring/aylıq kart limiti sınağı.
- Mobil TypeScript və ESLint — sıfır warning.
- Admin TypeScript və production build.
- Java API və simulator paketləri.

İnteqrasiya testləri əsas balans, P2P/QR, ləğv, paralel idempotency, fraud, kredit, BNPL, USDT, karta köçürmə, terminal, qeydiyyat, kontakt, büdcə və avtorizasiya axınlarını əhatə edir. Bu, tam TT-nin bütün qəbul meyarlarının yoxlanması demək deyil.

## Ekran yoxlaması

Mobil giriş və cihazda heşlənmiş kontakt uyğunlaşdırması brauzerdə yoxlanıldı. Admin paneldə TT üzrə 1% borc/kapital həddi, ledger cəmləri və kamera yoxlamasının simulyasiya etiketi görünür. Antigravity-nin mobil dizaynı saxlanılıb.

Yoxlanmış ekranlar: artifacts/review-mobile-preview.jpg və artifacts/review-admin-preview.jpg. API və simulator health endpoint-ləri UP qaytarır.

## APK və sərhədlər

Yığma skripti: scripts/build-apk.sh. Çıxış: artifacts/kampus-demo.apk (təxminən 33 MB). Son mobil düzəlişləri daxil edən JS bundle ilə release yığımı uğurla tamamlandı. apksigner ayrıca Kampus release sertifikatını (CN=Kampus Demo, O=Kampus, C=AZ) təsdiqlədi; SHA256 yoxlaması OK qaytardı. Checksum ayrıca artifacts/kampus-demo.apk.sha256 faylındadır və yığma skripti onu avtomatik yeniləyir. Paket az.kampus.wallet, minimum Android API 24, arxitektura arm64-v8a-dır. Versiya 0.2.0-dır. Şəxsi imza açarı .local/signing/ daxilindədir və source paketinə daxil edilmir.

Fiziki Android cihazında quraşdırma, kamera, biometrika və iki telefonlu axın yoxlanmayıb. Təqdimat istifadəçinin istəyinə görə sonraya saxlanılıb. Tamamlanmamış işlər ROADMAP.md-dədir.


## Deploy hazırlığında tamamlananlar

- PostgreSQL 17 və Flyway V1 migrasiyası; H2 lokal inkişaf üçün saxlanılır.
- API, simulator, admin və mobil veb üçün ayrı Docker image-lər, Caddy gateway və healthcheck asılılıqları.
- Same-origin API və hosted kart forması: deploy brauzeri və HTTPS Android build-i localhost:8081 tələb etmir.
- Hosted profildə təsadüfi DB/simulator açarları və PIN-lər; standart PIN/açar və H2 ilə start rədd edilir. Auth rate limit və sorğu ölçüsü limiti əlavə olunub.
- Logout server sessiyasını da ləğv edir.
- Sonra ödə uyğunluğu, avtomatik ödəniş, OVERDUE/BAD statusları; partnyor aylıq ödənişi və tarixçədən scoring; kart aylıq xərclərinin sıfırlanması; kripto QR üzrə gündəlik cəm limiti.
- VASP depozit/çıxarışında davamlı operationId keşi və dəyişmiş payload üçün conflict; retry eyni təsiri iki dəfə yaratmır.
- Backup/bərpa, deploy smoke, source arxivi və CI yoxlama konfiqurasiyası. Təlimat docs/DEPLOY.md daxilindədir.

İctimai serverə yayımlama istifadəçinin ayrıca göstərişinə saxlanılıb. Fiziki cihaz və həqiqi domen üzərində TLS qəbul yoxlaması hələ aparılmayıb.

## eKYC kamerası və şifrəli foto — 20 oktyabr

- Giriş ekranı yalnız telefon nömrəsi soruşur. Qeydiyyatlı nömrə PIN addımına, yeni nömrə mərhələli qeydiyyata keçir: SMS kodu (ayrıca `auth/otp/verify`, 5 yanlış cəhddən sonra kod yanır) → FİN və ŞV seriyası → ad, soyad, doğum tarixi (tarix seçicisi, `GG.AA.İİİİ`) → kamera → razılıqlar → 4 rəqəmli PIN (ekran klaviaturası, təkrarla təsdiq). PIN uzunluğu hesab üzrə `users.pin_length` sütununda saxlanır: yeni hesablar 4, əvvəlki hesablar 6 rəqəmlidir; giriş və əməliyyat təsdiqi ekranı hər hesabın uzunluğuna görə nöqtələri göstərir. Server təsdiqlənməmiş SMS kodu ilə qeydiyyatı qəbul etmir.
- Brauzerdə Chrome ilə yoxlanıldı (Chrome-un saxta kamera axını ilə, real üz fotosundan düzəldilmiş videoyla): kamera açılır, MediaPipe `public/`-dən yüklənir, üz tapılır, 10 saniyəlik hərəkət geri sayımı və vaxt bitəndə təkrar işləyir. Həm dev serverdə, həm Docker/Caddy üzərində.
- Yoxlanmayıb: həqiqi insanın başı çevirməsi, göz qırpması və gülümsəməsi. Hədd dəyərləri `src/Liveness.web.tsx` yuxarısındadır və real üzlə dəqiqləşdirilməlidir. Fiziki Android cihazında kamera yoxlaması yoxdur.
- API testləri: 30 HTTP axın testi (H2 və müvəqqəti PostgreSQL-də) və yeni `KycMediaTests` (şifrələmə, açar/istifadəçi/dəyişdirmə yoxlaması, selfi doğrulaması). Admin selfini oxuyanda audit yazılır (`KYC_SELFIE_VIEWED`).

## Açıq bankçılıq — 20 oktyabr

- 31 HTTP axın testi (yeni `test_29`): bank siyahısı, razılıq yaradılması, bankın hosted səhifəsində icazə (brauzerin göndərdiyi kimi: xanalar formaya aiddir), yalnız seçilən hesabın paylaşılması, yanlış nonce və boş seçimin rədd edilməsi, başqa istifadəçinin razılığa çıxışının bağlanması, ikinci sinxronizasiyanın təkrar yazmaması, xarici xərcin analitikaya düşməsi və ledger/üzləşdirmənin dəyişməməsi, kateqoriya dəyişmə, bank rədd etdikdə məlumat axmaması, təkrar bağlamanın 409 ilə dayandırılması, ləğvdən sonra məlumatın silinməsi və bankın `REVOKED` qaytarması, daxili köçürmələrin xərcə düşməməsi.
- `OpenBankingTests`: MCC → kateqoriya xəritəsi, gəlirin və daxili köçürmənin xərc sayılmaması.
- Müvəqqəti PostgreSQL stack-də Chrome ilə: bank seçimi, bankın yeni tabda açılması, gözləmə paneli, təsdiqin avtomatik aşkarlanması və sinxronizasiya, iki bankın tarixçədə birləşməsi və bank filtri, xarici əməliyyat detalı və kateqoriya dəyişmə, büdcə xəbərdarlığı bildirişi, ləğv və məlumatın silinməsi.
- Yoxlanmayıb: fiziki telefonda bank səhifəsinin açılması (mobil build `Linking.openURL` istifadə edir), icazənin vaxtının bitməsi ssenarisi (məntiq var, test üçün vaxtı irəli aparan mexanizm yoxdur), real bank API-ləri.

## Böyük məbləğdə KYC təsdiqi — 21 oktyabr

- 500 AZN və ondan böyük P2P köçürmədə PIN-dən sonra üzün də təsdiqlənməsi tələb olunur (`kampus.kyc-step-up-minor`, defolt 50000 qəpik). `auth/sca` server tərəfindən 3 təsadüfi hərəkət (`kycActions`) qaytarır, tətbiq kamera yoxlamasını açır, `auth/sca/kyc` canlılıq nəticəsini və selfini qəbul edir (selfi `PAYMENT_SELFIE` kimi şifrəli saxlanılır, audit `KYC_STEP_UP_CONFIRMED`). KYC olmadan `403 KYC_REQUIRED`. Yeni cihaz və ya yüksək risk səbəbilə yoxlamaya düşən böyük köçürmə də PIN və KYC-dən keçir.
- `KycStepUpTests` və HTTP `test_30` (kiçik köçürmə KYC istəmir, böyük köçürmə istəyir, KYC olmadan rədd, yeni cihaz halı) keçir. Brauzerdə saxta kamera ilə yoxlanıldı: PIN → üz yoxlaması → selfi və audit yazısı bazada. Mobil iOS bundle (SDK 57 nüsxəsi) quruldu.
- Yoxlanmayıb: fiziki telefonda Expo Go-da kamera. Canlılığı müştəri hesablayır; selfi qeydiyyat şəkli ilə üz tutuşdurulmur, yalnız sübut kimi saxlanır. Serverin öz Caddyfile-ında `/api/v1/auth/sca/kyc` üçün 600KB qaydası `update-server-config.sh` təkrar işə salınanda əlavə olunur.

## Tokenizasiya — 22 oktyabr

- `Tokens` xidməti və `payment_tokens` cədvəli (V4). QR parse tək istifadəlik token verir; ödəniş onu xərcləyir. Token başqa istifadəçiyə, başqa QR-a və ya ikinci ödənişə keçmir (`TOKEN_INVALID`, `TOKEN_MISMATCH`, `TOKEN_USED`, vaxtı bitəndə `TOKEN_EXPIRED`). Rədd edilən ödəniş (məsələn, məbləğ uyğunsuzluğu) tokeni yandırmır.
- FİN və ŞV seriyası: açarlı HMAC tokeni və maskalanmış forma. Admin paneldə `7••••44` kimi görünür. Seed başlanğıcda köhnə açıq qeydləri köçürür.
- Yoxlama: `TokensTests` (5 unit test) və HTTP `test_31`, `test_32`, mövcud QR testləri token ilə yenilənib. `./scripts/check.sh` keçir (34 HTTP testi).
- Mobil: QR təsdiqi `paymentToken` göndərir; vaxtı bitmiş və ya xərclənmiş token olduqda tətbiq skanerə qayıdır və ayrıca xəta ikonu göstərir.
- Yoxlanmayıb: fiziki telefonda QR skan; token müddətinin bitməsi ssenarisi (180 saniyə) HTTP testində deyil, yalnız unit testdədir.

## Açıq bankçılıq müştəri axını və kartdan ödəniş — 22 oktyabr

- Axın: sorğu → bankın müştəri axtarışı (telefon + FİN) və bildirişi → kart seçimi → müddət və aylıq limit → PDF sənəd önizləmə → SİMA imzası (server tərəfindən verilən 3 hərəkətlə kamera yoxlaması, selfi şifrəli saxlanır) → imzalı PDF → bankın «Açıq bankçılıq» bölməsi → Kampus-da kartlar. Sənəd Azərbaycan hərfləri ilə `ConsentDocument` tərəfindən PDF və ekran önizləməsi üçün eyni məzmundan yaradılır; imza məzmun heşinə bağlıdır.
- Kartdan ödəniş: `topups` (`source=BANK_CARD`), `payments/qr` (`fundingSource=BANK_CARD`) və `transfers` (`fundingAccountId`). Hamısı PIN tələb edir. Bank seçilmiş kart, aylıq limit və qalığı yoxlayır (`CARD_LIMIT`, `CARD_INSUFFICIENT_FUNDS`); rədd edilsə heç nə dəyişmir. Bank tərəfdəki yazılar müştərinin öz hərəkətləri kimi (`Daxili köçürmə`) görünür və xərc sayılmır. Ledger üzləşdirməsi sıfır fərqlə qalır.
- Yoxlama: HTTP `test_29` (bütün axın, yoxlamalar, ləğv), `test_29b` (bank müştərini tanımır), `test_33` (üç ödəniş növü, limit, başqasının kartı, ləğvdən sonra); `ConsentDocumentTests`. `./scripts/check.sh` keçir (36 HTTP testi). Brauzerdə Chrome ilə bütün axın (saxta kamera və MediaPipe əvəzləyicisi ilə) və üç ödəniş növü yoxlanıldı, PDF yüklənib açıldı.
- Yoxlanmayıb: fiziki telefonda bank axını, PDF-in telefonda paylaşım pəncərəsi ilə saxlanması, real kamera ilə imza.

## Tokenizasiyanın tamamlanması, arxa plan sinxronizasiyası, kart qaytarması, köhnə kartların silinməsi — 22 oktyabr

- P2P alıcı tokeni: `transfers/resolve` token verir, `transfers` PIN-dən sonra onu xərcləyir (başqa istifadəçiyə, başqa nömrəyə və ya ikinci dəfə keçmir). Bankın identifikatorları (`simConsentId`, `externalId`) müştəriyə göstərilmir.
- Arxa plan sinxronizasiyası: bütün aktiv icazələr hər 5 dəqiqədən bir (`kampus.ob-sync-ms`) yenilənir; test 1,5 saniyəyə qurulub və kart ödənişlərinin bank yazılarının «Yenilə» basılmadan gəldiyini yoxlayır.
- Kartdan P2P köçürmə ləğv, rədd, alınmama və yoxlama müddətinin bitməsi hallarında həmin karta qaytarılır (`BANK_CARD_REFUND`, bankda qaytarma); ləğv testi kartın qalığının və aylıq limitin bərpasını yoxlayır.
- Virtual kartlar, mübahisələr və kart avtorizasiyası backend, simulyator, mobil və admin paneldən silindi (3 köhnə HTTP testi və 2 unit test də). `./scripts/check.sh` keçir (33 HTTP testi).

## Xərc analitikası — 22 oktyabr

- Modul: `Insights` (statistika, saf funksiyalar), `SyntheticSpending` (bilinən nümunələri olan generator, həm demo tarixçə, həm test üçün), `InsightsService` (razılıq, bildirişlər, admin aqreqatı). API: `analytics/insights`, `analytics/consent`, `analytics/budgets/apply`, `admin/analytics`; ana səhifə üçün `dashboard.insightsBrief`.
- Keyfiyyət sintetik data ilə ölçülür (`InsightsTests`, 40 nəfər, 6 ay, hər birinə 3 qeyri-adi ödəniş qoyulub):
  - qeyri-adi ödənişlər: recall 98,3%, precision 92,2%; heç nə qoyulmayan datada nəfər başına 3 həftədə 0,20 yalançı xəbərdarlıq;
  - təkrarlanan ödənişlər (30 nəfər): recall 100%, yalançı aşkarlama 0;
  - ay sonu proqnozu (30 nəfər, ayın 12-si və 20-sində): median xəta 8,0%; faktiki nəticə göstərilən 80% aralığın içində 72% hallarda.
- HTTP `test_34`: proqnoz, abunəliklər, qeyri-adi ödənişin bir dəfə bildirilməsi və bildirişi, büdcə təklifinin tətbiqi, razılığın söndürülməsi, admin aqreqatda söndürənin və üç nəfərdən az qrupun gizlədilməsi. `./scripts/check.sh` keçir (34 HTTP testi).
- Mobil: Analitika ekranı (proqnoz, kartlar, qeyri-adi ödənişlər, kateqoriyalar, 6 ay, təkrarlanan ödənişlər, büdcə, həftənin günləri), ana səhifə kartı, tarixçədə «Qeyri-adi» və «Təkrar» nişanları, Profildə «Xərc təhlili» düyməsi; Chrome-da yoxlanıb.
- Yoxlanmayıb: real istifadəçi datası; fiziki telefonda ekranlar; admin analitika səhifəsi yalnız tip yoxlamasından keçib, brauzerdə baxılmayıb.

## Python ML müqayisəsi — 22 oktyabr

- `services/analytics`: Isolation Forest (anomaliya), Holt-Winters (proqnoz), qaydaların Python portu, FastAPI servisi, 7 pytest testi, `scripts/benchmark.sh` ilə eyni sintetik data üzərində Java ilə müqayisə.
- Nəticə (`docs/ML_COMPARISON.md`): qeyri-adi ödənişlərdə Java statistikası daha yaxşıdır (average precision 0,96, Isolation Forest 0,86); hər ikisi işarələyəndə (hibrid) xəbərdarlıqların 98,9%-i doğrudur, yalançı 0,03/nəfər (recall 77,5%). Proqnozda Holt-Winters orta xətanı 8,0%-dən 7,1%-ə endirir, amma fərq statistik olaraq sübut olunmayıb (95% interval sıfırı keçir). Təkrarlanan ödənişlərdə eyni nəticə. Qərar: canlıda Java statistikası qalır, Python servisi canlı stackə qoşulmayıb.
- Java proqnozuna günün qalan hissəsi əlavə edildi (median xəta 8,0%-dən 6,5%-ə, aralıq əhatəsi 72%-dən 78%-ə).

