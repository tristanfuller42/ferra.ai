"use client";
import { useEffect, useState, type ReactNode } from "react";
import type { Profile } from "./schema";


export const emptyProfile: Profile = {
  name: "", email: "", phone: "", location: "", links: [], summary: "",
  experiences: [], education: [], skills: [], extras: [],
};

/** State saved in this browser's localStorage. */
export function useLocal<T>(key: string, init: T) {
  const [v, setV] = useState<T>(init);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    try { const s = localStorage.getItem(key); if (s) setV(JSON.parse(s)); } catch {}
    setReady(true);
  }, [key]);
  useEffect(() => {
    if (ready) try { localStorage.setItem(key, JSON.stringify(v)); } catch {}
  }, [key, v, ready]);
  return [v, setV] as const;
}

export async function ai<T>(task: string, input: object): Promise<T> {
  let pw = "";
  try { pw = localStorage.getItem("pw") ?? ""; } catch {}
  const r = await fetch("/api/ai", {
    method: "POST",
    headers: { "content-type": "application/json", "x-app-password": pw },
    body: JSON.stringify({ task, ...input }),
  });
  const j = await r.json().catch(() => ({ error: `Server error ${r.status}.` }));
  if (r.status === 401) {
    const p = prompt("App password:");
    if (p) { try { localStorage.setItem("pw", p); } catch {} return ai(task, input); }
  }
  if (!r.ok) throw new Error(j.error);
  return j;
}

export const toBase64 = (f: File) =>
  new Promise<string>((ok, fail) => {
    const r = new FileReader();
    r.onload = () => ok(String(r.result).split(",")[1]);
    r.onerror = fail;
    r.readAsDataURL(f);
  });

export function download(name: string, content: string, type = "text/markdown") {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([content], { type }));
  a.download = name;
  a.click();
  URL.revokeObjectURL(a.href);
}

/* eslint-disable @typescript-eslint/no-explicit-any */
const getSR = () => (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

/** Mic button: speech-to-text in the browser (Chrome, Edge, Safari). Hidden where unsupported. */
export function Mic({ onText }: { onText: (t: string) => void }) {
  const [ok, setOk] = useState(false);
  const [rec, setRec] = useState<any>(null);
  useEffect(() => setOk(!!getSR()), []);
  if (!ok) return null;
  const toggle = () => {
    if (rec) return rec.stop();
    const r = new (getSR())();
    r.continuous = true;
    r.onresult = (e: any) => {
      let t = "";
      for (let i = e.resultIndex; i < e.results.length; i++) if (e.results[i].isFinal) t += e.results[i][0].transcript;
      if (t.trim()) onText(t.trim());
    };
    r.onend = () => setRec(null);
    r.start();
    setRec(r);
  };
  return <button type="button" className={rec ? "rec" : ""} onClick={toggle}>{rec ? "■ Stop" : "🎤 Speak"}</button>;
}

/** Minimal Markdown: #/##/### headings, - bullets, **bold**. */
const inline = (s: string) =>
  s.split(/(\*\*[^*]+\*\*)/).map((p, i) => (/^\*\*.+\*\*$/.test(p) ? <strong key={i}>{p.slice(2, -2)}</strong> : p));

export function Md({ text }: { text: string }) {
  const out: ReactNode[] = [];
  let list: string[] = [];
  const flush = () => {
    if (list.length) out.push(<ul key={out.length}>{list.map((l, i) => <li key={i}>{inline(l)}</li>)}</ul>);
    list = [];
  };
  for (const line of text.split("\n")) {
    if (/^\s*[-*] /.test(line)) { list.push(line.replace(/^\s*[-*] /, "")); continue; }
    flush();
    const h = line.match(/^(#{1,3}) (.*)/);
    if (h) { const H = `h${h[1].length}` as "h1"; out.push(<H key={out.length}>{inline(h[2])}</H>); }
    else if (line.trim()) out.push(<p key={out.length}>{inline(line)}</p>);
  }
  flush();
  return <>{out}</>;
}
