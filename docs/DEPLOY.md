# Kampus demo deploy

Bu paket real pul xidmətinə deyil, mövcud bank/SİMA/VASP simulyatorları ilə işləyən nümayiş sisteminə aiddir. Backend, Android tətbiqi, mobil veb və admin eyni API istifadə edir. İctimai yayımlama istifadəçi ayrıca deyəndə edilir.

## Paket

- Java 21 API və simulator üçün ayrıca multi-stage Docker image.
- PostgreSQL 17 və Flyway migrasiyaları. Yeni deploy lokal H2 məlumatlarını avtomatik daşımır.
- Next.js standalone admin və Expo statik mobil veb.
- Caddy: mobil və admin üçün ayrı domenlər, domenlər DNS ilə serverə baxanda avtomatik HTTPS.
- Yalnız gateway portları hosta çıxır; DB və simulatorun özəl API-ləri daxili Docker şəbəkəsindədir. Simulatorun yalnız `/card-form/*` formaları xaricdən açılır.
- Healthcheck-lər, restart siyasəti, davamlı DB/provider volume-ları, log ölçüsü limitləri və backup/bərpa skriptləri.

## Lokal deploy sınağı

Docker Engine və Compose v2 lazımdır. Layihə qovluğunda:

```sh
python3 scripts/deploy-init.py --local
./scripts/deploy.sh
python3 scripts/deploy-smoke.py
```

İlk əmr `.env` yoxdursa işləyir; mövcud faylın üzərinə yazmır. Lokal deploy yalnız `127.0.0.1`-ə bağlanır:

- Mobil: http://localhost:18080
- Admin/kassa: http://localhost:18081
- API: http://localhost:18080/api/v1

PIN-lər `.env` daxilində `DEMO_PIN` və `ADMIN_PIN` kimi saxlanır, `123456` deyil. 001–004 tələbələri üçün DEMO_PIN, 005 admin, 006 uyğunluq və 007 satıcı üçün ADMIN_PIN işləyir. Telefonlar README-dədir. Bu PIN-lər ilk DB yaradılarkən tətbiq edilir; mövcud DB üçün `.env`-i dəyişmək hesab PIN-lərini dəyişmir.

`docker compose down` servisləri dayandırır və məlumatları saxlayır. `down -v` məlumatları silir; normal yeniləmə üçün istifadə etməyin.

## Serverə keçid

Linux serverdə Docker Engine/Compose quraşdırılmış, 80 və 443 portları açıq olmalıdır. Mobil və admin üçün iki DNS adı eyni serverə baxmalıdır (məsələn, wallet və admin subdomenləri). Tam yığım üçün 4 GB və daha çox RAM məsləhətdir; daha kiçik serverdə image-ləri ayrıca build edib registry-dən gətirmək olar.

Yeni serverdə source paketini açın, sonra:

```sh
python3 scripts/deploy-init.py --app-domain wallet.example.com --admin-domain admin.example.com
./scripts/deploy.sh
python3 scripts/deploy-smoke.py
```

Nümunə domenləri həqiqi domenlərlə əvəz edin. `.env` terminal çıxışına, repoya və ümumi paylaşım paketinə daxil edilməməlidir. INIT skripti onu 0600 icazəsi ilə, təsadüfi simulator/DB açarları və PIN-lərlə yaradır. Hosted profil standart açar/PIN və H2 ilə başlamır.

Lokal `.env` yeni serverə kopyalanmamalıdır: yeni serverdə domain rejimində yaradılmalıdır. Yalnız mövcud deployu köçürəndə onun `.env`-i və backup-u təhlükəsiz şəkildə birlikdə köçürülür.

## Android

Hazır `artifacts/kampus-demo.apk` lokal/emulyator istifadəsi və API ünvanının girişdən əl ilə verilməsi üçündür. Server domeni məlum olduqda:

```sh
./scripts/build-apk.sh --api-url https://wallet.example.com/api/v1
```

Bu seçim HTTPS ünvanını bundle-a daxil edir və Android cleartext trafiki bağlayır. APK-nın simulator kart forması həmin HTTPS domendə açılır; ayrıca 8081 portu lazım deyil. API ünvanı giriş ekranında görünür və tənzimlənə bilir.

APK artıq ayrıca Kampus release açarı ilə imzalanır. Açar və şifrə `.local/signing/` daxilindədir; source paketinə daxil deyil. Onu təhlükəsiz ehtiyat nüsxədə saxlayın. Açar itərsə əvvəlki APK-nı adi yeniləmə ilə əvəz etmək mümkün olmayacaq. Əvvəlki debug sertifikatlı versiya quraşdırılıbsa ilk keçiddə onu silib yeni APK-nı quraşdırmaq tələb olunur. Minimum Android API 24, arxitektura arm64-v8a.

Fiziki telefonda quraşdırma/kamera/biometrika ayrıca qəbul yoxlamasıdır; kompüterdə APK yığılması bu yoxlamaları əvəz etmir.

## Backup və yeniləmə

```sh
./scripts/backup.sh
./scripts/deploy.sh
python3 scripts/deploy-smoke.py
```

Backup yazan servisləri qısa dayandırır, PostgreSQL custom dump və simulator JSON vəziyyətini eyni sərhəddə saxlayır, sonra servisləri başladır. Nəticə `.local/backups/TARIX/` daxilindədir. `.env` və imza açarının ayrıca təhlükəsiz nüsxəsi də saxlanmalıdır.

Mövcud məlumatları əvəz edən bərpa əmri yalnız məqsədli bərpa zamanı işlədilir:

```sh
./scripts/restore.sh .local/backups/TARIX --replace-data
```

Bərpa uğursuz olsa yazan servislər dayandırılmış qalır; problemi həll edib əmri yenidən işlətmək lazımdır. Migrasiya xətasında DB-ni silməyin. Geriyə keçid üçün əvvəlki source/image və uyğun backup istifadə olunur.

## Sərhədlər

Bu, deploy oluna bilən demo paketidir. SİMA, reyestr, canlılıq/üz yoxlaması, bank/kreditor və VASP simulyasiyadır; real maliyyə xidməti kimi təqdim edilmir. Kamera ML Kit, real bank çıxarışı ilə üzləşdirmə, push/SMS və aparatla cihaz bağlama ayrıca inteqrasiyalardır. Təqdimat razılaşmaya əsasən sonraya saxlanıb.

## KYC fotoları və `KYC_MEDIA_KEY`

Qeydiyyat selfisi PostgreSQL-də `kyc_media` cədvəlində yalnız AES-256-GCM şifrəli saxlanılır. Şifrələmə açarı bazada deyil, `.env` daxilindəki `KYC_MEDIA_KEY` dəyişənindədir (`scripts/deploy-init.py` onu təsadüfi yaradır, hosted profil 32 simvoldan qısa və ya lokal standart açarla başlamır). Backup bazanı saxlayır, açarı saxlamır: `.env` ayrıca təhlükəsiz nüsxədə saxlanmalıdır. Açar itərsə, saxlanmış fotolar oxunmaz olur. Mövcud serverin `.env` faylına bu dəyişəni əlavə etmədən yeni API deploy edilməməlidir, çünki Compose onsuz başlamır.
