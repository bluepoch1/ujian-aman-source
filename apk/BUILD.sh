#!/usr/bin/env bash
# ============================================================
#  Safe Browser 3.0 — build APK rilis, satu command
#
#      bash BUILD.sh
#
#  Tanpa pertanyaan. Tanpa isi apa pun. Semua otomatis:
#  swap, JDK, Android SDK, build, verifikasi keamanan.
#
#  Aman diulang. Yang sudah beres dilewati.
# ============================================================
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
export GRADLE_OPTS="-Dorg.gradle.daemon=false"
export TERM="${TERM:-dumb}"

C=$'\e[1;36m'; G=$'\e[1;32m'; R=$'\e[1;31m'; Y=$'\e[1;33m'; D=$'\e[2m'; N=$'\e[0m'
judul(){ printf '\n%s▸ %s%s\n' "$C" "$1" "$N"; }
ok(){    printf '%s  ✓%s %s\n' "$G" "$N" "$1"; }
info(){  printf '%s    %s%s\n' "$D" "$1" "$N"; }
warn(){  printf '%s  !%s %s\n' "$Y" "$N" "$1"; }
mati(){  printf '\n%s  ✗ %s%s\n\n' "$R" "$1" "$N"; exit 1; }

MULAI=$(date +%s)
printf '\n%s╔══════════════════════════════════════════════╗%s\n' "$C" "$N"
printf '%s║  Safe Browser 3.0 — build APK rilis          ║%s\n' "$C" "$N"
printf '%s╚══════════════════════════════════════════════╝%s\n' "$C" "$N"

# ---------------------------------------------------------------- 1. lokasi
[ -f gradlew ]            || mati "Jalankan dari dalam folder safe-browser."
[ -f release.keystore ]   || mati "release.keystore tidak ada di folder ini."
[ -f keystore.properties ]|| mati "keystore.properties tidak ada."
[ -f secrets.properties ] || mati "secrets.properties tidak ada."
ok "folder proyek + kredensial lengkap"

# ---------------------------------------------------------------- 2. sudo?
# Dipakai hanya untuk swap dan paket dasar. Kalau tidak ada, skrip
# tetap jalan dengan penyesuaian.
SUDO=""
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
  ADA_ROOT=1
elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
  SUDO="sudo -n"
  ADA_ROOT=1
else
  ADA_ROOT=0
fi

# ---------------------------------------------------------------- 3. paket
judul "Perkakas dasar"
KURANG=""
for P in curl unzip tar; do command -v $P >/dev/null 2>&1 || KURANG="$KURANG $P"; done
if [ -n "$KURANG" ]; then
  if [ "$ADA_ROOT" -eq 1 ]; then
    info "memasang:$KURANG"
    if command -v apt-get >/dev/null 2>&1; then
      $SUDO apt-get update -qq >/dev/null 2>&1
      $SUDO apt-get install -y -qq $KURANG >/dev/null 2>&1
    elif command -v dnf >/dev/null 2>&1; then $SUDO dnf install -y -q $KURANG >/dev/null 2>&1
    elif command -v yum >/dev/null 2>&1; then $SUDO yum install -y -q $KURANG >/dev/null 2>&1
    elif command -v apk >/dev/null 2>&1; then $SUDO apk add --quiet $KURANG >/dev/null 2>&1
    fi
  fi
  SISA=""
  for P in curl unzip tar; do command -v $P >/dev/null 2>&1 || SISA="$SISA $P"; done
  [ -n "$SISA" ] && mati "Perlu:$SISA — pasang dulu, mis. sudo apt install$SISA"
fi
ok "curl, unzip, tar siap"

# ---------------------------------------------------------------- 4. memori
judul "Memori"
RAM=$(free -m 2>/dev/null | awk '/^Mem:/{print $2}'); RAM=${RAM:-2048}
SWAP=$(free -m 2>/dev/null | awk '/^Swap:/{print $2}'); SWAP=${SWAP:-0}
TOTAL=$((RAM + SWAP))
info "RAM ${RAM} MB, swap ${SWAP} MB"

# R8 rakus. Di bawah ~3 GB total, OOM killer membunuh build di tengah.
if [ "$TOTAL" -lt 3000 ]; then
  if [ "$ADA_ROOT" -eq 1 ] && [ ! -f /swapfile ]; then
    info "menambah swap 4 GB otomatis..."
    if $SUDO fallocate -l 4G /swapfile 2>/dev/null || \
       $SUDO dd if=/dev/zero of=/swapfile bs=1M count=4096 status=none 2>/dev/null; then
      $SUDO chmod 600 /swapfile 2>/dev/null
      $SUDO mkswap /swapfile >/dev/null 2>&1
      if $SUDO swapon /swapfile 2>/dev/null; then
        grep -q '^/swapfile' /etc/fstab 2>/dev/null || \
          echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null 2>&1
        SWAP=$(free -m | awk '/^Swap:/{print $2}'); TOTAL=$((RAM + SWAP))
        ok "swap aktif — total ${TOTAL} MB"
      else
        warn "swapon ditolak (wajar di container/VPS ber-OpenVZ)"
      fi
    else
      warn "gagal membuat swapfile"
    fi
  elif [ -f /swapfile ] && [ "$SWAP" -eq 0 ] && [ "$ADA_ROOT" -eq 1 ]; then
    $SUDO swapon /swapfile 2>/dev/null && { SWAP=$(free -m|awk '/^Swap:/{print $2}'); TOTAL=$((RAM+SWAP)); ok "swap lama diaktifkan"; }
  fi
fi

if [ "$TOTAL" -lt 2600 ]; then
  warn "Total memori ${TOTAL} MB — di bawah kebutuhan R8 (~3 GB)."
  info "Build tetap dicoba dengan heap kecil, tapi bisa terbunuh OOM."
  [ "$ADA_ROOT" -eq 0 ] && info "Tanpa akses root skrip tidak bisa menambah swap sendiri."
fi

# Heap: sisakan ~1,2 GB untuk OS + Gradle daemon + kotlin worker.
HEAP=$((TOTAL - 1200))
[ "$HEAP" -lt 1024 ] && HEAP=1024
[ "$HEAP" -gt 4096 ] && HEAP=4096
ok "heap Gradle ${HEAP} MB"

# ---------------------------------------------------------------- 5. JDK 17
judul "JDK 17"
T="$HOME/tools"; mkdir -p "$T"
if [ -x "$T/jdk17/bin/javac" ]; then
  ok "sudah ada"
else
  A=$(uname -m); case "$A" in x86_64) JA=x64;; aarch64|arm64) JA=aarch64;; *) mati "arsitektur $A tidak didukung";; esac
  info "mengunduh Temurin 17 ($JA, ~180 MB)..."
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/${JA}/jdk/hotspot/normal/eclipse" \
    -o /tmp/jdk.tgz || mati "gagal mengunduh JDK — cek koneksi internet."
  rm -rf "$T/jdk17" && mkdir -p "$T/jdk17"
  tar xzf /tmp/jdk.tgz -C "$T/jdk17" --strip-components=1 || mati "arsip JDK rusak."
  rm -f /tmp/jdk.tgz
  [ -x "$T/jdk17/bin/javac" ] || mati "JDK tidak lengkap setelah ekstrak."
  ok "terpasang"
fi
export JAVA_HOME="$T/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"

# ---------------------------------------------------------------- 6. SDK
judul "Android SDK"
export ANDROID_HOME="$T/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -d "$ANDROID_HOME/platforms/android-34" ] && [ -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  ok "sudah ada"
else
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    info "mengunduh cmdline-tools..."
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
      -o /tmp/cmdline.zip || mati "gagal mengunduh cmdline-tools."
    unzip -qo /tmp/cmdline.zip -d /tmp/cmdtools || mati "arsip cmdline-tools rusak."
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv /tmp/cmdtools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf /tmp/cmdline.zip /tmp/cmdtools
  fi
  SM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  info "menyetujui lisensi + memasang platform 34 (~500 MB)..."
  yes 2>/dev/null | "$SM" --licenses >/dev/null 2>&1 || true
  yes 2>/dev/null | "$SM" "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1 || true
  [ -d "$ANDROID_HOME/build-tools/34.0.0" ] || mati "Android SDK gagal terpasang. Cek koneksi internet."
  ok "terpasang"
fi
echo "sdk.dir=$ANDROID_HOME" > local.properties

# ---------------------------------------------------------------- 7. build
judul "Membangun APK rilis"
info "R8 + obfuscation — 3 sampai 10 menit, harap tunggu"
chmod +x ./gradlew
./gradlew assembleRelease --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx${HEAP}m -XX:MaxMetaspaceSize=512m" \
  > build.log 2>&1
HASIL=$?

APK=app/build/outputs/apk/release/app-release.apk
if [ $HASIL -ne 0 ] || [ ! -f "$APK" ]; then
  printf '\n%s  ✗ Build gagal.%s\n\n' "$R" "$N"
  if grep -qiE "OutOfMemory|Killed|Java heap space|GC overhead|Could not reserve" build.log; then
    printf '%s  Sebabnya kehabisan memori.%s Total tersedia: %s MB.\n\n' "$Y" "$N" "$TOTAL"
    if [ "$ADA_ROOT" -eq 0 ]; then
      printf '  Skrip tidak punya akses root untuk menambah swap.\n'
      printf '  Jalankan ini lalu ulangi:\n\n'
      printf '    sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile\n'
      printf '    sudo mkswap /swapfile && sudo swapon /swapfile\n\n'
    else
      printf '  Swap sudah dicoba tapi tetap kurang. Perlu VPS dengan\n'
      printf '  RAM lebih besar, atau pakai GitHub Actions (bash RILIS.sh).\n\n'
    fi
  else
    printf '  Baris error terakhir:\n\n'
    grep -E "^e:|error:|FAILURE|Caused by|What went wrong" -A2 build.log | head -20 | sed 's/^/    /'
    printf '\n  Log lengkap: %s/build.log\n\n' "$PWD"
  fi
  exit 1
fi
ok "APK terbentuk"

# ---------------------------------------------------------------- 8. periksa
judul "Verifikasi keamanan"
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
BT="${BT%/}"
GAGAL=0
lulus(){ printf '%s  ✓%s %s\n' "$G" "$N" "$1"; }
tolak(){ printf '%s  ✗ %s%s\n' "$R" "$1" "$N"; GAGAL=1; }

# (a) debuggable -> siapa pun bisa adb masuk dan membuka URL ujian
if "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null | grep -q debuggable
  then tolak "APK masih debuggable"; else lulus "tidak debuggable"; fi

# (b) debug key sama di semua SDK -> orang lain bisa bikin update palsu
if "$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -q "CN=Android Debug"
  then tolak "ditandatangani debug key"; else lulus "ditandatangani kunci rilis"; fi

VN=$("$BT/aapt2" dump badging "$APK" 2>/dev/null | grep -o "versionName='[^']*'" | cut -d"'" -f2)
[ "$VN" = "3.0" ] && lulus "versi $VN" || tolak "versi $VN (seharusnya 3.0)"

DEXF=$(mktemp)
unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings > "$DEXF"
# CATATAN: JANGAN pakai  echo "$DEX" | grep -q ...  di sini.
# grep -q keluar begitu ketemu, penulis pipa kena SIGPIPE (141), dan
# 'set -o pipefail' membuat status pipa jadi 141 -> cabang && tidak
# jalan JUSTRU saat pola ditemukan. Hasil pemeriksaan jadi terbalik.
# Memakai file menghilangkan pipa sepenuhnya.
# (c) query lama membocorkan URL ujian hanya dengan tahu angka token
grep -q "rest/v1/tokens" "$DEXF" \
  && tolak "query tabel tokens lama masih ada" || lulus "tanpa query bocor lama"
# (d) service key melewati seluruh keamanan database
grep -qE "service_role|sb_secret_" "$DEXF" \
  && tolak "service key bocor di APK" || lulus "tanpa service key"
grep -q "rest/v1/rpc/" "$DEXF" \
  && lulus "jalur RPC ada" || tolak "jalur RPC tidak ditemukan"
rm -f "$DEXF"

if [ $GAGAL -ne 0 ]; then
  printf '\n%s  APK TIDAK AMAN dipakai ujian. Jangan disebar.%s\n\n' "$R" "$N"
  exit 1
fi

# ---------------------------------------------------------------- 9. selesai
OUT="SafeBrowser-${VN}-release.apk"
cp "$APK" "$OUT"
DETIK=$(( $(date +%s) - MULAI ))

printf '\n%s╔══════════════════════════════════════════════╗%s\n' "$G" "$N"
printf '%s║  SELESAI                                     ║%s\n' "$G" "$N"
printf '%s╚══════════════════════════════════════════════╝%s\n\n' "$G" "$N"
printf '  APK    : %s/%s\n' "$PWD" "$OUT"
printf '  Ukuran : %s\n' "$(du -h "$OUT" | cut -f1)"
printf '  Waktu  : %d menit %d detik\n\n' $((DETIK/60)) $((DETIK%60))
"$BT/apksigner" verify --print-certs "$OUT" 2>/dev/null \
  | grep -E "certificate DN|SHA-256 digest" | sed 's/^/  /'
printf '\n  Lolos 6 pemeriksaan keamanan. Siap dipasang.\n\n'
printf '%s  Simpan release.keystore offline. Hilang = tidak bisa%s\n' "$Y" "$N"
printf '%s  merilis update; siswa harus copot-pasang ulang.%s\n\n' "$Y" "$N"
