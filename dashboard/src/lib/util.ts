export function jam(detik: number | null | undefined): string {
  if (detik == null || detik < 0) return "—";
  const j = Math.floor(detik / 3600);
  const m = Math.floor((detik % 3600) / 60);
  const d = Math.floor(detik % 60);
  const p = (n: number) => String(n).padStart(2, "0");
  return j > 0 ? `${j}:${p(m)}:${p(d)}` : `${p(m)}:${p(d)}`;
}

export function tanggal(iso: string | null | undefined): string {
  if (!iso) return "—";
  const t = new Date(iso);
  if (isNaN(t.getTime())) return "—";
  return t.toLocaleString("id-ID", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function lalu(detik: number | null | undefined): string {
  if (detik == null) return "belum pernah";
  if (detik < 10) return "baru saja";
  if (detik < 60) return `${Math.floor(detik)} detik lalu`;
  if (detik < 3600) return `${Math.floor(detik / 60)} menit lalu`;
  return `${Math.floor(detik / 3600)} jam lalu`;
}

function sanitizeCsvValue(v: string | number | null): string {
  const s = v == null ? "" : String(v);
  if (/^[=+\-@\t\r\n]/.test(s)) return "'" + s;
  if (/[",\n;]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

export function unduhCsv(namaBerkas: string, baris: (string | number | null)[][]) {
  const isi = baris.map((r) => r.map(sanitizeCsvValue).join(",")).join("\r\n");
  const blob = new Blob(["\uFEFF" + isi], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = namaBerkas;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function stempel(): string {
  const t = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${t.getFullYear()}${p(t.getMonth() + 1)}${p(t.getDate())}-${p(t.getHours())}${p(t.getMinutes())}`;
}
