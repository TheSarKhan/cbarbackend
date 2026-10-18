# Kampus Mobil Dizayn Təhvili (`DESIGN_HANDOFF.md`)

Bu sənəd **Kampus** rəqəmsal tələbə cüzdanının (`apps/mobile`) mobil interfeys və istifadəçi təcrübəsi (UI/UX) üzrə aparılmış yeniləmələri, modulyar komponent arxitekturasını, yeni lokal vizual assetləri, yoxlanmış ekranları və gələcək mərhələlər üçün backend komandasına ötürülən texniki qeydləri əhatə edir.

---

## 1. Dəyişən və Yeni Yaradılan Mobil Fayllar

Funksional sərhədlərə uyğun olaraq `services/`, `scripts/`, `apps/admin/` və `apps/mobile/src/api.ts` fayllarına **toxunulmayıb**. Bütün dəyişikliklər yalnız `apps/mobile/` daxilində modulyar şəkildə icra olunub:

| Fayl | Rolu və Edilən Dəyişikliklər |
|---|---|
| `apps/mobile/src/ui.tsx` | Kampus brend palitrası (`#17352e` tünd meşə yaşılı, `#cbef93` laym aksent, `#f7f8f3` açıq krem-adaçayı fon), paylaşılan kontekst (`Context`, `useApp`), tarix və status formatlayıcıları (`fmtDate`, `fmtDateTime`, `statusLabel`, `statusTone`), minimum `48 dp` toxunma hədəfləri olan UI primitivləri (`Button`, `Field`, `Check`, `Badge`, `Box`, `Empty`, `Sheet`, `Tx`). |
| `apps/mobile/src/CardTransferSheet.tsx` | Karta və öz kartına çıxarış axını: saxlanılmış kartların seçimi, yeni kart PAN (Luhn yoxlaması və bank aşkarlanması), cəld məbləğ düymələri (`10 ₼`, `25 ₼`, `50 ₼`, `100 ₼`), aydın komissiya (`1.5%`, min. `0.50 ₼`) və yekun ödəniş xülasəsi, 6 rəqəmli SCA PIN təsdiqi və bütün yekun status ekranları (`COMPLETED`, `FAILED`, `UNDER_REVIEW`, `HELD`). |
| `apps/mobile/src/HubSections.tsx` | 3 əsas maliyyə modulu ayrı komponentlərə bölünüb: **`CardsSection`** (virtual kartlar, 30s PAN/CVV göstərilməsi, kanal açarları, satıcı bloklama, `>3x` anomaliya təsdiqi), **`CreditSection`** (`0%` Sonra ödə / BNPL və partnyor BOKT kredit müraciəti, təkliflərin müqayisəsi, ödəniş qrafikinə keçid) və **`CryptoSection`** (USDT TRC-20 portfeli, 30 saniyəlik məzənnə ilə Al/Sat, depozit, AML sanksiya testi və Travel Rule çıxarışı). |
| `apps/mobile/src/OperationsSheet.tsx` | Bütün aşağı pəncərə (`Sheet`) əməliyyatları: 4 kartlı balans artımı (`CARD`, `BITCOIN` prototipi, `OPEN_BANKING`, `TERMINAL` 8 rəqəmli kod), pul göndərmə və istəmə (`send`, `request`), hesab bölmə (`split`, `split-view`), kredit ödəniş qrafiki (`schedule`), yeni virtual kart (`new-card`), bildirişlər (`notifications`), QR kodum (`my-qr`) və əməliyyat detalları (`tx-detail`). |
| `apps/mobile/App.tsx` | Giriş/Qeydiyyat (OTP + eKYC + SİMA simulyasiyası), 5 alt tab naviqasiyası (`Home`, `Payments`, `QR`, `History`, `Profile`), Android geri düyməsi (`BackHandler`) dəstəyi, SCA təsdiq pəncərəsi və veb-brauzer üçün `393 × 852` mobil çərçivə simulyatoru. |

---

## 2. Yeni Lokal Vizual Assetlər (Nano Banana)

Bütün yeni vizuallar Kampus brend rənglərinə uyğun olaraq **Nano Banana** vasitəsilə yaradılıb, yalnız dekorativ/illüstrativ məqsədlə istifadə olunub (bütün mətnlər, balanslar, düymələr və formalar real React Native komponentləridir) və mobil APK ölçüsünü şişirtməmək üçün sıxılaraq `apps/mobile/assets/cards/` qovluğunda lokal saxlanılıb (cəmi **~105 KB**):

| Asset Faylı | Ölçü | İstifadə Yeri |
|---|---|---|
| `apps/mobile/assets/cards/auth-login-hero.jpg` | `640×360` (22 KB) | Giriş və Qeydiyyat ekranının yuxarı 3D hero baneri (tələbə papağı, rəqəmsal kart və qızıl sikkələr) |
| `apps/mobile/assets/cards/credit-cash.jpg` | `320×320` (10 KB) | Giriş ekranı mini-kartı və Ana səhifə — "Borc müraciəti 1 500 ₼-dək" Bento kartı |
| `apps/mobile/assets/cards/btc-topup.jpg` | `320×320` (10 KB) | Ana səhifə və Balans artımı — "Bitcoin" seçim kartı |
| `apps/mobile/assets/cards/virtual-card.jpg` | `320×320` (10 KB) | Ana səhifə — "Virtual Kartlar" Bento kartı və Kartlar bölməsi başlığı |
| `apps/mobile/assets/cards/split-bill.jpg` | `320×320` (15 KB) | Ana səhifə — "Hesabı böl" Bento kartı |
| `apps/mobile/assets/cards/btc-hero-banner.jpg` | `640×360` (21 KB) | Bitcoin ilə balans artımı prototip ekranının yuxarı hero baneri |
| `apps/mobile/assets/cards/btc-lightning.jpg` | `320×320` (7 KB) | Bitcoin prototip ekranı — Lightning şəbəkə seçimi |
| `apps/mobile/assets/cards/btc-onchain.jpg` | `320×320` (8 KB) | Bitcoin prototip ekranı — SegWit (On-Chain) şəbəkə seçimi |
| `apps/mobile/assets/cards/topup-card.jpg` | `320×320` (6 KB) | Balans artımı — "Bank kartı" seçim kartı və daxili başlıq |
| `apps/mobile/assets/cards/topup-bank.jpg` | `320×320` (10 KB) | Balans artımı — "Bank hesabı (IBAN / Açıq Bank)" seçim kartı və daxili başlıq |
| `apps/mobile/assets/cards/topup-terminal.jpg` | `320×320` (8 KB) | Balans artımı — "Terminal (MilliÖN / eManat)" seçim kartı və daxili başlıq |

---

## 3. Yoxlanmış Ekranlar və İstifadəçi Axınları

1. **Giriş və Qeydiyyat:**
   - Demo tələbə hesabları (`Aytən`, `Murad`, `Leyla`, PIN `123456`) ilə sürətli giriş.
   - Qeydiyyat zamanı `SMS + EKYC SİMULYASİYASI` etiketi, demo SMS kod, FİN (`17YASLI`, `LOWFACE` sınaq halları daxil), canlılıq (`Demo canlılıq addımını təsdiqləyirəm — kamera və ML Kit yoxlaması aparılmır`), SİMA imza və razılıq qutuları tam qorunub.
2. **Ana Səhifə və Balansın Gizlədilməsi:**
   - Tam və qəpik hissəsi fərqləndirilmiş balans görünüşü, `48×48 dp` göz düyməsi ilə balansın gizlədilməsi (`••••• ₼`), rezerv edilmiş vəsait (`heldAmountMinor`) xəbərdarlığı, "Balans artımı" və "Köçürmək" əsas düymələri, 4 Bento xidmət kartı.
3. **Balans Artırılması (4 Seçimli Kart Görünüşü):**
   - **Bank kartı:** Kapital Bank, ABB və ya `Yeni kart · demo` (`KART BAĞLANTISI · DEMO` simulyasiya etiketi ilə) seçimi, `0.00 ₼` komissiya xülasəsi və `POST /api/v1/topups` (`source: 'CARD'`).
   - **Bank hesabı (Açıq Bankçılıq):** IBAN hesab seçimi və `POST /api/v1/topups` (`source: 'OPEN_BANKING'`).
   - **Terminal:** 3 addımlı təlimat, 8 rəqəmli birdəfəlik kodun yaradılması (`POST /api/v1/topups/terminal-code`) və ödənişin tamamlanması (`POST /api/v1/topups/terminal-redeem`).
   - **Bitcoin (Prototip):** `BTC EKRANI · PROTOTİP` etiketi, Lightning / SegWit şəbəkə seçimi, canlı AZN ↔ BTC/satoshi kalkulyatoru və işlək **"USDT pilotuna keç"** düyməsi (`crypto-hub`-a yönləndirir).
4. **Pul Köçürmələri və Karta Çıxarış:**
   - Dostlar və kontakt uyğunlaşdırma (`POST /api/v1/contacts/match`), etibarlı alıcılar (`SCA İSTİSNASI`), alıcının yoxlanması (`GET /api/v1/transfers/resolve`), fırıldaqçılıq açar söz xəbərdarlığı, 5 saniyəlik ləğvetmə pəncərəsi (`SCHEDULED` → `CANCELLED`), admin yoxlaması (`UNDER_REVIEW`), hesab bölmə (`EQUAL` və `CUSTOM`) və gələn pul sorğuları.
5. **QR Ödənişi:**
   - Tam ekran kamera görünüşü, fənər (`torch`) və əllə QR mətni daxil etmə pəncərəsi, EMVCo QR parse (`POST /api/v1/payments/qr/parse`), ödəniş mənbəyi (`WALLET`, `BNPL`, `CRYPTO`), komissiya/yekun xülasəsi və uğurlu ödənişdən sonra hesabı dostlarla bölmə imkanı.
6. **Virtual Kartlar:**
   - 30 saniyəlik müvəqqəti PAN/CVV göstərilməsi (loglara və daimi yaddaşa yazılmır), dondurma/aktivləşdirmə, aylıq və birdəfəlik limit yeniləmə, `online`/`contactless`/`atm` kanal açarları, satıcı bloklama və `>3x` anomaliya əməliyyatlarının təsdiqi (`POST /api/v1/card-auth/:id/confirm`).
7. **Kredit Müraciəti, Təkliflərin Müqayisəsi və Sonra Ödə (BNPL):**
   - `0%` Sonra ödə limiti, partnyor kredit müraciəti forması (məbləğ, müddət, gəlir, xərc, GPA), BOKT təkliflərinin müqayisəsi (aylıq ödəniş, FİFD, müddət, yekun ödəniş), PIN ilə qəbul və aylıq ödəniş qrafiki (`schedule`).
8. **USDT Pilotu (Kripto Cüzdanı):**
   - TRC-20 portfel balansı, 30 saniyəlik geri sayımlı məzənnə təklifi (`POST /api/v1/crypto/quotes`), Al/Sat icrası (`POST /api/v1/crypto/orders`), TRC-20 depozit, AML sanksiya testi (`TBLACKLIST999SANCTION`) və Travel Rule ilə çıxarış.
9. **Əməliyyat Tarixçəsi və Əməliyyat Detalları:**
   - Kateqoriya üzrə sürətli filtrləmə (`Hamısı`, `Yemək`, `Abunəliklər`, `Köçürmələr`, `Nəqliyyat`, `Əyləncə`), hər əməliyyata toxunduqda açılan **Əməliyyat detalları (`tx-detail`)** pəncərəsi və kateqoriya dəyişdirmə.
10. **Büdcə, Analitika, Bildirişlər və Profil:**
    - Kateqoriya üzrə aylıq büdcə təyini, `80%+` xəbərdarlıq və `100%+` aşılma nişanları, oxunmamış bildiriş sayğacı və 5 dəqiqəlik sessiya təhlükəsizliyi.
11. **Texniki Yoxlamalar:**
    - `npm --prefix apps/mobile run typecheck` — **0 xəta**
    - `npm --prefix apps/mobile run lint` — **0 xəta, 0 xəbərdarlıq**

---

## 4. Backend Tərəfindən Lazım Olan Əlavələr (Gələcək Mərhələ Üçün)

Mövcud mərhələdə heç bir saxta uğur cavabı (`fake success`) əlavə edilməyib və bütün işlək axınlar mövcud API müqavilələrinə bağlanıb. Gələcək iterasiyalarda aşağıdakı imkanların aktivləşdirilməsi üçün backend dəstəyi tələb olunur:

1. **Bitcoin (Lightning / SegWit) İnteqrasiyası (`POST /api/v1/crypto/btc/invoices`):**
   - Hazırda Bitcoin balans artımı ekranı (`add:BITCOIN`) `BTC EKRANI · PROTOTİP` etiketi ilə yalnız vizual prototipdir və istifadəçini işlək USDT pilotuna (`crypto-hub`) yönləndirir.
   - Real demo axını üçün backend-də `POST /api/v1/crypto/btc/invoices` (məbləğə uyğun BOLT11 Lightning invoice və SegWit ünvanı yaradan) və webhook/simulyasiya təsdiqi (`POST /api/v1/crypto/btc/invoices/:id/settle`) endpoint-lərinə ehtiyac var.
2. **Real Kart Bağlantısı Tokenizasiyası (`POST /api/v1/payment-methods/cards`):**
   - Balans artımı ekranındakı `"Yeni kart · demo"` seçimi hazırda `KART BAĞLANTISI · DEMO` etiketi ilə kart PAN/CVV toplamadan simulyasiya edilir.
   - 3D Secure və kart tokenizasiyası simulyasiyası üçün partner-simulator (`:8081`) üzərindən `POST /api/v1/payment-methods/cards` endpoint-i əlavə edilə bilər.
3. **Əməliyyat Qəbzi (PDF / Paylaşılan Qəbz Endpoint-i):**
   - Yeni əlavə edilmiş `tx-detail` (Əməliyyat detalları) pəncərəsində əməliyyat kodu, tarix, kateqoriya və komissiya göstərilir. Rəsmi qəbz paylaşımı üçün `GET /api/v1/transactions/:id/receipt` endpoint-i faydalı olar.
