"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.cabutPengawas = exports.jadikanPengawas = exports.updatePengguna = exports.hapusPengguna = exports.tambahPengguna = exports.daftarPengguna = exports.updateKelas = exports.hapusKelas = exports.tambahKelas = exports.ambilKelas = exports.tindakanPengawas = exports.detailPeserta = exports.pantauKelas = exports.catatPelanggaran = exports.akhiriSesi = exports.heartbeat = exports.klaimSesi = exports.tutupUjianPengawas = exports.hapusUjian = exports.daftarUjianAktif = exports.buatUjian = exports.ambilProfil = exports.handleNewUser = void 0;
const admin = __importStar(require("firebase-admin"));
const functions = __importStar(require("firebase-functions"));
const https_1 = require("firebase-functions/v2/https");
admin.initializeApp();
const db = admin.firestore();
const auth = admin.auth();
// ═══════════════════════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════════════════════
function uid(ctx) {
    const uid = ctx.auth?.uid;
    if (!uid)
        throw new https_1.HttpsError("unauthenticated", "Sesi tidak terautentikasi.");
    return uid;
}
async function isAdmin(uid) {
    const snap = await db.collection("pengguna").doc(uid).get();
    return snap.exists && snap.data()?.role === "admin";
}
async function isPengawas(uid) {
    const snap = await db.collection("pengawas").doc(uid).get();
    return snap.exists;
}
function cleanToken(raw) {
    return (raw || "").replace(/[^0-9]/g, "");
}
function genToken6() {
    return String(100000 + Math.floor(Math.random() * 900000));
}
// ═══════════════════════════════════════════════════════
// AUTH: Auto-create pengguna on signup
// ═══════════════════════════════════════════════════════
exports.handleNewUser = functions.auth.user().onCreate(async (user) => {
    const { uid, email } = user;
    if (!email)
        return;
    await db.collection("pengguna").doc(uid).set({
        id: uid, email, nama_lengkap: email, role: "guru",
        mata_pelajaran: null, dibuat_pada: admin.firestore.FieldValue.serverTimestamp()
    });
});
// ═══════════════════════════════════════════════════════
// PROFIL
// ═══════════════════════════════════════════════════════
exports.ambilProfil = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    const snap = await db.collection("pengguna").doc(u).get();
    if (!snap.exists) {
        await db.collection("pengguna").doc(u).set({
            id: u, email: request.auth.token.email || "",
            nama_lengkap: "", role: "guru", mata_pelajaran: null,
            dibuat_pada: admin.firestore.FieldValue.serverTimestamp()
        });
        const fresh = await db.collection("pengguna").doc(u).get();
        return { ok: true, ...fresh.data() };
    }
    return { ok: true, ...snap.data() };
});
// ═══════════════════════════════════════════════════════
// BUAT UJIAN (Create Exam)
// ═══════════════════════════════════════════════════════
exports.buatUjian = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u) && !await isAdmin(u)) {
        throw new https_1.HttpsError("permission-denied", "BUKAN_PENGAWAS");
    }
    const { p_nama_kelas, p_url, p_durasi_menit, p_mata_pelajaran, p_berlaku_jam, p_max_peserta, p_token } = request.data;
    if (!p_url || !/^https?:\/\//.test(p_url)) {
        throw new https_1.HttpsError("invalid-argument", "URL_TIDAK_VALID");
    }
    if (!p_durasi_menit || p_durasi_menit < 1 || p_durasi_menit > 600) {
        throw new https_1.HttpsError("invalid-argument", "DURASI_TIDAK_VALID");
    }
    let token = cleanToken(p_token || "");
    if (!token)
        token = genToken6();
    // Check uniqueness
    const existing = await db.collection("tokens").where("token", "==", token).limit(1).get();
    if (!existing.empty) {
        throw new https_1.HttpsError("already-exists", "TOKEN_BENTROK");
    }
    const berlakuJam = Math.max(1, p_berlaku_jam || 24);
    const docRef = await db.collection("tokens").add({
        token, url: p_url, nama_kelas: p_nama_kelas,
        mata_pelajaran: p_mata_pelajaran || null,
        durasi_menit: p_durasi_menit, max_peserta: p_max_peserta || null,
        mulai_at: admin.firestore.FieldValue.serverTimestamp(),
        expired_at: admin.firestore.Timestamp.fromDate(new Date(Date.now() + berlakuJam * 3600 * 1000)),
        is_active: true, jumlah_klaim: 0, dibuat_oleh: u,
        dibuat_pada: admin.firestore.FieldValue.serverTimestamp()
    });
    return { ok: true, token, id: docRef.id };
});
// ═══════════════════════════════════════════════════════
// DAFTAR UJIAN AKTIF
// ═══════════════════════════════════════════════════════
exports.daftarUjianAktif = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u) && !await isAdmin(u)) {
        throw new https_1.HttpsError("permission-denied", "BUKAN_PENGAWAS");
    }
    const snap = await db.collection("tokens")
        .where("expired_at", ">", admin.firestore.Timestamp.fromDate(new Date(Date.now() - 86400000)))
        .orderBy("expired_at", "desc").get();
    const ujian = snap.docs.map(d => ({
        id: d.id, ...d.data(),
        mulai_at: d.data().mulai_at?.toDate?.()?.toISOString?.() || null,
        expired_at: d.data().expired_at?.toDate?.()?.toISOString?.() || null,
    }));
    return { ok: true, ujian, server_time: new Date().toISOString() };
});
// ═══════════════════════════════════════════════════════
// HAPUS UJIAN
// ═══════════════════════════════════════════════════════
exports.hapusUjian = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u))
        throw new https_1.HttpsError("permission-denied", "BUKAN_PENGAWAS");
    const { p_token } = request.data;
    const tokenSnap = await db.collection("tokens")
        .where("token", "==", cleanToken(p_token))
        .where("dibuat_oleh", "==", u).limit(1).get();
    if (tokenSnap.empty)
        throw new https_1.HttpsError("not-found", "TIDAK_DITEMUKAN");
    const tokenDoc = tokenSnap.docs[0];
    // Delete violations -> sessions -> token
    const sessSnap = await db.collection("sessions").where("token_id", "==", tokenDoc.id).get();
    const batch = db.batch();
    for (const sess of sessSnap.docs) {
        const vSnap = await db.collection("violations").where("session_id", "==", sess.id).get();
        for (const v of vSnap.docs)
            batch.delete(v.ref);
        batch.delete(sess.ref);
    }
    batch.delete(tokenDoc.ref);
    await batch.commit();
    return { ok: true };
});
// ═══════════════════════════════════════════════════════
// TUTUP UJIAN (Close exam by pengawas)
// ═══════════════════════════════════════════════════════
exports.tutupUjianPengawas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u) && !await isAdmin(u)) {
        throw new https_1.HttpsError("permission-denied", "BUKAN_PENGAWAS");
    }
    const { p_token } = request.data;
    const vBersih = cleanToken(p_token || "");
    const tokenSnap = await db.collection("tokens").where("token", "==", vBersih).limit(1).get();
    if (tokenSnap.empty)
        throw new https_1.HttpsError("not-found", "TIDAK_ADA");
    const tokenDoc = tokenSnap.docs[0];
    await tokenDoc.ref.update({ is_active: false });
    // Close active sessions
    const sessSnap = await db.collection("sessions")
        .where("token_id", "==", tokenDoc.id)
        .where("status", "==", "aktif").get();
    const batch = db.batch();
    for (const s of sessSnap.docs) {
        batch.update(s.ref, { status: "selesai", selesai_at: admin.firestore.FieldValue.serverTimestamp() });
    }
    await batch.commit();
    return { ok: true, token: vBersih, sesi_ditutup: sessSnap.size };
});
// ═══════════════════════════════════════════════════════
// KLAIM SESI (Student join exam)
// ═══════════════════════════════════════════════════════
exports.klaimSesi = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    const { p_token, p_nama, p_nomor_peserta, p_device_hash, p_device_model, p_app_version } = request.data;
    if (!p_nama || p_nama.trim().length < 2) {
        throw new https_1.HttpsError("invalid-argument", "NAMA");
    }
    if (!p_device_hash || p_device_hash.length < 16) {
        throw new https_1.HttpsError("invalid-argument", "DEVICE");
    }
    const vBersih = cleanToken(p_token || "");
    if (vBersih.length < 4)
        throw new https_1.HttpsError("invalid-argument", "TOKEN_TIDAK_VALID");
    // Rate limit check
    const rateSnap = await db.collection("percobaan_klaim")
        .where("uid", "==", u)
        .where("berhasil", "==", false)
        .where("waktu", ">", admin.firestore.Timestamp.fromDate(new Date(Date.now() - 300000)))
        .get();
    if (rateSnap.size >= 10) {
        throw new https_1.HttpsError("resource-exhausted", "TERLALU_SERING");
    }
    const tokenSnap = await db.collection("tokens")
        .where("token", "==", vBersih)
        .where("is_active", "==", true).limit(1).get();
    if (tokenSnap.empty) {
        await db.collection("percobaan_klaim").add({
            uid: u, token_coba: vBersih, berhasil: false,
            waktu: admin.firestore.FieldValue.serverTimestamp()
        });
        throw new https_1.HttpsError("not-found", "TOKEN_TIDAK_ADA");
    }
    const tokenDoc = tokenSnap.docs[0];
    const tokenData = tokenDoc.data();
    const now = Date.now();
    const mulaiMs = tokenData.mulai_at?.toMillis?.() || 0;
    const expiredMs = tokenData.expired_at?.toMillis?.() || Infinity;
    if (now < mulaiMs)
        throw new https_1.HttpsError("failed-precondition", "BELUM_MULAI");
    if (now > expiredMs)
        throw new https_1.HttpsError("failed-precondition", "EXPIRED");
    // Check existing session
    const existSess = await db.collection("sessions")
        .where("token_id", "==", tokenDoc.id)
        .where("uid", "==", u).limit(1).get();
    if (!existSess.empty) {
        const sess = existSess.docs[0];
        const sd = sess.data();
        if (sd.status === "dihentikan")
            throw new https_1.HttpsError("failed-precondition", "DIHENTIKAN");
        if (sd.status === "selesai")
            throw new https_1.HttpsError("failed-precondition", "SELESAI");
        // Re-enter: record violation
        await db.collection("violations").add({
            session_id: sess.id, jenis: "masuk_ulang",
            detail: "Aplikasi dibuka kembali di tengah ujian",
            waktu: admin.firestore.FieldValue.serverTimestamp()
        });
        await sess.ref.update({
            jumlah_pelanggaran: admin.firestore.FieldValue.increment(1),
            terakhir_aktif: admin.firestore.FieldValue.serverTimestamp()
        });
        const sisa = Math.max(0, Math.floor((sd.batas_waktu_at.toMillis() - now) / 1000));
        return {
            ok: true, masuk_ulang: true, session_id: sess.id,
            url: tokenData.url, nama_kelas: tokenData.nama_kelas,
            mata_pelajaran: tokenData.mata_pelajaran,
            nama_peserta: sd.nama_peserta, durasi_menit: tokenData.durasi_menit,
            batas_waktu_at: sd.batas_waktu_at?.toDate?.()?.toISOString?.(),
            server_time: new Date().toISOString(), sisa_detik: sisa
        };
    }
    // Device lock check
    if (tokenData.kunci_perangkat) {
        const devSnap = await db.collection("sessions")
            .where("token_id", "==", tokenDoc.id)
            .where("device_hash", "==", p_device_hash)
            .where("status", "!=", "dihentikan").limit(1).get();
        if (!devSnap.empty)
            throw new https_1.HttpsError("failed-precondition", "PERANGKAT_DIPAKAI");
    }
    // Quota check
    if (tokenData.max_peserta && tokenData.jumlah_klaim >= tokenData.max_peserta) {
        throw new https_1.HttpsError("resource-exhausted", "KUOTA_PENUH");
    }
    const batasMs = Math.min(now + tokenData.durasi_menit * 60000, expiredMs);
    const sessRef = await db.collection("sessions").add({
        token_id: tokenDoc.id, uid: u,
        nama_peserta: p_nama.trim(),
        nomor_peserta: (p_nomor_peserta || "").trim() || null,
        device_hash: p_device_hash,
        device_model: (p_device_model || "").substring(0, 100),
        app_version: (p_app_version || "").substring(0, 20),
        status: "aktif", jumlah_pelanggaran: 0, tambahan_menit: 0,
        mulai_at: admin.firestore.FieldValue.serverTimestamp(),
        batas_waktu_at: admin.firestore.Timestamp.fromDate(new Date(batasMs)),
        selesai_at: null, terakhir_aktif: admin.firestore.FieldValue.serverTimestamp(),
        catatan_pengawas: null, keluar_sementara: false
    });
    await tokenDoc.ref.update({ jumlah_klaim: admin.firestore.FieldValue.increment(1) });
    await db.collection("percobaan_klaim").add({
        uid: u, token_coba: vBersih, berhasil: true,
        waktu: admin.firestore.FieldValue.serverTimestamp()
    });
    const sisa = Math.floor((batasMs - now) / 1000);
    return {
        ok: true, masuk_ulang: false, session_id: sessRef.id,
        url: tokenData.url, nama_kelas: tokenData.nama_kelas,
        mata_pelajaran: tokenData.mata_pelajaran,
        nama_peserta: p_nama.trim(), durasi_menit: tokenData.durasi_menit,
        batas_waktu_at: new Date(batasMs).toISOString(),
        server_time: new Date().toISOString(), sisa_detik: sisa
    };
});
// ═══════════════════════════════════════════════════════
// HEARTBEAT
// ═══════════════════════════════════════════════════════
exports.heartbeat = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    const { p_session_id } = request.data;
    const sessSnap = await db.collection("sessions").doc(p_session_id).get();
    if (!sessSnap.exists || sessSnap.data().uid !== u) {
        throw new https_1.HttpsError("not-found", "SESI_TIDAK_ADA");
    }
    const sd = sessSnap.data();
    // Device check
    const deviceHash = request.rawRequest?.headers?.["x-device-hash"];
    if (sd.device_hash && deviceHash && sd.device_hash !== deviceHash) {
        throw new https_1.HttpsError("failed-precondition", "DEVICE_MISMATCH");
    }
    if (sd.status === "dihentikan")
        return { ok: false, kode: "DIHENTIKAN" };
    if (sd.status === "selesai")
        return { ok: false, kode: "SELESAI" };
    const now = Date.now();
    const batasMs = sd.batas_waktu_at?.toMillis?.() || 0;
    const sisa = Math.max(0, Math.floor((batasMs - now) / 1000));
    if (sisa === 0) {
        await sessSnap.ref.update({ status: "selesai", selesai_at: admin.firestore.FieldValue.serverTimestamp() });
        return { ok: true, sisa_detik: 0, status: "selesai", server_time: new Date().toISOString() };
    }
    await sessSnap.ref.update({ terakhir_aktif: admin.firestore.FieldValue.serverTimestamp() });
    return { ok: true, sisa_detik: sisa, status: sd.status, server_time: new Date().toISOString() };
});
// ═══════════════════════════════════════════════════════
// AKHIRI SESI
// ═══════════════════════════════════════════════════════
exports.akhiriSesi = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    const { p_session_id } = request.data;
    const sessSnap = await db.collection("sessions").doc(p_session_id).get();
    if (!sessSnap.exists || sessSnap.data().uid !== u || sessSnap.data().status !== "aktif") {
        throw new https_1.HttpsError("not-found", "SESI_TIDAK_ADA");
    }
    await sessSnap.ref.update({
        status: "selesai", selesai_at: admin.firestore.FieldValue.serverTimestamp(),
        terakhir_aktif: admin.firestore.FieldValue.serverTimestamp()
    });
    return { ok: true };
});
// ═══════════════════════════════════════════════════════
// CATAT PELANGGARAN
// ═══════════════════════════════════════════════════════
exports.catatPelanggaran = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    const { p_session_id, p_jenis, p_detail, p_durasi_detik, p_waktu_perangkat } = request.data;
    const sessSnap = await db.collection("sessions").doc(p_session_id).get();
    if (!sessSnap.exists || sessSnap.data().uid !== u) {
        throw new https_1.HttpsError("not-found", "SESI_TIDAK_ADA");
    }
    const sd = sessSnap.data();
    if (sd.status !== "aktif")
        throw new https_1.HttpsError("failed-precondition", "SESI_TIDAK_AKTIF");
    const validJenis = ["tab_switch", "focus_loss", "wajah_tidak_terdeteksi", "masuk_ulang", "lainnya"];
    const jenis = validJenis.includes(p_jenis) ? p_jenis : "lainnya";
    await db.collection("violations").add({
        session_id: p_session_id, jenis,
        detail: (p_detail || "").substring(0, 500),
        durasi_detik: p_durasi_detik || null,
        waktu_perangkat: p_waktu_perangkat || null,
        waktu: admin.firestore.FieldValue.serverTimestamp()
    });
    await sessSnap.ref.update({
        jumlah_pelanggaran: admin.firestore.FieldValue.increment(1),
        terakhir_aktif: admin.firestore.FieldValue.serverTimestamp()
    });
    return { ok: true, total: sd.jumlah_pelanggaran + 1 };
});
// ═══════════════════════════════════════════════════════
// PANTAU KELAS (Monitoring)
// ═══════════════════════════════════════════════════════
exports.pantauKelas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u) && !await isAdmin(u)) {
        throw new https_1.HttpsError("permission-denied", "BUKAN_PENGAWAS");
    }
    const { p_token } = request.data;
    const tokenSnap = await db.collection("tokens")
        .where("token", "==", cleanToken(p_token))
        .where("is_active", "==", true).limit(1).get();
    if (tokenSnap.empty)
        throw new https_1.HttpsError("not-found", "TOKEN_TIDAK_ADA");
    const td = tokenSnap.docs[0].data();
    const sessSnap = await db.collection("sessions")
        .where("token_id", "==", tokenSnap.docs[0].id).get();
    const now = Date.now();
    const peserta = sessSnap.docs.map(s => {
        const d = s.data();
        const batasMs = d.batas_waktu_at?.toMillis?.() || 0;
        const aktifMs = d.terakhir_aktif?.toMillis?.() || 0;
        return {
            session_id: s.id, nama_peserta: d.nama_peserta, nomor_peserta: d.nomor_peserta,
            device_model: d.device_model, device_hash: d.device_hash, app_version: d.app_version,
            status: d.status, mulai_at: d.mulai_at?.toDate?.()?.toISOString?.(),
            selesai_at: d.selesai_at?.toDate?.()?.toISOString?.(),
            batas_waktu_at: d.batas_waktu_at?.toDate?.()?.toISOString?.(),
            sisa_detik: Math.max(0, Math.floor((batasMs - now) / 1000)),
            terakhir_aktif: d.terakhir_aktif?.toDate?.()?.toISOString?.(),
            online: (now - aktifMs) < 45000,
            jumlah_pelanggaran: d.jumlah_pelanggaran
        };
    });
    return {
        ok: true, kelas: td.nama_kelas, mata_pelajaran: td.mata_pelajaran,
        durasi_menit: td.durasi_menit,
        expired_at: td.expired_at?.toDate?.()?.toISOString?.(),
        jumlah_klaim: td.jumlah_klaim, max_peserta: td.max_peserta,
        server_time: new Date().toISOString(), peserta
    };
});
// ═══════════════════════════════════════════════════════
// DETAIL PESERTA
// ═══════════════════════════════════════════════════════
exports.detailPeserta = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u) && !await isAdmin(u)) {
        throw new https_1.HttpsError("permission-denied", "BUKAN_PENGAWAS");
    }
    const { p_session_id } = request.data;
    const sessSnap = await db.collection("sessions").doc(p_session_id).get();
    if (!sessSnap.exists)
        throw new https_1.HttpsError("not-found", "SESI_TIDAK_ADA");
    const sd = sessSnap.data();
    const now = Date.now();
    const vSnap = await db.collection("violations")
        .where("session_id", "==", p_session_id).get();
    const pelanggaran = vSnap.docs.map(v => {
        const vd = v.data();
        return {
            jenis: vd.jenis, detail: vd.detail, durasi_detik: vd.durasi_detik,
            waktu: vd.waktu?.toDate?.()?.toISOString?.(),
            waktu_perangkat: vd.waktu_perangkat,
            selisih_jam_detik: vd.waktu_perangkat
                ? Math.abs(Math.floor(((vd.waktu?.toMillis?.() || 0) - (vd.waktu_perangkat || 0)) / 1000))
                : null
        };
    });
    return {
        ok: true, nama_peserta: sd.nama_peserta, nomor_peserta: sd.nomor_peserta,
        device_model: sd.device_model, app_version: sd.app_version,
        status: sd.status, mulai_at: sd.mulai_at?.toDate?.()?.toISOString?.(),
        batas_waktu_at: sd.batas_waktu_at?.toDate?.()?.toISOString?.(),
        selesai_at: sd.selesai_at?.toDate?.()?.toISOString?.(),
        terakhir_aktif: sd.terakhir_aktif?.toDate?.()?.toISOString?.(),
        tambahan_menit: sd.tambahan_menit, catatan_pengawas: sd.catatan_pengawas,
        jumlah_pelanggaran: sd.jumlah_pelanggaran,
        sisa_detik: Math.max(0, Math.floor(((sd.batas_waktu_at?.toMillis?.() || 0) - now) / 1000)),
        server_time: new Date().toISOString(), pelanggaran
    };
});
// ═══════════════════════════════════════════════════════
// TINDAKAN PENGAWAS (hentikan/tambah_waktu/aktifkan)
// ═══════════════════════════════════════════════════════
exports.tindakanPengawas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u) && !await isAdmin(u)) {
        throw new https_1.HttpsError("permission-denied", "BUKAN_PENGAWAS");
    }
    const { p_session_id, p_aksi, p_nilai, p_catatan } = request.data;
    const sessSnap = await db.collection("sessions").doc(p_session_id).get();
    if (!sessSnap.exists)
        throw new https_1.HttpsError("not-found", "SESI_TIDAK_ADA");
    if (p_aksi === "hentikan") {
        await sessSnap.ref.update({
            status: "dihentikan", selesai_at: admin.firestore.FieldValue.serverTimestamp(),
            catatan_pengawas: p_catatan || null
        });
    }
    else if (p_aksi === "tambah_waktu") {
        if (!p_nilai || p_nilai <= 0 || p_nilai > 180) {
            throw new https_1.HttpsError("invalid-argument", "NILAI_TIDAK_VALID");
        }
        const sd = sessSnap.data();
        const oldMs = sd.batas_waktu_at?.toMillis?.() || Date.now();
        await sessSnap.ref.update({
            batas_waktu_at: admin.firestore.Timestamp.fromDate(new Date(oldMs + p_nilai * 60000)),
            tambahan_menit: (sd.tambahan_menit || 0) + p_nilai,
            catatan_pengawas: p_catatan || sd.catatan_pengawas
        });
    }
    else if (p_aksi === "aktifkan") {
        await sessSnap.ref.update({
            status: "aktif", selesai_at: null, catatan_pengawas: p_catatan || null
        });
    }
    else {
        throw new https_1.HttpsError("invalid-argument", "AKSI_TIDAK_DIKENAL");
    }
    return { ok: true };
});
// ═══════════════════════════════════════════════════════
// KELAS
// ═══════════════════════════════════════════════════════
exports.ambilKelas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isPengawas(u) && !await isAdmin(u)) {
        throw new https_1.HttpsError("permission-denied", "TIDAK_AUTORIZASI");
    }
    const snap = await db.collection("kelas").orderBy("nama_rumpun").orderBy("nama_kelas").get();
    const data = snap.docs.map(d => ({ id: d.id, ...d.data() }));
    return { ok: true, data };
});
exports.tambahKelas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u))
        throw new https_1.HttpsError("permission-denied", "TIDAK_AUTORIZASI");
    const { p_nama_rumpun, p_wali_kelas, p_kelas_list } = request.data;
    let added = 0, skipped = 0;
    for (const nama of (p_kelas_list || [])) {
        try {
            await db.collection("kelas").add({
                nama_rumpun: p_nama_rumpun, nama_kelas: nama.toUpperCase().trim(),
                wali_kelas: p_wali_kelas, jumlah_siswa: null,
                dibuat_oleh: u, dibuat_pada: admin.firestore.FieldValue.serverTimestamp()
            });
            added++;
        }
        catch {
            skipped++;
        }
    }
    return { ok: true, ditambahkan: added, dilewati: skipped };
});
exports.hapusKelas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u))
        throw new https_1.HttpsError("permission-denied", "TIDAK_AUTORIZASI");
    await db.collection("kelas").doc(request.data.p_id).delete();
    return { ok: true };
});
exports.updateKelas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    const { p_id, p_wali_kelas, p_jumlah_siswa } = request.data;
    const doc = await db.collection("kelas").doc(p_id).get();
    if (!doc.exists || doc.data().dibuat_oleh !== u)
        throw new https_1.HttpsError("permission-denied", "TIDAK_AUTORIZASI");
    const update = {};
    if (p_wali_kelas !== undefined)
        update.wali_kelas = p_wali_kelas;
    if (p_jumlah_siswa !== undefined)
        update.jumlah_siswa = p_jumlah_siswa;
    await doc.ref.update(update);
    return { ok: true };
});
// ═══════════════════════════════════════════════════════
// PENGGUNA (User management)
// ═══════════════════════════════════════════════════════
exports.daftarPengguna = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u))
        throw new https_1.HttpsError("permission-denied", "BUKAN_ADMIN");
    const snap = await db.collection("pengguna").orderBy("role").orderBy("nama_lengkap").get();
    const data = snap.docs.map(d => ({ id: d.id, ...d.data() }));
    return { ok: true, data };
});
exports.tambahPengguna = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u))
        throw new https_1.HttpsError("permission-denied", "BUKAN_ADMIN");
    const { p_email, p_nama_lengkap, p_role, p_mata_pelajaran } = request.data;
    const userRecord = await auth.getUserByEmail(p_email).catch(() => null);
    if (!userRecord)
        throw new https_1.HttpsError("not-found", "USER_TIDAK_DITEMUKAN");
    await db.collection("pengguna").doc(userRecord.uid).set({
        id: userRecord.uid, email: p_email, nama_lengkap: p_nama_lengkap,
        role: p_role || "guru", mata_pelajaran: p_mata_pelajaran || null,
        dibuat_oleh: u, dibuat_pada: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
    return { ok: true, user_id: userRecord.uid };
});
exports.hapusPengguna = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u))
        throw new https_1.HttpsError("permission-denied", "BUKAN_ADMIN");
    if (request.data.p_id === u)
        throw new https_1.HttpsError("failed-precondition", "TIDAK_BOLEH_HAPUS_DIRI");
    await db.collection("pengguna").doc(request.data.p_id).delete();
    return { ok: true };
});
exports.updatePengguna = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u))
        throw new https_1.HttpsError("permission-denied", "BUKAN_ADMIN");
    const { p_id, p_nama_lengkap, p_role, p_mata_pelajaran } = request.data;
    const update = {};
    if (p_nama_lengkap !== undefined)
        update.nama_lengkap = p_nama_lengkap;
    if (p_role !== undefined)
        update.role = p_role;
    if (p_mata_pelajaran !== undefined)
        update.mata_pelajaran = p_mata_pelajaran;
    await db.collection("pengguna").doc(p_id).update(update);
    return { ok: true };
});
// ═══════════════════════════════════════════════════════
// PENGAWAS management
// ═══════════════════════════════════════════════════════
exports.jadikanPengawas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u) && !await isPengawas(u)) {
        throw new https_1.HttpsError("permission-denied", "DITOLAK");
    }
    const { p_email, p_nama, p_is_admin } = request.data;
    const userRecord = await auth.getUserByEmail(p_email).catch(() => null);
    if (!userRecord)
        throw new https_1.HttpsError("not-found", "USER_TIDAK_DITEMUKAN");
    await db.collection("pengawas").doc(userRecord.uid).set({
        uid: userRecord.uid, nama: p_nama, email: p_email.toLowerCase(),
        is_admin: !!p_is_admin, created_at: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
    return { ok: true, message: `${p_nama} terdaftar sebagai pengawas.` };
});
exports.cabutPengawas = (0, https_1.onCall)(async (request) => {
    const u = uid(request);
    if (!await isAdmin(u))
        throw new https_1.HttpsError("permission-denied", "HANYA_ADMIN");
    const { p_email } = request.data;
    const userRecord = await auth.getUserByEmail(p_email).catch(() => null);
    if (!userRecord)
        throw new https_1.HttpsError("not-found", "TIDAK_DITEMUKAN");
    // Prevent removing last admin
    const adminSnap = await db.collection("pengawas").where("is_admin", "==", true).get();
    if (adminSnap.size <= 1) {
        const target = adminSnap.docs.find(d => d.data().email === p_email.toLowerCase());
        if (target)
            throw new https_1.HttpsError("failed-precondition", "INI_ADMIN_TERAKHIR");
    }
    await db.collection("pengawas").doc(userRecord.uid).delete();
    return { ok: true, message: `${p_email} dicabut.` };
});
//# sourceMappingURL=index.js.map