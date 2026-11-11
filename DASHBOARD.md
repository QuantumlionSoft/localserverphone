# Pocket PaaS — APK ve Dashboard kullanımı

Bu proje rootsuz Android + Termux düzenini kullanır. Android uygulaması Dashboard, Room ve uzak API'yi; Termux ise Nginx, cloudflared, Python ve Node süreçlerini çalıştırır. Nginx/Python/Node binary'leri APK içine dahil değildir. Minimum Android 8.0/API 26, hedef API 36'dır.

## İlk kurulum

[Yeni 0.4.0-debug APK'yı](../PocketPaaS-SurecDuzeltmesi-debug.apk) telefonunuza kurup açın. Sağ üstte **0.4.0-debug** yazmalıdır. Debug imzası geliştirme içindir. Bilgisayar veya ADB gerekmez. Dört sayfalı kurulumda üstteki adım adları ve alttaki **Geri / İleri** düğmeleriyle gezinebilirsiniz.

Panelin üst bölümünde de **Sürüm 0.4.0-debug** görünür; hata bildirirken bu bilgiyi ekleyin. Hata metnine uzun basarak seçebilir ve Android'in **Kopyala** eylemini kullanabilirsiniz. API'nin çalışması, Nginx veya internet tünelinin de çalıştığını göstermez; üç durum satırını birlikte kontrol edin.

1. **Termux:** **Termux indirme sayfasını aç**, önerilen [F-Droid sayfasını](https://f-droid.org/packages/com.termux/) açar; alternatif [resmî GitHub kurulumudur](https://github.com/termux/termux-app#installation). Termux'u kurup bir kez açın, komut satırı görünene kadar bekleyin. Pocket PaaS'a dönüp durumu yeniden kontrol edin.
2. **İzin:** Sayfanın altındaki **İzin iste** ve **Pocket PaaS ayarlarını aç** düğmeleri sürekli görünür. Android penceresi açılmazsa **Pocket PaaS** uygulama ayarlarında **İzinler → Ek izinler / Diğer izinler → Termux ortamında komut çalıştırma** yolunu kontrol edin. İzin Termux'un izinler sayfasına değil, Pocket PaaS'a verilir. Dönüşte **İzin durumunu yeniden kontrol et** seçin; **İzin verilmiş** yazıyorsa yeni pencere gerekmez.
3. **Araçlar:** **Kurulum komutunu kopyala**, ardından **Termux’u aç** seçin. Terminale uzun basın → **Paste / Yapıştır** → klavyeden **Enter**. Termux'u açmak komutu otomatik yapıştırmaz veya çalıştırmaz. Komut bütün kurulu Termux paketlerini günceller, gerekli araçları kurar ve Python/Nginx/Node/npm/cloudflared sürüm komutlarını gerçekten çalıştırır. `allow-external-apps=true` ayarı da hazırlanır. **Kurulum tamamlandı.** mesajından sonra Pocket PaaS'a dönün.
4. **Kontrol:** **Kontrol et ve başlat**, önce izni yeniden denetler; ardından gerçek bağlantıyı ve Python, Nginx, Node.js, npm, cloudflared araçlarını kontrol edip sunucuyu başlatır. Android 13+ bildirim izni ayrıca sorulabilir. Yerel API, Nginx ve internet tüneli ayrı durum satırlarıyla gösterilir. Panel düğmesiyle çalışma durumunu inceleyin.

**Android 12+ ve Play Store Termux:** Play Store dalının 18 Ekim 2026'da incelenen [manifesti](https://github.com/termux-play-store/termux-apps/blob/main/termux-app/src/main/AndroidManifest.xml) gerekli `RUN_COMMAND` izni ve `RunCommandService` arayüzünü sunmuyor. Uygulama bunu **Bu Termux sürümünde komut izni bulunmuyor** diye gösterir; ayarlarda olmayan izin açılamaz. Uyumlu F-Droid/GitHub kaynağına geçmeden önce Termux dosyalarını yedekleyin. Farklı kaynakların uygulama/eklenti imzalarını karıştırmayın; kaldırma verileri silebilir. Pocket PaaS otomatik kaldırma veya veri temizleme yapmaz. Ayrıntılı geçiş ve sorun giderme [KOLAY-KURULUM.md](KOLAY-KURULUM.md) içindedir.

Android izninin verilmesi, Termux bağlantı ayarının ve araçların hazır olduğunu kanıtlamaz. Komutun kopyalanması çalıştığını kanıtlamaz. Yerel API'nin çalışması da Nginx veya internet tünelinin hazır olduğunu göstermez. Komut ağ/paket hatasıyla kesilirse hatayı giderip aynı komutu yeniden çalıştırın. [Termux izin sözleşmesi](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent); elle kurulum alternatifi [TERMUX-RUNTIME.md](TERMUX-RUNTIME.md#cihaz-hazırlığı).

Kontrol sırasında ekranı kapatmak başlatma isteğini iptal eder; dönünce düğmeye yeniden dokunun. İzin ayarlarından dönüş yalnız durumu yeniler. Panelde **Kolay kurulum** bu dört sayfayı yeniden açar; **Kurulumu sonra tamamla · Paneli aç** hazırlığı tamamlanmış saymaz. Sonraki açılışlarda **Ana sunucu** anahtarını kullanın. Servis cold recovery, Ktor, Nginx ve tünel başlatmasını sırayla yürütür. Cloudflare erişimi başarısız olursa yerel API açık kalır; **Bağlantıyı yeniden kur** ile tekrar deneyin.

## Proje yayınlama

**Yeni proje** → proje türü → domain/site adı → **ZIP seç** → **Yayına al** sırasını kullanın. ZIP seçimi Android belge seçicisiyle yapılır; geniş depolama izni istenmez. Dosyayı seçmek deployment başlatmaz. [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).

Domain alanı routing/dizin kimliğidir; `my-app` veya `demo.example` gibi değerler kabul edilir. DNS kaydı açılmaz. Durdurulmuş veya PID'siz FAILED bir siteye aynı domain ve aynı türle ZIP seçmek mevcut siteyi yeniden deploy eder. Çalışan siteyi önce durdurun. Tür değişikliği aynı kayıt üzerinde yapılmaz.

| Tür | ZIP içeriği ve davranış |
|---|---|
| STATIC | Web kökünde `index.html`; Nginx statik dosya sunar |
| NODEJS | `package.json` ve `index.js`; npm install, ardından PORT/HOST ortamıyla Node |
| DJANGO | `manage.py` ve `requirements.txt`; venv/pip ve reloader kapalı geliştirme runner'ı |
| DJANGO / Flask fallback | `manage.py` yerine `app.py` varsa Flask runner; `requirements.txt` gerekir |
| REACT | `package.json`; npm install/build; `dist` veya `build` çıktısı Nginx ile sunulur |

Tam arşiv, dependency ve base-path sözleşmesi [DEPLOYMENT-RUNTIME.md](DEPLOYMENT-RUNTIME.md) içindedir. Proje ön yüzü `/sites/<domain>/` altına uygun asset/base URL üretmelidir. ZIP en fazla 64 MiB; gerçek açma sınırları ayrıca uygulanır. Sadece güvenilir projeler yükleyin: npm script'leri ve Python/Node kodu aynı Termux kullanıcısı altında çalışır.

Dosya okuma ve deployment, uygulama scope'unda runtime'ın kayıtlı mutasyon kapısı içinde yürütülür. Activity/ViewModel yeniden oluşturulduğunda veya ekran son uygulamalardan kaldırıldığında kabul edilmiş deployment sürer. Taslak ViewModel ömrünce korunur; process ölümünden sonra otomatik yükleme devam ettirilmez. ZIP'in erişim izni yeniden seçme gerektirebilir. ContentProvider iptal sinyalini veya stream kapatmayı yok sayarsa IO süre sınırı onu zorla öldüremez.

## Günlükler ve kontrol

Bir siteye veya **Canlı günlükler** düğmesine dokunun. Alttan açılan panel Room'a yazılan süreç/deployment loglarını reaktif gösterir; ekranda son 16.384 UTF-16 karakter bulunur. **Yeni satırları takip et** otomatik kaydırmayı açar/kapatır. **Günlükleri kopyala** yalnız açıkça dokunulduğunda panoya yazar. Başlat/Durdur düğmeleri aynı uygulama runtime koordinatörünü kullanır.

Kurulum sırasında **İşlemi iptal et**, kayıtlı deployment'ı iptal eder ve commit öncesi rollback'i bekler. Sunucu/tünel başlatması sırasında iptal, servis durdurma isteği gönderir. **Ana sunucu** anahtarını kapatmak API'yi, devam eden yerel/uzak deployment'ları, projeleri, Nginx'i ve tüneli durdurur. Bildirimde de **Durdur** eylemi vardır. Belirsiz cleanup durumunda **Temizliği yeniden dene** kullanılır; uygulama sahipliği belirsiz PID'leri tahminen öldürmez.

0.4.0, Termux çıktı akışındaki thread değişimine bağlı boru hatası ihtimalini kaldıran sınırlı tamponlar kullanır. Bir stdout/stderr okuma hatası oluştuğunda geçmiş hata korunur; **Temizliği yeniden dene**, durdurmanın gerçekten doğrulanmasına göre sonucu günceller. Sonlandırma doğrulanamadığı sürece `FAILED` gösterimi ve sahiplik kaydı korunur. Nginx'in 15 saniye veya tünelin 60 saniye içinde hazır olamaması da ayrı hata mesajı verir; kullanıcı iptaliyle karıştırılmaz. Bu düzeltmeler henüz gerçek telefonda doğrulanmadı.

`CANNOT LINK EXECUTABLE` veya `cannot locate symbol SSL_set_quic_tls_cbs` görürseniz Nginx, Termux kitaplıklarıyla yüklenemiyordur; Cloudflare başlamadan önce bu düzelmelidir. Güncel kurulum komutunu **3 · Araçlar** sayfasından yeniden kopyalayıp Termux'ta çalıştırın; tam paket yükseltmesinin ve sürüm kontrollerinin tamamlanmasını bekleyin. APK güncellemesi Termux'un Nginx/OpenSSL kitaplıklarını tek başına değiştirmez. Hata sürerse tam metni kopyalayın; bu hata için Termux'u yeniden kaldırmanız gerekmez.

Tünel URL kartına dokunmak adresi panoya kopyalar. Quick Tunnel modunda siteler `https://<tünel>/sites/<domainName>/` yolunda yayınlanır. Nginx cihaz içinde `127.0.0.1:8081`, yönetim API'si varsayılan `127.0.0.1:8080` kullanır. Cloudflare ayarları doğrulanıp bir custom hostname atandığında uygulama Named Tunnel'a geçer ve bu siteyi `https://<customHostname>/` kökünde yayınlar. Son custom hostname kaldırılınca Quick Tunnel'a döner.

## Özel alan adı ve Cloudflare

1. Dashboard'daki **Özel alan adları · Cloudflare** kartında Cloudflare Account ID, zone adı ve API tokenı girin. Token için hesap kapsamında **Cloudflare Tunnel Write**, zone kapsamında **Zone Read**, **DNS Edit** ve **DNS Read** izinleri gereklidir.
2. **Cloudflare'ı bağla** tokenı doğrular ve zone'un girilen hesaba ait olduğunu kontrol eder. API tokenı ve Cloudflare'dan alınan Remote Tunnel run tokenı Android Keystore AES-GCM ile şifrelenmiş uygulama özelindeki no-backup dosyada saklanır; ikisi de platform export/backup içine girmez. Aynı hesap/zone için token yenilerken token alanını boş bırakabilirsiniz. Hesap veya zone değiştirmek için önce tüm custom hostname'leri kaldırın, ardından **Bağlantıyı kes** ile uygulamanın eski Tunnel'ını silin.
3. Site kartındaki **Özel public hostname** alanına `www.example.com` gibi zone içi bir hostname yazıp **Hostname'i bağla** düğmesine dokunun. Proje adı `domainName` değişmez; `/sites/<domainName>/` site/path kimliğidir, DNS adı değildir. Aynı custom hostname iki siteye atanamaz.
4. Uygulama app-managed Named Tunnel ingress'ini ve Cloudflare proxied CNAME kaydını yönetir. Var olan A/AAAA/başka CNAME çakışması varsa kaydı ezmez; Cloudflare hata mesajını gösterir ve kullanıcıdan çakışmayı Cloudflare panelinde çözmesini ister. Hostname kaldırıldığında yalnız uygulamanın yönettiği ve hâlâ o Tunnel'ı hedefleyen CNAME temizlenir.
5. Named Tunnel'da yönetim API'si ve web dosya yöneticisi özel hostname üzerinden yayınlanmaz. Dosya yöneticisini LAN modunda güvenilir yerel ağdan kullanın veya özel hostname atamalarını kaldırarak Quick Tunnel moduna dönün. Remote Tunnel run tokenı cloudflared için Termux runtime'da 0600 izinli dosyaya açılır; Tunnel durdurulunca veya Quick mode başlatılırken silinir. `--token-file` için cloudflared 2025.4.0 veya yenisi gerekir.

Quick Tunnel geliştirme/test içindir ve kalıcı production adresi/uptime garantisi sağlamaz. [Cloudflare Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/).

## Uzak yönetim ve anahtar

**Yerel ağdan yönet** yalnız sunucu durmuşken seçilebilir. Açılırsa API `0.0.0.0:8080` üzerinden erişilir. API HTTP kullanır ve trafiği şifrelemez; yalnız güvenilir ağ/VPN kullanın. Güvenli varsayılan loopback'tir; bilgisayardan USB `adb forward tcp:8080 tcp:8080` ile erişilebilir.

Sunucu açıkken **API anahtarını kopyala** 256 bit Bearer anahtarını Android'in hassas clipboard işaretiyle panoya yazar. Anahtar UI state, SavedState, log, URL veya bildirim içine yerleştirilmez. Endpoint ve multipart örnekleri [REMOTE-API.md](REMOTE-API.md) içindedir.

## Çalışma sınırları

Foreground Service, Android/OEM'in process öldürmesini veya Doze/ağ kısıtlarını bütünüyle engellemez. Uzun süreli kullanımda cihazın Pocket PaaS ve Termux arka plan ayarlarını kontrol edin. Yeni oturum otomatik projeleri başlatmaz; journal/PID/URL durumunu güvenli uzlaştırır ve açık başlatma isteği bekler. Recovery belirsiz kaldığında dosyalar/PID kayıtları korunur.

0.4.0 sürümünün doğrulama kapsamı [RUNTIME-FIX-VALIDATION.md](RUNTIME-FIX-VALIDATION.md), önceki 0.2.0 raporu [EASY-SETUP-VALIDATION.md](EASY-SETUP-VALIDATION.md), Faz 7 tesliminin raporu [PHASE-7-VALIDATION.md](PHASE-7-VALIDATION.md) içindedir. Gerçek Android/Termux cihaz çalıştırması ve release doğrulaması henüz yapılmadı. Django/Flask geliştirme runner'ları ve Quick Tunnel ile bu sistemin üretim SLA'sı sağladığı iddia edilmez.
