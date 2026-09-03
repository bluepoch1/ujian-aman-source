"use client";
import { useCallback, useEffect, useState } from "react";
import { rpc, SesiHabis, type KelasItem } from "@/lib/supabase";

const RUMPUN_OPTIONS = ["Kelas 7", "Kelas 8", "Kelas 9", "Kelas 10", "Kelas 11", "Kelas 12"];

export default function KelasManager({ onSesiHabis }: { onSesiHabis: () => void }) {
  const [daftar, setDaftar] = useState<KelasItem[]>([]);
  const [sibuk, setSibuk] = useState(false);
  const [galat, setGalat] = useState("");

  // Form tambah
  const [rumpun, setRumpun] = useState("Kelas 7");
  const [inputKelas, setInputKelas] = useState(""); // "7A,7B,7C"
  const [wali, setWali] = useState("");

  const muat = useCallback(async () => {
    try {
      const d = await rpc<any>("ambil_kelas");
      if (d?.ok) setDaftar(d.data || []);
    } catch (ex) {
      if (ex instanceof SesiHabis) return onSesiHabis();
    }
  }, [onSesiHabis]);

  useEffect(() => { muat(); }, [muat]);

  async function tambahKelas(e: React.FormEvent) {
    e.preventDefault();
    setGalat("");
    const list = inputKelas.split(",").map(s => s.trim()).filter(Boolean);
    if (!list.length) { setGalat("Masukkan nama kelas, pisahkan koma"); return; }
    setSibuk(true);
    try {
      const d = await rpc<any>("tambah_kelas", {
        p_nama_rumpun: rumpun,
        p_kelas_list: list,
        p_wali_kelas: wali || null
      });
      if (d?.ok) {
        setInputKelas("");
        setWali("");
        await muat();
      }
    } catch (ex: any) {
      if (ex instanceof SesiHabis) return onSesiHabis();
      setGalat(ex?.message || "Gagal");
    } finally { setSibuk(false); }
  }

  async function hapusKelas(id: string, nama: string) {
    if (!confirm(`Hapus kelas ${nama}?`)) return;
    try {
      await rpc("hapus_kelas", { p_id: id });
      await muat();
    } catch (ex: any) {
      if (ex instanceof SesiHabis) return onSesiHabis();
      alert("Gagal hapus");
    }
  }

  // Group by rumpun
  const grouped = daftar.reduce((acc, k) => {
    if (!acc[k.nama_rumpun]) acc[k.nama_rumpun] = [];
    acc[k.nama_rumpun].push(k);
    return acc;
  }, {} as Record<string, KelasItem[]>);

  return (
    <div>
      <h2 style={{ margin: "0 0 18px", fontSize: 18 }}>Kelola Kelas</h2>

      {/* Form tambah */}
      <form onSubmit={tambahKelas} className="card" style={{ padding: 20, marginBottom: 20 }}>
        <h3 style={{ margin: "0 0 14px", fontSize: 15 }}>Tambah Kelas</h3>
        <div style={{ display: "grid", gap: 12 }}>
          <div>
            <label className="label">Rumpun Kelas</label>
            <select className="field" value={rumpun} onChange={e => setRumpun(e.target.value)}>
              {RUMPUN_OPTIONS.map(r => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>
          <div>
            <label className="label">Nama Kelas (pisahkan koma)</label>
            <input
              className="field"
              placeholder="7A, 7B, 7C, 7D"
              value={inputKelas}
              onChange={e => setInputKelas(e.target.value)}
            />
            <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 4 }}>
              Contoh: 7A, 7B, 7C atau VII-A, VII-B
            </div>
          </div>
          <div>
            <label className="label">Wali Kelas (opsional)</label>
            <input
              className="field"
              placeholder="Nama wali kelas"
              value={wali}
              onChange={e => setWali(e.target.value)}
            />
          </div>
        </div>
        {galat && <div style={{ background: "var(--danger-soft)", color: "var(--danger)", padding: "9px 12px", borderRadius: 8, fontSize: 13, marginTop: 12 }}>{galat}</div>}
        <button className="btn" style={{ marginTop: 14 }} disabled={sibuk}>
          {sibuk ? "Menambahkan…" : "Tambah Kelas"}
        </button>
      </form>

      {/* Daftar kelas */}
      {Object.keys(grouped).length === 0 ? (
        <div className="card" style={{ padding: 40, textAlign: "center", color: "var(--muted)" }}>
          Belum ada kelas. Tambahkan kelas di atas.
        </div>
      ) : (
        Object.entries(grouped).map(([rumpun, kelas]) => (
          <div key={rumpun} style={{ marginBottom: 20 }}>
            <h3 style={{ margin: "0 0 10px", fontSize: 15, color: "var(--accent)" }}>{rumpun}</h3>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: 10 }}>
              {kelas.map(k => (
                <div key={k.id} className="card" style={{ padding: 14 }}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "start" }}>
                    <div>
                      <div style={{ fontWeight: 600, fontSize: 16 }}>{k.nama_kelas}</div>
                      {k.wali_kelas && (
                        <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 2 }}>
                          👤 {k.wali_kelas}
                        </div>
                      )}
                      {k.jumlah_siswa ? (
                        <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 2 }}>
                          📋 {k.jumlah_siswa} siswa
                        </div>
                      ) : null}
                    </div>
                    <button
                      className="btn-ghost btn-sm"
                      style={{ color: "var(--danger)", fontSize: 12 }}
                      onClick={() => hapusKelas(k.id, k.nama_kelas)}
                    >
                      Hapus
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))
      )}
    </div>
  );
}
