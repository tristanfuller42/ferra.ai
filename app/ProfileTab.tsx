"use client";
import { useState } from "react";
import { ai, download, Mic, toBase64, useLocal, emptyProfile } from "@/lib/client";
import type { Profile } from "@/lib/schema";

type Run = (label: string, fn: () => Promise<void>) => Promise<void>;
type Sugg = { title: string; question: string };

function Field({ label, value, set, area }: { label: string; value: string; set: (v: string) => void; area?: boolean }) {
  return (
    <label>{label}
      {area ? <textarea value={value} onChange={(e) => set(e.target.value)} rows={4} />
            : <input value={value} onChange={(e) => set(e.target.value)} />}
    </label>
  );
}
const lines = (v: string) => v.split("\n").map((s) => s.trim()).filter(Boolean);

export default function ProfileTab({ profile, setProfile, sample, setSample, run }: {
  profile: Profile; setProfile: (p: Profile) => void; sample: string; setSample: (s: string) => void; run: Run;
}) {
  const [input, setInput] = useState("");
  const [suggs, setSuggs] = useState<Sugg[]>([]);
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [rejected, setRejected] = useLocal<string[]>("rejected", []);
  const set = (patch: Partial<Profile>) => setProfile({ ...profile, ...patch });

  const merge = (input: string, file?: object) => ai<Profile>("extract", { profile, input, file });

  const upload = (files: FileList) => run(`Reading ${files.length} file(s)…`, async () => {
    let p = profile;
    for (const f of Array.from(files)) {
      const file = { name: f.name, mime: f.type, data: await toBase64(f) };
      p = await ai<Profile>("extract", { profile: p, input: "", file });
      setProfile(p);
    }
  });

  const importJson = async (f: File) => {
    try { setProfile({ ...emptyProfile, ...JSON.parse(await f.text()) }); }
    catch { alert("That file is not a valid profile JSON."); }
  };

  const apply = (i: number) => run("Updating profile…", async () => {
    const s = suggs[i];
    setProfile(await merge(`About "${s.title}" — Q: ${s.question}\nA: ${answers[i]}`));
    setSuggs(suggs.filter((_, j) => j !== i));
  });
  const dismiss = (i: number) => {
    setRejected([...rejected, suggs[i].title]);
    setSuggs(suggs.filter((_, j) => j !== i));
  };

  const exp = profile.experiences, edu = profile.education;
  return (
    <>
      <section>
        <h2>Add information</h2>
        <p className="muted">Upload old resumes (PDF, DOCX, TXT), or type or speak anything: jobs, projects, things you are proud of. AI merges it into your profile and removes duplicates.</p>
        <div className="row">
          <label className="button">Upload files<input type="file" multiple hidden accept=".pdf,.docx,.txt,.md"
            onChange={(e) => e.target.files?.length && upload(e.target.files)} /></label>
          <button onClick={() => download("profile.json", JSON.stringify(profile, null, 2), "application/json")}>Export profile</button>
          <label className="button">Import profile<input type="file" hidden accept=".json"
            onChange={(e) => e.target.files?.[0] && importJson(e.target.files[0])} /></label>
          <button onClick={() => confirm("Delete the whole profile?") && setProfile(emptyProfile)}>Clear</button>
        </div>
        <textarea rows={5} value={input} onChange={(e) => setInput(e.target.value)}
          placeholder="e.g. 2021–2024 I was a shift lead at Joe's Coffee. Trained 8 new hires, cut waste 15%…" />
        <div className="row">
          <Mic onText={(t) => setInput((p) => (p ? p + " " : "") + t)} />
          <button className="primary" disabled={!input.trim()}
            onClick={() => run("Adding to profile…", async () => { setProfile(await merge(input)); setInput(""); })}>Add to profile</button>
          <button onClick={() => run("Reviewing profile…", async () => {
            setSuggs((await ai<{ suggestions: Sugg[] }>("gaps", { profile, rejected })).suggestions); setAnswers({});
          })}>Suggest improvements</button>
        </div>
        {suggs.map((s, i) => (
          <div key={s.title} className="card">
            <strong>{s.title}</strong><p>{s.question}</p>
            <div className="row">
              <input placeholder="Your answer" value={answers[i] ?? ""} onChange={(e) => setAnswers({ ...answers, [i]: e.target.value })} />
              <button disabled={!answers[i]} onClick={() => apply(i)}>Apply</button>
              <button onClick={() => dismiss(i)}>Dismiss</button>
            </div>
          </div>
        ))}
      </section>

      <section>
        <h2>Profile</h2>
        <div className="grid">
          {(["name", "email", "phone", "location"] as const).map((k) =>
            <Field key={k} label={k[0].toUpperCase() + k.slice(1)} value={profile[k]} set={(v) => set({ [k]: v })} />)}
        </div>
        <Field label="Links (one per line)" area value={profile.links.join("\n")} set={(v) => set({ links: lines(v) })} />
        <Field label="Summary" area value={profile.summary} set={(v) => set({ summary: v })} />

        <h3>Experience</h3>
        {exp.map((x, i) => {
          const upd = (patch: Partial<typeof x>) => set({ experiences: exp.map((y, j) => (j === i ? { ...y, ...patch } : y)) });
          return (
            <div key={x.id} className="card">
              <div className="grid">
                <Field label="Title" value={x.title} set={(title) => upd({ title })} />
                <Field label="Company" value={x.company} set={(company) => upd({ company })} />
                <Field label="Dates" value={x.dates} set={(dates) => upd({ dates })} />
                <Field label="Location" value={x.location} set={(location) => upd({ location })} />
              </div>
              <Field label="Bullets (one per line)" area value={x.bullets.join("\n")} set={(v) => upd({ bullets: v.split("\n") })} />
              <button onClick={() => set({ experiences: exp.filter((_, j) => j !== i) })}>Remove</button>
            </div>
          );
        })}
        <button onClick={() => set({ experiences: [...exp, { id: crypto.randomUUID(), title: "", company: "", location: "", dates: "", bullets: [] }] })}>+ Experience</button>

        <h3>Education</h3>
        {edu.map((x, i) => {
          const upd = (patch: Partial<typeof x>) => set({ education: edu.map((y, j) => (j === i ? { ...y, ...patch } : y)) });
          return (
            <div key={x.id} className="card grid">
              <Field label="School" value={x.school} set={(school) => upd({ school })} />
              <Field label="Degree" value={x.degree} set={(degree) => upd({ degree })} />
              <Field label="Dates" value={x.dates} set={(dates) => upd({ dates })} />
              <button onClick={() => set({ education: edu.filter((_, j) => j !== i) })}>Remove</button>
            </div>
          );
        })}
        <button onClick={() => set({ education: [...edu, { id: crypto.randomUUID(), school: "", degree: "", dates: "" }] })}>+ Education</button>

        <h3>Skills and extras</h3>
        <Field label="Skills (one per line)" area value={profile.skills.join("\n")} set={(v) => set({ skills: v.split("\n") })} />
        <Field label="Certifications, awards, projects, volunteering (one per line)" area value={profile.extras.join("\n")} set={(v) => set({ extras: v.split("\n") })} />
        <Field label="Writing sample (optional): paste something you wrote so cover letters sound like you" area value={sample} set={setSample} />
      </section>
    </>
  );
}
