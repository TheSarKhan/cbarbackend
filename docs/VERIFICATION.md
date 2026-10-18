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
