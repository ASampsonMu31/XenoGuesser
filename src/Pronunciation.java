import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a player's username is said (M58), as phonemes in the voice's alphabet (eSpeak's IPA,
 * British, stress marked with ˈ before the stressed vowel), worked out from its spelling alone:
 * eSpeak isn't part of the project and names aren't in any dictionary. The name is split into
 * words where its case changes ("ILoveMaps": I, Love, Maps), at digits and at _ or -; numbers
 * are said as numbers, short capitals and words with no vowel as letters ("TJ", "XKCD"), and
 * every other word by English letter-to-sound rules: the US Naval Research Laboratory's (Elovitz
 * et al., 1976), given British vowels and no R after a vowel. Each word is stressed on its first
 * vowel (its second, should the first be a schwa), as most names are. NameCheck uses the same
 * phonemes to hear names that sound like swearing.
 */
public final class Pronunciation {

    private Pronunciation() {
    }

    /** The phonemes for a username, its words a space apart. */
    public static String phonemes(String name) {
        List<String> said = new ArrayList<>();
        for (String word : words(name)) {
            if (Character.isDigit(word.charAt(0))) said.add(number(word));
            else if (spelt(word)) said.add(letters(word));
            else said.add(stress(word(word.toLowerCase(java.util.Locale.ROOT))));
        }
        said.removeIf(String::isEmpty);
        return String.join(" ", said);
    }

    /** A name's words: runs of letters split where the case changes, and runs of digits. */
    public static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean starts = word.length() > 0 && (Character.isDigit(c) != Character.isDigit(word.charAt(word.length() - 1))
                    // a capital after a small letter ("loveMaps"), or the last of a run of capitals before a small one ("XMLParser")
                    || Character.isUpperCase(c) && Character.isLowerCase(word.charAt(word.length() - 1))
                    || Character.isUpperCase(c) && i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1))
                       && word.length() > 1 && Character.isUpperCase(word.charAt(word.length() - 1)));
            if (!Character.isLetterOrDigit(c) || starts) {
                if (word.length() > 0) words.add(word.toString());
                word.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) word.append(c);
        }
        if (word.length() > 0) words.add(word.toString());
        return words;
    }

    /** Whether a word is said letter by letter: a letter on its own, two or three capitals, or no vowel to say. */
    private static boolean spelt(String word) {
        if (word.length() == 1) return true;
        if (word.length() > 1 && word.length() <= 3 && word.equals(word.toUpperCase(java.util.Locale.ROOT))) return true;
        for (char c : word.toLowerCase(java.util.Locale.ROOT).toCharArray()) if ("aeiouy".indexOf(c) >= 0) return false;
        return true;
    }

    private static final String[] LETTERS = {
        "ˈeɪ", "bˈiː", "sˈiː", "dˈiː", "ˈiː", "ˈɛf", "dʒˈiː", "ˈeɪtʃ", "ˈaɪ", "dʒˈeɪ", "kˈeɪ", "ˈɛl", "ˈɛm",
        "ˈɛn", "ˈəʊ", "pˈiː", "kjˈuː", "ˈɑː", "ˈɛs", "tˈiː", "jˈuː", "vˈiː", "dˈʌbəljuː", "ˈɛks", "wˈaɪ", "zˈɛd" };

    private static String letters(String word) {
        List<String> said = new ArrayList<>();
        for (char c : word.toLowerCase(java.util.Locale.ROOT).toCharArray()) if (c >= 'a' && c <= 'z') said.add(LETTERS[c - 'a']);
        return String.join(" ", said);
    }

    // The numbers' words, as the April message's recording says them
    private static final String[] ONES = { "zˈiəɹəʊ", "wˈɒn", "tˈuː", "θɹˈiː", "fˈɔː", "fˈaɪv", "sˈɪks", "sˈɛvən", "ˈeɪt", "nˈaɪn",
        "tˈɛn", "ɪlˈɛvən", "twˈɛlv", "θˈɜːtiːn", "fˈɔːtiːn", "fˈɪftiːn", "sˈɪkstiːn", "sˈɛvəntˌiːn", "ˈeɪtiːn", "nˈaɪntiːn" };
    private static final String[] TENS = { "", "", "twˈɛnti", "θˈɜːti", "fˈɔːti", "fˈɪfti", "sˈɪksti", "sˈɛvənti", "ˈeɪti", "nˈaɪnti" };

    /** A run of digits as a number up to 9,999 (a leading 0, or anything longer, digit by digit). */
    private static String number(String digits) {
        if (digits.length() > 4 || digits.length() > 1 && digits.charAt(0) == '0') {
            List<String> said = new ArrayList<>();
            for (char c : digits.toCharArray()) said.add(ONES[c - '0']);
            return String.join(" ", said);
        }
        int n = Integer.parseInt(digits);
        if (n == 0) return ONES[0];
        List<String> said = new ArrayList<>();
        if (n >= 1000) {
            said.add(ONES[n / 1000]);
            said.add("θˈaʊzənd");
            n %= 1000;
        }
        if (n >= 100) {
            said.add(ONES[n / 100]);
            said.add("hˈʌndɹɪd");
            n %= 100;
            if (n > 0) said.add("ənd");
        }
        if (n >= 20) {
            said.add(TENS[n / 10]);
            if (n % 10 > 0) said.add(ONES[n % 10]);
        } else if (n > 0) {
            said.add(ONES[n]);
        }
        return String.join(" ", said);
    }

    /** The word's stress: before its first vowel, or its second should the first be a schwa. */
    private static String stress(String phonemes) {
        List<Integer> vowels = new ArrayList<>();
        for (int i = 0; i < phonemes.length(); i++) {
            // (a vowel's start: not the second half of a diphthong, as in aɪ, eɪ, əʊ)
            if (VOWELS.indexOf(phonemes.charAt(i)) >= 0 && (i == 0 || VOWELS.indexOf(phonemes.charAt(i - 1)) < 0)) vowels.add(i);
        }
        if (vowels.isEmpty()) return phonemes;
        int at = vowels.get(0);
        if (vowels.size() > 1 && phonemes.charAt(at) == 'ə' && !phonemes.startsWith("əʊ", at)) at = vowels.get(1);
        return phonemes.substring(0, at) + "ˈ" + phonemes.substring(at);
    }

    private static final String VOWELS = "aɑɒɔəɛɜɪiʊuʌeo";

    // ------------------------------------------------------------------ letter-to-sound rules

    /**
     * Each rule: what comes before, the letters it says, what comes after, and the phonemes, as
     * "before[letters]after=phonemes". In the contexts a space is the word's edge; # one or
     * more vowels; : none or more consonants; ^ one consonant; . a voiced consonant (b d v g j
     * l m n r w z); + a front vowel (e i y); % a suffix (er e es ed ing ely); & a sibilant (s c
     * g z x j ch sh); @ a consonant a long u follows as "oo" (t s r d l z n j th ch sh). For
     * each letter the first rule that fits is used.
     */
    private static final String[] RULES = {
        // A
        " [a] =ə", " [are] =ɑː", " [ar]o=əɹ", "[ar]#=eəɹ", "^[as]#=eɪs", "[a]wa=ə", "[aw]=ɔː", " :[any]=ɛni",
        "[a]^+#=eɪ", "#:[ally]=əli", " [al]#=əl", "[again]=əɡɛn", "#:[ag]e=ɪdʒ", "[a]^+:#=a", " :[a]^+ =eɪ",
        "[a]^%=eɪ", " [arr]=əɹ", "[arr]=aɹ", " :[ar] =ɑː", "[ar] =ə", "[ar]=ɑː", "[air]=eə", "[ai]=eɪ", "[ay]=eɪ",
        "[au]=ɔː", "#:[al] =əl", "#:[als] =əlz", "[alk]=ɔːk", "[al]^=ɔːl", " :[able]=eɪbəl", "[able]=əbəl",
        "[ang]+=eɪndʒ", "[a]=a",
        // B
        " [be]^#=bɪ", "[being]=biːɪŋ", " [both] =bəʊθ", " [bus]#=bɪz", "[buil]=bɪl", "[bb]=b", "[b]=b",
        // C
        " [ch]^=k", "^e[ch]=k", "[ch]=tʃ", " s[ci]#=saɪ", "[ci]a=ʃ", "[ci]o=ʃ", "[ci]en=ʃ", "[c]+=s", "[ck]=k",
        "[com]%=kʌm", "[cc]=k", "[c]=k",
        // D
        "#:[ded] =dɪd", ".e[d] =d", "#^:e[d] =t", " [de]^#=dɪ", " [do] =duː", " [does]=dʌz", " [doing]=duːɪŋ",
        " [dow]=daʊ", "[du]a=dʒuː", "[dd]=d", "[d]=d",
        // E
        "#:[e] =", " :^[e] =iː", " :[e] =iː", "#[ed] =d", "#:[e]d =", "[ev]er=ɛv", "[e]^%=iː", "[eri]#=ɪəɹi",
        "[eri]=ɛɹɪ", "#:[er]#=əɹ", "[er]#=ɛɹ", "[er]=ɜː", " [even]=iːvən", "#:[e]w=", "@[ew]=uː", "[ew]=juː",
        "[e]o=iː", "#:&[es] =ɪz", "#:[e]s =", "#:[ely] =li", "#:[ement]=mənt", "[eful]=fʊl", "[ee]=iː",
        "[earn]=ɜːn", " [ear]^=ɜː", "[ead]=ɛd", "#:[ea] =iːə", "[ea]su=ɛ", "[ea]=iː", "[eigh]=eɪ", "[ei]=iː",
        " [eye]=aɪ", "[ey]=i", "[eu]=juː", "[e]=ɛ",
        // F
        "[ful]=fʊl", "[ff]=f", "[f]=f",
        // G
        "[giv]=ɡɪv", " [g]i^=ɡ", "[ge]t=ɡɛ", "su[gges]=ɡdʒɛs", "[gg]=ɡ", " b#[g]=ɡ", "[g]+=dʒ", "[great]=ɡɹeɪt",
        "#[gh]=", "[g]=ɡ",
        // H
        " [hav]=hav", " [here]=hɪə", " [hour]=aʊə", "[how]=haʊ", "[h]#=h", "[h]=",
        // I
        " [in]=ɪn", " [i] =aɪ", "[in]d=aɪn", "[ier]=ɪə", "#:r[ied] =id", "[ied] =aɪd", "[ien]=iːɛn", "[ie]t=aɪɛ",
        " :[i]%=aɪ", "[i]%=iː", "[ie]=iː", "[i]^+:#=ɪ", "[ir]#=aɪɹ", "[iz]%=aɪz", "[is]%=aɪz", "[i]d%=aɪ",
        "+^[i]^+=ɪ", "[i]t%=aɪ", "#^:[i]^+=ɪ", "[i]^+=aɪ", "[ir]=ɜː", "[igh]=aɪ", "[ild]=aɪld", "[ign] =aɪn",
        "[ign]^=aɪn", "[ign]%=aɪn", "[ique]=iːk", "[i] =i", "[i]=ɪ",
        // J
        "[j]=dʒ",
        // K
        " [k]n=", "[kk]=k", "[k]=k",
        // L
        "[lo]c#=ləʊ", "l[l]=", "#^:[l]%=əl", "[lead]=liːd", "[l]=l",
        // M
        "[mov]=muːv", "[mm]=m", "[m]=m",
        // N
        "e[ng]+=ndʒ", "[ng]r=ŋɡ", "[ng]#=ŋɡ", "[ngl]%=ŋɡəl", "[ng]=ŋ", "[nk]=ŋk", " [now] =naʊ", "[nn]=n", "[n]=n",
        // O
        "[of] =ɒv", "[orough]=ʌɹə", "#:[or] =ə", "#:[ors] =əz", "[or]=ɔː", " [one]=wʌn", "[ow]=əʊ", " [over]=əʊvə",
        "[ov]=ʌv", "[o]^%=əʊ", "[o]^en=əʊ", "[o]^i#=əʊ", "[ol]d=əʊl", "[ought]=ɔːt", "[ough]=ʌf", " [ou]=aʊ",
        "h[ou]s#=aʊ", "[ous]=əs", "[our]=ɔː", "[ould]=ʊd", "^[ou]^l=ʌ", "[oup]=uːp", "[ou]=aʊ", "[oy]=ɔɪ",
        "[oing]=əʊɪŋ", "[oi]=ɔɪ", "[oor]=ɔː", "[ook]=ʊk", "[ood]=ʊd", "[oo]=uː", "[o]e=əʊ", "[o] =əʊ", "[oa]=əʊ",
        " [only]=əʊnli", " [once]=wʌns", "c[o]n=ɒ", "[o]ng=ɒ", " :^[o]n=ʌ", "i[on]=ən", "#:[on] =ən", "#^[on]=ən",
        "[o]st =əʊ", "[of]^=ɒf", "[other]=ʌðə", "[oss] =ɒs", "#^:[om]=ʌm", "[o]=ɒ",
        // P
        "[ph]=f", "[peop]=piːp", "[pow]=paʊ", "[put] =pʊt", "[pp]=p", "[p]=p",
        // Q
        "[quar]=kwɔː", "[qu]=kw", "[q]=k",
        // R
        " [re]^#=ɹiː", "[rr]=ɹ", "[r]=ɹ",
        // S
        "[sh]=ʃ", "#[sion]=ʒən", "[some]=sʌm", "#[sur]#=ʒə", "[sur]#=ʃə", "#[su]#=ʒuː", "#[ssu]#=ʃuː", "#[sed] =zd",
        "#[s]#=z", "[said]=sɛd", "^[sion]=ʃən", "[s]s=", ".[s] =z", "#:.e[s] =z", "#^:##[s] =z", "#^:#[s] =s",
        "u[s] =s", " :#[s] =z", " [sch]=sk", "[s]c+=", "#[sm]=zəm", "[s]=s",
        // T
        " [the] =ðə", "[to] =tuː", "[that] =ðat", " [this] =ðɪs", " [they]=ðeɪ", " [there]=ðeə", "[ther]=ðə",
        "[their]=ðeə", " [than] =ðan", " [them] =ðɛm", "[these] =ðiːz", " [then]=ðɛn", "[through]=θɹuː",
        "[those]=ðəʊz", "[though] =ðəʊ", " [thus]=ðʌs", "[th]=θ", "#:[ted] =tɪd", "s[ti]#n=tʃ", "[ti]o=ʃ",
        "[ti]a=ʃ", "[tien]=ʃən", "[tur]#=tʃə", "[tu]a=tʃuː", " [two]=tuː", "[tt]=t", "[t]=t",
        // U
        " [un]i=juːn", " [un]=ʌn", " [upon]=əpɒn", "@[ur]#=ʊəɹ", "[ur]#=jʊəɹ", "[ur]=ɜː", "[u]^ =ʌ", "[u]^^=ʌ",
        "[uy]=aɪ", " g[u]#=", "g[u]%=", "g[u]#=w", "#n[u]=juː", "@[u]=uː", "[u]=juː",
        // V
        "[view]=vjuː", "[v]=v",
        // W
        " [were]=wɜː", "[wa]s=wɒ", "[wa]t=wɒ", "[where]=weə", "[what]=wɒt", "[whol]=həʊl", "[who]=huː", "[wh]=w",
        "[war]=wɔː", "[wor]^=wɜː", "[wr]=ɹ", "[w]=w",
        // X
        " [x]=z", "[x]=ks",
        // Y
        "[young]=jʌŋ", " [you]=juː", " [yes]=jɛs", "[y]o=j", "[y]u=j", " [y]#=j", "#^:[y] =i", "#^:[y]i=i",
        " :[y] =aɪ", " :[y]#=aɪ", " :[y]^+:#=ɪ", " :[y]^#=aɪ", "[y]=ɪ",
        // Z
        "[zz]=z", "[z]=z"
    };

    private record Rule(String before, String letters, String after, String phonemes) {
    }

    // The rules by the letter they start with, in order
    private static final Map<Character, List<Rule>> BY_LETTER = new HashMap<>();

    static {
        for (String text : RULES) {
            int open = text.indexOf('['), close = text.indexOf(']'), equals = text.indexOf('=', close);
            Rule rule = new Rule(text.substring(0, open), text.substring(open + 1, close), text.substring(close + 1, equals),
                    text.substring(equals + 1));
            BY_LETTER.computeIfAbsent(rule.letters.charAt(0), c -> new ArrayList<>()).add(rule);
        }
    }

    /** A lower-case word's phonemes by the rules (unstressed). */
    static String word(String letters) {
        String w = " " + letters + " ";
        StringBuilder out = new StringBuilder();
        int i = 1;
        while (i < w.length() - 1) {
            List<Rule> rules = BY_LETTER.get(w.charAt(i));
            Rule used = null;
            if (rules != null) {
                for (Rule r : rules) {
                    if (w.startsWith(r.letters, i) && before(w, i - 1, r.before) && after(w, i + r.letters.length(), r.after)) {
                        used = r;
                        break;
                    }
                }
            }
            if (used == null) {
                i++;
                continue;
            }
            out.append(used.phonemes);
            i += used.letters.length();
        }
        return out.toString();
    }

    private static boolean vowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }

    private static boolean consonant(char c) {
        return c >= 'a' && c <= 'z' && !vowel(c);
    }

    /** Whether what's before the rule's letters (ending at index at, read backwards) fits its context. */
    private static boolean before(String w, int at, String context) {
        for (int k = context.length() - 1; k >= 0; k--) {
            char c = context.charAt(k);
            switch (c) {
                case '#' -> {
                    if (at < 0 || !vowel(w.charAt(at))) return false;
                    while (at >= 0 && vowel(w.charAt(at))) at--;
                }
                case ':' -> {
                    while (at >= 0 && consonant(w.charAt(at))) at--;
                }
                case '^' -> {
                    if (at < 0 || !consonant(w.charAt(at))) return false;
                    at--;
                }
                case '.' -> {
                    if (at < 0 || "bdvgjlmnrwz".indexOf(w.charAt(at)) < 0) return false;
                    at--;
                }
                case '+' -> {
                    if (at < 0 || "eiy".indexOf(w.charAt(at)) < 0) return false;
                    at--;
                }
                case '&' -> {
                    if (at >= 1 && w.charAt(at) == 'h' && (w.charAt(at - 1) == 'c' || w.charAt(at - 1) == 's')) at -= 2;
                    else if (at >= 0 && "scgzxj".indexOf(w.charAt(at)) >= 0) at--;
                    else return false;
                }
                case '@' -> {
                    if (at >= 1 && w.charAt(at) == 'h' && "tcs".indexOf(w.charAt(at - 1)) >= 0) at -= 2;
                    else if (at >= 0 && "tsrdlznj".indexOf(w.charAt(at)) >= 0) at--;
                    else return false;
                }
                case ' ' -> {
                    if (at >= 0 && Character.isLetter(w.charAt(at))) return false;
                    at--;
                }
                default -> {
                    if (at < 0 || w.charAt(at) != c) return false;
                    at--;
                }
            }
        }
        return true;
    }

    /** Whether what follows the rule's letters (from index at) fits its context. */
    private static boolean after(String w, int at, String context) {
        int n = w.length();
        for (int k = 0; k < context.length(); k++) {
            char c = context.charAt(k);
            switch (c) {
                case '#' -> {
                    if (at >= n || !vowel(w.charAt(at))) return false;
                    while (at < n && vowel(w.charAt(at))) at++;
                }
                case ':' -> {
                    while (at < n && consonant(w.charAt(at))) at++;
                }
                case '^' -> {
                    if (at >= n || !consonant(w.charAt(at))) return false;
                    at++;
                }
                case '.' -> {
                    if (at >= n || "bdvgjlmnrwz".indexOf(w.charAt(at)) < 0) return false;
                    at++;
                }
                case '+' -> {
                    if (at >= n || "eiy".indexOf(w.charAt(at)) < 0) return false;
                    at++;
                }
                case '%' -> {
                    String rest = w.substring(Math.min(at, n));
                    String found = null;
                    for (String suffix : new String[] { "ing", "ely", "er", "es", "ed", "e" }) {
                        if (rest.startsWith(suffix)) {
                            found = suffix;
                            break;
                        }
                    }
                    if (found == null) return false;
                    at += found.length();
                }
                case ' ' -> {
                    if (at < n && Character.isLetter(w.charAt(at))) return false;
                    at++;
                }
                default -> {
                    if (at >= n || w.charAt(at) != c) return false;
                    at++;
                }
            }
        }
        return true;
    }

    /** Developer aid: prints the phonemes for each name given. */
    public static void main(String[] args) {
        for (String name : args) System.out.println(name + "\t" + phonemes(name));
    }
}
