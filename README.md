# Resume Studio (ferra.ai)

Keep one master profile of your career. Paste a job, get a tailored resume and cover letter.

## Features
- **Profile:** upload old resumes (PDF, DOCX, TXT), or type/speak notes. AI merges everything and removes duplicate jobs. Edit any field by hand. Export/import as JSON.
- **Suggestions:** AI asks questions to improve weak spots. Dismissed suggestions are not repeated.
- **Job:** fetch a job posting URL or paste the text. AI pulls out requirements and keywords.
- **Documents:** generate a resume and cover letter. Edit by typing or speaking an instruction, use quick actions (one page, concise, tone), or edit the text directly. Every change is a saved version you can restore.
- **Export:** print / save as PDF (3 templates, 3 font sizes), download Markdown, copy.
- **Library:** all generated documents, saved in your browser.

The AI is told to use only facts from your profile. It never invents jobs, numbers or skills.

## Run locally
```bash
npm install
cp .env.example .env.local   # add your ANTHROPIC_API_KEY
npm run dev                  # http://localhost:3000
```

## Deploy
Works on Vercel or any Node host. Set `ANTHROPIC_API_KEY`. Set `APP_PASSWORD` too if the site is public, or anyone can spend your API credits.

## Notes
- Data is stored in your browser (localStorage). Use **Export profile** to back it up or move it to another device.
- Voice input uses the browser's speech recognition (Chrome, Edge, Safari). The button is hidden in browsers without it.
- Some job sites (LinkedIn, Indeed) block automatic fetching. Paste the text instead.

## Code
| File | Purpose |
|---|---|
| `app/api/ai/route.ts` | Server: all AI calls (extract, gaps, job, generate, edit). API key stays here. |
| `lib/schema.ts` | Data shapes, also used to force structured AI output. |
| `lib/client.tsx` | Browser helpers: storage, API call, mic, Markdown renderer. |
| `app/ProfileTab.tsx` | Profile tab. |
| `app/page.tsx` | Job, Documents and Library tabs. |
