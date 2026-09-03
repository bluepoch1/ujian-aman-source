"use client";
import { useState } from "react";
import { masuk, belumDikonfigurasi, bacaCsrf } from "@/lib/supabase";

export default function Login({ onMasuk }: { onMasuk: () => void }) {
  const [email, setEmail] = useState("");
  const [pw, setPw] = useState("");
  const [sibuk, setSibuk] = useState(false);
  const [galat, setGalat] = useState("");
  const [csrfToken] = useState(() => bacaCsrf());
  const belumSiap = belumDikonfigurasi();

  async function kirim(e: React.FormEvent) {
    e.preventDefault(); setGalat(""); setSibuk(true);
    try { await masuk(email.trim(), pw); onMasuk(); } catch (ex: any) { setGalat(ex?.message || "Gagal masuk."); } finally { setSibuk(false); }
  }

  return (
    <div style={{ minHeight: "100dvh", display: "grid", placeItems: "center", padding: 20 }}>
      <div style={{ width: "100%", maxWidth: 380 }}>
        <div style={{ textAlign: "center", marginBottom: 26 }}>
          <h1 style={{ margin: 0, fontSize: 21 }}>Ujian Aman</h1>
          <p style={{ margin: "5px 0 0", color: "var(--muted)", fontSize: 14 }}>Dashboard pengawas</p>
        </div>
        <form onSubmit={kirim} className="card" style={{ padding: 22 }}>
          <input type="hidden" name="csrf_token" value={csrfToken} />
          <div style={{ marginBottom: 14 }}>
            <label className="label" htmlFor="email">Email</label>
            <input id="email" className="field" type="email" required autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="guru@sekolah.sch.id" maxLength={254} />
          </div>
          <div style={{ marginBottom: 18 }}>
            <label className="label" htmlFor="pw">Kata sandi</label>
            <input id="pw" className="field" type="password" required autoComplete="current-password" value={pw} onChange={(e) => setPw(e.target.value)} maxLength={128} />
          </div>
          {galat && <div style={{ background: "var(--danger-soft)", border: "1px solid var(--danger-border)", color: "var(--danger)", padding: "9px 12px", borderRadius: 8, fontSize: 13, marginBottom: 14 }}>{galat}</div>}
          <button className="btn" style={{ width: "100%" }} disabled={sibuk || belumSiap}>{sibuk ? "Memeriksa…" : "Masuk"}</button>
        </form>
        <p style={{ textAlign: "center", color: "var(--faint)", fontSize: 12.5, marginTop: 16 }}>Hanya akun pengawas yang dapat masuk.</p>
      </div>
    </div>
  );
}
