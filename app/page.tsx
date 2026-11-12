"use client";
import { useState } from "react";
import { ai, download, Md, Mic, useLocal, emptyProfile } from "@/lib/client";
import type { Entry, Job, Profile, Version } from "@/lib/schema";
import ProfileTab from "./ProfileTab";

const TABS = ["Profile", "Job", "Documents", "Library"] as const;
const QUICK = ["Fit everything on one page", "Make it more concise", "Make the tone more formal", "Make the tone warmer"];
type DocKey = "resume" | "coverLetter";

export default function App() {
  const [tab, setTab] = useState<(typeof TABS)[number]>("Profile");
  const [profile, setProfile] = useLocal<Profile>("profile", emptyProfile);
  const [sample, setSample] = useLocal("sample", "");
  const [library, setLibrary] = useLocal<Entry[]>("library", []);
  const [openId, setOpenId] = useLocal("open", "");
  const [style, setStyle] = useLocal("style", { template: "classic", size: 11 });
  const [busy, setBusy] = useState("");
  const [err, setErr] = useState("");

  const [url, setUrl] = useState("");
  const [jobText, setJobText] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [notes, setNotes] = useState("");

  const [docKey, setDocKey] = useState<DocKey>("resume");
  const [instruction, setInstruction] = useState("");
  const [draft, setDraft] = useState<string | null>(null);

  const run = async (label: string, fn: () => Promise<void>) => {
    setBusy(label); setErr("");
    try { await fn(); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
    setBusy("");
  };

  const entry = library.find((e) => e.id === openId);
  const cur = entry?.versions.at(-1);
  const addVersion = (v: Version) =>
    setLibrary(library.map((e) => (e.id === openId ? { ...e, versions: [...e.versions, v] } : e)));
  const edit = (instr: string) => run("Revising…", async () => {
    const { doc } = await ai<{ doc: string }>("edit", { doc: cur![docKey], instruction: instr, profile, job: entry!.job });
    addVersion({ ...cur!, [docKey]: doc, note: instr, at: new Date().toISOString() });
    setInstruction("");
  });
  const generate = () => run("Writing your resume and cover letter…", async () => {
    const docs = await ai<{ resume: string; coverLetter: string }>("generate", { profile, job, notes, sample });
    const e: Entry = { id: crypto.randomUUID(), job: job!, versions: [{ ...docs, note: "First draft", at: new Date().toISOString() }] };
    setLibrary([e, ...library]); setOpenId(e.id); setTab("Documents");
  });
  const fileName = `${profile.name || "Resume"} - ${entry?.job.company} - ${docKey === "resume" ? "Resume" : "Cover Letter"}`.replace(/[\\/:*?"<>|]/g, "");

  return (
    <main>
      <header>
        <h1>Resume Studio</h1>
        <nav>{TABS.map((t) => <button key={t} className={t === tab ? "on" : ""} onClick={() => setTab(t)}>{t}</button>)}</nav>
      </header>
      {busy && <div className="banner">⏳ {busy}</div>}
      {err && <div className="banner error" onClick={() => setErr("")}>⚠ {err} (click to close)</div>}

      {tab === "Profile" && <ProfileTab {...{ profile, setProfile, sample, setSample, run }} />}

      {tab === "Job" && (
        <section>
          <h2>Target job</h2>
          <div className="row">
            <input placeholder="Job posting URL" value={url} onChange={(e) => setUrl(e.target.value)} />
            <button disabled={!url} onClick={() => run("Reading job page…", async () => setJob(await ai<Job>("job", { url })))}>Fetch</button>
          </div>
          <p className="muted">Some sites (LinkedIn, Indeed) block automatic reading. If fetching fails, paste the description:</p>
          <textarea rows={8} value={jobText} onChange={(e) => setJobText(e.target.value)} placeholder="Paste the job description" />
          <button disabled={!jobText.trim()} onClick={() => run("Analyzing job…", async () => setJob(await ai<Job>("job", { input: jobText })))}>Analyze</button>
          {job && (
            <div className="card">
              <h3>{job.title} — {job.company}</h3>
              <p>{job.summary}</p>
              <ul>{job.requirements.map((r) => <li key={r}>{r}</li>)}</ul>
              <p>{job.keywords.map((k) => <span key={k} className="chip">{k}</span>)}</p>
              <label>Anything to emphasize? (optional)
                <textarea rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
              </label>
              <button className="primary" disabled={!profile.experiences.length} onClick={generate}>Generate resume + cover letter</button>
              {!profile.experiences.length && <p className="muted">Add experience to your profile first.</p>}
            </div>
          )}
        </section>
      )}

      {tab === "Documents" && (!entry || !cur ? <section><p>No document open. Generate one from the Job tab or open one from the Library.</p></section> : (
        <section>
          <h2>{entry.job.title} — {entry.job.company}</h2>
          <div className="row">
            <button className={docKey === "resume" ? "on" : ""} onClick={() => { setDocKey("resume"); setDraft(null); }}>Resume</button>
            <button className={docKey === "coverLetter" ? "on" : ""} onClick={() => { setDocKey("coverLetter"); setDraft(null); }}>Cover letter</button>
            <select value={style.template} onChange={(e) => setStyle({ ...style, template: e.target.value })}>
              <option value="classic">Classic</option><option value="modern">Modern</option><option value="compact">Compact</option>
            </select>
            <select value={style.size} onChange={(e) => setStyle({ ...style, size: +e.target.value })}>
              {[10, 11, 12].map((s) => <option key={s} value={s}>{s}pt</option>)}
            </select>
          </div>
          <div className="row">
            <input placeholder="Describe a change, e.g. 'emphasize leadership'" value={instruction} onChange={(e) => setInstruction(e.target.value)} />
            <Mic onText={(t) => setInstruction((p) => (p ? p + " " : "") + t)} />
            <button className="primary" disabled={!instruction.trim()} onClick={() => edit(instruction)}>Apply</button>
          </div>
          <div className="row">{QUICK.map((q) => <button key={q} onClick={() => edit(q)}>{q}</button>)}</div>
          <div className="row">
            {draft === null ? <button onClick={() => setDraft(cur[docKey])}>Edit text directly</button> : <>
              <button className="primary" onClick={() => { addVersion({ ...cur, [docKey]: draft, note: "Manual edit", at: new Date().toISOString() }); setDraft(null); }}>Save edit</button>
              <button onClick={() => setDraft(null)}>Cancel</button></>}
            <button onClick={() => window.print()}>Print / Save as PDF</button>
            <button onClick={() => download(`${fileName}.md`, cur[docKey])}>Download .md</button>
            <button onClick={() => navigator.clipboard.writeText(cur[docKey])}>Copy</button>
          </div>
          {draft !== null
            ? <textarea className="draft" rows={30} value={draft} onChange={(e) => setDraft(e.target.value)} />
            : <article className={`doc t-${style.template}`} style={{ fontSize: `${style.size}pt` }}><Md text={cur[docKey]} /></article>}
          <details>
            <summary>Version history ({entry.versions.length})</summary>
            {entry.versions.map((v, i) => (
              <div key={i} className="row">
                <span>{i + 1}. {v.note} · {new Date(v.at).toLocaleString()}</span>
                {i < entry.versions.length - 1 && <button onClick={() => addVersion({ ...v, note: `Restored version ${i + 1}`, at: new Date().toISOString() })}>Restore</button>}
              </div>
            ))}
          </details>
        </section>
      ))}

      {tab === "Library" && (
        <section>
          <h2>Library</h2>
          {!library.length && <p className="muted">Nothing saved yet.</p>}
          {library.map((e) => (
            <div key={e.id} className="card row">
              <span><strong>{e.job.title}</strong> — {e.job.company} · {new Date(e.versions[0].at).toLocaleDateString()} · {e.versions.length} version(s)</span>
              <button onClick={() => { setOpenId(e.id); setTab("Documents"); }}>Open</button>
              <button onClick={() => confirm("Delete this entry?") && setLibrary(library.filter((x) => x.id !== e.id))}>Delete</button>
            </div>
          ))}
        </section>
      )}
    </main>
  );
}
