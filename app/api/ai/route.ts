import Anthropic from "@anthropic-ai/sdk";
import { betaZodOutputFormat } from "@anthropic-ai/sdk/helpers/beta/zod";
import mammoth from "mammoth";
import { z } from "zod";
import { Doc, Docs, JobAnalysis, Profile, Suggestions } from "@/lib/schema";

export const maxDuration = 300;
const client = new Anthropic();
type Block = Anthropic.Beta.BetaContentBlockParam;

const RULES = `Use only facts present in the provided data. Never invent employers, dates, degrees, numbers, tools or skills.
Write in plain, specific, natural language. Avoid clichés and keyword stuffing.`;

async function ask<T extends z.ZodType>(schema: T, system: string, content: Block[]): Promise<z.infer<T>> {
  const res = await client.beta.messages.parse({
    model: "claude-opus-5-5",
    max_tokens: 16000,
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    output_config: { effort: "medium", format: betaZodOutputFormat(schema) },
    system: `${system}\n\n${RULES}`,
    messages: [{ role: "user", content }],
  });
  if (res.stop_reason === "refusal") throw new Error("The AI declined this request.");
  if (!res.parsed_output) throw new Error("The AI response was incomplete. Try again.");
  return res.parsed_output as z.infer<T>;
}

const text = (t: string): Block => ({ type: "text", text: t });
const json = (label: string, v: unknown) => text(`${label}:\n${JSON.stringify(v)}`);

async function fileBlock(f: { name: string; mime: string; data: string }): Promise<Block> {
  if (f.mime === "application/pdf")
    return { type: "document", source: { type: "base64", media_type: "application/pdf", data: f.data } };
  const buf = Buffer.from(f.data, "base64");
  if (f.name.toLowerCase().endsWith(".docx")) return text((await mammoth.extractRawText({ buffer: buf })).value);
  return text(buf.toString("utf8"));
}

async function fetchPage(url: string) {
  if (!/^https?:\/\//i.test(url)) throw new Error("Enter a full http(s) URL.");
  const r = await fetch(url, { headers: { "user-agent": "Mozilla/5.0" }, signal: AbortSignal.timeout(15000) });
  if (!r.ok) throw new Error(`That page returned HTTP ${r.status}. Paste the job text instead.`);
  return (await r.text())
    .replace(/<(script|style|noscript|svg)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ").replace(/&amp;/g, "&")
    .replace(/\s+/g, " ").trim();
}

/* eslint-disable @typescript-eslint/no-explicit-any */
const tasks: Record<string, (i: any) => Promise<unknown>> = {
  // Merge a document, free text, voice transcript or an instruction into the profile.
  async extract({ profile, input, file }) {
    const blocks = file ? [await fileBlock(file)] : [];
    return ask(Profile,
      `You maintain a person's master career profile. Merge the new material into the current profile and return the complete updated profile.
- Merge duplicate jobs (same employer and role) into one entry; combine bullets without repeating them; keep the most complete dates.
- Deduplicate skills. Order experiences and education newest first.
- Keep existing ids. Give new entries short unique ids.
- Keep everything already in the profile unless the new material says to change or remove it.
- Rewrite rough notes into concise, achievement-focused bullets, keeping the person's facts and numbers.`,
      [...blocks, json("Current profile", profile), text(`New material:\n${input || "(the document above)"}`)]);
  },

  async gaps({ profile, rejected }) {
    return ask(Suggestions,
      `Review this master profile. Suggest up to 6 specific improvements, such as bullets without results or numbers, missing dates, vague wording, or skills implied by the experience but not listed.
Each suggestion has a short title and one direct question the person can answer to fix it.
Do not repeat any of these previously dismissed suggestions: ${JSON.stringify(rejected ?? [])}`,
      [json("Profile", profile)]);
  },

  async job({ url, input }) {
    const description = url ? await fetchPage(url) : String(input ?? "");
    if (description.length < 50) throw new Error("Not enough job text found. Paste the job description instead.");
    const analysis = await ask(JobAnalysis,
      "Analyze this job posting. Extract the title, company, a 2-3 sentence summary, the key requirements, and the keywords an applicant tracking system would scan for. Ignore page navigation and unrelated text.",
      [text(description)]);
    return { ...analysis, description };
  },

  async generate({ profile, job, notes, sample }) {
    return ask(Docs,
      `Write a resume and a cover letter in Markdown, tailored to the job.
Resume: "# Name", one contact line, then "## Section" headings, "### Title — Company · Dates" for each role, and "- " bullets. Single column, no tables, so applicant tracking systems parse it. Aim for one page. Put the most relevant experience and skills first and use the job's keywords where they truthfully apply.
Cover letter: 250-350 words, addressed to the company, specific to the role, no placeholders like [Hiring Manager].
${sample ? "Match the voice and rhythm of the person's writing sample in the cover letter." : ""}`,
      [json("Profile", profile), json("Job", job), text(`Applicant notes: ${notes || "none"}${sample ? `\n\nWriting sample:\n${sample}` : ""}`)]);
  },

  async edit({ doc, instruction, profile, job }) {
    return ask(Doc,
      "Revise this Markdown document according to the instruction. Keep the same Markdown structure. Return the full revised document.",
      [json("Profile (source of truth)", profile), json("Job", job), text(`Document:\n${doc}`), text(`Instruction: ${instruction}`)]);
  },
};

export async function POST(req: Request) {
  const pw = process.env.APP_PASSWORD;
  if (pw && req.headers.get("x-app-password") !== pw)
    return Response.json({ error: "Wrong or missing app password." }, { status: 401 });
  if (!process.env.ANTHROPIC_API_KEY)
    return Response.json({ error: "The server has no ANTHROPIC_API_KEY set. See README." }, { status: 500 });
  const { task, ...input } = await req.json();
  if (!tasks[task]) return Response.json({ error: "Unknown task." }, { status: 400 });
  try {
    return Response.json(await tasks[task](input));
  } catch (e) {
    const error =
      e instanceof Anthropic.AuthenticationError ? "The server's ANTHROPIC_API_KEY is missing or invalid." :
      e instanceof Anthropic.RateLimitError ? "Rate limited. Wait a minute and try again." :
      e instanceof Error ? e.message : "Unknown error.";
    return Response.json({ error }, { status: 500 });
  }
}
