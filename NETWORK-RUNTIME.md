# Nginx ve Cloudflare yönetim motoru

`NetworkManager`, Hilt singleton olarak süreç motoru, runtime dosya deposu, deployment workspace'i ve Room repository'lerine bağlıdır. Uzun ömürlü Nginx ve cloudflared süreçlerini sahip olduğu `ManagedProcess` nesneleriyle izler. Güncel çalışma ağacı Quick Tunnel'ı korur ve Cloudflare ayarları doğrulandığında custom hostname varsa PocketPaaS-managed Named Tunnel'a geçer. Aşağıdaki davranışlar kod sözleşmesidir; gerçek Android/Termux doğrulaması ayrıca gerekir.

## Termux hazırlığı

[TERMUX-RUNTIME.md](TERMUX-RUNTIME.md) içindeki `RUN_COMMAND` iznini ve `allow-external-apps=true` ayarını tamamlayın. Termux terminalinde:

```bash
pkg update -y
pkg upgrade -y
pkg install -y python nginx nodejs cloudflared
python --version
nginx -v
node --version
npm --version
cloudflared --version
```

Nginx ve cloudflared resmi [Termux paket ağacında](https://github.com/termux/termux-packages/tree/master/packages) bulunur. APK bu executable'ları kendi sandbox'ına kopyalamaz; çözümleme ve çalışma Termux UID'sinde yapılır. Kurulu sürümleri cihaz üzerinde kontrol edin. Python agent yalnız localhost üzerinde bearer token doğrulamasıyla çalışır.

Güncel kurulum sihirbazı bu tam paket yükseltmesini ve sürüm komutlarını tek kopyalanabilir komut içinde uygular. `nginx -v` bile `CANNOT LINK EXECUTABLE` / `cannot locate symbol SSL_set_quic_tls_cbs` ile biterse sorun Nginx'in Termux kitaplıklarıyla yüklenme aşamasındadır. APK içindeki HTTP portunu veya Cloudflare protokolünü değiştirmek bu native yükleme hatasını onarmaz. Paket yükseltmesinin ve sürüm komutlarının başarılı bitmesi gerekir; bu kontrol Cloudflare edge bağlantısını doğrulamaz. Kurulu Termux'u tekrar kaldırmak bu düzeltmenin bir adımı değildir.

Varsayılan çalışma ağacı agent tarafından `$HOME/.pocket-paas` altında oluşturulur:

```text
htdocs/<canonical-domain>/
nginx/nginx.conf
nginx/conf.d/<canonical-domain>.conf
nginx/conf.d/custom/<canonical-domain>.conf
runtime/nginx.pid
runtime/cloudflared-quick.yml
runtime/cloudflared-named.token
runtime/{client-body,proxy-temp,fastcgi-temp,uwsgi-temp,scgi-temp}/
logs/nginx-access.log
logs/nginx-error.log
```

Runtime dizini `ProcessExecutor.runtimeLayout()` ile alınır. APK içindeki `filesDir` yolu Termux proje root'u olarak kullanılamaz. Dosya işlemleri Python helper'ına argv ile gönderilir; shell interpolasyonu kullanılmaz. Helper config başına 32 KiB UTF-8 sınırı ve liste başına 128 dosya sınırı uygular. Bu kanal ZIP veya büyük proje dosyası transferi için tasarlanmamıştır.

## Motor API'si

| Çağrı | Davranış |
|---|---|
| `startNginx()` | Config'i test eder, sahip olunan Nginx'i başlatır ve HTTP readiness bekler. |
| `startCloudflareTunnel()` / `startAll()` | Önce Nginx'i başlatır; atanmış custom hostname varsa Cloudflare Named Tunnel, yoksa Quick Tunnel çalıştırır ve doğrulanmış public URL'yi kaydeder. |
| `stopCloudflareTunnel()` | Tüneli durdurur, main/site public URL'lerini temizler; Nginx kalır. |
| `stopNginx()` / `stopAll()` | Tüneli ve Nginx'i durdurur. |
| `generateVirtualHost(site, rootDir)` | Kayıtlı site için path ve varsa custom-host config adaylarını birlikte test eder, uygular ve çalışan Nginx'i reload eder. |
| `state` | `StateFlow<NetworkState>`; iki servisin durumu, PID'leri, URL ve son hata. |
| `logs` | İki servisin stdout/stderr olaylarını yayınlayan sınırlı `SharedFlow<ProcessOutput>`. |

`generateVirtualHost` hem domain `Site` hem Room `SiteEntity` kabul eder. Site önce repository'de bulunmalıdır. ID, canonical domain, custom hostname ve internal port config değişikliğinin öncesinde/sonrasında DB ile karşılaştırılır. `setCustomHostname(siteId, hostname)` ayrı `customHostname` alanını yönetir; `domainName` ve `/sites/<domainName>/` yolu değişmez. Ana URL `ServerStateRepository.publicUrl`, site URL'leri `SiteRepository.sites` üzerinden gözlenebilir. Room v5 `4→5` migration nullable custom hostname ve unique index ekler; mevcut site/kurulum verisi korunur.

Aşağıdaki suspend yardımcı, DB'de mevcut bir sitenin önceden hazırlanmış Termux dizinini sunar; ZIP dağıtımı gerçekleştirmez:

```kotlin
suspend fun exposePreparedSite(
    siteId: Long,
    siteRepository: SiteRepository,
    processes: ProcessExecutor,
    network: NetworkManager,
) {
    val site = requireNotNull(siteRepository.getSite(siteId)) {
        "The site must exist before it can be exposed."
    }
    val layout = processes.runtimeLayout()
    val rootDir = "${layout.projectsPath}/${site.domainName}"
    network.generateVirtualHost(site, rootDir)
    network.startAll()
}
```

STATIC root'u hazırlanmış HTML dizini, REACT root'u hazırlanmış build dizini olmalıdır. Django/Node backend'i site portunda ayrıca çalışıyor olmalıdır; Faz 4 uygulama backend'i başlatmaz ve site `RUNNING` durumunu kendi başına değiştirmez. Config başarılı olsa da backend yoksa proxy isteği 502 döner.

## Routing sözleşmesi

Nginx **127.0.0.1:8081**, dahili process agent **127.0.0.1:8766**, cloudflared metrics **127.0.0.1:8767** kullanır. Faz 6 yönetim API'si için **127.0.0.1:8080** ayrılmıştır. Rootsuz seçime uygun olarak ayrıcalıklı port 80 kullanılmaz.

Ana Nginx `server` bloğu, `conf.d/*.conf` path location'larını içerir. `domainName` proje/path kimliğidir; tek başına DNS kaydı değildir. Örnek:

```text
http://127.0.0.1:8081/sites/my-app/
https://<assigned-name>.trycloudflare.com/sites/my-app/
```

Her `customHostname` için ayrı `nginx/conf.d/custom/<domainName>.conf` server'ı üretilir. Bu exact-host server projeyi `/` kökünde sunar; statik/React root ya da Node/Django backend portuna yönlendirir. Örneğin `domainName = my-app`, `customHostname = www.example.com` ise public adres `https://www.example.com/` olurken `/sites/my-app/` iç route'u da korunur. Custom-host server yönetim API'si veya web dosya yöneticisi rotalarını içermez.

Son slash eksikse 308 yönlendirmesi query parametrelerini korur. STATIC dosyaları `alias` ile sunulur; dizin listesi kapalıdır, gizli dosyalar 403 döner ve POSIX Nginx'te symlink takibi kapatılır. REACT, bulunamayan client route'larını `index.html` üzerinden sunar; giriş dosyası yoksa sonsuz yönlendirme yerine 404 verir. React derleme base/public path ve router basename'i `/sites/example.com/` ile uyumlu olmalıdır.

DJANGO/NODEJS için `proxy_pass http://127.0.0.1:<internalPort>/` routing prefix'ini backend isteğinden çıkarır. Host, forwarded IP/protocol/host/prefix ve WebSocket upgrade header'ları taşınır; cookie root path'i site prefix'ine çevrilir. Backend'in ürettiği absolute URL, asset path ve redirect'ler uygulamanın proxy/prefix ayarlarına bağlıdır; bütün yanıt gövdeleri yeniden yazılmaz. Django'da `ALLOWED_HOSTS`, script prefix ve güvenilen proxy ayarları; Flask/Node'da karşılık gelen prefix/proxy ayarları proje sahibi tarafından yapılandırılmalıdır. Django production sunucusu ve Flask dağıtımı Faz 5 kararıdır.

## Config uygulama ve süreç sahipliği

Domain ve dosya yolları canonical/normalize edilmelidir. Config metni ekleyebilen karakterler, traversal, sibling proje root'u, platform port çakışması ve kayıtlı olmayan site reddedilir. Varsayılan en fazla 64 virtual host'tur.

Path-host, custom-host ve main config aday dosyalara yazılır. Gerçek `nginx -p <runtime-root>/ -c <candidate> -t` başarılı olmadan aktif dosyalar değiştirilmez. Uygulama atomik dosya değişimi ve `nginx -s reload` sonrası `/_pocketpaas/health` yanıtındaki yeni revision'ı bekler. Eski Nginx worker'ın 200 cevabı tek başına başarı sayılmaz. Hata durumunda önceki path/custom-host/main içerikleri geri yüklenir, tekrar test/reload edilir ve eski revision doğrulanır. Rollback başarısızsa servisler durdurulmaya çalışılır ve hata yayınlanır.

Nginx `daemon off;` ile sahip olunan process olarak çalışır. Başlatma/durdurma/config işlemleri `Mutex` ile sıraya alınır. Eski process watcher'ları yeni oturumun state'ini değiştiremez. Kalıcı PID'ler yeni uygulama oturumunda process sahipliği kanıtı sayılmaz; rastgele PID öldürme veya çalışan eski süreci otomatik devralma yapılmaz. Durdurma doğrulanamazsa `FAILED` ve sahiplik referansı korunur; işlem başarılı durdurulmuş gibi gösterilmez. Agent lease'i terk edilen süreçleri temizlemeyi sınırlar.

0.4.0'da Termux stdout/stderr adaptörü Java `PipedInputStream` kullanmaz. Her akışın 64 KiB sınırlı byte tamponu ve bağımsız IO üreticisi vardır; akışın yaşamı coroutine'in son çalıştığı worker thread'ine bağlı değildir. Normal EOF, hata, tüketici kapatması ve tampon doluluğu ayrı ele alınır. APK stdout/stderr gobbler'ları yine ayrı IO coroutine'lerinde çalışır.

`ManagedProcess.await()` bir okuma/supervision hatasıyla tamamlandıysa sonraki başarılı Stop o geçmiş sonucu değiştirmez. `stop()` ayrıca sahip olunan sürecin fiziksel sonlandırmasını doğrular; bu onay alınırsa ağ motoru PID/sahiplik referansını temizleyebilir. Onay alınamazsa sahiplik korunur ve açık Stop tekrarı yeniden dener. Yalnız başarısız `await()` sonucu veya Java adaptörünün kapanması, uzak sürecin öldüğüne kanıt sayılmaz. `RemoteServerRuntimeManager` bütün child cleanup işlemleri başarılı olmadan genel durumu `STOPPED` yapmaz.

Room'daki URL son bilinen metadata'dır; canlı tünel kanıtı değildir. Kullanıcının açık başlatma isteğinde `TermuxRuntimeRecovery`, önce authenticated process reset ve journal/URL/config uzlaştırmasını yapar. Kurtarma belirsizse başlangıç durur ve sahiplik tahmin edilmez. Önceki Termux süreçleri transport kaybından sonra lease süresi boyunca kalabilir; hemen yeniden başlatmada port çakışması bildirilebilir. Yeni `NetworkManager.state` yalnız bu oturumun sahip olduğu süreçleri temsil eder; kurtarma projeleri kendiliğinden başlatmaz.

Sekiz process slot'u kısa komutlar ve uzun ömürlü süreçler arasında ortaktır. En fazla yedi uzun süreç kabul edilir; bir kontrol slot'u config helper/test/reload komutlarına ayrılır. Nginx ve tünel iki uzun süreç kullanır; `DeploymentPolicy.maximumBackendProcesses` varsayılan en fazla beş backend kabul eder.

Nginx access/error logları yukarıdaki dosyalara gider; `NetworkManager.logs` process stdout/stderr akışını verir. UI dosya loglarını otomatik tail etmez. Canlı flow tamponu sınırlıdır; yavaş tüketicide eski olaylar düşebilir.

## Cloudflare Quick/Named Tunnel ve public URL

Cloudflare'ı Dashboard'daki **Özel alan adları** kartından bağlayın. API token'ı şu least-privilege izinleriyle oluşturun: hesabınızda **Cloudflare Tunnel Write** (alternatif olarak **Cloudflare One Connectors Write** veya **Cloudflare One Connector: cloudflared Write**), seçili zone'da **Zone: Read**, **DNS: Edit** ve **DNS: Read**. UI'ya Account ID ve zone adı girilir; token verify endpoint'i ve aynı hesaba ait aktif zone Cloudflare API üzerinden doğrulanır. `domainName` bu işlemde DNS hostname olarak kullanılmaz.

Cloudflare API tokenı, remote Tunnel run tokenı ve managed-hostname intent'i Android Keystore AES-GCM anahtarıyla şifrelenen `noBackupFilesDir/cloudflare-settings.bin` içinde tutulur. API client yalnız sabit HTTPS `api.cloudflare.com` origin'ine gider, redirect izlemez, response boyutunu sınırlar ve yönetim tokenını URL'ye koymaz. Yedek/export bu sırları içermez. `config_src=cloudflare` Named Tunnel için run tokenı Cloudflare token endpoint'inden alınır; Termux runtime'a mode-0600 `cloudflared-named.token` dosyasıyla geçilir ve `cloudflared tunnel --no-autoupdate --metrics 127.0.0.1:8767 run --token-file` kullanılır (cloudflared 2025.4.0+). Tunnel ingress Cloudflare'da tutulduğundan yerel credentials/config dosyası gerekmez. Tunnel normal biçimde durdurulunca ve Quick mode başlatılmadan önce token dosyası silinir. Android/Termux zorla kapatılırsa dosya sonraki ağ başlangıcına kadar kalabilir. Bu runtime dosyası Termux UID'si altında çalıştığından aynı UID'deki programlara karşı ayrı bir güvenlik sınırı değildir.

Cloudflare bağlantısı doğrulandıktan sonra site kartındaki **Özel public hostname** alanından zone içi bir hostname atayın (ör. `www.example.com`). Host canonical DNS formatında olmalı, yapılandırılmış zone içinde kalmalı ve başka siteye atanmış olmamalıdır. Uygulama app-managed Named Tunnel oluşturur/yeniden kullanır, remote ingress'e exact hostname → `http://127.0.0.1:8081` rotası ve son kural olarak `http_status:404` yazar; DNS'te proxied CNAME'i `<tunnel-id>.cfargotunnel.com` hedefine ayarlar.

Tunnel ingress ve DNS reconciliation idempotent'tir. Aynı hostname üzerinde A/AAAA/CNAME çatışması varsa mevcut kayıt sessizce ezilmez; hata gösterilir. Kaldırma yalnız PocketPaaS'in daha önce yönettiği hostname'leri ele alır ve CNAME hâlâ bu Tunnel'ın hedefini gösteriyorsa siler. API hatasında `managedHostnames` pending isteğin önceki+istenen birleşimi olarak şifreli depoda tutulur; sonraki deneme kayıp yanıt sonrası oluşan kayıtları uzlaştırabilir. Account/zone kapsamı değiştirilmeden önce özel hostname'ler kaldırılmalı ve mevcut uygulama Tunnel'ı disconnect onayıyla silinmelidir.

Site custom hostname'i varsa `startAll()` Named Tunnel başlatır; yoksa Quick Tunnel geriye dönük varsayılan olarak kalır. Named Tunnel cloudflared komutu:

```text
cloudflared tunnel --no-autoupdate --metrics 127.0.0.1:8767 run --token-file <runtime>/cloudflared-named.token
```

Cloudflare API'den alınan remote Tunnel tokenı `--token-file` ile cloudflared'a verilir; token komut satırına yazılmaz. Tunnel ingress Cloudflare'da yönetildiğinden yerel YAML config gerekmez ve Quick Tunnel `--url` kullanılmaz. Named readiness metrics `/ready` ve sahip olunan process ile doğrulanır. Public platform URL'si `https://<ilk-hostname>`, her site URL'si ise kendi `https://<customHostname>/` köküdür. Son hostname kaldırılırsa ingress 404'e alınır, yalnız app'in yönettiği uygun DNS kayıtları temizlenir ve çalışan tunnel Quick moda döner. `domainName`/`/sites/<domainName>/` route'u yerel ve Quick modda çalışmaya devam eder.

Quick Tunnel, kullanıcı global config'ini kullanmamak için boş, uygulamaya ait config ile başlatılır:

```text
cloudflared tunnel --config <runtime>/cloudflared-quick.yml --no-autoupdate --url http://127.0.0.1:8081 --metrics 127.0.0.1:8767
```

Stdout ve stderr ayrı IO okuyucularından canlı izlenir. Quick parser banner sonrasında parçalanmış logları da birleştirerek `https://[a-zA-Z0-9-]+\.trycloudflare\.com` adresini yakalar. Yanlış suffix ve tamamlanmamış URL reddedilir. Adresin logda görünmesi yeterli değildir: metrics `/ready` 200 dönmeli, `/quicktunnel` hostname'i logdaki hostname ile eşleşmeli ve process çalışıyor olmalıdır. Named modda URL parser kullanılmaz; sabit hostname için edge readiness kontrol edilir.

Başlangıç timeout'ları varsayılan Nginx 15 saniye, tünel 60 saniye, config testi 10 saniyedir. Ağ motorunun kendi readiness süresi aşılırsa `ProcessIOException`, `FAILED` ve anlamlı `lastError` üretilir. Bu hata sessiz `STOPPED` sayılmaz. Dış coroutine iptali ayrı kalır; iptal temizliği doğrulanırsa `STOPPED`, doğrulanamazsa `FAILED` ve sahiplik korunur. Canlı sağlık her iki saniyede kontrol edilir; edge bağlantısı kaybolursa public URL'ler temizlenir ve aynı process yeniden hazır olduğunda yayınlanır. Nginx kaybı tüneli de durdurur. Bu local readiness kontrolü internette uçtan uca istek doğrulaması değildir.

Named custom-host server yalnız ilgili siteyi yayınlar; yönetim API'si ve web dosya yöneticisi bu hostname'e eklenmez. Dosya yöneticisi Quick Tunnel'da `/manage/`, LAN modunda API listener üzerinden kullanılabilir. Quick Tunnel geçici geliştirme/test adresidir; uptime garantisi, 200 eşzamanlı istek üstü veya SSE desteği sağlamaz. [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)
## Resmi sözleşmeler

- [Nginx komut seçenekleri ve config testi](https://nginx.org/en/docs/switches.html)
- [Nginx reload ve graceful shutdown](https://nginx.org/en/docs/control.html)
- [Nginx alias](https://nginx.org/en/docs/http/ngx_http_core_module.html#alias)
- [Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)
- [cloudflared metrics rotaları](https://github.com/cloudflare/cloudflared/blob/master/metrics/metrics.go)
- [cloudflared readiness kontrolü](https://github.com/cloudflare/cloudflared/blob/master/metrics/readiness.go)
