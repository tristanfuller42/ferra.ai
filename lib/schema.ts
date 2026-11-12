import { z } from "zod";

const s = z.string();
export const Profile = z.object({
  name: s, email: s, phone: s, location: s,
  links: z.array(s),
  summary: s,
  experiences: z.array(z.object({ id: s, title: s, company: s, location: s, dates: s, bullets: z.array(s) })),
  education: z.array(z.object({ id: s, school: s, degree: s, dates: s })),
  skills: z.array(s),
  extras: z.array(s), // certifications, awards, projects, volunteering
});
export const JobAnalysis = z.object({ title: s, company: s, summary: s, requirements: z.array(s), keywords: z.array(s) });
export const Docs = z.object({ resume: s, coverLetter: s });
export const Doc = z.object({ doc: s });
export const Suggestions = z.object({ suggestions: z.array(z.object({ title: s, question: s })) });

export type Profile = z.infer<typeof Profile>;
export type Job = z.infer<typeof JobAnalysis> & { description: string };
export type Version = z.infer<typeof Docs> & { note: string; at: string };
export type Entry = { id: string; job: Job; versions: Version[] };

