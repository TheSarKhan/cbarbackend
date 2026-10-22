# Kampus inkişaf vəziyyəti

Məqsəd React Native Android tətbiqi, Next.js admin və kassa, ortaq Spring Boot API və ayrıca xarici partnyor simulyatorudur. Mərhələlərin sayı sabit deyil; işlər asılılığa və demo üçün vacibliyinə görə birləşdirilir və ya bölünür. Təqdimat istifadəçinin istəyi ilə sonraya saxlanılır.

Bu sənəd bütün TT-nin tamamlandığını bildirmir. “İşləyir” lokal sandbox daxilində yoxlanmış axın deməkdir; real bank, dövlət reyestri, SİMA, kreditor və blokçeyn bağlantısı yoxdur.

## İşləyən lokal demo

- Ledger: ikili yazılış, balans, HOLD/CAPTURE/release, sorğu idempotency-si və qoruma hesabı ilə daxili üzləşdirmə.
- Giriş: Argon2 PIN, cihaz identifikatoruna bağlı sessiya, vaxt limiti və üç səhv cəhddən sonra bloklama.
- Balans: mock kart və açıq bankçılıq, 15 dəqiqəlik birdəfəlik terminal kodu.
- Köçürmə: daxili P2P və AniPay mock-u, 5 saniyəlik ləğv, pul istə, bərabər/fərdi hesab bölmə.
- Etibarlı alıcılar: PIN təsdiqi ilə əlavə etmə. Kontakt uyğunlaşdırması cihazda SHA-256 hesablayır; boş sorğu istifadəçiləri sıralamır.
- QR/kassa: EMV TLV və CRC16, statik/dinamik QR, müddət və birdəfəlik istifadə, komissiya və geri qaytarma.
- Karta köçürmə: ayrıca mock forma, Luhn və demo BIN, tokenləşdirmə, yadda saxlanan alıcı kartları, komissiya, rezerv, şəbəkə təsdiqi/rəddi və gecikmiş cavabın izlənməsi. Kartın sahibinin real yoxlanması yoxdur; öz kartına pulsuz çıxarış da demo seçiminə əsaslanır.
- Kampus öz kartını buraxmır: virtual, birdəfəlik və satıcıya kilidli kartlar, kart mübahisələri və mock avtorizasiya çıxarılıb. Kartlar yalnız digər banklardan açıq bankçılıq ilə bağlanır.
- Fraud: seçilmiş qaydalar, rezervli admin növbəsi və rədd zamanı rezervin açılması. Qara siyahıdakı alıcıya göndəriş bloklanır.
- Kredit: QR üçün 50 AZN Sonra ödə, öz kapitalından maliyyələşmə, portfelin 10% həddi. 60 gündən çox gecikmiş və son 12 ayda silinmiş borcun kapitala nisbəti həqiqi qeydlərdən hesablanır; TT üzrə hədd 1%-dir.
- Partnyor kreditləri: razılıq, tarixçədən hesablanan aktiv aylar və son 90 günlük orta daxilolma, iki mock kreditor, aylıq/cəmi/FİFD, demo qəbul, balansa köçürmə və qrafik üzrə avtomatik/əl ilə ödəniş. Gecikmiş borc scoring-də nəzərə alınır.
- USDT pilotu: default OFF flag, mock VASP portfeli, 30 saniyə quote, al/sat və QR ödənişi; mock depozit, ünvan üzrə sadə AML ssenarisi və Travel Rule ilə çıxarış.
- Büdcə/analitika: kateqoriya üzrə aylıq xərclər, büdcə və hədd bildirişləri, kateqoriyanın dəyişdirilməsi.
- Admin: monitorinq, fraud, kart köçürmələri, demo KYC reyestri, kripto AML qeydləri, audit, kapital, feature flag və CSV.
- Açıq bankçılıq (müştəri axını): Kartlar tabında «Kart əlavə et» → bank seçimi → bankın tətbiqi (Kampus içində, bankın rəngində simulyasiya) müştərini telefon və FİN ilə tapır və bildiriş göndərir (FİN-ə görə bəzi banklarda müştəri tapılmır) → bildirişə toxunanda bank tətbiqi açılır, bir və ya bir neçə kart seçilir → müddət (1/2/3/6/12 ay) və aylıq ödəniş limiti seçilir → icazə sənədi PDF kimi önizlənir → sənəd SİMA ilə (üz yoxlaması) imzalanır → «Sənədi yüklə» və «Davam et» → bankın «Açıq bankçılıq» bölməsində Kampus görünür → Kampus-a keçəndə kartlar «Kartlarım»-da çıxır. Bağlı kartlar QR ödənişində, P2P köçürmədə və balans artırmada balansla yanaşı mənbə kimi seçilir; əməliyyat PIN (böyük məbləğdə üz yoxlaması) ilə təsdiqlənir, bank seçilən aylıq limiti və kartın qalığını özü tətbiq edir. Kampus balans və əməliyyatları çəkir, MCC koduna görə kateqoriyalara ayırır, xərcləri analitikaya mənbə etiketi ilə qatır. İcazə ləğv ediləndə bankdan gətirilən bütün məlumat silinir və kartdan ödəniş dayanır. Bank məlumatı kredit scoring-ə və fraud qaydalarına daxil olmur.
- Tokenizasiya: QR ödənişi tək istifadəlik ödəniş tokeni ilə aparılır (`payments/qr/parse` verir, `payments/qr` xərcləyir). Token təsadüfi 256 bitdir, bazada yalnız SHA-256 heşi saxlanılır, istifadəçiyə, QR-a və satıcıya bağlıdır, 180 saniyə keçərlidir və atomik xərclənir. Uğursuz ödəniş tokeni yandırmır (sorğu geri qaytarılır). FİN və ŞV seriyası bazada açıq saxlanılmır: açarlı HMAC tokeni (təkrar qeydiyyatı yoxlamaq üçün) və maskalanmış forma (`7••••34`) saxlanılır, mövcud qeydlər başlanğıcda köçürülür. P2P köçürmənin alıcısı da tək istifadəlik, 300 saniyəlik alıcı tokeni ilə təsdiqlənir (`transfers/resolve` verir, `transfers` xərcləyir, PIN-dən sonra). Bankın öz hesab və icazə identifikatorları müştəriyə göstərilmir. FİN və ŞV bankla paylaşmaq üçün AES-256-GCM ilə möhürlənir.

## Tamamlanmış kimi təqdim edilməməli hissələr

- Qeydiyyat brauzerdə kameranı açır. MediaPipe kadrda tək üz olduğunu və serverin verdiyi 3 hərəkətin (sola/sağa çevirmə, göz qırpma, təbəssüm) edildiyini yoxlayır, selfi isə AES-256-GCM ilə şifrələnib bazada saxlanır. Canlılıq nəticəsini müştəri hesablayır və server onu müstəqil yoxlamır (`liveness=CLIENT_REPORTED`); video təkrarından qorunma və reyestr fotosu ilə həqiqi üz tutuşdurma yoxdur, `faceMatchScore` mock-dur. Mobil tətbiqdə eyni yoxlama WebView-da işləyir (fiziki telefonda sınanmayıb). 500 AZN və yuxarı köçürmələr PIN-dən sonra üz yoxlaması da tələb edir. Dövlət reyestri və SİMA mock-dur, SİMA imzası hüquqi elektron imza deyil. SMS göndərilmir: kod test rejimində ekranda göstərilir, real SMS provayderi qoşulmayıb.
- Bitcoin mədaxil ekranı dizayn prototipidir. Oradakı ünvan/invoice nümunədir; Bitcoin adı ilə AZN balans artırılması bağlanıb. İşləyən kripto axını USDT pilotudur.
- Rüblük TRA fraud əmsalı hesablanmır və TRA istisnası aktiv deyil. Statik rəqəm real ölçmə kimi göstərilmir.
- Sonra ödə üçün 14 günlük hesab, KYC/18+, son 30 gündə iki daxilolma və gecikməsiz borc yoxlamaları işləyir. Ödəniş tarixində mövcud və rezervsiz balansdan avtomatik qaytarılır; vəsait çatmayanda OVERDUE, 60 gündən çox gecikəndə BAD olur.
- Scoring artıq əməliyyat tarixçəsindən hesablanır; kreditor qərarları xarici simulatorun demo qaydalarıdır.
- VASP depozitləri real şəbəkə təsdiqlərini gözləmir; AML real sanksiya xidməti deyil. Provider sorğularında sabit operationId və davamlı cavab keşi retry zamanı təkrar depozit/çıxarışı saxlayır. Ayrı açarla gələn qeyri-müəyyən əməliyyatların tam saga/kompensasiya sistemi yoxdur.
- Kartın aylıq xərci yeni UTC təqvim ayında sıfırlanır. Tam clearing/chargeback və təhlükəsiz hosted reveal axını ayrıca real inteqrasiyadır.
- Bankla üzləşdirmə hazırda daxili ledger hesablarını müqayisə edir; müstəqil bank çıxarışı ilə tam yoxlama deyil.
- Yenilənmə polling ilədir; push/SMS, transactional outbox və WebSocket/STOMP yoxdur.
- Deploy PostgreSQL/Flyway istifadə edir; lokal inkişaf üçün H2 dəstəyi saxlanılıb. Bəzi domenlər JSON records-dadır, yazılar DB kilidi ilə ardıcıllaşır. Redis, hesab üzrə ayrıca kilid və 24 saatlıq idempotency təmizlənməsi böyümə üçün ayrıca işdir.
- Aparat açarı ilə cihaz bağlama, JWT/refresh rotasiyası, fiziki telefonda biometrika/kamera və iki telefonlu demo yoxlanmayıb.
- Docker/Compose, Caddy HTTPS konfiqurasiyası, ayrıca təsadüfi deploy credential-ları, healthcheck, backup/bərpa, source paketi və release imzalı APK hazırlanıb. Server/domenə faktiki yayımlama istifadəçinin ayrıca göstərişini gözləyir. Video və təqdimat sonraya saxlanıb.
- Açıq bankçılıq real Mərkəzi Bank platformasına qoşulmayıb: banklar, müştərilər, kartlar, əməliyyatlar, bank bildirişi və bank tətbiqi `kampus-sim` və Kampus tətbiqinin içində simulyasiya olunur (real push bildirişi və ayrıca bank tətbiqinə keçid yoxdur). Yenilənmə hər 5 dəqiqədən bir arxa planda və «Yenilə» düyməsi ilə gedir (webhook yoxdur). İcazənin müddəti bitəndə yenidən bağlamaq lazımdır. Kartdan P2P köçürmə ləğv edilərsə, rədd edilərsə və ya alınmasa, vəsait həmin karta qaytarılır (bank qaytarmanı qəbul etməsə, Kampus balansında qalır). Sənədin SİMA imzası mock-dur, hüquqi qüvvəsi yoxdur; üz canlılığını müştəri hesablayır. Mövcud köhnə (yalnız oxuma) icazələr ödəniş üçün yararsızdır, kart yenidən bağlanmalıdır.

## Növbəti iş sırası

Hazırkı hədəf deploy oluna bilən demo paketidir. Növbəti istifadəçi addımı server/domen seçimi və yayımlama göstərişidir. Fiziki cihaz yoxlaması və demo məşqi həmin mühitdə aparılır. Real ML Kit/SİMA/bank/VASP, push və yüksək yüklənmə arxitekturası ayrıca genişləndirmədir.

## Məlumat və təhlükəsizlik

Pul integer qəpiklə, USDT integer mikro vahidlə saxlanır. Deploy profili lokal standart credential-larla başlamağı rədd edir. Demo bank əməliyyatları real pul xidməti kimi istifadə edilmir. Lokal HTTP yalnız sandbox üçündür. Tam TT qəbul meyarları ayrıca yoxlanmalıdır.
- Tokenizasiyada açarlar `KYC_MEDIA_KEY`-dən törədilir; ayrıca KMS/HSM və açar rotasiyası yoxdur. Real kart şəbəkəsi tokenizasiyası (Visa/Mastercard token xidməti, cihaz tokeni) yoxdur, çünki Kampus kart saxlamır.
