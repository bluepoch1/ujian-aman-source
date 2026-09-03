"use client";
import { useCallback, useEffect, useState } from "react";
import { rpc, SesiHabis, type Ujian } from "@/lib/supabase";
import DinoQR from "./DinoQR";

export default function DaftarUjian({ ujian, muat, onPantau, onQR, onHapus, onSesiHabis }: {
  ujian: Ujian[];
  muat: () => void;
  onPantau: (token: string) => void;
  onQR: (u: Ujian) => void;
  onHapus: (token: string) => void;
  onSesiHabis: () => void;
}) {
  const [filter, setFilter] = useState<"semua" | "aktif" | "selesai">("semua");
  const [sibuk, setSibuk] = useState<string | null>(null);

  const filtered = ujian.filter(u => {
    if (filter === "aktif") return u.is_active;
    if (filter === "selesai") return !u.is_active;
    return true;
  });

  async function akhiriUjian(token: string) {
    if (!confirm("Akhiri ujian ini? Semua sesi aktif akan dihentikan.")) return;
    setSibuk(token);
    try {
      await rpc("akhiri_semua_sesi", { p_token: token });
      await muat();
    } catch (ex) {
      if (ex instanceof SesiHabis) return onSesiHabis();
      alert("Gagal mengakhiri ujian.");
    } finally {
      setSibuk(null);
    }
  }

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 16, flexWrap: "wrap", gap: 10 }}>
        <h2 style={{ margin: 0, fontSize: 18 }}>Daftar Ujian</h2>
        <div style={{ display: "flex", gap: 6 }}>
          {(["semua", "aktif", "selesai"] as const).map(f => (
            <button
              key={f}
              className={`btn-ghost btn-sm`}
              style={{
                background: filter === f ? "var(--accent-soft)" : "transparent",
                color: filter === f ? "var(--accent)" : "var(--muted)",
                fontWeight: filter === f ? 600 : 500,
              }}
              onClick={() => setFilter(f)}
            >
              {f === "semua" ? "Semua" : f === "aktif" ? "Aktif" : "Selesai"}
              <span className="tnum" style={{ marginLeft: 4, fontSize: 11 }}>
                ({ujian.filter(u => f === "semua" ? true : f === "aktif" ? u.is_active : !u.is_active).length})
              </span>
            </button>
          ))}
        </div>
      </div>

      {!ujian.length && (
        <div className="card" style={{ padding: 48, textAlign: "center", color: "var(--muted)" }}>
          Belum ada ujian. Buat ujian baru di tab <b>Buat</b>.
        </div>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 12 }}>
        {filtered.map(u => (
          <div key={u.token} className="card" style={{ padding: 16, borderColor: u.is_active ? "var(--border)" : "var(--border)", opacity: u.is_active ? 1 : 0.75 }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "start", marginBottom: 10 }}>
              <div>
                <div style={{ fontWeight: 650, fontSize: 15 }}>{u.nama_kelas}</div>
                <div style={{ fontSize: 13, color: "var(--muted)" }}>{u.mata_pelajaran || "Umum"}</div>
              </div>
              <span className="pill" style={{
                background: u.is_active ? "var(--ok-soft)" : "var(--warn-soft)",
                color: u.is_active ? "var(--ok)" : "var(--warn)",
                fontSize: 11,
              }}>
                {u.is_active ? "Aktif" : "Selesai"}
              </span>
            </div>

            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 8, marginBottom: 12 }}>
              <div style={{ textAlign: "center", padding: "8px 4px", background: "var(--sunken)", borderRadius: 6 }}>
                <div className="tnum" style={{ fontSize: 18, fontWeight: 700 }}>{u.peserta_aktif || 0}</div>
                <div style={{ fontSize: 11, color: "var(--faint)" }}>Online</div>
              </div>
              <div style={{ textAlign: "center", padding: "8px 4px", background: "var(--sunken)", borderRadius: 6 }}>
                <div className="tnum" style={{ fontSize: 18, fontWeight: 700 }}>{u.jumlah_klaim || 0}</div>
                <div style={{ fontSize: 11, color: "var(--faint)" }}>Klaim</div>
              </div>
              <div style={{ textAlign: "center", padding: "8px 4px", background: "var(--sunken)", borderRadius: 6 }}>
                <div className="tnum" style={{ fontSize: 18, fontWeight: 700, color: u.total_pelanggaran > 0 ? "var(--danger)" : "var(--muted)" }}>{u.total_pelanggaran || 0}</div>
                <div style={{ fontSize: 11, color: "var(--faint)" }}>Langgar</div>
              </div>
            </div>

            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 10 }}>
              <div className="mono" style={{ fontSize: 13, background: "var(--sunken)", padding: "3px 8px", borderRadius: 4, fontWeight: 600 }}>
                {u.token}
              </div>
              <div style={{ fontSize: 12, color: "var(--faint)" }}>
                {u.durasi_menit ? u.durasi_menit + " mnt" : ""}
                {u.max_peserta ? " · Max " + u.max_peserta : ""}
              </div>
            </div>

            <div style={{ display: "flex", gap: 6 }}>
              {u.is_active && (
                <button className="btn btn-sm" style={{ flex: 1, fontSize: 12 }} onClick={() => onPantau(u.token)}>
                  Pantau Live
                </button>
              )}
              <button className="btn-ghost btn-sm" style={{ flex: 1, fontSize: 12 }} onClick={() => onQR(u)}>
                QR Code
              </button>
              {u.is_active && (
                <button
                  className="btn-ghost btn-sm"
                  style={{ fontSize: 12, color: "var(--danger)" }}
                  disabled={sibuk === u.token}
                  onClick={() => akhiriUjian(u.token)}
                >
                  {sibuk === u.token ? "..." : "Akhiri"}
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
