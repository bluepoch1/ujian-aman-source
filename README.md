# Ujian Aman - Source Code

Sistem ujian online dengan pengawasan real-time. Terdiri dari 3 komponen:

## 📁 Struktur

```
ujian-aman-source/
├── functions/          # Firebase Cloud Functions (backend)
│   └── src/index.ts    # 23 functions (auth, ujian, sesi, pelanggaran, kelas)
├── firestore.rules     # Firestore Security Rules
├── firestore.indexes.json  # Composite indexes
├── dashboard/          # Web dashboard (Next.js + Tailwind)
│   └── src/            # React components, lib, pages
├── apk/                # Android APK source (Java + Gradle)
│   └── app/src/main/java/com/safebrowser/app/
└── firebase.json       # Firebase project config
```

## 🔧 Firebase Cloud Functions

| Function | Deskripsi |
|----------|-----------|
| `handleNewUser` | Auto-create pengguna on signup |
| `ambilProfil` | Get user profile |
| `buatUjian` | Create exam with token |
| `daftarUjianAktif` | List active exams |
| `hapusUjian` | Delete exam + sessions + violations |
| `tutupUjianPengawas` | Close exam, end active sessions |
| `klaimSesi` | Student join exam (with rate limit, device lock) |
| `akhiriSesi` | Student end exam |
| `heartbeat` | Keep session alive, check expiry |
| `catatPelanggaran` | Record violation (tab switch, face, etc) |
| `pantauKelas` | Real-time monitoring |
| `detailPeserta` | Student detail + violations |
| `tindakanPengawas` | Stop/add time/activate session |
| `ambilKelas` | List classes |
| `tambahKelas` | Add classes (batch) |
| `hapusKelas` | Delete class |
| `updateKelas` | Update class |
| `daftarPengguna` | List users (admin) |
| `tambahPengguna` | Add user (admin) |
| `hapusPengguna` | Delete user (admin) |
| `updatePengguna` | Update user (admin) |
| `jadikanPengawas` | Register supervisor |
| `cabutPengawas` | Revoke supervisor |

## 🛡️ Security

- Firestore Security Rules: auth check on every collection
- Rate limiting: 10 failed attempts per 5 minutes
- Device lock: one device per token (optional)
- Column protection: trigger prevents session tampering
- Admin-only functions: user/class management

## 🚀 Deploy

```bash
# Install Firebase CLI
npm install -g firebase-tools

# Login
firebase login

# Deploy everything
firebase deploy

# Or deploy individually
firebase deploy --only functions
firebase deploy --only firestore:rules
firebase deploy --only firestore:indexes
```

## 📱 Build APK

```bash
cd apk
./BUILD.sh
# Output: app/build/outputs/apk/release/
```

## 🌐 Dashboard

```bash
cd dashboard
npm install
npm run dev    # development
npm run build  # production
```

## 🔑 Environment Variables

### Dashboard (.env.local)
```
NEXT_PUBLIC_SUPABASE_URL=...  # Will be replaced with Firebase config
NEXT_PUBLIC_SUPABASE_ANON_KEY=...
```

### APK (secrets.defaults.properties)
```properties
SUPABASE_URL=...
SUPABASE_ANON_KEY=...
```

## ⚠️ Migration Status

This repo contains the Firebase backend that replaces the Supabase backend.
- [x] Cloud Functions (23 functions)
- [x] Firestore Security Rules
- [x] Firestore Indexes
- [ ] Dashboard Firebase client (in progress)
- [ ] APK Firebase client (in progress)
- [ ] Data migration from Supabase
