# Kampus

Tələbələr üçün demo rəqəmsal cüzdan. Android üçün React Native/Expo tətbiqi, Next.js admin/kassa, Java 21/Spring Boot API və ayrıca mock partnyor servisi.

## Deploy paketi

Docker/Compose, PostgreSQL, Flyway, Caddy HTTPS, statik mobil veb və standalone admin hazırdır. Lokal deploy sınağı və serverə keçid üçün [docs/DEPLOY.md](docs/DEPLOY.md) sənədinə baxın. İctimai yayımlama ayrıca edilir.

## Başlatmaq

Java 21, Maven və Node 24 tələb olunur. Android APK üçün Android SDK 37 və uyğun NDK lazımdır.

```sh
./scripts/setup.sh
./scripts/dev.sh
```

- Mobil brauzer prototipi: http://localhost:8082
- Admin və satıcı kassası: http://localhost:3000
- API health: http://localhost:8080/api/v1/health
- Simulator health: http://localhost:8081/health

Mobil: Aytən +994501000001, Murad +994501000002, Leyla +994501000003, Kamran +994501000004. PIN hamısında 123456.

Admin: +994501000005. Uyğunluq: +994501000006. Satıcı: +994501000007. PIN 123456.

## Telefon bağlantısı

Telefon və kompüter eyni şəbəkədə olmalıdır. Giriş ekranındakı “API bağlantısını tənzimlə” sahəsinə kompüterin lokal IP-si ilə `http://IP:8080/api/v1` yazın. Android emulyatorunda standart `http://10.0.2.2:8080/api/v1` işləyir. APK API və simulyator servisləri işləyən kompüterə bağlanır.

Kripto ilkin olaraq söndürülüb. Admin → Sistem ayarları → Kripto pilotu açarı ilə aktivləşdirin.

## Yoxlamalar

```sh
./scripts/check.sh
```

İnteqrasiya testləri ayrı portlarda, müvəqqəti DB-də işləyir; sizin lokal demo məlumatlarınızı dəyişdirmir. PIN bloklaması, rollar, QR CRC/replay, paralel idempotency, balans, köçürmə ləğvi, fraud, kredit, BNPL və kripto axınları yoxlanılır.

## APK

```sh
./scripts/build-apk.sh
```

Çıxış: artifacts/kampus-demo.apk. Ayrıca Kampus release açarı ilə imzalanır, Metro serveri olmadan JS bundle-dan işləyir. Lokal HTTP bağlantısına yalnız sandbox üçün icazə verilir. `--api-url https://domen/api/v1` ilə server üçün HTTPS-only APK yığılır. İmza açarı `.local/signing/` daxilindədir; ehtiyat nüsxəsini saxlayın.

## Vəziyyət

Bu bank və dövlət sistemlərini simulyasiya edən işlək demo layihəsidir. Tam texniki tapşırıqla funksional fərqlər, növbəti işlər və inkişaf prioritetləri [docs/ROADMAP.md](docs/ROADMAP.md) daxilindədir. Təqdimat sonraya saxlanılıb.

Mənbə DOCX-lər dəyişdirilməyib. Onların mətn çıxarışları docs/spec daxilindədir. Heç bir real bank, SMS, dövlət reyestri, SİMA, kreditor və ya blokçeyn bağlantısı yoxdur. Mock VASP balans və sifarişlərini ayrıca .local/vasp-state.json faylında saxlayır.
