"use client";
import { useCallback, useEffect, useMemo, useState } from "react";
import { bacaSesi, keluar as keluarSesi, rpc, SesiHabis, type Ujian, type KelasItem, type Profil } from "@/lib/supabase";

import Login from "@/components/Login";
import Pantau from "@/components/Pantau";
import KartuToken from "@/components/KartuToken";
import KelasManager from "@/components/KelasManager";
import UserManager from "@/components/UserManager";
import DaftarUjian from "@/components/DaftarUjian";

type Tab = "pantau" | "ujian" | "buat" | "kelas" | "pengguna";


export default function Halaman() {
  const [siap, setSiap] = useState(false);
  const [masuk, setMasuk] = useState(false);
  const [email, setEmail] = useState("");
  const [tab, setTab] = useState<Tab>("pantau");
  const [ujian, setUjian] = useState<Ujian[]>([]);
  const [pilih, setPilih] = useState<string>("");
  const [showDetail, setShowDetail] = useState(false);
  const [kartu, setKartu] = useState<Ujian | null>(null);
  const [muat, setMuat] = useState(false);
  const [hapusSibuk, setHapusSibuk] = useState(false);
  const [daftarKelas, setDaftarKelas] = useState<KelasItem[]>([]);
  const [profil, setProfil] = useState<Profil>({ ok: false } as Profil);

  useEffect(() => { const s = bacaSesi(); if (s) { setMasuk(true); setEmail(s.email); } setSiap(true); }, []);
  const sesiHabis = useCallback(() => { setMasuk(false); setUjian([]); setPilih(""); setDaftarKelas([]); setProfil({ ok: false } as Profil); }, []);

  const muatProfil = useCallback(async () => {
    try { const d = await rpc<Profil>("ambil_profil"); if (d?.ok) setProfil(d); } catch (ex) { if (ex instanceof SesiHabis) sesiHabis(); }
  }, [sesiHabis]);

  const muatUjian = useCallback(async () => {
    setMuat(true);
    try { const d = await rpc<any>("daftar_ujian_aktif", {}); const daftar: Ujian[] = d?.ujian ?? d?.data ?? []; setUjian(daftar); } catch (ex) { if (ex instanceof SesiHabis) sesiHabis(); } finally { setMuat(false); }
  }, [sesiHabis]);

  const muatKelas = useCallback(async () => {
    try { const d = await rpc<any>("ambil_kelas"); if (d?.ok) setDaftarKelas(d.data || []); } catch (ex) { if (ex instanceof SesiHabis) sesiHabis(); }
  }, [sesiHabis]);

  useEffect(() => { if (masuk) { muatProfil(); muatUjian(); muatKelas(); } }, [masuk, muatProfil, muatUjian, muatKelas]);

  const grouped = useMemo(() => {
    const g: Record<string, KelasItem[]> = {};
    const rumpunOrder = ["Kelas 7", "Kelas 8", "Kelas 9", "Kelas 10", "Kelas 11", "Kelas 12"];
    const sorted = [...daftarKelas].sort((a, b) => {
      const ri = rumpunOrder.indexOf(a.nama_rumpun);
      const rj = rumpunOrder.indexOf(b.nama_rumpun);
      if (ri !== rj) return (ri === -1 ? 99 : ri) - (rj === -1 ? 99 : rj);
      return a.nama_kelas.localeCompare(b.nama_kelas);
    });
    for (const k of sorted) {
      if (!g[k.nama_rumpun]) g[k.nama_rumpun] = [];
      g[k.nama_rumpun].push(k);
    }
    return g;
  }, [daftarKelas]);

  async function hapusUjianToken(token: string) {
    const u = ujian.find((u) => u.token === token);
    if (!u) return;
    if (!confirm("Hapus ujian " + u.nama_kelas + "? Semua data peserta dan pelanggaran akan dihapus.")) return;
    setHapusSibuk(true);
    try {
      const d = await rpc<any>("hapus_ujian", { p_token: token });
      if (d?.ok) { await muatUjian(); } else { alert("Gagal hapus: " + (d?.kode || "Unknown error")); }
    } catch (ex: any) { if (ex instanceof SesiHabis) return sesiHabis(); alert("Gagal hapus ujian");
    } finally { setHapusSibuk(false); }
  }

  if (!siap) return null;
  if (!masuk) return <Login onMasuk={() => { const s = bacaSesi(); setEmail(s?.email ?? ""); setMasuk(true); }} />;

  const isAdmin = profil?.role === "admin";
  const namaUser = profil?.nama_lengkap || profil?.email || email;

  const tabs: [Tab, string][] = [["pantau", "Pantau"], ["ujian", "Ujian"], ["buat", "Buat"], ["kelas", "Kelas"]];
  if (isAdmin) tabs.push(["pengguna", "Pengguna"]);

  return (
    <div style={{ minHeight: "100dvh" }}>
      <header className="no-print" style={{ borderBottom: "1px solid var(--border)", background: "var(--raised)", position: "sticky", top: 0, zIndex: 30 }}>
        <div style={{ maxWidth: 1180, margin: "0 auto", padding: "11px 18px", display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <div style={{ fontWeight: 650 }}>Ujian Aman</div>
          <nav style={{ display: "flex", gap: 3, marginLeft: 6 }}>
            {tabs.map(([k, l]) => (
              <button key={k} onClick={() => { setTab(k); if (k === "pantau") setShowDetail(false); }} style={{ border: "none", background: tab === k ? "var(--accent-soft)" : "transparent", color: tab === k ? "var(--accent)" : "var(--muted)", fontWeight: tab === k ? 600 : 500, padding: "7px 13px", borderRadius: 8, fontSize: 14 }}>{l}</button>
            ))}
          </nav>
          <div style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 10 }}>
            <span className="pill" style={{ background: isAdmin ? "var(--accent-soft)" : "var(--ok-soft)", color: isAdmin ? "var(--accent)" : "var(--ok)", fontSize: 11 }}>{isAdmin ? "Admin" : "Guru"}</span>
            <span style={{ fontSize: 13, color: "var(--muted)" }}>{namaUser}</span>
            <button className="btn-ghost btn-sm" onClick={async () => { await keluarSesi(); sesiHabis(); }}>Keluar</button>
          </div>
        </div>
      </header>
      <main style={{ maxWidth: 1180, margin: "0 auto", padding: "20px 18px 60px" }}>
        {tab === "pantau" && (
          showDetail && pilih ? (
            <div>
              <button className="btn-ghost btn-sm" onClick={() => setShowDetail(false)} style={{ marginBottom: 14 }}>&larr; Kembali</button>
              <Pantau key={pilih} token={pilih} namaKelas={ujian.find(u => u.token === pilih)?.nama_kelas ?? ""} onSesiHabis={sesiHabis} />
            </div>
          ) : (
            <TabelPantau grouped={grouped} ujian={ujian} isAdmin={isAdmin} onPantau={(token) => { setPilih(token); setShowDetail(true); }} onQR={(u) => setKartu(u)} onHapus={hapusUjianToken} />
          )
        )}
        {tab === "ujian" && (
          <DaftarUjian
            ujian={ujian}
            muat={muatUjian}
            onPantau={(token) => { setTab("pantau"); setPilih(token); setShowDetail(true); }}
            onQR={(u) => setKartu(u)}
            onHapus={hapusUjianToken}
            onSesiHabis={sesiHabis}
          />
        )}
        {tab === "kelas" && <KelasManager onSesiHabis={sesiHabis} />}
        {tab === "pengguna" && isAdmin && <UserManager onSesiHabis={sesiHabis} />}
        {tab === "buat" && <BuatUjian daftarKelas={daftarKelas} onSelesai={async (t) => { await muatUjian(); setPilih(t); setShowDetail(true); setTab("pantau"); }} onSesiHabis={sesiHabis} />}
      </main>
      {kartu && <KartuToken ujian={kartu} onTutup={() => setKartu(null)} />}
    </div>
  );
}

function TabelPantau({ grouped, ujian, isAdmin, onPantau, onQR, onHapus }: {
  grouped: Record<string, KelasItem[]>;
  ujian: Ujian[];
  isAdmin: boolean;
  onPantau: (token: string) => void;
  onQR: (u: Ujian) => void;
  onHapus: (token: string) => void;
}) {
  const totalUjian = ujian.length;
  const totalOnline = ujian.reduce((n, u) => n + (u.peserta_aktif || 0), 0);

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 16 }}>
        <h2 style={{ margin: 0, fontSize: 18 }}>Daftar Ujian</h2>
        <div style={{ display: "flex", gap: 16, fontSize: 13, color: "var(--muted)" }}>
          <span>{totalUjian} ujian aktif</span>
          <span>{totalOnline} peserta online</span>
        </div>
      </div>

      {!totalUjian && (
        <div className="card" style={{ padding: 48, textAlign: "center", color: "var(--muted)" }}>
          Belum ada ujian aktif. Buat ujian baru di tab <b>Buat</b>.
        </div>
      )}

      {Object.entries(grouped).map(([rumpun, kelasList]) => {
        const ujianListGroup = kelasList.filter(k => ujian.some(u => u.nama_kelas === k.nama_kelas));
        if (!ujianListGroup.length) return null;
        return (
          <div key={rumpun} style={{ marginBottom: 24 }}>
            <h3 style={{ margin: "0 0 10px", fontSize: 15, color: "var(--accent)", fontWeight: 600 }}>{rumpun}</h3>
            <div className="card" style={{ overflow: "hidden" }}>
              <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
                <thead>
                  <tr style={{ borderBottom: "1px solid var(--border)", background: "var(--sunken)" }}>
                    <th style={{ padding: "10px 14px", textAlign: "left", fontWeight: 600 }}>Kelas</th>
                    <th style={{ padding: "10px 14px", textAlign: "left", fontWeight: 600 }}>Mapel</th>
                    <th style={{ padding: "10px 14px", textAlign: "left", fontWeight: 600 }}>Token</th>
                    <th style={{ padding: "10px 14px", textAlign: "center", fontWeight: 600 }}>Status</th>
                    <th style={{ padding: "10px 14px", textAlign: "center", fontWeight: 600 }}>Online</th>
                    <th style={{ padding: "10px 14px", textAlign: "center", fontWeight: 600 }}>Pelanggaran</th>
                    <th style={{ padding: "10px 14px", textAlign: "center", fontWeight: 600 }}>Aksi</th>
                  </tr>
                </thead>
                <tbody>
                  {ujianListGroup.flatMap(k => ujian.filter(u => u.nama_kelas === k.nama_kelas)).map(u => (
                    <tr key={u.token} style={{ borderBottom: "1px solid var(--border)" }}>
                      <td style={{ padding: "10px 14px", fontWeight: 600 }}>{u.nama_kelas}</td>
                      <td style={{ padding: "10px 14px", color: "var(--muted)" }}>{u.mata_pelajaran || "-"}</td>
                      <td style={{ padding: "10px 14px" }}><span className="mono" style={{ fontSize: 12, background: "var(--sunken)", padding: "2px 6px", borderRadius: 4 }}>{u.token}</span></td>
                      <td style={{ padding: "10px 14px", textAlign: "center" }}>
                        <span className="pill" style={{ background: u.is_active ? "var(--ok-soft)" : "var(--warn-soft)", color: u.is_active ? "var(--ok)" : "var(--warn)", fontSize: 11 }}>
                          {u.is_active ? "Aktif" : "Selesai"}
                        </span>
                      </td>
                      <td style={{ padding: "10px 14px", textAlign: "center", fontWeight: 600 }}>{u.peserta_aktif || 0}</td>
                      <td style={{ padding: "10px 14px", textAlign: "center" }}>
                        {u.total_pelanggaran > 0 ? (
                          <span className="pill" style={{ background: "var(--danger-soft)", color: "var(--danger)", fontSize: 11 }}>{u.total_pelanggaran}</span>
                        ) : <span style={{ color: "var(--faint)" }}>0</span>}
                      </td>
                      <td style={{ padding: "10px 14px", textAlign: "center" }}>
                        <div style={{ display: "flex", gap: 4, justifyContent: "center" }}>
                          <button className="btn-ghost btn-sm" onClick={() => onPantau(u.token)}>Pantau</button>
                          <button className="btn-ghost btn-sm" onClick={() => onQR(u)}>QR</button>
                          {isAdmin && <button className="btn-ghost btn-sm" style={{ color: "var(--danger)" }} onClick={() => onHapus(u.token)}>Hapus</button>}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function BuatUjian({ daftarKelas, onSelesai, onSesiHabis }: { daftarKelas: KelasItem[]; onSelesai: (t: string) => void; onSesiHabis: () => void }) {
  const [f, setF] = useState({ kelas: "", mapel: "", url: "", durasi: 90, maks: 40, berlakuJam: 24 });
  const [sibuk, setSibuk] = useState(false);
  const [galat, setGalat] = useState("");
  const [hasil, setHasil] = useState("");

  async function kirim(e: React.FormEvent) {
    e.preventDefault(); setGalat(""); setSibuk(true);
    try {
      const d0 = await rpc<any>("buat_ujian", { p_nama_kelas: f.kelas.trim(), p_mata_pelajaran: f.mapel.trim() || null, p_url: f.url.trim(), p_durasi_menit: f.durasi || null, p_max_peserta: f.maks || null, p_berlaku_jam: f.berlakuJam || 24 });
      const d = Array.isArray(d0) ? d0[0] : d0;
      if (!d?.ok) throw new Error(d?.pesan || d?.kode || "Gagal membuat ujian.");
      const token = d?.token ?? d?.data?.token;
      if (!token) throw new Error("Server tidak mengembalikan token.");
      setHasil(token);
    } catch (ex: any) { if (ex instanceof SesiHabis) return onSesiHabis(); setGalat(ex?.message || "Gagal"); } finally { setSibuk(false); }
  }

  if (hasil) {
    const mapel = f.mapel || "Umum";
    return (
      <div className="card" style={{ padding: 34, textAlign: "center", maxWidth: 460, margin: "0 auto" }}>
        <div style={{ fontSize: 14, color: "var(--muted)", marginBottom: 8 }}>Ujian berhasil dibuat</div>
        <div style={{ fontSize: 18, fontWeight: 600, marginBottom: 8 }}>{f.kelas} {mapel}</div>
        <div className="mono" style={{ fontSize: 38, fontWeight: 700, color: "var(--accent)", margin: "18px 0" }}>{hasil}</div>
        <button className="btn" onClick={() => onSelesai(hasil)}>Pantau</button>
      </div>
    );
  }

  const grouped = daftarKelas.reduce((acc, k) => {
    if (!acc[k.nama_rumpun]) acc[k.nama_rumpun] = [];
    acc[k.nama_rumpun].push(k);
    return acc;
  }, {} as Record<string, KelasItem[]>);

  return (
    <form onSubmit={kirim} className="card" style={{ padding: 24, maxWidth: 540, margin: "0 auto" }}>
      <h2 style={{ margin: "0 0 18px", fontSize: 18 }}>Buat ujian</h2>
      <div style={{ display: "grid", gap: 14 }}>
        <div>
          <label className="label">Pilih Kelas</label>
          {Object.keys(grouped).length > 0 ? (
            <select className="field" required value={f.kelas} onChange={e => setF({ ...f, kelas: e.target.value })}>
              <option value="">-- Pilih Kelas --</option>
              {Object.entries(grouped).map(([rumpun, kelas]) => (
                <optgroup key={rumpun} label={rumpun}>
                  {kelas.map(k => (
                    <option key={k.id} value={k.nama_kelas}>{k.nama_kelas}{k.wali_kelas ? ` (${k.wali_kelas})` : ""}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          ) : (
            <div>
              <input className="field" placeholder="Atau ketik manual: 7A" value={f.kelas} onChange={e => setF({ ...f, kelas: e.target.value })} />
              <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 4 }}>Belum ada kelas? Tambah di tab <b>Kelas</b>.</div>
            </div>
          )}
        </div>
        <div><label className="label">Mata Pelajaran</label><input className="field" value={f.mapel} onChange={(e) => setF({ ...f, mapel: e.target.value })} placeholder="Contoh: MATEMATIKA" /></div>
        <div><label className="label">URL soal</label><input className="field" required type="url" value={f.url} onChange={(e) => setF({ ...f, url: e.target.value })} /></div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
          <div><label className="label">Durasi (menit)</label><input className="field" type="number" min={1} max={600} value={f.durasi} onChange={(e) => setF({ ...f, durasi: Number(e.target.value) })} /></div>
          <div><label className="label">Maks peserta</label><input className="field" type="number" min={1} max={500} value={f.maks} onChange={(e) => setF({ ...f, maks: Number(e.target.value) })} /></div>
        </div>
      </div>
      {galat && <div style={{ background: "var(--danger-soft)", color: "var(--danger)", padding: "9px 12px", borderRadius: 8, fontSize: 13, marginTop: 14 }}>{galat}</div>}
      <button className="btn" style={{ width: "100%", marginTop: 18 }} disabled={sibuk}>{sibuk ? "Membuat..." : "Buat ujian"}</button>
    </form>
  );
}
