"use client";
import React from "react";

class ErrorCatcher extends React.Component<
  { children: React.ReactNode },
  { error: Error | null; info: string }
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { error: null, info: "" };
  }
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  componentDidCatch(error: Error, info: React.ErrorInfo) {
    this.setState({ info: info.componentStack || "" });
  }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: 20, fontFamily: "monospace", fontSize: 13, background: "#fff0f0", minHeight: "100dvh" }}>
          <h2 style={{ color: "red", marginBottom: 10 }}>Application Error</h2>
          <div style={{ background: "#ffe0e0", padding: 12, borderRadius: 8, marginBottom: 10 }}>
            <b>{this.state.error.message}</b>
          </div>
          <pre style={{ whiteSpace: "pre-wrap", wordBreak: "break-all", fontSize: 11, color: "#666" }}>
            {this.state.error.stack}
          </pre>
          {this.state.info && <pre style={{ whiteSpace: "pre-wrap", wordBreak: "break-all", fontSize: 11, color: "#888", marginTop: 10 }}>{this.state.info}</pre>}
        </div>
      );
    }
    return this.props.children;
  }
}

export default function ErrorBoundary({ children }: { children: React.ReactNode }) {
  return <ErrorCatcher>{children}</ErrorCatcher>;
}
