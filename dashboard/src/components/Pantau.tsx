"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { rpc, SesiHabis, type Kelas, type Peserta } from "@/lib/supabase";
import { jam, lalu, unduhCsv, stempel } from "@/lib/util";
import DinoQR from "./DinoQR";

const JEDA_MS = 5000;

function sejakDetak(p: Peserta, serverTime: string | undefined): number | null {
  if (!p.terakhir_aktif || !serverTime) return null;
  const a = new Date(p.terakhir_aktif).getTime();
  const b = new Date(serverTime).getTime();
  if (isNaN(a) || isNaN(b)) return null;
  return Math.max(0, (b - a) / 1000);
}

function statusLabel(s: string): { text: string; color: string; bg: string } {
  switch (s) {
    case "aktif": return { text: "Aktif", color: "var(--ok)", bg: "var(--ok-soft)" };
    case "selesai": return { text: "Selesai", color: "var(--accent)", bg: "var(--accent-soft)" };
    case "dihentikan": return { text: "Dihentikan", color: "var(--danger)", bg: "var(--danger-soft)" };
    case "kedaluwarsa": return { text: "Kedaluwarsa", color: "var(--warn)", bg: "var(--warn-soft)" };
    case "menunggu": return { text: "Menunggu", color: "var(--warn)", bg: "var(--warning-soft)" };
    default: return { text: s, color: "var(--muted)", bg: "var(--sunken)" };
  }
}

function kirimNotifikasi(judul: string, pesan: string) {
  if (typeof window === "undefined") return;
  if (!("Notification" in window)) return;
  if (Notification.permission !== "granted") return;
  try {
    new Notification(judul, { body: pesan, tag: "ujian-aman", renotify: true });
  } catch {}
}

export default function Pantau({ token, namaKelas, onSesiHabis }: { token: string; namaKelas: string; onSesiHabis: () => void }) {
  const [data, setData] = useState<Kelas | null>(null);
  const [galat, setGalat] = useState("");
  const [hidup, setHidup] = useState(false);
  const [rateLimited, setRateLimited] = useState(false);
  const [showQR, setShowQR] = useState(false);
  const tokenRef = useRef(token);
  tokenRef.current = token;
  const prevData = useRef<Kelas | null>(null);

  const muat = useCallback(async () => {
    if (rateLimited) return;
    try {
      const d = await rpc<Kelas>("pantau_kelas", { p_token: tokenRef.current });
      if (!d?.ok) { setGalat(d?.kode || "Tidak dapat memuat kelas"); setHidup(false); return; }

      // Cek pelanggaran baru untuk notifikasi
      if (prevData.current) {
        const oldPeserta = prevData.current.peserta || [];
        const newPeserta = d.peserta || [];
        for (const np of newPeserta) {
          const op = oldPeserta.find(o => o.session_id === np.session_id);
          if (op && np.jumlah_pelanggaran > op.jumlah_pelanggaran) {
            kirimNotifikasi(
              "Pelanggaran Baru",
              `${np.nama_peserta || "Siswa"} - ${np.jumlah_pelanggaran - op.jumlah_pelanggaran} pelanggaran baru`
            );
          }
          if (op && op.online && !np.online) {
            kirimNotifikasi(
              "Siswa Offline",
              `${np.nama_peserta || "Siswa"} kehilangan koneksi`
            );
          }
        }
      }
      prevData.current = d;

      setData(d); setGalat(""); setHidup(true);
    } catch (ex: any) {
      if (ex instanceof SesiHabis) { onSesiHabis(); return; }
      const msg = String(ex?.message || "");
      if (msg.includes("429") || msg.includes("too many")) { setRateLimited(true); setGalat("Terlalu banyak permintaan."); setTimeout(() => setRateLimited(false), 30000); }
      setHidup(false);
    }
  }, [onSesiHabis, rateLimited]);

  useEffect(() => {
    // Minta izin notifikasi
    if (typeof window !== "undefined" && "Notification" in window && Notification.permission === "default") {
      Notification.requestPermission();
    }

    setData(null); muat();
    const t = setInterval(muat, JEDA_MS);
    const vis = () => { if (document.visibilityState === "visible") muat(); };
    document.addEventListener("visibilitychange", vis);
    return () => { clearInterval(t); document.removeEventListener("visibilitychange", vis); };
  }, [token, muat]);

  async function tindakan(sid: string, aksi: string, nilai?: number) {
    const last = parseInt(sessionStorage.getItem("ua.lastAction") || "0");
    if (Date.now() - last < 1000) { alert("Tunggu sebentar."); return; }
    sessionStorage.setItem("ua.lastAction", String(Date.now()));
    try { await rpc("tindakan_pengawas", { p_session_id: sid, p_aksi: aksi, p_nilai: nilai ?? null }); await muat(); } catch (ex: any) { if (ex instanceof SesiHabis) return onSesiHabis(); alert("Tindakan gagal."); }
  }

  const P = data?.peserta ?? [];
  const online = P.filter((p) => p.online && p.status === "aktif").length;
  const selesai = P.filter((p) => p.status === "selesai").length;
  const dihentikan = P.filter((p) => p.status === "dihentikan").length;
  const langgar = P.reduce((n, p) => n + (p.jumlah_pelanggaran || 0), 0);

  function ekspor() {
    unduhCsv(`peserta-${namaKelas.replace(/\s+/g, "-")}-${stempel()}.csv`, [
      ["Nama", "Nomor", "Status", "Online", "Sisa waktu", "Device", "App", "Pelanggaran", "Mulai", "Selesai"],
      ...P.map((p) => [p.nama_peserta ?? "", p.nomor_peserta ?? "", p.status, p.online ? "ya" : "tidak", jam(p.sisa_detik), p.device_model ?? "", p.app_version ?? "", p.jumlah_pelanggaran ?? 0, p.mulai_at ?? "", p.selesai_at ?? ""]),
    ]);
  }

  const judul = data ? (data.kelas + " " + (data.mata_pelajaran || "")) : namaKelas;

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap", marginBottom: 14 }}>
        <div>
          <h2 style={{ margin: 0, fontSize: 18 }}>{judul}</h2>
          <div style={{ fontSize: 13, color: "var(--muted)", marginTop: 3 }}>token <span className="mono">{token}</span></div>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          <button className="btn-ghost btn-sm" onClick={() => setShowQR(!showQR)}>
            {showQR ? "Tutup QR" : "QR Code"}
          </button>
          <button className="btn-ghost btn-sm" onClick={ekspor} disabled={!P.length}>Ekspor CSV</button>
        </div>
      </div>

      {showQR && (
        <div className="card" style={{ padding: 20, marginBottom: 16, textAlign: "center" }}>
          <div style={{ marginBottom: 12, fontSize: 14, fontWeight: 600 }}>Scan QR untuk masuk ujian</div>
          <DinoQR value={token} size={200} title={judul} />
          <div style={{ marginTop: 12, fontSize: 12, color: "var(--muted)" }}>
            Token: <span className="mono" style={{ fontWeight: 600 }}>{token}</span>
          </div>
        </div>
      )}

      {galat && <div className="card" style={{ padding: 12, marginBottom: 14, background: "var(--danger-soft)", color: "var(--danger)", fontSize: 13 }}>{galat}</div>}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(104px, 1fr))", gap: 10, marginBottom: 16 }}>
        {[{ n: P.length, l: "Peserta" }, { n: online, l: "Online" }, { n: selesai, l: "Selesai" }, { n: dihentikan, l: "Dihentikan" }, { n: langgar, l: "Pelanggaran" }].map((s) => (
          <div key={s.l} className="card" style={{ padding: "12px 14px" }}>
            <div className="tnum" style={{ fontSize: 23, fontWeight: 650 }}>{s.n}</div>
            <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 2 }}>{s.l}</div>
          </div>
        ))}
      </div>
      {!data ? <div style={{ padding: 40, textAlign: "center", color: "var(--faint)" }}>Memuat...</div> : !P.length ? (
        <div className="card" style={{ padding: 40, textAlign: "center", color: "var(--muted)" }}>Belum ada peserta yang masuk. Bagikan token <span className="mono">{token}</span>.</div>
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 10 }}>
          {P.map((p) => {
            const st = statusLabel(p.status);
            return (
              <div key={p.session_id} className="card" style={{ padding: 13, borderColor: p.jumlah_pelanggaran ? "var(--danger-border)" : "var(--border)", opacity: p.status !== "aktif" ? 0.7 : 1 }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "start" }}>
                  <div>
                    <div style={{ fontWeight: 600, fontSize: 14.5 }}>{p.nama_peserta || "Tanpa nama"}</div>
                    {p.nomor_peserta && <div style={{ fontSize: 12, color: "var(--muted)" }}>#{p.nomor_peserta}</div>}
                  </div>
                  <span className="pill" style={{ background: st.bg, color: st.color, fontSize: 11 }}>{st.text}</span>
                </div>
                <div className="tnum" style={{ fontSize: 12.5, color: "var(--muted)", marginTop: 6 }}>
                  {p.status === "aktif" ? "Sisa " + jam(p.sisa_detik) + " . " + lalu(sejakDetak(p, data?.server_time)) : p.selesai_at ? "Selesai " + lalu(sejakDetak(p, data?.server_time)) : ""}
                </div>
                {p.device_model && (
                  <div style={{ fontSize: 11, color: "var(--faint)", marginTop: 4, display: "flex", gap: 8, flexWrap: "wrap" }}>
                    <span>Device: {p.device_model}</span>
                    {p.app_version && <span>v{p.app_version}</span>}
                  </div>
                )}
                {p.device_hash && (
                  <div style={{ fontSize: 10, color: "var(--faint)", marginTop: 2, fontFamily: "monospace" }}>
                    ID: {p.device_hash.substring(0, 16)}...
                  </div>
                )}
                {p.jumlah_pelanggaran > 0 && <div className="pill" style={{ marginTop: 6, background: "var(--danger-soft)", color: "var(--danger)" }}>{p.jumlah_pelanggaran} pelanggaran</div>}
                {p.keluar_sementara && <div className="pill" style={{ marginTop: 4, background: "var(--warning-soft)", color: "var(--warning)", fontSize: 11 }}>Keluar sementara - masuk lagi dengan token</div>}
                {p.status === "aktif" && (
                  <div style={{ display: "flex", gap: 6, marginTop: 8 }}>
                    <button className="btn-danger btn-sm" onClick={() => { if (confirm("Hentikan " + p.nama_peserta + "?")) tindakan(p.session_id, "hentikan"); }}>Hentikan</button>
                    <button className="btn-ghost btn-sm" onClick={() => tindakan(p.session_id, "tambah_waktu", 5)}>+5 mnt</button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
