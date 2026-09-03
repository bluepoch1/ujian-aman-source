"use client";
import { useEffect, useRef } from "react";
import QRCode from "qrcode";

type DinoQRProps = {
  value: string;
  size?: number;
  title?: string;
};

export default function DinoQR({ value, size = 280, title }: DinoQRProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    QRCode.toCanvas(canvas, value, {
      width: size,
      margin: 2,
      color: {
        dark: "#1B1F23",
        light: "#FFFFFF",
      },
      errorCorrectionLevel: "M",
    }).catch(console.error);
  }, [value, size]);

  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 8 }}>
      <canvas
        ref={canvasRef}
        style={{
          width: size,
          height: size,
          borderRadius: 8,
          border: "2px solid var(--border)",
        }}
      />
      {title && (
        <div style={{ fontSize: 12, color: "var(--muted)", textAlign: "center" }}>
          {title}
        </div>
      )}
    </div>
  );
}
