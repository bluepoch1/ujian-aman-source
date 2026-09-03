export const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL ?? "";
export const ANON_KEY = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY ?? "";
const KUNCI_SESI = "ua.sesi";
const KUNCI_CSRF = "ua.csrf";

export type Sesi = { access_token: string; refresh_token: string; expires_at: number; email: string; };

export function belumDikonfigurasi(): boolean { return !SUPABASE_URL || !ANON_KEY; }

export function bacaSesi(): Sesi | null {
  if (typeof window === "undefined") return null;
  try {
    const mentah = localStorage.getItem(KUNCI_SESI);
    if (!mentah) return null;
    const s = JSON.parse(mentah) as Sesi;
    if (s.expires_at && s.expires_at < Math.floor(Date.now() / 1000)) { localStorage.removeItem(KUNCI_SESI); return null; }
    return s?.access_token ? s : null;
  } catch { return null; }
}

export function simpanSesi(s: Sesi | null) {
  if (typeof window === "undefined") return;
  if (s) localStorage.setItem(KUNCI_SESI, JSON.stringify(s)); else localStorage.removeItem(KUNCI_SESI);
}

export function bacaCsrf(): string {
  if (typeof window === "undefined") return "";
  let token = sessionStorage.getItem(KUNCI_CSRF);
  if (!token) { const array = new Uint8Array(32); crypto.getRandomValues(array); token = Array.from(array, (b) => b.toString(16).padStart(2, "0")).join(""); sessionStorage.setItem(KUNCI_CSRF, token); }
  return token;
}

async function auth(path: string, body: unknown) {
  const r = await fetch(`${SUPABASE_URL}/auth/v1/${path}`, { method: "POST", headers: { apikey: ANON_KEY, "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) { const e = d?.error_code || ""; throw new Error(e === "invalid_login" ? "Email atau kata sandi salah." : e === "too_many_requests" ? "Terlalu banyak percobaan." : "Gagal masuk."); }
  return d;
}

export async function masuk(email: string, password: string): Promise<Sesi> {
  const last = parseInt(sessionStorage.getItem("ua.lastLogin") || "0"); if (Date.now() - last < 2000) throw new Error("Tunggu sebentar."); sessionStorage.setItem("ua.lastLogin", String(Date.now()));
  const d = await auth("token?grant_type=password", { email, password });
  const s: Sesi = { access_token: d.access_token, refresh_token: d.refresh_token, expires_at: Math.floor(Date.now() / 1000) + (d.expires_in ?? 3600), email: d.user?.email ?? email };
  simpanSesi(s); return s;
}

export async function keluar() {
  const s = bacaSesi(); simpanSesi(null); if (!s) return;
  try { await fetch(`${SUPABASE_URL}/auth/v1/logout`, { method: "POST", headers: { apikey: ANON_KEY, Authorization: `Bearer ${s.access_token}` } }); } catch {}
}

async function segarkanBilaPerlu(): Promise<Sesi | null> {
  const s = bacaSesi(); if (!s) return null; if (s.expires_at - Math.floor(Date.now() / 1000) > 300) return s;
  try { const d = await auth("token?grant_type=refresh_token", { refresh_token: s.refresh_token }); const b: Sesi = { access_token: d.access_token, refresh_token: d.refresh_token, expires_at: Math.floor(Date.now() / 1000) + (d.expires_in ?? 3600), email: d.user?.email ?? s.email }; simpanSesi(b); return b; } catch { simpanSesi(null); return null; }
}

export class SesiHabis extends Error { constructor() { super("Sesi berakhir."); } }

export async function rpc<T = any>(fn: string, args: Record<string, unknown> = {}): Promise<T> {
  const s = await segarkanBilaPerlu(); if (!s) throw new SesiHabis();
  
  
  const r = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: {
      apikey: ANON_KEY,
      Authorization: `Bearer ${s.access_token}`,
      "Content-Type": "application/json",
      "Prefer": "return=representation"
    },
    body: JSON.stringify(args)
  });
  
  
  if (r.status === 401) { simpanSesi(null); throw new SesiHabis(); }
  
  const d = await r.json().catch(() => null);
  
  if (!r.ok) {
    const errorMsg = d?.message || d?.msg || "Terjadi kesalahan.";
      throw new Error(errorMsg);
  }
  
  return (Array.isArray(d) ? d[0] : d) as T;
}

export type Ujian = { token: string; nama_kelas: string; mata_pelajaran: string | null; durasi_menit: number | null; max_peserta: number | null; expired_at: string | null; mulai_at: string | null; is_active: boolean; jumlah_klaim: number; peserta_aktif: number; total_pelanggaran: number; };
export type Peserta = { session_id: string; nama_peserta: string | null; nomor_peserta: string | null; status: string; online: boolean; sisa_detik: number | null; mulai_at: string | null; selesai_at: string | null; terakhir_aktif: string | null; batas_waktu_at: string | null; device_model: string | null; device_hash: string | null; app_version: string | null; jumlah_pelanggaran: number; keluar_sementara: boolean; };
export type Kelas = { ok: boolean; kode?: string; kelas: string; mata_pelajaran: string | null; durasi_menit: number; max_peserta: number | null; jumlah_klaim: number; expired_at: string | null; server_time: string; peserta: Peserta[]; };
export type Detail = { ok: boolean; nama_peserta: string | null; nomor_peserta: string | null; status: string; device_model: string | null; app_version: string | null; mulai_at: string | null; selesai_at: string | null; batas_waktu_at: string | null; terakhir_aktif: string | null; sisa_detik: number | null; tambahan_menit: number; catatan_pengawas: string | null; selisih_jam_detik: number; jumlah_pelanggaran: number; pelanggaran: Pelanggaran[]; };
export type Pelanggaran = { jenis: string; keterangan: string | null; dibuat_pada: string; };

export type KelasItem = {
  id: string;
  nama_rumpun: string;
  nama_kelas: string;
  wali_kelas: string | null;
  jumlah_siswa: number | null;
  dibuat_pada: string;
};

export type Pengguna = {
  id: string;
  email: string;
  nama_lengkap: string;
  role: 'admin' | 'guru';
  mata_pelajaran: string | null;
  dibuat_pada: string;
};

export type Profil = {
  ok: boolean;
  id?: string;
  email?: string;
  nama_lengkap?: string;
  role?: 'admin' | 'guru';
  mata_pelajaran?: string | null;
  kode?: string;
};


export function formatJudulUjian(u: { nama_kelas: string; mata_pelajaran: string | null; token: string }): string {
  const mapel = u.mata_pelajaran || "Umum";
  return u.nama_kelas + " " + mapel + " (" + u.token + ")";
}
