import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The planet's name, made up from the world's seed: two or three syllables put together the
 * way English words are, spelled the way an English reader would say them, and never a real
 * word (nothing in the dictionary) or a well-known name from elsewhere. It comes with how it
 * is said, as phonemes in the voice's own alphabet (eSpeak's IPA, stress marked with ˈ), so
 * the message can speak it without having to work out how to read it.
 */
public final class PlanetName {

    public static final String DICTIONARY = "assets/text/words.txt.gz";

    public final String spelling;
    public final String phonemes;

    private PlanetName(String spelling, String phonemes) {
        this.spelling = spelling;
        this.phonemes = phonemes;
    }

    @Override
    public String toString() {
        return spelling + " /" + phonemes + "/";
    }

    // ------------------------------------------------------------------ the pieces

    /** A piece of a name: how it's said, how it's spelled, how often it comes up. */
    private record Piece(String ipa, String spell, float weight) {
    }

    private static Piece p(String ipa, String spell, float weight) {
        return new Piece(ipa, spell, weight);
    }

    // Consonants a stressed syllable may start with (the name's first, or after a syllable)
    private static final Piece[] ONSETS = {
            p("b", "b", 3), p("d", "d", 3), p("f", "f", 2), p("ɡ", "g", 2), p("h", "h", 1.5f), p("k", "k", 4),
            p("l", "l", 3), p("m", "m", 3), p("n", "n", 2.5f), p("p", "p", 2), p("ɹ", "r", 2.5f), p("s", "s", 3),
            p("t", "t", 3), p("v", "v", 3), p("z", "z", 3), p("ʃ", "sh", 1.5f), p("θ", "th", 1.5f), p("dʒ", "j", 1.5f),
            p("tʃ", "ch", 1), p("w", "w", 1), p("j", "y", 0.8f),
            p("bɹ", "br", 1), p("dɹ", "dr", 1), p("ɡɹ", "gr", 1), p("kɹ", "kr", 1), p("pɹ", "pr", 0.8f), p("tɹ", "tr", 1),
            p("fɹ", "fr", 0.6f), p("θɹ", "thr", 0.5f), p("bl", "bl", 0.6f), p("kl", "kl", 0.8f), p("ɡl", "gl", 0.6f),
            p("pl", "pl", 0.5f), p("fl", "fl", 0.5f), p("sl", "sl", 0.5f), p("sk", "sk", 0.8f), p("sp", "sp", 0.5f),
            p("st", "st", 0.8f), p("sm", "sm", 0.3f), p("sn", "sn", 0.3f), p("sw", "sw", 0.4f), p("kw", "qu", 0.8f),
            p("tw", "tw", 0.3f), p("stɹ", "str", 0.5f), p("skɹ", "scr", 0.3f)
    };
    // Consonants starting a later, unstressed syllable
    private static final Piece[] INNER_ONSETS = {
            p("b", "b", 2), p("d", "d", 3), p("f", "f", 1), p("ɡ", "g", 1), p("k", "k", 3), p("l", "l", 4), p("m", "m", 3),
            p("n", "n", 4), p("p", "p", 1), p("ɹ", "r", 4), p("s", "s", 2), p("t", "t", 3), p("v", "v", 3), p("z", "z", 2),
            p("θ", "th", 1), p("ʃ", "sh", 0.8f), p("dʒ", "j", 0.6f)
    };

    // A stressed vowel: short ones need a consonant after them to be read short
    private record Vowel(String ipa, String closedSpell, String openSpell, String codas, float weight, boolean front, boolean afterW) {
    }

    private static final Vowel[] VOWELS = {
            new Vowel("a", "a", null, "n m s k t d b p ŋ nd nt ŋk st sk ks θ ʃ ɡ", 4, false, false),
            new Vowel("ɛ", "e", null, "n m l s k t d b p nd nt lk ld lt st sk ks θ ʃ ɡ", 4, true, true),
            new Vowel("ɪ", "i", null, "n m l s k t d b p nd nt ŋk lk lt st sk ks θ ʃ ɡ", 3, true, true),
            new Vowel("ɒ", "o", null, "n m l s k t d b p nd nt lk lt st sk ks θ ʃ ɡ", 3, false, false),
            new Vowel("ʌ", "u", null, "n m s k t d nd nt ŋk sk", 2, false, false),
            new Vowel("eɪ", "ai", "ay", "n l d t m", 2, true, true),
            new Vowel("iː", "ee", "ee", "n m l k t d s", 2, true, true),
            new Vowel("uː", "oo", "oo", "n m l d t", 1.2f, false, false),
            new Vowel("ɔː", "or", "or", "n k t d m", 2, false, false),
            new Vowel("ɑː", "ar", "ar", "n k t d m θ", 2.5f, false, false),
            new Vowel("əʊ", "oa", null, "n k t d m l", 1, false, false),
            new Vowel("ɜː", "er", null, "n k t d s", 1, true, true)
    };

    // How each coda sound is spelled
    private static String codaSpell(String ipa) {
        return switch (ipa) {
            case "ŋ" -> "ng";
            case "ŋk" -> "nk";
            case "ks" -> "x";
            case "θ" -> "th";
            case "ʃ" -> "sh";
            case "ɡ" -> "g";
            default -> ipa;
        };
    }

    // The unstressed syllable a name ends with (after a consonant): how it's said and spelled
    private static final Piece[] ENDINGS = {
            p("ə", "a", 6), p("ən", "on", 2), p("ən", "an", 2), p("əs", "us", 1.5f),
            p("ɪks", "ix", 1), p("ɔː", "or", 1.5f), p("ɑː", "ar", 1), p("ɛk", "ek", 0.8f), p("aks", "ax", 0.8f),
            p("ɒθ", "oth", 0.6f), p("i", "i", 0.8f), p("ɪn", "in", 1), p("əl", "el", 0.8f)
    };
    // ... or an "-ia" ending, after one of these
    private static final Piece[] IA_ONSETS = { p("ɹ", "r", 3), p("l", "l", 2), p("n", "n", 2), p("v", "v", 1), p("d", "d", 1), p("b", "b", 0.6f), p("m", "m", 0.6f) };

    // ------------------------------------------------------------------ making one

    /** The name for a world, the same every time for the same seed. */
    public static PlanetName forSeed(long seed) {
        Set<String> dictionary = dictionary();
        long h = seed * 0x9E3779B97F4A7C15L + 0x7A11E7L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        Random rand = new Random(h ^ (h >>> 29));
        for (int attempt = 0; attempt < 500; attempt++) {
            PlanetName name = make(rand);
            if (name != null && acceptable(name.spelling, dictionary)) return name;
        }
        return new PlanetName("Veyra", "vˈeɪɹə");
    }

    private static PlanetName make(Random rand) {
        StringBuilder ipa = new StringBuilder(), spell = new StringBuilder();
        // The stressed syllable
        Vowel vowel = pickVowel(rand);
        Piece onset;
        do {
            onset = pick(ONSETS, rand);
        } while (!fits(onset, vowel));
        ipa.append(onset.ipa).append('ˈ').append(vowel.ipa);
        String coda = "";
        boolean open = vowel.closedSpell == null || (vowel.openSpell != null && rand.nextFloat() < 0.5f);
        if (open) {
            spell.append(onset.spell).append(vowel.openSpell);
        } else {
            String[] codas = vowel.codas.split(" ");
            coda = codas[rand.nextInt(codas.length)];
            ipa.append(coda);
            spell.append(onset.spell).append(vowel.closedSpell).append(codaSpell(coda));
        }
        // Then the ending, so the stress falls where an English reader would put it: on the
        // syllable before the last (or before "-ia")
        if (open && vowel.openSpell.endsWith("r")) coda = "r";
        // "oo" before an r is read as in "door", and before a k as in "look"
        if (open && vowel.ipa.equals("uː")) coda = "oo";
        if (rand.nextFloat() < 0.2f) {
            Piece inner = pick(IA_ONSETS, rand);
            if (!joins(coda, inner)) return null;
            ipa.append(inner.ipa).append("iə");
            spell.append(inner.spell).append("ia");
        } else {
            Piece inner = innerOnset(rand, coda);
            if (inner == null) return null;
            Piece ending = pick(ENDINGS, rand);
            // A g before e or i is read soft
            if (inner.spell.equals("g") && "ei".indexOf(ending.spell.charAt(0)) >= 0) return null;
            ipa.append(inner.ipa).append(ending.ipa);
            spell.append(inner.spell).append(ending.spell);
        }
        String word = spell.toString();
        if (word.length() < 4 || word.length() > 10) return null;
        // Not too many r's, nor the same letter three times
        if (word.chars().filter(c -> c == 'r').count() > 2) return null;
        for (char c = 'a'; c <= 'z'; c++) {
            final char letter = c;
            if (word.chars().filter(x -> x == letter).count() > 2) return null;
        }
        return new PlanetName(Character.toUpperCase(word.charAt(0)) + word.substring(1), ipa.toString());
    }

    /** Whether a syllable can start this way before this vowel, and still be read as meant. */
    private static boolean fits(Piece onset, Vowel vowel) {
        // A g before e, i or y is read soft
        if (onset.spell.startsWith("g") && !onset.spell.startsWith("gr") && !onset.spell.startsWith("gl") && vowel.front) return false;
        // "wa" and "qua" are read as in "water" and "quarter"
        if ((onset.spell.endsWith("w") || onset.spell.equals("qu")) && !vowel.afterW) return false;
        // "quay" is read "key"
        if (onset.spell.equals("qu") && vowel.ipa.equals("eɪ")) return false;
        // "ye" and "yi" look odd, and "yee" worse
        if (onset.spell.equals("y") && vowel.front) return false;
        return true;
    }

    /** A consonant to start a later syllable, that won't run into the last one's to make another sound. */
    private static Piece innerOnset(Random rand, String coda) {
        for (int tries = 0; tries < 20; tries++) {
            Piece inner = pick(INNER_ONSETS, rand);
            if (joins(coda, inner)) return inner;
        }
        return null;
    }

    private static boolean joins(String coda, Piece onset) {
        if (coda.isEmpty()) return true;
        // An "ar", "or" or "er" before an r is read as in "carry"
        if (coda.equals("r")) return !onset.ipa.startsWith("ɹ");
        if (coda.equals("oo")) return !onset.ipa.startsWith("ɹ") && !onset.ipa.startsWith("k");
        // Only the easy meetings, the kind English words have in their middles (n-d, l-v, s-t,
        // m-b, k-r...): a soft consonant before most others, a hard one only before an r
        String follows = switch (coda) {
            case "n" -> "d t z s v θ ʃ";
            case "m" -> "b p";
            case "l" -> "b d f k m n p s t v z θ ʃ";
            case "s" -> "t k p m n";
            case "k", "t", "d", "p", "b", "ɡ" -> "ɹ";
            default -> "";
        };
        for (String ok : follows.split(" ")) if (ok.equals(onset.ipa)) return true;
        return false;
    }

    private static Vowel pickVowel(Random rand) {
        float total = 0f;
        for (Vowel v : VOWELS) total += v.weight;
        float roll = rand.nextFloat() * total;
        for (Vowel v : VOWELS) {
            roll -= v.weight;
            if (roll <= 0f) return v;
        }
        return VOWELS[0];
    }

    private static Piece pick(Piece[] pieces, Random rand) {
        float total = 0f;
        for (Piece piece : pieces) total += piece.weight;
        float roll = rand.nextFloat() * total;
        for (Piece piece : pieces) {
            roll -= piece.weight;
            if (roll <= 0f) return piece;
        }
        return pieces[0];
    }

    // ------------------------------------------------------------------ keeping it made up

    // Names known from elsewhere (other stories' worlds, brands), kept clear of
    private static final Set<String> KNOWN = Set.of(
            "tatooine", "naboo", "hoth", "endor", "dagobah", "coruscant", "alderaan", "kashyyyk", "mustafar", "jakku",
            "vulcan", "romulus", "kronos", "bajor", "cardassia", "ferenginar", "arrakis", "caladan", "kaitain", "pandora",
            "krypton", "gallifrey", "skaro", "mondas", "telos", "cybertron", "solaris", "trantor", "terminus", "magrathea",
            "mongo", "eternia", "etheria", "namek", "zebes", "sakaar", "xandar", "asgard", "vormir", "titan", "thra",
            "lusitania", "hyperion", "reach", "harvest", "lv", "klendathu", "abydos", "chulak", "dakara", "melmac",
            "ork", "zeist", "tralfamadore", "solaria", "aurora", "kobol", "caprica", "gemenon", "picon", "tauron",
            "rakata", "korriban", "dantooine", "kamino", "geonosis", "utapau", "mandalore", "lothal", "yavin", "bespin",
            "kessel", "corellia", "ryloth", "rodia", "trandosha", "dathomir", "exegol", "scarif", "jedha", "crait",
            "nintendo", "sega", "lego", "nike", "adidas", "kodak", "xerox", "rolex", "pepsi", "fanta", "sprite",
            "tesla", "lexus", "toyota", "honda", "nokia", "samsung", "sony", "intel", "zara", "ikea", "oreo", "spotify");
    // Not to be spelled out anywhere in a name
    private static final String[] RUDE = { "fuk", "fuc", "shit", "cunt", "kunt", "dick", "cock", "kok", "piss", "tit", "nig", "fag", "wank",
            "slut", "twat", "porn", "rape", "anal", "anus", "arse", "cum", "jizz", "bum", "poo", "nazi", "kkk", "sex", "pube", "smeg" };

    private static boolean acceptable(String spelling, Set<String> dictionary) {
        String lower = spelling.toLowerCase();
        if (dictionary.contains(lower) || KNOWN.contains(lower)) return false;
        // Nor just a real word with an "s" or "a" on the end
        if (lower.length() > 4 && (dictionary.contains(lower.substring(0, lower.length() - 1)))) return false;
        for (String rude : RUDE) if (lower.contains(rude)) return false;
        return true;
    }

    private static volatile Set<String> words;

    /** The dictionary's words (public-domain ENABLE list), loaded once; empty if it can't be read. */
    private static Set<String> dictionary() {
        Set<String> loaded = words;
        if (loaded != null) return loaded;
        Set<String> set = new HashSet<>(200_000);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(DICTIONARY)), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                String w = line.trim();
                if (!w.isEmpty()) set.add(w.toLowerCase());
            }
        } catch (Exception e) {
            System.err.println("Dictionary unavailable: " + e.getMessage());
        }
        words = set;
        return set;
    }

    /** Developer aid: prints the names of the first seeds, spelling then phonemes, a tab between. */
    public static void main(String[] args) {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        List<String> lines = new ArrayList<>();
        for (int s = 0; s < count; s++) {
            PlanetName name = forSeed(s);
            lines.add(name.spelling + "\t" + name.phonemes);
        }
        try {
            java.nio.file.Files.write(java.nio.file.Path.of(args.length > 1 ? args[1] : "planet_names.txt"), lines, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
