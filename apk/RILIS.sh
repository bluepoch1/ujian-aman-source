#!/usr/bin/env bash
# ============================================================
#  Safe Browser 3.0 — Rilis sekali jalan
#
#  Semua nilai sudah terisi. Jalankan:
#      bash RILIS.sh
#
#  Skrip ini: buat repo privat -> push -> isi 4 secrets ->
#  jalankan build di GitHub -> tunggu -> unduh APK ke sini.
#
#  Aman diulang. Kalau berhenti di tengah, jalankan lagi;
#  bagian yang sudah beres dilewati.
# ============================================================
set -uo pipefail

REPO_NAME="safe-browser"
WORKFLOW="release.yml"

M=$'\e[1;35m'; C=$'\e[1;36m'; G=$'\e[1;32m'; R=$'\e[1;31m'; Y=$'\e[1;33m'; D=$'\e[2m'; N=$'\e[0m'
judul(){ printf '\n%s▸ %s%s\n' "$C" "$1" "$N"; }
ok(){    printf '%s  ✓%s %s\n' "$G" "$N" "$1"; }
info(){  printf '%s    %s%s\n' "$D" "$1" "$N"; }
warn(){  printf '%s  !%s %s\n' "$Y" "$N" "$1"; }
mati(){  printf '%s  ✗ %s%s\n\n' "$R" "$1" "$N"; exit 1; }

printf '\n%s╔════════════════════════════════════════════════╗%s\n' "$M" "$N"
printf '%s║   Safe Browser 3.0 — Rilis sekali jalan        ║%s\n' "$M" "$N"
printf '%s╚════════════════════════════════════════════════╝%s\n' "$M" "$N"

# ---------------------------------------------------------------- 0. lokasi
[ -f gradlew ] || mati "Jalankan dari dalam folder safe-browser."
[ -f .github/workflows/$WORKFLOW ] || mati "Workflow tidak ada."
[ -f release.keystore ] || mati "release.keystore tidak ada di folder ini."
[ -f keystore.properties ] || mati "keystore.properties tidak ada."
[ -f secrets.properties ] || mati "secrets.properties tidak ada."

# ---------------------------------------------------------------- 1. nilai
judul "Membaca kredensial dari folder ini"
SUPABASE_URL=$(grep -m1 '^SUPABASE_URL='      secrets.properties   | cut -d= -f2-)
SUPABASE_KEY=$(grep -m1 '^SUPABASE_ANON_KEY=' secrets.properties   | cut -d= -f2-)
KEYSTORE_PW=$( grep -m1 '^storePassword='     keystore.properties  | cut -d= -f2-)
[ -n "$SUPABASE_URL" ] && [ -n "$SUPABASE_KEY" ] && [ -n "$KEYSTORE_PW" ] \
  || mati "Ada nilai kosong di secrets.properties / keystore.properties."
KEYSTORE_B64=$(base64 -w0 release.keystore)
ok "4 nilai siap (keystore ${#KEYSTORE_B64} karakter base64)"

# ---------------------------------------------------------------- 2. gh CLI
judul "Menyiapkan GitHub CLI"
if command -v gh >/dev/null 2>&1; then
  GH=gh; ok "gh sudah terpasang ($(gh --version | head -1 | awk '{print $3}'))"
else
  GH="$HOME/tools/gh/bin/gh"
  if [ ! -x "$GH" ]; then
    info "mengunduh gh 2.63.2 (13 MB, sekali saja)..."
    mkdir -p "$HOME/tools/gh"
    A=$(uname -m); case "$A" in x86_64) A=amd64;; aarch64|arm64) A=arm64;; *) mati "arsitektur $A tidak didukung";; esac
    curl -fsSL "https://github.com/cli/cli/releases/download/v2.63.2/gh_2.63.2_linux_${A}.tar.gz" \
      -o /tmp/gh.tgz || mati "gagal mengunduh gh — cek koneksi internet."
    tar xzf /tmp/gh.tgz -C /tmp || mati "arsip gh rusak."
    cp -r /tmp/gh_2.63.2_linux_${A}/* "$HOME/tools/gh/"
    rm -rf /tmp/gh.tgz /tmp/gh_2.63.2_linux_${A}
  fi
  ok "gh siap"
fi

# ---------------------------------------------------------------- 3. login
judul "Login GitHub"
if $GH auth status >/dev/null 2>&1; then
  AKUN=$($GH api user --jq .login 2>/dev/null)
  ok "sudah login sebagai $AKUN"
else
  printf '\n%s  Browser akan terbuka untuk login GitHub.%s\n' "$Y" "$N"
  info "Kalau tidak ada browser, gh menampilkan kode + tautan"
  info "untuk dibuka di HP atau komputer lain."
  echo
  $GH auth login --hostname github.com --git-protocol https --web --scopes repo,workflow \
    || mati "Login dibatalkan."
  AKUN=$($GH api user --jq .login 2>/dev/null) || mati "Login gagal."
  ok "login sebagai $AKUN"
fi
$GH auth setup-git >/dev/null 2>&1

# ---------------------------------------------------------------- 4. repo
judul "Menyiapkan repo privat"
SLUG="$AKUN/$REPO_NAME"
if $GH repo view "$SLUG" >/dev/null 2>&1; then
  ok "repo $SLUG sudah ada"
else
  $GH repo create "$SLUG" --private --disable-issues --disable-wiki \
    -d "Safe Browser 3.0 — peramban ujian terkunci" >/dev/null \
    || mati "gagal membuat repo."
  ok "repo privat $SLUG dibuat"
fi

# ---------------------------------------------------------------- 5. commit
judul "Menyiapkan commit"
[ -d .git ] || { git init -q; ok "git init"; }
git config user.name  >/dev/null 2>&1 || git config user.name  "$AKUN"
git config user.email >/dev/null 2>&1 || git config user.email "$AKUN@users.noreply.github.com"

# Jaring pengaman: rahasia tidak boleh masuk repo.
for F in secrets.properties keystore.properties release.keystore local.properties; do
  grep -qxF "$F" .gitignore 2>/dev/null || echo "$F" >> .gitignore
done
git rm -r --cached secrets.properties keystore.properties release.keystore local.properties \
  >/dev/null 2>&1 || true

git add -A >/dev/null 2>&1
# CATATAN: --cached WAJIB sebelum pola. Kalau dibalik, git grep keluar
# dengan kode 128 dan hasil kosong -> pemindai diam-diam selalu "lolos".
# Cocokkan NILAI rahasia, bukan katanya. "service_role" muncul sah di
# SQL/komentar sebagai nama peran Postgres; yang berbahaya adalah kunci
# sb_secret_xxx, JWT service, dan password keystore.
BOCOR=""
for POLA in 'sb_secret_[A-Za-z0-9_-]{12}' 'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}'; do
  H=$(git grep -l --cached -E -e "$POLA" 2>/dev/null | grep -v '^\.github/' || true)
  [ -n "$H" ] && BOCOR="$BOCOR $H"
done
H=$(git grep -l --cached -F -e "$KEYSTORE_PW" 2>/dev/null | grep -v '^\.github/' || true)
[ -n "$H" ] && BOCOR="$BOCOR $H"
# Bukti pemindai benar-benar hidup: pola yang pasti ada harus ketemu.
git grep -l --cached -F -e 'com.safebrowser.app' >/dev/null 2>&1 \
  || mati "Pemindai rahasia tidak berfungsi — push dibatalkan demi keamanan."
[ -n "$BOCOR" ] && mati "Rahasia terdeteksi di:$BOCOR — push dibatalkan."
ok "tidak ada rahasia di commit (pemindai terverifikasi hidup)"

if git diff --cached --quiet 2>/dev/null && git rev-parse HEAD >/dev/null 2>&1; then
  ok "tidak ada perubahan baru"
else
  git commit -qm "Safe Browser 3.0" 2>/dev/null && ok "commit dibuat" || ok "commit sudah ada"
fi

judul "Mengirim ke GitHub"
git branch -M main
git remote get-url origin >/dev/null 2>&1 \
  && git remote set-url origin "https://github.com/$SLUG.git" \
  || git remote add origin "https://github.com/$SLUG.git"
git push -u origin main --force >/dev/null 2>&1 || mati "push gagal. Coba: git push -u origin main"
ok "kode terkirim"

# ---------------------------------------------------------------- 6. secrets
judul "Mengisi 4 Secrets"
isi(){ printf '%s' "$2" | $GH secret set "$1" --repo "$SLUG" >/dev/null 2>&1 \
       && ok "$1" || mati "gagal mengisi $1"; }
isi SUPABASE_URL      "$SUPABASE_URL"
isi SUPABASE_ANON_KEY "$SUPABASE_KEY"
isi KEYSTORE_PASSWORD "$KEYSTORE_PW"
isi KEYSTORE_BASE64   "$KEYSTORE_B64"

# ---------------------------------------------------------------- 7. build
judul "Menjalankan build di GitHub"
SEBELUM=$($GH run list --repo "$SLUG" --workflow "$WORKFLOW" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || echo "")
$GH workflow run "$WORKFLOW" --repo "$SLUG" >/dev/null 2>&1 \
  || mati "gagal memicu workflow. Buka https://github.com/$SLUG/actions dan klik Run workflow."
info "menunggu runner mengambil pekerjaan..."

RUN_ID=""
for _ in $(seq 1 30); do
  sleep 3
  KINI=$($GH run list --repo "$SLUG" --workflow "$WORKFLOW" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || echo "")
  if [ -n "$KINI" ] && [ "$KINI" != "$SEBELUM" ]; then RUN_ID="$KINI"; break; fi
done
[ -z "$RUN_ID" ] && mati "run tidak muncul. Cek https://github.com/$SLUG/actions"
ok "run #$RUN_ID dimulai"
info "https://github.com/$SLUG/actions/runs/$RUN_ID"

printf '\n%s  Membangun — sekitar 5 menit. Biarkan jendela ini terbuka.%s\n\n' "$D" "$N"
$GH run watch "$RUN_ID" --repo "$SLUG" --exit-status >/dev/null 2>&1
HASIL=$?

if [ $HASIL -ne 0 ]; then
  printf '\n%s  ✗ Build gagal.%s\n\n' "$R" "$N"
  printf '%s  Sebab kegagalan:%s\n' "$Y" "$N"
  $GH run view "$RUN_ID" --repo "$SLUG" --log-failed 2>/dev/null | grep -E '::error|error:' | head -15
  printf '\n  Log lengkap: https://github.com/%s/actions/runs/%s\n\n' "$SLUG" "$RUN_ID"
  exit 1
fi
ok "build berhasil"

# ---------------------------------------------------------------- 8. unduh
judul "Mengunduh APK"
rm -rf .unduh && mkdir -p .unduh
$GH run download "$RUN_ID" --repo "$SLUG" -D .unduh >/dev/null 2>&1 \
  || mati "gagal mengunduh artifact."
APK=$(find .unduh -name '*.apk' | head -1)
[ -z "$APK" ] && mati "artifact tidak berisi APK."
mv "$APK" ./SafeBrowser-3.0-release.apk
rm -rf .unduh
ok "SafeBrowser-3.0-release.apk ($(du -h SafeBrowser-3.0-release.apk | cut -f1))"

printf '\n%s╔════════════════════════════════════════════════╗%s\n' "$G" "$N"
printf '%s║   SELESAI                                      ║%s\n' "$G" "$N"
printf '%s╚════════════════════════════════════════════════╝%s\n\n' "$G" "$N"
printf '  APK  : %s/SafeBrowser-3.0-release.apk\n' "$PWD"
printf '  Repo : https://github.com/%s\n\n' "$SLUG"
printf '  Sudah lolos 6 pemeriksaan keamanan di GitHub:\n'
printf '  tidak debuggable, kunci rilis, versi 3.0,\n'
printf '  tanpa query bocor, tanpa service key, jalur RPC ada.\n\n'
printf '%s  Simpan release.keystore offline. Hilang = tidak bisa%s\n' "$Y" "$N"
printf '%s  merilis update; siswa harus copot-pasang ulang.%s\n\n' "$Y" "$N"
printf '  Build berikutnya cukup jalankan skrip ini lagi.\n\n'
