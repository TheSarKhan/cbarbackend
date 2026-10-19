# Kampus backend

Java 21/Spring Boot API və ayrıca bank, kart, reyestr/SİMA və VASP simulyatoru. Demo vəsaitləri istifadə olunur.

Layihə üç repoya bölünüb:

- `TheSarKhan/cbarbackend`: API, simulator, inteqrasiya testləri və deploy infrastrukturu.
- `TheSarKhan/cbarfrontend`: Next.js admin və satıcı kassası.
- `TheSarKhan/cbarmobile`: React Native/Expo, mobil veb və imzalanmış Android APK.

## Yoxlama və lokal işə salma

Java 21, Maven və Python 3 tələb olunur:

```sh
./scripts/check.sh
./scripts/dev.sh
```

API `http://localhost:8080/api/v1`, simulator `http://localhost:8081` ünvanında qalxır. Lokal demo PIN-i `123456`-dır. Hosted PIN və simulator/DB açarları repoda saxlanmır.

## CI/CD

PR-lərdə Java və izolə HTTP inteqrasiya testləri işləyir. `main` üzərində testlər keçəndən sonra serverə yalnız API və simulator buraxılışı göndərilir. Deploy komponentləri ortaq server kilidi ilə növbəyə düzülür.

Mövcud deploy: `/opt/cbar`, Compose layihəsi `kampus`. Digər server layihələrinin qovluqları, bazaları, Nginx və servisləri deploy əmrinə daxil deyil. DB silinmir; `.env` və canlı Compose konfiqurasiyası repo ilə üstündən yazılmır.

Serverdə root-a məxsus `deploy/kampus-ci` gate-i quraşdırılır. Hər repoya ayrıca, forced-command SSH açarı verilir; açar shell, rsync və port forwarding aça bilmir. SSH host açarı workflow secret-ində sabitlənir.

- `KAMPUS_DEPLOY_KEY`: yalnız bu komponent üçün SSH private açarı.
- `KAMPUS_KNOWN_HOSTS`: yoxlanmış server host açarı.
- `VPS_HOST`, `VPS_USER`: deploy ünvanı və istifadəçisi.

`deploy/compose.ci.yaml` əvvəlcədən qurulmuş image-lərin commit SHA-sını pin edir. Build canlı servislər işləyərkən aparılır. Backend dəyişəndə DB/provider backup-ı alınır; yalnız sim və API yenilənir. Health yoxlaması uğursuz olsa əvvəlki image-lər və revision pin-ləri qaytarılır. DB dump avtomatik geri yazılmır: migrasiyalar geriyə uyğun olmalıdır, data bərpası məqsədli əməliyyatdır.

Gate və canlı Compose konfiqurasiyasının dəyişməsi ayrıca operator bootstrap tələb edir; adi tətbiq deployu onları əvəz etmir. Deploy qeydləri və snapshot-lar `/opt/cbar/.ci/` daxilindədir.
