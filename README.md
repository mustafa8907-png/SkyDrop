# SkyDrop

SkyDrop plugin (Paper 1.20.4+).

## JAR nasıl alınır? (GitHub Actions)

Bu repoda `build.yml` workflow'u her `push`, `pull_request` ve manuel çalıştırmada (`workflow_dispatch`) Maven ile derleme yapar ve JAR artifact üretir.

1. GitHub'da **Actions** sekmesine gir.
2. **Build SkyDrop JAR** workflow'unu aç.
3. Son başarılı çalıştırmaya gir.
4. Sayfanın altındaki **Artifacts** bölümünden `SkyDrop-jar` dosyasını indir.
5. ZIP içindeki `.jar` dosyasını sunucunun `plugins/` klasörüne at.

Yerelde derlemek istersen:

```bash
mvn clean package
```

Çıktı dosyası `target/` klasöründe oluşur.
