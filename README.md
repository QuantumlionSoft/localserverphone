# Pocket PaaS — Faz 1–7 + 0.5.0 Platform Genişletmesi

Kotlin ile Android APK projesi. Temel platform özelliklerine ek olarak çalışma ağacı **Room v5** (mevcut tablolar korunarak `customHostname` migration'ı), site kapsamlı dosya yöneticisi ve uygulama içi Cloudflare Named Tunnel/DNS yönetimi içerir. API tokenı Android Keystore AES-GCM ile şifrelenir. Rootsuz cihaz + Termux bağlantısı kullanılır; Nginx, Python, Node.js, cloudflared, sshd ve veritabanı servisleri Termux içinde çalışır.

**Son yayımlanmış APK: 0.5.0-debug, Room v4 + SSH + Veritabanı Servisleri + Yedekleme & Log Arşivleme.** Aşağıdaki çalışma ağacı değişiklikleri henüz yeni bir release olarak yayımlanmadı. [0.5.0-debug APK'yı indir](https://github.com/QuantumlionSoft/localserverphone/releases/download/v0.5.0-debug/PocketPaaS-0.5.0-debug.apk) · [v0.5.0-debug kaynak kodu ZIP'i](https://github.com/QuantumlionSoft/localserverphone/archive/refs/tags/v0.5.0-debug.zip). Kurulum dört sayfaya ayrılır: **Termux → İzin → Araçlar → Kontrol**. Kurulumda ve panelde uygulama sürümü, 2. sayfanın altında sürekli görünen **İzin iste** ve **Pocket PaaS ayarlarını aç** düğmeleri vardır. Android izni, araç kurulumu, yerel API, Nginx, internet tüneli, SSH ve veritabanı servisleri ayrı durumlarla gösterilir. Komutu kopyalamak veya Termux'u açmak komutu çalıştırmaz; kullanıcı terminale yapıştırıp Enter'a basar.

0.4.0, Termux süreç çıktısını taşıyan Java `PipedInputStream` yerine coroutine'lerin hangi IO thread'inde devam ettiğine bağlı olmayan, ayrı ve sınırlı stdout/stderr byte tamponları kullanır. Geçmiş bir okuma hatası `await()` sonucunda korunur; **Durdur** ise sahip olunan sürecin gerçekten sonlandığını bağımsız olarak doğrulayabilir ve başarısız temizliği yeniden deneyebilir. Doğrulama yoksa sahiplik/PID kaydı korunur. Nginx/tünel başlangıç süre aşımı artık kullanıcı iptalinden ayrı `FAILED` ve açıklayıcı hata üretir. Paneldeki hata metinleri seçilip kopyalanabilir.

Kurulum komutu artık önce bütün kurulu Termux paketlerini `pkg upgrade -y` ile yükseltir, ardından sunucu paketlerini kurar ve gerçek sürüm komutlarını çalıştırır. Böylece yalnız dosyanın bulunması kurulumun tamamlandığı sayılmaz; Nginx/OpenSSL gibi binary/kitaplık uyumsuzluğu sürüm komutunda da hata verir. APK, Termux kitaplıklarını içermez veya tek başına onarmaz. Güncel komutu Termux'ta çalıştırma adımları [KOLAY-KURULUM.md](KOLAY-KURULUM.md) içindedir. Bu değişikliklerin kullanıcının telefonundaki sonucu henüz doğrulanmadı.

Android 12+ cihazda Play Store Termux sürümünde komut izni görünmüyorsa, yalnız izin düğmesi aramak çözüm olmayabilir: Play Store dalının 18 Ekim 2026'da incelenen [manifestinde](https://github.com/termux-play-store/termux-apps/blob/main/termux-app/src/main/AndroidManifest.xml) gerekli `RUN_COMMAND`/`RunCommandService` arayüzü yoktur. Yeni denetim bu durumu **Bu Termux sürümünde komut izni bulunmuyor** diye açıklar. Önerilen kurulum kaynağı [F-Droid](https://f-droid.org/packages/com.termux/); alternatif [resmî GitHub kurulum sayfasıdır](https://github.com/termux/termux-app#installation). Kaynak değiştirmeden önce Termux dosyalarını yedekleyin; uygulama/eklenti imzalarını karıştırmayın. Uygulama otomatik kaldırma veya veri silme yapmaz.

[RUNTIME-FIX-VALIDATION.md](RUNTIME-FIX-VALIDATION.md), 0.4.0-debug sürümünün tarihsel doğrulama raporudur; 0.5.0 için ayrı bir makine-test raporu bu depoda bulunmuyor. 0.5.0-debug APK'sı GitHub Release'te yayımlanmıştır; gerçek Android/Termux cihaz çalıştırması ve küçültülmüş release derlemesi ayrıca doğrulanmalıdır. [0.2.0 kolay kurulum raporu](EASY-SETUP-VALIDATION.md) ve önceki faz raporları, kendi teslimlerinin tarihsel kapsamını korur.

Telefon üzerinde kurulum için [KOLAY-KURULUM.md](KOLAY-KURULUM.md), panelin kullanımı için [DASHBOARD.md](DASHBOARD.md) dosyasını izleyin. Bilgisayar veya ADB gerekmez. Önceki fazların değişmeden korunan raporları: [Faz 7](PHASE-7-VALIDATION.md), [Faz 6](PHASE-6-VALIDATION.md), [Faz 5](PHASE-5-VALIDATION.md), [Faz 4](PHASE-4-VALIDATION.md), [Faz 3](PHASE-3-VALIDATION.md), [Faz 2](PHASE-2-VALIDATION.md), [Faz 1](VALIDATION.md).

## Dahil edilen kod

- `ApplicationClass`: Hilt uygulaması, asenkron Room başlangıcı ve `HiltWorkerFactory` ile WorkManager yapılandırması.
- `AppModule`: singleton Room, DAO/repository'ler, süreç/runtime/deployment/proxy arayüzleri, sistem yöneticisi, Netty engine fabrikası ve WorkManager; nitelikli IO dispatcher ve uygulama coroutine scope'u. Network ve deployment motorları Hilt constructor/provider binding'leri kullanır.
- Domain arayüzleri ve use case, data uygulamaları, MVVM Dashboard: Android kaynakları domain modeline taşınmaz.
- `SetupScreen` ve `SetupViewModel`: dört sayfalı ilk açılış sihirbazı, sabit izin/uygulama ayarları düğmeleri, sürüm bilgisi, uyumsuz Termux rehberi, tek komut kopyalama ve gerçek bağlantı/araç doğrulaması. `TermuxPermissionInspector` kurulu/etkin uygulama, izin sahibi, erişilebilir komut servisi ve gerçek grant durumunu ayırır; başlatmadan önce izni yeniden denetler. `TermuxSetupInstructions` paket kurulumu ve mevcut ayarları koruyan atomik Termux ayarı sağlar; tamamlanma yalnız sunucu ve Nginx çalıştıktan sonra kaydedilir.
- `MainViewModel` ve `DashboardScreen`: Room, sunucu ve ağ durumlarını lifecycle ile izleyen Material 3 ekran; Ana sunucu anahtarı, Nginx/tünel durumları, tünel adresini kopyalama, site listesi ve canlı log paneli. Açık/koyu tema ve Termux kurulum yardımı dahildir.
- Yeni proje formu tür/domain ve Android belge seçicisiyle ZIP alır; yalnız **Yayına al** eylemi deployment başlatır. Başlat/Durdur/İptal işlemleri Foreground Service oturumuna bağlı uygulama scope'unda yürütülür. Activity/ViewModel yeniden oluşturulması kabul edilmiş deployment'ı iptal etmez.
- LAN erişimi sunucu durmuşken açıkça seçilir; varsayılan loopback korunur. Bildirim/Termux izin akışları ve hassas işaretli API anahtarı kopyalama bağlıdır; anahtar UI state veya SavedState içine alınmaz.
- `AndroidSystemManager`: uygulamanın özel depolamasında çalışma dizinleri oluşturur; Termux kurulumu, etkinliği, sürümü, komut arayüzü, izin ve ABI durumunu denetler. Tanımsız veya uyumsuz izin/servisi hazır saymaz. Bir komut veya sunucu başlatmaz.
- Gerçek Room `platform_settings` tablosu: kurulum kimliğini transaction ile yalnızca bir kez kaydeder. `SiteEntity`, `SiteDao`, domain `SiteRepository` ve `DefaultSiteRepository`; `1→2` migration mevcut kurulum verisini korur. Faz 4'te `2→3` migration ana sunucunun nullable `publicUrl` alanını ekler; `ServerStateRepository` bu değeri `Flow` ile yayınlar.
- Site ve durum sorguları `Flow`, repository siteleri `StateFlow` ile yayınlar. Domain modelleri Room annotation'larına bağlı değildir. Veri okuma hatasında son bilinen liste korunur; tekrar okuma gecikmesi 1–16 saniyeye kadar artar ve coroutine iptali tekrar denenmez.
- `SELECT MAX(internalPort)` ve kullanılan portları okuma, port seçimi ve kayıt ekleme aynı transaction içindedir. Domain/port UNIQUE indeksleri ek koruma sağlar. Port aralığı 10000–65535; API ve Nginx portları hariç tutulur. Dolu aralıkta silinen portlar yeniden kullanılabilir.
- Loglar tek SQL `UPDATE` ile eklenir ve son 65536 Unicode karakterle sınırlandırılır. Runtime durumu/PID birlikte güncellenir; koşullu güncelleme (CAS) eski işlemlerin yeni durumu ezmesini önlemek için kullanılabilir.
- Room v1–v5 şema dışa aktarımları, Android migration testleri ve AAPT2 gerektirmeyen yeniden çalıştırılabilir JVM doğrulama modülü teslimdedir.
- `ShellExecutor`: stdout ve stderr için ayrı IO coroutine'leri, sınırlı capture/live log tamponları, PID, command timeout, iptal temizliği ve uzun ömürlü `ManagedProcess`. Başarısız `await()` geçmişi, fiziksel sonlandırma onayı ve Stop tekrarları ayrı tutulur. Toplam sekiz slot'un biri kısa kontrol komutlarına ayrılır; en fazla yedi uzun süreç.
- `LocalProcessLauncher`: erişilebilir native binary'ler için gerçek `ProcessBuilder`; Termux/global PATH ve binary alias çözümleme.
- `TermuxProcessLauncher`, authenticated localhost bridge, `RUN_COMMAND` bootstrap ve özel token deposu Hilt singleton graph'ına bağlıdır. `BoundedProcessInputStream` thread kimliğinden bağımsız tampon, EOF, hata ve kapatma davranışını sağlar. Python agent Termux UID'sinde process group yönetimi, bağımsız stream kuyrukları ve 300 saniyelik terk edilmiş süreç lease'i uygular.
- Termux kurulumu, API kullanımı ve süreç davranışları [TERMUX-RUNTIME.md](TERMUX-RUNTIME.md) dosyasındadır.
- `NetworkManager`: Nginx config testi, foreground süreç takibi, revision ile HTTP readiness, Quick Tunnel veya custom-hostname varsa Named Tunnel seçimi, edge readiness ve süreç temizliği. Quick URL'ler site yolu kullanır; Named Tunnel her custom hostname'i kendi site köküne yollar. Tünel kaybında kalıcı public URL'ler temizlenir.
- `NginxConfigBuilder`: mevcut `/sites/<domain>/` yolunu korur; ayrıca yalnız atanmış custom hostname için ayrı Nginx server dosyası üretir. STATIC, React SPA ve Django/Node reverse proxy desteklenir. Config aday dosyalarda doğrulanır; atomik uygulama/reload hatasında önceki iki config ve revision geri yüklenir.
- `ShellRuntimeFileStore` ve `pocketpaas_files.py`: Termux UID'sinde sınırlandırılmış UTF-8 config okuma/yazma; POSIX descriptor ile symlink/traversal engelleme ve fsync'li atomik dosya değiştirme.
- **Site dosya yöneticisi:** uygulamadaki her site kartından yalnız o sitenin proje klasörü yönetilir. Quick Tunnel modunda web paneli `https://<quick-tunnel-host>/manage/`, LAN modu açıkken `http://<cihaz-lan-ip>:8080/_pocketpaas/manager/` adresindedir. Arayüz gizli dosyaları ve `node_modules`/`venv` gibi dizinleri saklar; path traversal ve symlink reddedilir. Metin düzenleme, yeni metin dosyası/klasör ve silme sunar; metin dosyası 32 KiB ile sınırlıdır, binary upload/download yoktur.
- Web paneli mevcut API anahtarını ister ve anahtarı yalnız sekme belleğinde tutar. Quick Tunnel Nginx üzerinden yalnız dosya yöneticisi rotalarını aktarır; Named Tunnel özel hostnamelerinde yönetim rotaları yayınlanmaz. LAN modu etkinleştirildiğinde 8080 portundaki API listener tüm arayüzlere bağlanır ve yönetim API'si güvenilir yerel ağdan erişilebilir olur. LAN HTTP şifreleme sağlamaz; yalnız güvenilir LAN/VPN kullanın. `REMOTE-API.md` içinde güvenlik sınırları ve endpoint ayrıntısı vardır.
- **Cloudflare Named Tunnel yönetimi:** Dashboard'dan API tokenı doğrulama, Account ID/zone eşleştirme, custom hostname atama/kaldırma ve Tunnel ingress/DNS CNAME kayıtlarını yönetme. API tokenı ve remote Tunnel run tokenı AES-GCM/Android Keystore ile şifrelenmiş no-backup dosyada saklanır; export/import yedeklerine dahil edilmez. `domainName` site/path kimliği olmaya devam eder; `customHostname` ayrı alan ve nullable benzersiz kayıttır.
- Cloudflare token kapsamları, DNS sahipliği/temizliği ve Named/Quick Tunnel çalışma sözleşmesi [NETWORK-RUNTIME.md](NETWORK-RUNTIME.md) dosyasındadır.
- `DeploymentEngine`: ContentResolver/Uri veya özel spool File üzerinden deployment; `DeploymentOrchestrator` dependency/build, süreç/PID/readiness, Nginx apply, CAS commit, log, stop/start ve cleanup retry yönetir.
- `TermuxRuntimeArchiveTransfer`: 64 MiB authenticated streaming upload, nonce/receipt ile belirsiz sonucun sahipliğe bağlı temizliği; mevcut arşiv korunur.
- `ShellDeploymentWorkspace` ve `pocketpaas_deploy.py`: bounded ZIP/central directory doğrulaması, özel staging, journal'lı activation/rollback ve eski backup temizliği. Django/Flask venv, Node index.js, React build ve STATIC desteklenir.
- ZIP biçimi, Termux paketleri, API, kapasite ve yaşam döngüsü sözleşmesi [DEPLOYMENT-RUNTIME.md](DEPLOYMENT-RUNTIME.md) dosyasındadır. pip/npm registry ve gerçek framework kurulumları cihaz üzerinde henüz test edilmedi.
- `RemoteServerService` ve `RemoteServerRuntimeManager`: API/foreground bildirimi, aktif ve bekleyen mutasyon iptali, cleanup owner koruması, Nginx/tünel/proje durdurma ve tekrar deneme. `AndroidApiKeyStore` özel/backup dışı atomik anahtar deposudur.
- `installRemoteApi` ve `RemoteMultipartUpload`: Bearer doğrulaması, bounded streaming ZIP/katı JSON, deploy/start/stop/list endpointleri, timeout/admission/bağlantı sınırları. API sözleşmesi [REMOTE-API.md](REMOTE-API.md) içindedir.
- `TermuxRuntimeRecovery`: authenticated process group reset, bilinmeyen PID reddi, commit journal uzlaştırması, eski URL/config/runtime kayıtlarını güvenli temizleme.
- Room şema dışa aktarımı, Android manifest, Compose kaynakları, doğrulanmış Gradle Wrapper, release imzalama yapılandırması ve başlangıç testleri.

`AppModule`, gerçek `Netty` fabrikasını ve API/runtime/recovery/SSH/servis/yedekleme arayüzlerini singleton sağlar. `RemoteServerService` bu listener ve yönetilen süreçlerin yaşam döngüsünü yönetir.

## Çalışma ağacındaki yayımlanmamış eklemeler

- Room v5 migration, her site için nullable `customHostname` ve unique index ekler; eski `domainName` değerleri ve site URL yolu değişmeden kalır. Uygulama ve SQLite doğrulamaları `app/schemas/.../5.json` ile senkron tutulur.
- Cloudflare API istekleri HTTPS sabit origin'e gider, redirect izlemez ve sınırlandırılmış cevap okur. Named Tunnel yönetimi doğrulanmış account/zone, 404 catch-all ingress ve yalnız app'in yönettiği proxied CNAME kayıtlarıyla idempotent reconciliation yapar. Yarım kalan API değişikliği sonraki denemede temizlenebilmesi için pending hostname intent'i şifreli ayarda korur.
- Dashboard Cloudflare token/account/zone formu ve site başına ayrı custom-hostname alanı sağlar. Bir hostname başka siteye atanamaz ve yapılandırılmış zone dışında reddedilir. Son hostname kaldırıldığında uygulama Quick Tunnel moduna döner.
- Yedekler site envanterindeki custom hostname bilgisini taşır; API tokenı, remote Tunnel run tokenı veya canlı Tunnel token dosyası hiçbir zaman yedeklenmez. Site kayıtları envanter bilgisidir; mevcut import akışı siteleri/route'ları yeniden oluşturmaz. Cloudflare bağlantısı cihazda yeniden doğrulanmalı ve hostnames elle atanmalıdır.

## 0.5.0 ile eklenen platform özellikleri

- **Room v4 veritabanı (`MIGRATION_3_4`):** `activity_events` (500 kayıt retansiyonu), `deployments` (200 kayıt retansiyonu), `services`, `ssh_settings` ve `ssh_authorized_keys` (parmak izi `UNIQUE` indeksli, en fazla 32 anahtar) tabloları eklendi. Şema dosyası `app/schemas/dev.pocketpaas.data.local.PocketPaaSDatabase/4.json` ve `verification/room-jvm/schemas/.../4.json` altında senkron tutulur.
- **SSH sunucusu (`TermuxSshManager`, `SshKeyFormat`, `SshdArguments`):** Varsayılan `8022` portu (`1024..65535`, `8080/8081/8767` rezerve), `ssh-ed25519` / `ssh-rsa` / `ecdsa-sha2-nistp*` public key wire doğrulaması ve `SHA256:` parmak izi üretimi, `~/.ssh/authorized_keys` atomik yazımı, `sshd -D` argv tabanlı argüman üretimi (shell enjeksiyonu yok), parola ile giriş ve LAN (`0.0.0.0`) erişim anahtarları, tek dokunuşla bağlantı komutu kopyalama.
- **Yönetilen veritabanı servisleri (`TermuxServiceManager`, `ServiceCatalog`, `ServicePolicy`):** PostgreSQL (`127.0.0.1:5432`), Redis (`127.0.0.1:6379`) ve MariaDB (`127.0.0.1:3306`) için Termux `pkg install -y`, idempotent `initdb` / `mariadb-install-db` hazırlığı, TCP readiness probe ve süreç bütçesine uygun eşzamanlı en fazla 2 veritabanı servisi sınırı.
- **Log arama, filtreleme ve dışa aktarma (`LogExportFormatter`, `ActivityFilter`):** Dashboard **Kayıtlar** sekmesinde metin arama, seviye (`INFO`/`WARNING`/`ERROR`) ve kategori (`SYSTEM`/`SITE`/`DEPLOYMENT`/`SSH`/`DATABASE`/`SECURITY`) filtre çipleri; RFC 4180 uyumlu ve tablo formül enjeksiyonuna (`=`, `+`, `-`, `@`) karşı korumalı CSV veya JSON dışa aktarma.
- **HMAC-SHA256 imzalı yedekleme ve geri yükleme (`BackupCodec`, `DefaultPlatformBackupManager`):** Room v4 veritabanındaki SSH ayarları, yetkili anahtarlar, servis durumları, siteler ve son kayıtları API anahtarıyla HMAC-SHA256 imzalanmış JSON zarfı olarak dışa aktarma ve sabit zamanlı (`MessageDigest.isEqual`) imza doğrulamasıyla geri yükleme. Güvenlik gereği geri yükleme sonrası SSH kendiliğinden başlatılmaz (`enabled = false`).
- **Genişletilmiş Remote API (`RemoteApiModule`):** Mevcut `/api/sites` ve `/api/deploy` uçlarına ek olarak Bearer korumalı yeni uçlar:
  - `GET /api/ssh`, `POST /api/ssh/start`, `POST /api/ssh/stop`, `POST /api/ssh/settings`, `POST /api/ssh/keys`, `DELETE /api/ssh/keys/{id}`
  - `GET /api/services`, `POST /api/services/{key}/install`, `POST /api/services/{key}/start`, `POST /api/services/{key}/stop`
  - `GET /api/activity?limit=100&level=INFO&category=SSH&q=...`, `GET /api/deployments?limit=50&q=...`, `GET /api/logs/export?format=json|csv`
  - `GET /api/backup/export`, `POST /api/backup/import`
- **Otomatik doğrulama ve CI (`.github/workflows/verify.yml`):**
  - `python3 verification/database/test_schema_v4.py` (gerçek SQLite üzerinde 1→2→3→4 migration zinciri ve indeks/retansiyon testleri)
  - `python3 verification/database/test_policies.py` (SSH/Servis politikaları ve argüman güvenliği)
  - `python3 verification/database/test_backup_and_logs.py` (HMAC-SHA256 yedek zarfı, CSV formül koruması ve Remote API sözleşmeleri)

## Sabitlenmiş sürümler

| Bileşen | Sürüm |
|---|---|
| Android Gradle Plugin / Gradle | 8.13.2 / 8.13 |
| Kotlin / Compose compiler plugin | 2.2.21 / 2.2.21 |
| KSP | 2.2.21-2.0.5 |
| Compose BOM | 2025.10.00 |
| Room | 2.8.3 |
| Dagger Hilt / AndroidX Hilt | 2.57.2 / 1.3.0 |
| Ktor Server Core, Netty, ContentNegotiation, Gson, Auth, StatusPages, CallLogging | 3.3.3 |
| Gson | 2.13.2 |
| Coroutines Core / Android / Test | 1.10.2 |
| WorkManager | 2.10.5 |
| Core KTX / Activity Compose / Lifecycle | 1.17.0 / 1.11.0 / 2.9.4 |
| Netty Java epoll/kqueue availability sınıfları | 4.2.7.Final |
| Desugaring / SLF4J Simple | 2.1.5 / 2.0.17 |
| JUnit / AndroidX Test Core ve Runner / AndroidX Test JUnit | 4.13.2 / 1.7.0 / 1.3.0 |

Sürümler güncel en yüksek sürüm iddiasıyla seçilmedi; API 36 ve AGP 8 için tutarlı, sabit bir taban seçildi. Compose alt bileşenlerinin sürümleri BOM tarafından tam olarak belirlenir. Dependency güncellemeleri sonraki APK'nın cihaz ve release testleriyle birlikte yapılmalıdır.

## APK derleme

Gereksinimler: JDK 17, Android SDK platform 36 ve Build Tools 35.0.0. Android Studio'da bu klasörü açın; SDK yolunu IDE'nin oluşturduğu, Git'e girmeyen `local.properties` ile ayarlayın. Gradle Wrapper ilk çalıştırmada resmi dağıtımı indirir ve SHA-256 doğrular.

Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux/macOS:

```bash
sh ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Çıktı: `app/build/outputs/apk/debug/app-debug.apk`. Debug application ID: `dev.pocketpaas.debug`; release application ID: `dev.pocketpaas`. Android 8.0/API 26 ve üstü desteklenir. `compileSdk` ve `targetSdk`: 36.

Bu Windows bilgisayarında resmi AAPT2'nin native ZIP yazma adımı çöküyordu. Kaynak kodu teslim edilen isteğe bağlı [windows-aapt2-wrapper](tools/windows-aapt2-wrapper/README.md), resmi AAPT2'nin `--output-to-dir` çıktısını ZIP'e yazarak bu engeli çözdü; APK Gradle'ın normal Kotlin/KSP/Hilt/D8, paketleme ve imzalama görevleriyle üretildi. Araç yalnız açık `android.aapt2FromMavenOverride` seçeneğiyle etkinleşir; normal Android Studio/Gradle derlemesi ve global SDK değişmez. Kullanım, sürüm ve kapsam ayrıntıları araç kılavuzundadır.

Cihaz/emülatör üzerindeki Room, anahtar deposu ve Compose UI testleri:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Room/KSP, repository ve gerçek SQLite kontrollerini Android paketleme aracı olmadan çalıştırmak için:

```powershell
.\gradlew.bat -p .\verification\room-jvm test
```

Bu bağımsız Gradle doğrulama projesi üretim source dosyalarını aynen derler; APK'nın bağımlılıklarını veya Android derlemesini değiştirmez. Desktop JDK süreç/ağ testleri de bu host modülünde yürütülür. `DatabaseMigrations.kt` Android adapter'ı JVM modülüne alınmaz; aynı `SiteSchema.statements` ve `ServerStateSchema.addPublicUrl` gerçek Room migration üzerinden doğrulanır. Android adapter'ı ayrıca `SiteMigrationTest` içinde test edilir; cihaz testleri henüz çalıştırılmadı. Python ve Nginx executable yolları ile ek doğrulama komutları güncel faz raporundadır.

Release için CI secret store üzerinden şu dört ortam değişkenini sağlayın: `POCKETPAAS_KEYSTORE_PATH`, `POCKETPAAS_KEYSTORE_PASSWORD`, `POCKETPAAS_KEY_ALIAS`, `POCKETPAAS_KEY_PASSWORD`. Ardından `:app:assembleRelease` çalıştırın. Anahtar, parola ve debug imza dosyaları kaynak arşivine dahil edilmez. Eksik imzalama bilgisiyle release derlemesi açık hata verir.

## Android çalışma ortamı kararları

Modern rootsuz Android'de ayrı APK, `/data/data/com.termux/files/usr/bin` içindeki binary'leri doğrudan çalıştıramaz. Uygulama sandbox'ı ayrı UID ve SELinux sınırları koyar. Termux entegrasyonu `RUN_COMMAND` IPC, kullanıcı tarafından verilmiş `com.termux.permission.RUN_COMMAND` izni ve Termux'un `allow-external-apps=true` ayarı gerektirir. Faz 3'te bu IPC ile localhost süreç agent'ı başlatılır; eksik izin veya erişim açık hata verir. Kurulum adımları [TERMUX-RUNTIME.md](TERMUX-RUNTIME.md) içindedir.

Termux'un kurulu olması ve komut izninin verilmesi, external-apps ayarının etkin olduğunu veya Nginx/Python/Node/cloudflared'ın kurulu olduğunu kanıtlamaz. Agent Python gerektirir; diğer binary'ler `resolveBinary` ile gerçek Termux ortamında bulunur ve eksikleri hata verir. Uygulamanın özel `htdocs` dizini de Termux UID'sinden erişilebilir değildir; çalışma dizini `ShellExecutor.runtimeLayout()` ile Termux'tan alınır. Faz 5 APK spool'unu authenticated localhost üzerinden bu backend'e aktarır ve ZIP'i Termux UID'sinde açar.

Android 10+ hedefli APK'nın yazılabilir app home içindeki indirilen executable'ları çalıştırması da kısıtlıdır. Bu proje seçilen rootsuz Termux backend'ini kullanır; Python agent, Nginx ve proje runtime'ları Termux içinde yürütülür. APK asset'i Python supervisor kaynağını taşır.

Nginx port 80 için ayrıcalık varsayılmadı. Varsayılan olarak Nginx **127.0.0.1:8081**, API **127.0.0.1:8080** kullanır. Cloudflare origin adresi Nginx'in 8081 portudur; metrics yalnız `127.0.0.1:8767` üzerinde tutulur. Application kendiliğinden dinleyici açmaz. Dashboard'daki **Ana sunucu** eylemi `RemoteServerController` üzerinden Foreground Service'i başlatır; cold recovery ve API ardından Nginx/tünel başlatılır. Tünel hatasında yerel API açık kalır ve ağ bağlantısı ayrıca yeniden denenebilir.

Özel hostname atanmadığında `trycloudflare.com` Quick Tunnel varsayılandır; Cloudflare bunu geliştirme/test için sunar ve uptime garantisi vermez. Cloudflare kartından zone bağlayıp custom hostname atarsanız çalışma ağacı app-managed Named Tunnel'a geçer. Quick Tunnel ayrıca 200 eşzamanlı istek sınırına sahiptir ve SSE desteklemez. [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

API varsayılanı `127.0.0.1`'dir; 256 bit Bearer doğrulaması her yönetim isteğinde zorunludur. LAN binding açık seçimle etkinleştirilir. API HTTP kullanır; TLS sonlandırması bu fazda eklenmedi. Güvenilen ağ/VPN veya USB adb forward erişimi ve tam sözleşme [REMOTE-API.md](REMOTE-API.md) içindedir. Android network security config yalnızca localhost çıkışları için cleartext'e izin verir; bu ayar gelen sunucu bağlantılarını yetkilendirmez.

Android `specialUse` foreground service tipi, izinleri, subtype açıklaması ve Durdur eylemli bildirim manifest/servis içinde eklendi. WorkManager sürekli server process'i tutmak için kullanılmaz. Servis boot/background autostart yapmaz; kullanıcı tarafından başlatılır. Dashboard bildirim izni akışını yönetir; Ana sunucu anahtarı veya bildirimdeki **Durdur** eylemi API, deployment ve yönetilen süreçlerin temizliğini başlatır.

## Resmi kaynaklar

- [AGP 8.13 uyumluluğu](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Room sürümleri](https://developer.android.com/jetpack/androidx/releases/room)
- [KSP 2.2.21-2.0.5](https://github.com/google/ksp/releases/tag/2.2.21-2.0.5)
- [Dagger/Hilt sürümleri](https://github.com/google/dagger/releases)
- [Ktor 3.3.3 bağımlılık sürümleri](https://github.com/ktorio/ktor/blob/3.3.3/gradle/libs.versions.toml)
- [WorkManager özel başlangıç yapılandırması](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)
- [Android uygulama sandbox'ı](https://source.android.com/docs/security/app-sandbox)
- [Android 10 execution kısıtları](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Termux RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Foreground service türleri](https://developer.android.com/develop/background-work/services/fgs/service-types)

Yedi fazın tamamlanması ve debug APK'nın üretilmesi bir üretim SLA'sı sağlamaz. Django/Flask geliştirme runner'ları production WSGI sunucusu değildir; Quick Tunnel kalıcı adres/uptime garantisi sunmaz. Yüklenen kod aynı Termux kullanıcısı altında çalışır; yalnız güvenilir projeler kurulmalıdır. Android/OEM arka plan kısıtları, gerçek cihaz/Termux entegrasyonu ve release imzası ayrıca doğrulanmalıdır. Kullanım sınırları [DASHBOARD.md](DASHBOARD.md), güncel doğrulama kapsamı [RUNTIME-FIX-VALIDATION.md](RUNTIME-FIX-VALIDATION.md) içindedir.
