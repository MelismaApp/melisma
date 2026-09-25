#!/usr/bin/env python3
"""
Builds the Taiwanese Hokkien tables in app/src/main/resources/hokkien/.

    python3 tools/hokkien/build_tables.py \
        --chhoetaigi path/to/ChhoeTaigiDatabase/ChhoeTaigiDatabase \
        --wiktionary path/to/kaikki.org-dictionary-Chinese.jsonl[.gz] \
        --opencc path/to/TSCharacters.txt \
        --out app/src/main/resources/hokkien

Sources, and why only these (docs/ROMANIZATION.md has the full reasoning):

- ChhoeTaigi 台華線頂對照典, CC BY-SA 4.0, and iTaigi 華台對照典, CC0 — both from
  https://github.com/ChhoeTaigi/ChhoeTaigiDatabase. The readings are Tâi-lô (KipUnicode).
- English Wiktionary's Hokkien readings via https://kaikki.org, CC BY-SA 4.0. Used only to fill
  characters and words the two dictionaries lack: it does not keep literary and colloquial readings
  apart, so letting it override them made the output worse.
- OpenCC's TSCharacters.txt, Apache-2.0, to fold Traditional characters to Simplified.

Outputs:
- words.txt  — Han word, tab, reading. Syllables joined by "-", "--" before a neutral tone, or a
  space between words of a phrase; one syllable per character, always. A word the character defaults
  already read correctly is listed without a reading: it is still needed, to say where the word
  ends, which is where Tâi-lô puts its spaces.
- chars.txt  — character, tab, its default syllable: the reading it most often has across the words.
- poj.txt    — Tâi-lô syllable, tab, the same syllable in POJ, as the dictionary spells it in its own
  POJ column; tone marks sit differently in the two, so this is read off the data, not converted.
- fold.txt   — Traditional character, tab, Simplified.
- detect.txt — per-character and per-pair weights telling Hokkien text from Mandarin, trained on the
  dictionaries' Hokkien headwords against their Mandarin glosses, both folded to Simplified.
"""
import argparse
import csv
import gzip
import json
import math
import os
import re
import unicodedata
from collections import Counter, defaultdict

# Characters Hokkien lyrics write the Mandarin way and sing as the Hokkien word: 會 for ē, 沒 for 無 bô,
# 在 for 佇 tī, 給 for 予 hōo, 他 for 伊 i. The dictionaries give their literary readings, which is right
# inside the words they list and wrong where a lyric uses them on their own. Applied before the words
# are written, so a listed word keeps its own reading: 不過 stays put-kò.
LYRIC_READINGS = {
    "會": "ē", "沒": "bô", "在": "tī", "給": "hōo", "他": "i", "她": "i", "它": "i", "就": "tō",
    "都": "to", "跟": "kap", "還": "iáu", "要": "beh", "那": "hit", "不": "m̄",
    # Not in the dictionaries at all: 佫 is how lyrics often write 閣 koh, "again".
    "佫": "koh",
}

# OpenCC keeps 著 as its own Simplified form, since Mandarin reads it zhù, but Simplified lyrics write
# Hokkien tio̍h as 着 — which then has no reading.
EXTRA_FOLD = {"著": "着"}

# The same for the words that need it: 不是 in a song is m̄-sī, "is not", where the dictionary lists the
# noun put-sī, "a fault". 不通 and 不免 are 毋通 "don't" and 毋免 "no need" written the Mandarin way.
LYRIC_WORDS = {"不是": "m̄-sī", "就是": "tō-sī", "不通": "m̄-thang", "不免": "m̄-bián"}

HAN = re.compile(r"[㐀-䶿一-鿿豈-﫿\U00020000-\U0002fa1f]")
SEPARATOR = re.compile(r"(--|-|\s+)")


def is_han(ch):
    return bool(HAN.fullmatch(ch))


def all_han(text):
    return bool(text) and all(is_han(c) for c in text)


def han_only(text):
    return "".join(c for c in text if is_han(c))


def nfc(text):
    return unicodedata.normalize("NFC", text.strip())


def parse_reading(reading):
    """Syllables and the separators between them, or None if it is not a clean reading."""
    parts = SEPARATOR.split(nfc(reading).lower())
    syllables = parts[0::2]
    joins = [" " if s.isspace() else s for s in parts[1::2]]
    # Letters and combining marks: POJ's o͘ ends in one (U+0358), and it is part of the vowel.
    if not syllables or any(not s or not all(c.isalpha() or unicodedata.category(c).startswith("M") for c in s)
                            for s in syllables):
        return None
    return syllables, joins


def render(syllables, joins):
    out = [syllables[0]]
    for join, syllable in zip(joins, syllables[1:]):
        out.append(join)
        out.append(syllable)
    return "".join(out)


class Lexicon:
    def __init__(self):
        self.words = defaultdict(Counter)  # han -> Counter(rendered reading)
        self.chars = defaultdict(Counter)  # char -> Counter(syllable)

    def add(self, han, reading, weight):
        if not all_han(han):
            return
        parsed = parse_reading(reading)
        if parsed is None:
            return
        syllables, joins = parsed
        if len(syllables) != len(han):
            return
        if len(han) == 1:
            self.chars[han][syllables[0]] += weight
            return
        self.words[han][render(syllables, joins)] += weight
        for ch, syllable in zip(han, syllables):
            self.chars[ch][syllable] += weight


def split_alternates(cell):
    return [r for r in re.split(r"\s*/\s*", cell or "") if r.strip()]


def load_chhoetaigi(directory):
    lexicon = Lexicon()
    corpus = {"hokkien": [], "mandarin": []}
    poj = defaultdict(Counter)
    # The edited dictionary outweighs the crowd-sourced one where they disagree: iTaigi is anyone's
    # suggestion, and has 查某囡仔 as tsa-póo gín-á more often than as tsa-bóo-gín-á.
    for name, trust in (("ChhoeTaigi_TaihoaSoanntengTuichiautian.csv", 4), ("ChhoeTaigi_iTaigiHoataiTuichiautian.csv", 1)):
        with open(os.path.join(directory, name), encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                han = nfc(row.get("HanLoTaibunKip") or "")
                readings = split_alternates(row.get("KipUnicode"))
                # The headline reading counts double, so an alternate never outvotes it.
                for index, reading in enumerate(readings):
                    lexicon.add(han, reading, (2 if index == 0 else 1) * trust)
                for reading in split_alternates(row.get("KipUnicodeOthers")):
                    lexicon.add(han, reading, trust)
                corpus["hokkien"].append(han)
                corpus["mandarin"].append(nfc(row.get("HoaBun") or ""))
                pairs = zip(split_alternates(row.get("KipUnicode")), split_alternates(row.get("PojUnicode")))
                for kip, poj_reading in pairs:
                    a, b = parse_reading(kip), parse_reading(poj_reading)
                    if a and b and len(a[0]) == len(b[0]):
                        for x, y in zip(a[0], b[0]):
                            poj[x][y] += 1
    return lexicon, corpus, poj


def load_wiktionary(path):
    """Taiwanese Hokkien Tâi-lô readings: General Taiwanese first, then any Taiwanese locale."""
    taiwan = {"Taipei", "Kaohsiung", "Tainan", "Taichung", "Yilan", "Kinmen", "Lukang", "Taiwan",
              "Sanxia", "Magong", "Hsinchu", "Chiayi", "Wanhua", "Tamsui"}
    chars = defaultdict(lambda: [Counter(), Counter(), Counter()])
    words = defaultdict(lambda: [Counter(), Counter()])
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as f:
        for line in f:
            if '"Tai-lo"' not in line:
                continue
            entry = json.loads(line)
            word = entry.get("word", "")
            if not all_han(word):
                continue
            for sound in entry.get("sounds") or []:
                tags = sound.get("tags") or []
                if "Hokkien" not in tags or "Tai-lo" not in tags:
                    continue
                reading = sound.get("zh_pron") or ""
                if not reading or "/" in reading:
                    continue
                parsed = parse_reading(reading)
                if parsed is None or len(parsed[0]) != len(word):
                    continue
                raw = sound.get("raw_tags") or []
                general = "General Taiwanese" in raw
                local = general or any(t in taiwan for t in tags + raw)
                if len(word) == 1:
                    counters = chars[word]  # general, Taiwanese locale, any
                    counters[2][parsed[0][0]] += 1
                    if local:
                        counters[1][parsed[0][0]] += 1
                    if general:
                        counters[0][parsed[0][0]] += 1
                elif len(word) <= 8:
                    counters = words[word]  # general, any
                    counters[1][render(*parsed)] += 1
                    if general:
                        counters[0][render(*parsed)] += 1
    # Taiwanese readings only. Wiktionary also records Quanzhou, Zhangzhou, Xiamen and Lukang, and
    # those are how some people speak, not how Taiwanese songs are sung: 回去 huê-khìr, 還是 hâi-sǐ.
    # A character with no place given at all is taken only when every reading agrees: 們 bûn, 惦 tiàm.
    char_default = {}
    for ch, (general, local, any_) in chars.items():
        if general or local:
            char_default[ch] = (general or local).most_common(1)[0][0]
        elif len(any_) == 1:
            char_default[ch] = next(iter(any_))
    word_default = {w: general.most_common(1)[0][0] for w, (general, _) in words.items() if general}
    return char_default, word_default


def load_fold(path):
    fold = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or "\t" not in line:
                continue
            source, targets = line.rstrip("\n").split("\t", 1)
            target = targets.split(" ")[0]
            if len(source) == 1 and len(target) == 1 and source != target:
                fold[source] = target
    fold.update(EXTRA_FOLD)
    return fold


def syllables_of(reading):
    return SEPARATOR.split(reading)[0::2]


def choose(word, counter, chars):
    """The most-voted reading, unless it has no more than twice the next one's votes and one candidate is the characters' own."""
    ranked = counter.most_common()
    best, votes = ranked[0]
    if len(ranked) > 1 and votes <= 2 * ranked[1][1]:
        for reading, _ in ranked:
            if compositional(word, reading, chars):
                return reading
    return best


def compositional(word, reading, chars):
    """Whether the character defaults, hyphenated, already give this reading."""
    return set(SEPARATOR.findall(reading)) <= {"-"} and syllables_of(reading) == [chars.get(c) for c in word]


def detector(corpus, fold):
    def folded(text):
        return "".join(fold.get(c, c) for c in han_only(text))

    counts = {}
    for side, texts in corpus.items():
        uni, bi = Counter(), Counter()
        for text in texts:
            h = folded(text)
            uni.update(h)
            bi.update(h[i:i + 2] for i in range(len(h) - 1))
        counts[side] = (uni, bi)

    (hu, hb), (mu, mb) = counts["hokkien"], counts["mandarin"]
    lines = []

    def weights(h, m, kind, min_total, min_weight):
        nh, nm = sum(h.values()), sum(m.values())
        vh, vm = len(h), len(m)
        floor = math.log((0.5 / (nh + 0.5 * vh)) / (0.5 / (nm + 0.5 * vm)))
        lines.append(f"#{kind}\t{floor:.4f}")
        for key in sorted(set(h) | set(m)):
            if h[key] + m[key] < min_total:
                continue
            w = math.log(((h[key] + 0.5) / (nh + 0.5 * vh)) / ((m[key] + 0.5) / (nm + 0.5 * vm)))
            if abs(w - floor) >= min_weight:
                lines.append(f"{key}\t{w:.2f}")

    weights(hu, mu, "u", 2, 0.0)
    weights(hb, mb, "b", 4, 1.0)
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--chhoetaigi", required=True)
    parser.add_argument("--wiktionary", required=True)
    parser.add_argument("--opencc", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    lexicon, corpus, poj = load_chhoetaigi(args.chhoetaigi)
    chars = {c: counter.most_common(1)[0][0] for c, counter in lexicon.chars.items()}

    wik_chars, wik_words = load_wiktionary(args.wiktionary)
    added_chars = {c: r for c, r in wik_chars.items() if c not in chars}
    chars.update(added_chars)
    # Wiktionary's General Taiwanese reading of a word is a vote between the edited dictionary's and
    # iTaigi's, which settles the words iTaigi alone disagrees with itself about. It never moves a
    # character default: its readings do not say which is literary, and letting them decide made
    # the output worse.
    added_words = [w for w in wik_words if w not in lexicon.words]
    for word, reading in wik_words.items():
        lexicon.words[word][reading] += 3
    words = {w: choose(w, counter, chars) for w, counter in lexicon.words.items()}
    chars.update(LYRIC_READINGS)
    words.update(LYRIC_WORDS)

    fold = load_fold(args.opencc)

    os.makedirs(args.out, exist_ok=True)

    def write(name, lines):
        with open(os.path.join(args.out, name), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

    write("words.txt", [w if compositional(w, words[w], chars) else f"{w}\t{words[w]}" for w in sorted(words)])
    write("chars.txt", [f"{c}\t{chars[c]}" for c in sorted(chars)])
    write("fold.txt", [f"{t}\t{s}" for t, s in sorted(fold.items())])
    write("poj.txt", [f"{k}\t{c.most_common(1)[0][0]}" for k, c in sorted(poj.items())])
    write("detect.txt", detector(corpus, fold))

    bare = sum(1 for w in words if compositional(w, words[w], chars))
    print(f"words: {len(words)} ({len(added_words)} from Wiktionary), {bare} without a reading")
    print(f"chars: {len(chars)} ({len(added_chars)} from Wiktionary)")
    print(f"fold: {len(fold)}, poj syllables: {len(poj)}")
    missing = {x for r in list(words.values()) + list(chars.values()) for x in syllables_of(r)} - set(poj)
    print(f"syllables with no POJ spelling: {len(missing)}")


if __name__ == "__main__":
    main()
