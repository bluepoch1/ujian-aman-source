import type { Metadata } from "next";
import "./globals.css";
import ErrorBoundary from "@/components/ErrorBoundary";
export const metadata: Metadata = { title: "Ujian Aman", description: "Dashboard pengawas ujian" };
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (<html lang="id"><body><ErrorBoundary>{children}</ErrorBoundary></body></html>);
}
