"use client";
import DinoQR from "./DinoQR";

export default function KartuToken({ ujian, onTutup }: { ujian: { token: string; nama_kelas: string; mata_pelajaran?: string | null; durasi_menit?: number | null }; onTutup: () => void }) {
  const url = ujian.token;
  const judul = ujian.nama_kelas + " " + (ujian.mata_pelajaran || "Umum");

  return (
    <div style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,.55)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 18 }} onClick={onTutup}>
      <div className="card" style={{ padding: 28, width: 380, maxWidth: "100%", textAlign: "center" }} onClick={(e) => e.stopPropagation()}>
        <h2 style={{ margin: "0 0 4px", fontSize: 17 }}>Kartu Token</h2>
        <div style={{ color: "var(--muted)", fontSize: 13, marginBottom: 16 }}>{judul}</div>

        <div style={{ display: "flex", justifyContent: "center", marginBottom: 16 }}>
          <DinoQR value={url} size={220} title={judul} />
        </div>

        <div className="card" style={{ padding: 14, marginBottom: 14, background: "var(--sunken)" }}>
          <div style={{ fontSize: 11, color: "var(--muted)", marginBottom: 4 }}>TOKEN</div>
          <div className="mono" style={{ fontSize: 28, fontWeight: 700, color: "var(--accent)", letterSpacing: 3 }}>{ujian.token}</div>
        </div>

        <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 14 }}>
          {ujian.durasi_menit ? "Durasi: " + ujian.durasi_menit + " menit" : ""}
        </div>

        <div style={{ fontSize: 11, color: "var(--faint)", wordBreak: "break-all", marginBottom: 16, fontFamily: "monospace" }}>
          Buka di HP: ujnamn.liwezy-yi.cc.cd/token
        </div>

        <div style={{ display: "flex", gap: 10 }}>
          <button className="btn-ghost" style={{ flex: 1 }} onClick={onTutup}>Tutup</button>
          <button className="btn" style={{ flex: 1 }} onClick={() => { navigator.clipboard.writeText(ujian.token); alert("Token disalin!"); }}>Salin Token</button>
        </div>
      </div>
    </div>
  );
}
