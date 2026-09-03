# Cara Build APK Rilis

APK debug yang ada **tidak boleh dipakai ujian** — `debuggable=true`
membuat siapa pun bisa masuk lewat `adb`, membaca memori, dan membuka
URL ujian. Seluruh penguncian jadi percuma.

Kodenya sendiri sudah bersih (terverifikasi). Yang kurang hanya build
rilis. Sandbox tempat saya bekerja hanya punya 2 GB RAM dan R8 selalu
kehabisan memori, jadi langkah terakhir ini perlu mesin lain.

Pilih salah satu.

---

## Opsi A — GitHub Actions (tanpa server, gratis)

Runner GitHub punya 16 GB RAM. Build otomatis dan langsung diverifikasi.

### 1. Push proyek ke repo privat

```bash
cd safe-browser
git init && git add -A
git commit -m "Safe Browser 3.0"
git remote add origin git@github.com:USERNAME/safe-browser.git
git push -u origin main
```

`.gitignore` sudah menahan `secrets.properties`, `release.keystore`,
dan `keystore.properties`. Ketiganya masuk lewat Secrets, bukan repo.

### 2. Isi Secrets

Settings → Secrets and variables → Actions → **New repository secret**

| Nama | Isi |
|---|---|
| `SUPABASE_URL` | `https://ydfaxiwxwuxtzepdhttt.supabase.co` |
| `SUPABASE_ANON_KEY` | anon key dari Settings → API |
| `KEYSTORE_BASE64` | seluruh isi `keystore-base64.txt` |
| `KEYSTORE_PASSWORD` | `storePassword` dari `keystore.properties` |

### 3. Jalankan

Tab **Actions** → *Build APK Rilis* → **Run workflow**.

Sekitar 5 menit. APK muncul sebagai artifact bernama
`SafeBrowser-release`. Workflow gagal keras kalau APK debuggable,
tertandatangani debug key, atau query bocor lama muncul lagi.

---

## Opsi B — VPS atau laptop Linux

Butuh RAM ≥ 4 GB. Kalau kurang, tambah swap dulu:

```bash
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
```

Lalu:

```bash
scp build-kit.tar.gz user@vps:~/
ssh user@vps
tar xzf build-kit.tar.gz && cd safe-browser
bash build-release.sh
```

Skrip memasang JDK 17 + Android SDK ke `$HOME` bila belum ada (tanpa
root), build, lalu menjalankan pemeriksaan keamanan yang sama.

Ambil hasilnya:

```bash
scp user@vps:~/safe-browser/app/build/outputs/apk/release/app-release.apk .
```

---

## Opsi C — Android Studio

Buka folder `safe-browser` → **Build → Generate Signed App Bundle / APK**
→ APK → pilih `release.keystore`, alias `safebrowser`, password dari
`keystore.properties` → varian **release**.

---

## Verifikasi manual

Apa pun caranya, pastikan hasilnya benar:

```bash
BT=$ANDROID_HOME/build-tools/34.0.0

# Harus KOSONG
$BT/aapt2 dump xmltree --file AndroidManifest.xml app-release.apk | grep debuggable

# Harus BUKAN "CN=Android Debug"
$BT/apksigner verify --print-certs app-release.apk | grep "certificate DN"

# Harus KOSONG — ini lubang yang ditutup di 3.0
unzip -p app-release.apk 'classes*.dex' | strings | grep "rest/v1/tokens"
```

---

## Jaga keystore

`release.keystore` menentukan identitas aplikasi Anda selamanya.

- **Hilang** → tidak bisa merilis update; pengguna harus copot-pasang ulang.
- **Bocor** → orang lain bisa membuat APK palsu yang dianggap update sah.

Simpan salinannya di tempat terpisah dan offline. Password ada di
`keystore.properties` — file itu tidak ikut ke Git.
