# Safe Browser — Catatan Pembaruan 2.0

Ringkasan pekerjaan pada aplikasi ujian Android (`com.safebrowser.app`).

---

## Kondisi awal: project tidak bisa di-build

Sebelum apa pun bisa diperbaiki, ada lima kesalahan yang membuat project
**gagal dikompilasi sama sekali**:

| # | Masalah | Akibat |
|---|---------|--------|
| 1 | `app/build.gradle` punya `}` berlebih di baris terakhir | Gradle gagal parse |
| 2 | `SecurityChecker` **tidak memiliki 7 method** yang dipanggil dari activity — `isRunningOnEmulator`, `isUsbDebuggingEnabled`, `isMockLocationEnabled`, `isScreenRecording`, `getDangerousInstalledApps`, `isInLockTask`, `isLockTaskPinned` | Compile error |
| 3 | `settings.gradle` memakai `jcenter()` | Repositori mati sejak 2022 |
| 4 | Nama root project `"safe browser "` (spasi + spasi di akhir) | Path build bermasalah |
| 5 | Duplikasi dependensi & AGP 7.0.1 dengan Gradle 7.0.2 | Rantai toolchain usang |

Semua sudah diperbaiki. **Build sekarang berhasil** dan menghasilkan APK 8,6 MB.

---

## 1. Perbaikan bug fungsional

**`InputAddressActivity.startExam()` memanggil `super.finish()`**
Ini menutup activity yang salah dan meninggalkan gerbang di back stack, sehingga
peserta bisa menekan Kembali untuk **keluar dari ujian**. Sekarang `finish()`.

**Parsing timestamp menolak semua token yang valid**
Versi lama hanya menerima `yyyy-MM-dd'T'HH:mm:ss`. Supabase mengembalikan
timestamptz dengan mikrodetik dan offset zona waktu (`2026-09-12T10:30:00.123456+00:00`),
yang gagal diurai — dan blok `catch` mengembalikan `true` (**dianggap kedaluwarsa**).
Praktis setiap token ditolak. Sekarang tujuh pola dicoba, offset dinormalkan, dan
timestamp yang tidak terbaca tidak lagi otomatis memblokir peserta.

**Dialog menumpuk tanpa batas**
`runSecurityCheck()` dipanggil di setiap `onResume()` dan selalu membuat
`AlertDialog` baru. Berpindah ke Pengaturan lalu kembali beberapa kali akan
menumpuk puluhan jendela. Sekarang dialog aktif dilacak dan digunakan ulang.

**`ResponseBody` bocor di setiap jalur error**
Panggilan jaringan tidak pernah menutup body pada cabang error, yang perlahan
menghabiskan connection pool OkHttp. Sekarang memakai try-with-resources.

**Dua penutupan aplikasi yang bersaing**
`exitApp()` menjadwalkan callback suara *dan* timeout 5 detik, keduanya memanggil
`System.exit(0)`. Sekarang satu jalur dengan penjaga idempoten.

**Perulangan izin kamera tak berujung**
`QRScanActivity` memanggil `recreate()` setelah izin diberikan, yang menjalankan
ulang pemeriksaan izin. Sekarang callback surface langsung dipasang.

**Kebocoran Handler di splash screen**
Callback tertunda bisa menjalankan activity yang sudah mati. Sekarang dibersihkan
di `onDestroy()`.

**Layar error muncul saat satu gambar gagal dimuat**
`onReceivedError` tidak memeriksa `isForMainFrame()` pada salah satu overload,
jadi satu ikon rusak menutupi seluruh ujian.

**Perhitungan kecepatan jaringan salah**
Selisih byte dibagi dengan asumsi tepat 1 detik. Setiap kali handler tertunda,
angkanya melenceng. Sekarang dibagi waktu nyata yang berlalu.

**`Intent.setType()` menghapus URI**
Pemilih galeri memakai `new Intent(ACTION_PICK, uri)` lalu `setType()`, yang
membatalkan URI tersebut. Sekarang `setDataAndType()`.

---

## 2. Perbaikan keamanan

**Lalu lintas HTTP polos diizinkan ke seluruh internet**
`android:usesCleartextTraffic="true"` berarti token ujian dan jawaban peserta
bisa dibaca siapa pun di WiFi sekolah. Diganti dengan `network_security_config.xml`
yang **HTTPS-only**, dengan pengecualian eksplisit untuk alamat LAN — karena
banyak sekolah menjalankan server ujian lokal tanpa sertifikat.

**WebView bisa dinavigasi ke mana saja**
Tidak ada `shouldOverrideUrlLoading`. Satu tautan di halaman ujian bisa membawa
peserta ke Google — meniadakan seluruh tujuan browser terkunci. Sekarang navigasi
dibatasi pada host ujian dan subdomainnya; skema `intent:`, `tel:`, `file:` diblokir.

**URL dari basis data tidak divalidasi**
Baris yang disusupi bisa mengirim WebView ke `javascript:` atau `file:`, dan
`file:` memberi akses ke penyimpanan internal aplikasi. Sekarang hanya
`http`/`https` yang diterima.

**Kredensial Supabase tertanam dalam kode**
Dipindah ke `secrets.properties` (masuk `.gitignore`) dan diekspos lewat
`BuildConfig`. Memutar kunci tidak lagi berarti mengedit source.

**Injeksi filter PostgREST**
Token peserta digabung langsung ke query string. Sekarang memakai
`HttpUrl.Builder` yang meng-encode nilainya.

**Pengerasan WebView tambahan**
`setAllowFileAccess(false)`, `setAllowContentAccess(false)`, cookie pihak ketiga
dimatikan, `MIXED_CONTENT_NEVER_ALLOW`, permintaan kamera/mikrofon dari halaman
otomatis ditolak, geolokasi dimatikan.

**Deteksi aplikasi terlarang tidak berfungsi di Android 11+**
`getInstalledApplications()` mengembalikan daftar kosong tanpa deklarasi
visibilitas paket. Ditambahkan blok `<queries>` dengan 26 package eksplisit
(asisten AI, remote desktop, perekam layar) plus `QUERY_ALL_PACKAGES` sebagai
jaring pengaman.

**Deteksi emulator terlalu rapuh**
Satu kecocokan fingerprint sudah cukup untuk memblokir. Banyak ponsel murah asli
memakai build generik, jadi peserta sah ikut terkunci di luar ujiannya. Sekarang
memakai sistem skor dengan ambang batas.

**Kata kunci blacklist terlalu longgar**
Daftar lama berisi `"ai chat"`, `"ava ai"`, `"nova ai"` — dan pencocokan substring
pada label. Kata sependek itu menandai aplikasi tak bersalah. Daftar dipersempit
ke frasa spesifik.

**Izin berlebihan dihapus**
Dibuang: `WRITE_EXTERNAL_STORAGE`, `READ_PHONE_STATE`, `KILL_BACKGROUND_PROCESSES`,
`BLUETOOTH*`, `CHANGE_WIFI_STATE`, `DISABLE_KEYGUARD`, `BIND_DEVICE_ADMIN` (izin
tingkat aplikasi), `MODIFY_AUDIO_SETTINGS`. Dari 19 izin menjadi 7.

**Aturan pencadangan**
Ditambahkan `data_extraction_rules.xml` dan `backup_rules.xml`: state sesi,
cookie, dan cache tidak boleh berpindah perangkat.

---

## 3. Perubahan perilaku yang disengaja

**Hitung mundur kehilangan fokus, bukan penutupan mendadak**
Sebelumnya: dialog "Aplikasi akan segera tertutup!" lalu mati dalam 10–20 detik
tanpa informasi. Sekarang hitung mundur **15 detik yang terlihat** — kembali
tepat waktu, ujian berlanjut.

**Alasan sah kehilangan fokus diperiksa lebih dulu**
Keyboard muncul, layar mati, dan overlay penyematan sistem tidak lagi dituduh
sebagai kecurangan.

**Keluar selalu meminta konfirmasi**
Pola "ketuk dua kali" lama berarti dua sentuhan tak sengaja bisa mengakhiri
ujian. Pada dialog konfirmasi, **"Lanjutkan ujian" adalah tombol utama**;
"Ya, keluar" sekunder dan merah.

**Volume tidak lagi dipaksa maksimum untuk setiap bunyi**
Versi lama menaikkan volume musik, dering, DAN alarm ke maksimum — bahkan untuk
bip pemindai QR — dan tidak pernah mengembalikannya. Sekarang hanya alarm
pelanggaran, dan nilainya dipulihkan.

**Pendekodean QR dipindah ke thread latar**
ZXing berjalan pada setiap frame preview di thread utama, membekukan UI. Sekarang
di `HandlerThread`, dibatasi pada persegi tengah (lebih cepat, tidak salah baca
kode lain di dekatnya).

**Cache tidak lagi dinonaktifkan**
`LOAD_NO_CACHE` memaksa setiap aset diunduh ulang — boros kuota dan lambat di
WiFi sekolah yang padat.

**User agent jujur**
Berhenti menyamar sebagai Chrome 98 (yang memicu peringatan "browser usang").
Sekarang UA sistem + penanda `SafeBrowser/2.0`, sehingga server ujian bisa
memverifikasi peserta memakai browser terkunci.

**Splash 2,5 s → 1,2 s.** Terasa lama saat sekelas menunggu untuk mulai.

---

## 4. Desain ulang antarmuka

Buka **`PREVIEW.html`** untuk melihat keenam layar.

**Masalah pada desain lama:** biru Material `#1565C0` bawaan template, lingkaran
dekoratif di latar, sudut sangat bulat, header serba tengah, ikon campur antara
filled dan outlined, dan padding piksel mentah (`setPadding(0, 16, 0, 16)`) yang
menyusut jadi tak terlihat pada layar kepadatan tinggi.

**Sistem desain baru** (`colors.xml`, `dimens.xml`, `themes.xml`):

- **Palet**: netral hangat berbasis kertas + satu aksen biru tinta `#1F4B99`.
  Aksen dipakai **hanya** untuk tindakan utama, jadi tombol yang benar selalu
  jelas. Netral hangat lebih nyaman dipandang berjam-jam daripada abu-abu dingin.
- **Tipografi**: empat ukuran, dua bobot. Hierarki dibangun dari warna dan spasi,
  bukan belasan ukuran font.
- **Spasi**: skala 4pt yang dipatuhi di seluruh aplikasi.
- **Radius**: 6/10/16dp. Sudut sangat bulat membuat perkakas serius terasa mainan.
- **Ikon**: satu keluarga stroke 2dp berujung bulat, digambar ulang seluruhnya.
- **Target sentuh**: minimum 48dp di semua kontrol.
- **State**: setiap tombol punya state pressed dan disabled yang nyata.

**Perubahan per layar:**

- **Gerbang** — rata kiri satu kolom, bukan tengah. Kolom token memakai angka
  monospace besar berspasi lebar (token dibaca dari papan tulis dan diketik di
  bawah tekanan). Lencana status perangkat langsung terlihat. Galat tampil di
  bawah kolom, bukan sebagai Toast yang hilang.
- **Layar ujian** — kromium gelap dan sempit agar mundur secara visual; konten
  ujian mendapat hampir seluruh piksel. Waktu berjalan adalah elemen paling
  menonjol. Tombol keluar diberi jarak dan warna berbeda.
- **Konfirmasi ujian** — detail disusun sebagai daftar label/nilai agar peserta
  bisa memverifikasi kelasnya sebelum perangkat terkunci. Peringatan penguncian
  ditampilkan sebagai panel, bukan teks kecil.
- **Daftar aplikasi terlarang** — dipindah ke `item_blocked_app.xml` (sebelumnya
  dibangun di Java dengan piksel mentah), menampilkan nama package, dan daftarnya
  dibatasi tinggi agar tombol tidak terdorong keluar layar.
- **Pemindai QR** — jendela pindai jadi satu-satunya area terang.
- **Offline** — nada menenangkan ("Jawaban Anda tetap tersimpan"), bukan
  "OFFLINE" merah 40sp.

**Kualitas kode UI:** semua string masuk ke resource (siap dilokalkan), plurals
dipakai untuk teks berhitung, activity dipindah ke `AppCompatActivity`, widget
ke varian AppCompat agar tint bekerja konsisten, dan ikon peluncur adaptif
ditambahkan.

---

## 5. Modernisasi toolchain

| | Sebelum | Sesudah |
|---|---|---|
| AGP | 7.0.1 | 8.5.2 |
| Gradle | 7.0.2 | 8.7 |
| Java | 8 | 17 |
| compileSdk / targetSdk | 30 | 34 |
| minSdk | 21 | 24 |
| appcompat | 1.3.1 | 1.7.0 |
| OkHttp | 4.9.3 | 4.12.0 |
| ZXing | 3.4.1 | 3.5.3 |

Ditambahkan: `namespace`, view binding, aturan R8/ProGuard, `buildConfig`,
shrinking untuk release.

**Hasil lint: 0 error** (dari 15). Warning tersisa bersifat kosmetik atau
disengaja — misalnya `SetJavaScriptEnabled`, yang memang wajib untuk ujian web.

---

## Cara build

```bash
# 1. Isi kredensial
cp secrets.defaults.properties secrets.properties
#    lalu edit SUPABASE_URL dan SUPABASE_ANON_KEY

# 2. Arahkan ke Android SDK
echo "sdk.dir=/path/ke/Android/Sdk" > local.properties

# 3. Build (butuh JDK 17)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Untuk penguncian penuh tanpa dialog konfirmasi peserta, jadikan aplikasi sebagai
device owner pada perangkat yang baru di-factory-reset:

```bash
adb shell dpm set-device-owner com.safebrowser.app/.DeviceAdminReceiver
```

---

## Yang perlu Anda tindaklanjuti

1. **Row Level Security di Supabase.** Keamanan token yang sesungguhnya ada di
   sisi server, bukan di aplikasi. Pastikan tabel `tokens` hanya mengizinkan
   `SELECT` dan tidak pernah mengembalikan kolom kunci jawaban.
2. **Tambahkan domain server ujian Anda** ke `network_security_config.xml` bila
   memakai HTTP di jaringan lokal.
3. **`QUERY_ALL_PACKAGES`** perlu justifikasi di Play Console. Bila ditolak,
   hapus baris tersebut — blok `<queries>` tetap menangani 26 package yang
   sudah dikenal tanpa izin sensitif.
4. **Build release** belum saya verifikasi di sini karena R8 butuh RAM lebih
   besar dari yang tersedia di lingkungan build ini. Aturan ProGuard sudah
   ditulis; jalankan `./gradlew assembleRelease` di mesin Anda.
5. **Deteksi perekaman layar** pada dasarnya tidak mungkin di Android.
   `FLAG_SECURE` (sudah aktif) membuat hasil rekaman menjadi hitam — itulah
   pertahanan yang sebenarnya bekerja.
