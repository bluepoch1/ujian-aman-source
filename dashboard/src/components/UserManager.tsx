"use client";
import { useCallback, useEffect, useState } from "react";
import { rpc, SesiHabis, type Pengguna } from "@/lib/supabase";

export default function UserManager({ onSesiHabis }: { onSesiHabis: () => void }) {
  const [daftar, setDaftar] = useState<Pengguna[]>([]);
  const [sibuk, setSibuk] = useState(false);
  const [galat, setGalat] = useState("");
  const [sukses, setSukses] = useState("");

  // Form tambah
  const [email, setEmail] = useState("");
  const [nama, setNama] = useState("");
  const [role, setRole] = useState<"admin" | "guru">("guru");
  const [mapel, setMapel] = useState("");

  const muat = useCallback(async () => {
    try {
      const d = await rpc<any>("daftar_pengguna");
      if (d?.ok) setDaftar(d.data || []);
    } catch (ex) {
      if (ex instanceof SesiHabis) return onSesiHabis();
    }
  }, [onSesiHabis]);

  useEffect(() => { muat(); }, [muat]);

  async function tambahPengguna(e: React.FormEvent) {
    e.preventDefault();
    setGalat(""); setSukses(""); setSibuk(true);
    try {
      const d = await rpc<any>("tambah_pengguna", {
        p_email: email.trim(),
        p_nama_lengkap: nama.trim(),
        p_role: role,
        p_mata_pelajaran: mapel.trim() || null
      });
      if (d?.ok) {
        setSukses(`Berhasil menambahkan ${nama}`);
        setEmail(""); setNama(""); setMapel("");
        await muat();
      } else {
        setGalat(d?.pesan || d?.kode || "Gagal");
      }
    } catch (ex: any) {
      if (ex instanceof SesiHabis) return onSesiHabis();
      setGalat(ex?.message || "Gagal");
    } finally { setSibuk(false); }
  }

  async function hapusPengguna(id: string, nama: string) {
    if (!confirm(`Hapus akun ${nama}?`)) return;
    try {
      const d = await rpc<any>("hapus_pengguna", { p_id: id });
      if (d?.ok) await muat();
      else alert(d?.kode || "Gagal");
    } catch (ex: any) {
      if (ex instanceof SesiHabis) return onSesiHabis();
      alert("Gagal");
    }
  }

  async function toggleRole(p: Pengguna) {
    const newRole = p.role === "admin" ? "guru" : "admin";
    if (!confirm(`Ubah ${p.nama_lengkap} menjadi ${newRole}?`)) return;
    try {
      await rpc("update_pengguna", { p_id: p.id, p_role: newRole });
      await muat();
    } catch (ex: any) {
      if (ex instanceof SesiHabis) return onSesiHabis();
    }
  }

  return (
    <div>
      <h2 style={{ margin: "0 0 18px", fontSize: 18 }}>Kelola Pengguna</h2>

      {/* Form tambah */}
      <form onSubmit={tambahPengguna} className="card" style={{ padding: 20, marginBottom: 20 }}>
        <h3 style={{ margin: "0 0 14px", fontSize: 15 }}>Tambah Akun Guru</h3>
        <div style={{ display: "grid", gap: 12 }}>
          <div>
            <label className="label">Email (harus terdaftar di Supabase Auth)</label>
            <input
              className="field"
              type="email"
              required
              placeholder="guru@sekolah.id"
              value={email}
              onChange={e => setEmail(e.target.value)}
            />
          </div>
          <div>
            <label className="label">Nama Lengkap</label>
            <input
              className="field"
              required
              placeholder="Pak Budi / Bu Siti"
              value={nama}
              onChange={e => setNama(e.target.value)}
            />
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <div>
              <label className="label">Role</label>
              <select className="field" value={role} onChange={e => setRole(e.target.value as any)}>
                <option value="guru">Guru</option>
                <option value="admin">Admin</option>
              </select>
            </div>
            <div>
              <label className="label">Mata Pelajaran (opsional)</label>
              <input
                className="field"
                placeholder="Matematika"
                value={mapel}
                onChange={e => setMapel(e.target.value)}
              />
            </div>
          </div>
        </div>
        {galat && <div style={{ background: "var(--danger-soft)", color: "var(--danger)", padding: "9px 12px", borderRadius: 8, fontSize: 13, marginTop: 12 }}>{galat}</div>}
        {sukses && <div style={{ background: "var(--ok-soft)", color: "var(--ok)", padding: "9px 12px", borderRadius: 8, fontSize: 13, marginTop: 12 }}>{sukses}</div>}
        <button className="btn" style={{ marginTop: 14 }} disabled={sibuk}>
          {sibuk ? "Menambahkan…" : "Tambah Akun"}
        </button>
      </form>

      {/* Daftar pengguna */}
      {!daftar.length ? (
        <div className="card" style={{ padding: 40, textAlign: "center", color: "var(--muted)" }}>
          Belum ada pengguna.
        </div>
      ) : (
        <div style={{ display: "grid", gap: 10 }}>
          {daftar.map(p => (
            <div key={p.id} className="card" style={{ padding: 14 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, fontSize: 15 }}>
                    {p.nama_lengkap || "Tanpa nama"}
                  </div>
                  <div style={{ fontSize: 13, color: "var(--muted)", marginTop: 2 }}>
                    📧 {p.email}
                  </div>
                  {p.mata_pelajaran && (
                    <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 2 }}>
                      📚 {p.mata_pelajaran}
                    </div>
                  )}
                </div>
                <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                  <span
                    className="pill"
                    style={{
                      background: p.role === "admin" ? "var(--accent-soft)" : "var(--ok-soft)",
                      color: p.role === "admin" ? "var(--accent)" : "var(--ok)",
                      cursor: "pointer"
                    }}
                    onClick={() => toggleRole(p)}
                    title="Klik untuk ubah role"
                  >
                    {p.role === "admin" ? "👑 Admin" : "👨‍🏫 Guru"}
                  </span>
                  <button
                    className="btn-ghost btn-sm"
                    style={{ color: "var(--danger)", fontSize: 12 }}
                    onClick={() => hapusPengguna(p.id, p.nama_lengkap)}
                  >
                    Hapus
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
