import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a username will do (M58): 3 to 16 letters, digits, _ or -, and nothing that is, holds,
 * or sounds like swearing or a slur, however it's dressed up. Deliberately strict: a name with a
 * swear word inside it is turned down even where the word is innocent ("ILoveScunthorpe").
 *
 * The name is looked at three ways, each with its digits read as the letters they stand for
 * (1 as i and as l, 0 as o, 3 as e, 4 as a, 5 as s, 7 as t, 8 as b, 9 as g, 6 as g):
 *   spelling - reduced to a skeleton (ph and ck and c before a back vowel as their sounds, q as k,
 *              x as ks, y as i, runs of a letter as one), so "Phuuck", "fCuK" and "Sh1t" are
 *              their words; the worst words are refused anywhere inside it, a few only spelt
 *              with their double letters, and mild or short ones only as a word of their own
 *              ("BigAss", not "Assassin");
 *   sound    - the phonemes it would be said with (Pronunciation), coarsened (similar vowels as
 *              one, h and y-sounds dropped), so "fhukyoo" is heard for what it is.
 */
public final class NameCheck {

    public static final int SHORTEST = 3, LONGEST = 16;

    private NameCheck() {
    }

    // Refused anywhere in the name's skeleton
    private static final String[] ANYWHERE = {
        "fuck", "fuk", "fuq", "fcuk", "phuk", "shit", "cunt", "twunt", "cock", "dick", "twat", "wank", "bitch",
        "biatch", "bastard", "slut", "whore", "nigger", "nigga", "niga", "nigr", "faggot", "retard", "bollock",
        "spunk", "prick", "pussy", "dildo", "porn", "penis", "vagina", "nazi", "hitler", "tosser", "bellend",
        "minge", "nonce", "spastic", "spaz", "cumshot", "blowjob", "handjob", "rimjob", "asshole", "arsehole",
        "dumbass", "jackass", "motherf", "clit", "scrotum", "testicle", "horny", "molest", "paedo", "incest",
        "skank", "wanker", "shag", "jerkoff", "pissed", "pisshead", "cumslut", "sperm"
    };
    // Refused anywhere, but only spelt with their double letters (as one letter they're everywhere: "episode")
    private static final String[] DOUBLED = { "piss", "tits", "jizz", "kkk", "boob" };
    // Refused only as a word of their own in the name
    private static final String[] WORDS = {
        "ass", "arse", "sex", "sexy", "cum", "anal", "anus", "rape", "rapist", "raping", "butt", "crap", "damn",
        "fag", "fags", "tit", "hoe", "hoes", "nob", "knob", "bum", "turd", "coon", "chink", "gook", "kike", "wog",
        "homo", "lesbo", "tranny", "bugger", "sod", "paki", "pedo", "boner", "fck", "fk", "wtf", "stfu",
        "kys", "poo", "poop", "milf", "dyke", "slag", "git", "prat", "pillock", "piss", "tits", "balls", "fart",
        "bj", "willy", "nude"
    };
    // Refused anywhere in the sound of the name: coarsened phonemes (see key), "!x" after one
    // meaning not where x follows ("country" isn't refused for its first syllable)
    private static final String[] SOUNDS = {
        "fUK", "SIt", "KUnt!r", "KOK", "dIK!t", "twAt", "twOt", "wANK", "wONK", "bIC", "slUt", "prIK", "nIg",
        "fAg", "pUsI", "bOlUK", "KlIt", "wONKU", "tOsU"
    };

    private static final String[] ANYWHERE_SKELETONS = skeletons(ANYWHERE, 1);
    private static final String[] DOUBLED_SKELETONS = skeletons(DOUBLED, 2);
    private static final String[] WORD_SKELETONS = skeletons(WORDS, 2);

    private static String[] skeletons(String[] words, int run) {
        String[] out = new String[words.length];
        for (int i = 0; i < words.length; i++) out[i] = skeleton(words[i], run);
        return out;
    }

    /** Why a name won't do, to show the player, or null if it will. */
    public static String problem(String typed) {
        String name = typed.trim();
        if (name.length() < SHORTEST) return "At least " + SHORTEST + " characters, please.";
        if (name.length() > LONGEST) return "At most " + LONGEST + " characters, please.";
        int letters = 0;
        for (char c : name.toCharArray()) {
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                return "Letters, numbers, _ and - only, please.";
            }
            if (Character.isLetter(c)) letters++;
        }
        if (letters < 2) return "At least two letters, please.";
        if (offensive(name)) return "Xenocorp Human Resources has rejected that name. Please choose another.";
        return null;
    }

    /** Whether a name is, holds or sounds like swearing (see the class's comment). */
    public static boolean offensive(String name) {
        for (String read : readings(name)) {
            String letters = read.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
            String once = skeleton(letters, 1), twice = skeleton(letters, 2);
            for (String bad : ANYWHERE_SKELETONS) if (once.contains(bad)) return true;
            for (String bad : DOUBLED_SKELETONS) if (twice.contains(bad)) return true;
            for (String word : Pronunciation.words(read)) {
                String w = skeleton(word.toLowerCase(Locale.ROOT), 2);
                for (String bad : WORD_SKELETONS) if (w.equals(bad)) return true;
            }
            String heard = key(Pronunciation.phonemes(read));
            for (String sound : SOUNDS) if (heard(heard, sound)) return true;
        }
        return false;
    }

    /** The name with its digits read as letters, both ways for 1 (i and l); other characters as they are. */
    private static List<String> readings(String name) {
        List<String> out = new ArrayList<>();
        for (char one : new char[] { 'i', 'l' }) {
            StringBuilder b = new StringBuilder();
            for (char c : name.toCharArray()) {
                // (a digit between letters keeps their word together; read small, so it starts no new word)
                char letter = switch (c) {
                    case '0' -> 'o';
                    case '1' -> one;
                    case '3' -> 'e';
                    case '4' -> 'a';
                    case '5' -> 's';
                    case '6', '9' -> 'g';
                    case '7' -> 't';
                    case '8' -> 'b';
                    case '@' -> 'a';
                    case '$' -> 's';
                    default -> c;
                };
                b.append(letter);
            }
            out.add(b.toString());
        }
        // ...and as typed, digits as numbers ("Sh1t" read as letters above; "B00bs" too)
        out.add(name);
        return out;
    }

    /**
     * Lower-case letters reduced to how they sound, runs of a letter cut to at most run:
     * ph as f, ck as k, c as s before e, i and y and as k otherwise, q as k, x as ks, y as i.
     */
    static String skeleton(String letters, int run) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i), next = i + 1 < letters.length() ? letters.charAt(i + 1) : ' ';
            if (c == 'p' && next == 'h') {
                b.append('f');
                i++;
            } else if (c == 'c' && next == 'k') {
                b.append('k');
                i++;
            } else if (c == 'c') {
                b.append(next == 'h' ? 'c' : "eiy".indexOf(next) >= 0 ? 's' : 'k');
            } else if (c == 'q') {
                b.append('k');
            } else if (c == 'x') {
                b.append("ks");
            } else if (c == 'y') {
                b.append('i');
            } else {
                b.append(c);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < b.length(); i++) {
            int same = 0;
            while (same < out.length() && out.charAt(out.length() - 1 - same) == b.charAt(i)) same++;
            if (same < run) out.append(b.charAt(i));
        }
        return out.toString();
    }

    /**
     * Phonemes coarsened to how alike they sound: the short central and back vowels as U, the
     * i's as I, the o's as O, a as A, the e's as E, k as K, ch as C, sh as S, ng as N, j as J;
     * stress, length, h and the y-sound left out, and the words run together.
     */
    static String key(String phonemes) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < phonemes.length(); i++) {
            char c = phonemes.charAt(i);
            String next = i + 1 < phonemes.length() ? phonemes.substring(i + 1, i + 2) : "";
            if (c == 't' && next.equals("ʃ")) {
                b.append('C');
                i++;
                continue;
            }
            if (c == 'd' && next.equals("ʒ")) {
                b.append('J');
                i++;
                continue;
            }
            switch (c) {
                case 'ʌ', 'ə', 'ɐ', 'ʊ', 'ɜ' -> b.append('U');
                case 'ɪ', 'i' -> b.append('I');
                case 'ɒ', 'ɔ', 'ɑ' -> b.append('O');
                case 'a', 'æ' -> b.append('A');
                case 'ɛ', 'e' -> b.append('E');
                case 'k', 'q' -> b.append('K');
                case 'ʃ' -> b.append('S');
                case 'ŋ' -> b.append('N');
                case 'ɡ', 'g' -> b.append('g');
                case 'ɹ' -> b.append('r');
                case 'ˈ', 'ˌ', 'ː', 'h', 'j', ' ' -> {
                }
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Whether a coarsened sound ("KUnt!r": KUnt, not followed by r) is heard in key. */
    private static boolean heard(String key, String sound) {
        int bang = sound.indexOf('!');
        String core = bang < 0 ? sound : sound.substring(0, bang), not = bang < 0 ? null : sound.substring(bang + 1);
        for (int at = key.indexOf(core); at >= 0; at = key.indexOf(core, at + 1)) {
            if (not == null || !key.startsWith(not, at + core.length())) return true;
        }
        return false;
    }

    /** Developer aid: says whether each name given would do, and why not. */
    public static void main(String[] args) {
        for (String name : args) {
            String problem = problem(name);
            System.out.println(name + "\t" + (problem == null ? "ok" : problem) + "\t/" + Pronunciation.phonemes(name) + "/\t"
                    + key(Pronunciation.phonemes(name)));
        }
    }
}
