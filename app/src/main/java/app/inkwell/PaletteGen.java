package app.inkwell;

import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Palette ideas from keywords (like Adobe Color's explore, but offline): each word suggests hues and a look
 * (saturation and brightness); "pastel", "dark", "neon", "muted"... adjust the look; unknown words still give
 * their own repeatable palettes. Each result uses a colour rule: analogous, complementary, triadic, and so on.
 */
public final class PaletteGen {
    private PaletteGen() { }

    /** What a word means for colour: hues (degrees), saturation range, brightness range. */
    private static final class Mood {
        final float[] hues; final float s0, s1, b0, b1;
        Mood(float[] h, float s0, float s1, float b0, float b1) { hues = h; this.s0 = s0; this.s1 = s1; this.b0 = b0; this.b1 = b1; }
    }

    private static final java.util.HashMap<String, Mood> WORDS = new java.util.HashMap<>();
    private static void w(String words, float[] hues, float s0, float s1, float b0, float b1) {
        for (String k : words.split(",")) WORDS.put(k.trim(), new Mood(hues, s0, s1, b0, b1));
    }
    private static float[] h(float... v) { return v; }

    static {
        w("ocean,sea,marine,wave,waves,underwater", h(195, 210, 180, 170), .45f, .9f, .35f, .9f);
        w("beach,coast,shore,tropical,island", h(185, 45, 30, 170), .35f, .8f, .7f, .98f);
        w("sky,air,cloud,clouds", h(205, 215, 195), .2f, .6f, .75f, 1f);
        w("sunset,dusk,evening", h(15, 330, 280, 35), .55f, .95f, .55f, .98f);
        w("sunrise,dawn,morning", h(30, 45, 340, 200), .35f, .8f, .8f, 1f);
        w("forest,woods,jungle,leaf,leaves,moss", h(110, 90, 140, 30), .35f, .8f, .25f, .75f);
        w("nature,garden,plant,plants,green,grass", h(95, 120, 60, 45), .4f, .8f, .35f, .85f);
        w("flower,flowers,floral,bloom,blossom", h(330, 300, 50, 110), .35f, .8f, .7f, .98f);
        w("spring", h(100, 330, 55, 190), .3f, .65f, .8f, 1f);
        w("summer", h(45, 190, 10, 330), .6f, .95f, .8f, 1f);
        w("autumn,fall,harvest,pumpkin", h(25, 15, 40, 0), .55f, .9f, .4f, .9f);
        w("winter,snow,frost,ice,icy,cold", h(200, 210, 190, 230), .08f, .4f, .75f, 1f);
        w("desert,sand,dune,canyon", h(30, 25, 40, 15), .3f, .65f, .55f, .95f);
        w("earth,earthy,soil,clay,terracotta,rust", h(20, 30, 15, 40), .4f, .75f, .3f, .75f);
        w("stone,rock,concrete,slate,gravel", h(210, 30, 0), .03f, .18f, .3f, .8f);
        w("mountain,mountains,alpine", h(210, 120, 30), .2f, .55f, .3f, .9f);
        w("coffee,chocolate,cocoa,wood,wooden", h(25, 20, 30), .4f, .75f, .15f, .7f);
        w("wine,berry,berries,plum", h(340, 320, 350, 280), .5f, .85f, .2f, .65f);
        w("rose,roses,romantic,love,blush", h(345, 330, 0, 15), .3f, .75f, .6f, .98f);
        w("lavender,lilac,violet,purple", h(270, 285, 260, 300), .25f, .6f, .6f, .95f);
        w("mint,fresh", h(150, 160, 170, 50), .25f, .55f, .75f, 1f);
        w("lemon,citrus,lime,sunny,sunshine", h(55, 50, 80, 30), .55f, .95f, .85f, 1f);
        w("cherry,strawberry,red,blood", h(355, 0, 345), .6f, .95f, .4f, .9f);
        w("orange,tangerine,peach,apricot", h(25, 30, 15), .45f, .9f, .75f, 1f);
        w("fire,flame,lava,volcano,hot", h(10, 25, 0, 45), .75f, 1f, .5f, 1f);
        w("night,midnight,dark sky,moon,moonlight", h(230, 250, 220, 270), .3f, .7f, .1f, .5f);
        w("galaxy,space,cosmic,nebula,star,stars,universe", h(260, 280, 300, 220), .5f, .9f, .15f, .8f);
        w("neon,cyber,cyberpunk,synthwave,rave,electric", h(300, 180, 280, 60), .8f, 1f, .85f, 1f);
        w("retro,vintage,old,antique,sepia", h(30, 15, 45, 180), .25f, .55f, .5f, .9f);
        w("candy,sweet,bubblegum,cute,kawaii", h(330, 190, 50, 280), .3f, .65f, .85f, 1f);
        w("gold,golden,luxury,royal,regal", h(45, 40, 270, 0), .5f, .85f, .45f, .95f);
        w("silver,metal,metallic,steel,chrome", h(210, 200), .02f, .12f, .45f, .95f);
        w("halloween,spooky,witch", h(25, 275, 90), .6f, .95f, .2f, .9f);
        w("christmas,xmas,holiday", h(0, 130, 45), .6f, .9f, .35f, .9f);
        w("rain,rainy,storm,stormy,fog,foggy,mist,smoke", h(210, 220, 200), .08f, .35f, .3f, .8f);
        w("skin,portrait,face,flesh", h(25, 20, 30, 15), .25f, .6f, .35f, .97f);
        w("baby,nursery,soft", h(200, 330, 55, 150), .15f, .35f, .9f, 1f);
        w("happy,joy,fun,playful,cheerful", h(50, 190, 330, 100), .55f, .9f, .85f, 1f);
        w("sad,melancholy,lonely,blue mood", h(215, 230, 200), .2f, .5f, .3f, .7f);
        w("calm,peaceful,serene,zen,relax", h(180, 160, 200, 40), .15f, .45f, .65f, .95f);
        w("energy,energetic,bold,loud,vibrant", h(0, 50, 200, 300), .8f, 1f, .75f, 1f);
        w("moody,mysterious,gothic,noir,gloomy", h(260, 340, 210), .25f, .6f, .1f, .5f);
        w("blue", h(215, 205, 225), .5f, .9f, .4f, .95f);
        w("yellow", h(52, 48), .6f, .95f, .85f, 1f);
        w("pink", h(330, 340), .35f, .75f, .8f, 1f);
        w("brown", h(25, 30), .45f, .75f, .25f, .6f);
        w("teal,turquoise,aqua,cyan", h(180, 175, 190), .45f, .85f, .5f, .9f);
        w("grey,gray,monochrome,neutral", h(0), 0f, .06f, .1f, .95f);
        w("japan,japanese,sakura", h(350, 0, 200, 45), .3f, .75f, .5f, .97f);
        w("ink,sketch,comic,manga", h(220, 0), .0f, .25f, .05f, .95f);
    }

    /** A look modifier: scales saturation and brightness ranges. */
    private static float[] modifier(String w) {
        switch (w) {
            case "pastel": case "light": case "pale": return new float[]{.35f, .9f, 1f};      // satScale, bMin, bMax
            case "dark": case "deep": return new float[]{1f, .1f, .5f};
            case "neon": case "bright": case "vivid": case "saturated": return new float[]{1.6f, .85f, 1f};
            case "muted": case "dusty": case "faded": case "soft": return new float[]{.5f, .45f, .85f};
            case "warm": return new float[]{-1f, -1f, -1f}; // handled as a hue pull
            case "cool": case "cold": return new float[]{-2f, -1f, -1f};
            default: return null;
        }
    }

    public static final String[] RULES = {"Analogous", "Complementary", "Triadic", "Split complementary", "Monochrome", "Tetradic"};

    /** count palettes of 5 colours for the words; seed picks the variation (Shuffle adds 1). */
    public static List<int[]> generate(String query, int count, int seed, List<String> ruleNames) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        ArrayList<Float> hues = new ArrayList<>();
        float s0 = .35f, s1 = .85f, b0 = .4f, b1 = .95f;
        float satK = 1, bLo = -1, bHi = -1;
        int pull = 0; // +1 warm, -1 cool
        int found = 0;
        // two-word phrases first ("dark sky", "blue mood")
        String[] ws = q.isEmpty() ? new String[0] : q.split("[^a-z]+");
        for (int i = 0; i < ws.length; i++) {
            if (ws[i].isEmpty()) continue;
            Mood m = i + 1 < ws.length ? WORDS.get(ws[i] + " " + ws[i + 1]) : null;
            if (m != null) i++;
            else m = WORDS.get(ws[i]);
            float[] mod = modifier(ws[i]);
            if (mod != null && m == null) {
                if (mod[0] == -1f) pull = 1; else if (mod[0] == -2f) pull = -1;
                else { satK = mod[0]; bLo = mod[1]; bHi = mod[2]; }
                continue;
            }
            if (m == null) { // unknown word: its own repeatable hue and look
                int hsh = ws[i].hashCode();
                m = new Mood(h(Math.floorMod(hsh, 360)), .35f + Math.floorMod(hsh >> 9, 40) / 100f, .8f, .4f, .95f);
            }
            for (float hh : m.hues) hues.add(hh);
            if (found == 0) { s0 = m.s0; s1 = m.s1; b0 = m.b0; b1 = m.b1; }
            else { s0 = (s0 + m.s0) / 2; s1 = (s1 + m.s1) / 2; b0 = (b0 + m.b0) / 2; b1 = (b1 + m.b1) / 2; }
            found++;
        }
        Random r = new Random(q.hashCode() * 31L + seed * 7919L);
        if (hues.isEmpty()) for (int i = 0; i < 4; i++) hues.add(r.nextFloat() * 360); // nothing typed: anything goes
        if (pull != 0) for (int i = 0; i < hues.size(); i++) hues.set(i, pullHue(hues.get(i), pull > 0 ? 30 : 210, .45f));
        s0 = Math.min(1, s0 * satK); s1 = Math.min(1, s1 * satK);
        if (bLo >= 0) { b0 = bLo; b1 = bHi; }
        ArrayList<int[]> out = new ArrayList<>();
        for (int p = 0; p < count; p++) {
            int rule = (p + seed) % RULES.length;
            if (ruleNames != null) ruleNames.add(RULES[rule]);
            float base = hues.get(r.nextInt(hues.size())) + (r.nextFloat() - 0.5f) * 16;
            float[] hs = new float[5];
            for (int k = 0; k < 5; k++) {
                float off;
                switch (rule) {
                    case 0: off = (k - 2) * (12 + r.nextFloat() * 10); break;                 // neighbours
                    case 1: off = k % 2 == 0 ? (r.nextFloat() - .5f) * 14 : 180 + (r.nextFloat() - .5f) * 14; break;
                    case 2: off = (k % 3) * 120 + (r.nextFloat() - .5f) * 10; break;
                    case 3: off = new float[]{0, 150, 210, 0, 150}[k] + (r.nextFloat() - .5f) * 10; break;
                    case 4: off = (r.nextFloat() - .5f) * 8; break;                            // one hue, many tones
                    default: off = (k % 4) * 90 + (r.nextFloat() - .5f) * 10;
                }
                float hue = ((base + off) % 360 + 360) % 360;
                if (found > 0 && rule != 4) { // keep palettes on theme: pull each colour toward the nearest hue the words suggest
                    float best = hues.get(0), bd = 999;
                    for (float hh : hues) { float d = Math.abs(((hh - hue + 540) % 360) - 180); if (d < bd) { bd = d; best = hh; } }
                    hue = pullHue(hue, best, 0.7f);
                }
                hs[k] = hue;
            }
            int[] cs = new int[5];
            for (int k = 0; k < 5; k++) {
                float t = k / 4f; // from darker to lighter across the palette, with some play
                float s = s0 + (s1 - s0) * r.nextFloat();
                float b = b0 + (b1 - b0) * (rule == 4 ? t : 0.25f + 0.75f * r.nextFloat());
                cs[k] = Color.HSVToColor(new float[]{hs[k], Math.max(0, Math.min(1, s)), Math.max(0.04f, Math.min(1, b))});
            }
            sortByBrightness(cs);
            out.add(cs);
        }
        return out;
    }

    private static float pullHue(float h, float toward, float k) {
        float d = ((toward - h + 540) % 360) - 180;
        return ((h + d * k) % 360 + 360) % 360;
    }

    private static void sortByBrightness(int[] cs) {
        Integer[] b = new Integer[cs.length];
        for (int i = 0; i < cs.length; i++) b[i] = cs[i];
        java.util.Arrays.sort(b, (x, y) -> Integer.compare(Fx.lum(x), Fx.lum(y)));
        for (int i = 0; i < cs.length; i++) cs[i] = b[i];
    }

    /** Words the generator knows (for hints). */
    public static int knownWords() { return WORDS.size(); }
}
