# Romanization

How the app turns a script you may not read into one you do, what it gets right, and where it
cannot win. Turned on under **Settings → Language → Romanization**; on by default.

Five writing systems are handled, and they are not equally hard.

| Script | Engine | Ambiguity |
|---|---|---|
| **Japanese** | Kuromoji, a morphological analyser | Solved. It reads words, so 行った is *itta* and 行く is *iku*. |
| **Chinese** | ICU `Han-Latin`, plus a word table | Not solvable by character. See below. |
| **Korean** | ICU `Hangul-Latin` | None worth worrying about — Hangul spells its own sounds. |
| **Cyrillic** | ICU `Cyrillic-Latin` | None. |
| **Greek** | ICU `Greek-Latin` | None. |

## The Chinese problem

A Chinese character does not have a reading. It has a reading *in a word*. ICU's `Han-Latin` gives
each character one fixed reading regardless, which is wrong often enough to matter, and the words it
breaks are ordinary:

| | ICU alone | Actually said |
|---|---|---|
| 音乐 *music* | `yīn lè` | `yīn yuè` |
| 银行 *bank* | `yín xíng` | `yín háng` |
| 了解 *to understand* | `le jiě` | `liǎo jiě` |
| 地方 *place* | `de fāng` | `dì fāng` |
| 目的 *purpose* | `mù de` | `mù dì` |
| 重复 *to repeat* | `zhòng fù` | `chóng fù` |

The reported version of this was 的, 地 and 得 — all `de` to ICU, when they can be *dí*, *dì*, *dé* or
*děi*. That is the middle of the problem rather than its edge.

### What the app does about it

`PinyinWords` holds **31,710 words**: exactly those where a per-character reading disagrees with a
word-level dictionary. It was built by taking every word containing a character that has more than
one reading anywhere in the data — 264,677 of them — running each through the real `Han-Latin`, and
keeping only the disagreements. ICU was already right about 232,967 of them, and those are not
stored, which is why the table is 295 KB in the APK rather than several megabytes.

Reading a line consults the table first, longest match forward, and falls back to ICU for any stretch
no word covered — so a line is a mix of the two, and "not in the table" means "ICU was already
right".

The same lookup picks up the tone sandhi of 一 and 不, which are rules rather than lexical exceptions
but cannot be expressed per character either: 不要 is *bú yào*, 一起 is *yì qǐ*.

### Why this needed the line and not the syllable

Word timings arrive per syllable, and a syllable is usually one character. Romanizing each on its own
— which is what the app did — means 音 and 乐 are handed over separately and neither of them is 音乐.
So a Chinese line is now reconstructed whole, read as words, and the readings handed back to the
syllables they came from. Japanese already worked this way for the same reason.

### What is still not fixed

Segmentation is longest-match, not a parser, so a phrase that can be cut two ways can be cut the
wrong one. A word absent from the dictionary falls back to per-character readings. And a reading that
depends on meaning rather than word membership — 行 in a name, say — is beyond any table.

Which is why the characters can stay on screen: **Romanization → Keep the characters** shows them
small under the reading, swept in step with it, so a reading you cannot trust is one you can check.

## Characters that only look like characters

Reported as "some characters just show up as characters, no romanization" — 見, 貝 and 一 coming out raw
in 帶你飛 by 告五人, while romanizing those same characters by hand worked.

They were not those characters. Apple Music sends them from the **Kangxi Radicals** block, which exists
so dictionaries can refer to radicals, and which renders identically to the ideographs it depicts:

```
⾒ U+2F92 KANGXI RADICAL SEE     looks like 見 U+898B
⾙ U+2F99 KANGXI RADICAL SHELL   looks like 貝 U+8C9D
⼀ U+2F00 KANGXI RADICAL ONE     looks like 一 U+4E00
```

Nine of them in that one track. Nothing that reads Chinese has a reading for a radical — ICU returns it
untouched, no dictionary word contains one — so the character was shown where its reading should be.
They were not counted as Han by the script detector either, so a line made mostly of them could fail to
register as romanizable at all.

`HanCanonical` maps them back for lookups, one character in and one character out. The length has to be
preserved because a reading is handed to its syllable by index, so full NFKC is not usable here — it
turns a single ﷺ into thirty-odd letters and would put every later reading on the wrong character. Only
single-character results are accepted.

**What gets drawn is still what the provider sent.** The two forms are visually identical, so rewriting
the lyrics would be an invisible change to text the app does not own.

## Where the data comes from

The word readings are the non-CC-CEDICT half of
[phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data) (MIT) — its `pinyin.txt`,
`overwrite.txt`, `di.txt`, and the two 汉典 files.

Its combined `large_pinyin.txt` also folds in [CC-CEDICT](https://cc-cedict.org/), which is
CC-BY-SA 4.0. An upstream project relabelling third-party data does not change that data's licence,
so those entries are left out rather than relied on. Everything shipped here traces to MIT-licensed
input.

CC-CEDICT could be used — Creative Commons designates GPLv3 a BY-SA 4.0-compatible licence, and a
derived table could equally ship as a separate CC-BY-SA file with attribution while the code stays
AGPL-3.0 — but there was no need once an MIT source covered it.

## Cost

The table is loaded from a classpath resource on the first Chinese line of a session, never on the
main thread, and never at all if no Chinese is played. A few megabytes of heap, against the tens of
megabytes Kuromoji costs to do the same job for Japanese.

## Furigana

A separate feature and a different bargain: kana over the kanji, rather than Latin instead of them.
Only shown while romanization is **off**, because the two are alternative answers to the same
question and stacking them helps nobody. Kuromoji supplies the readings, so it is as accurate as the
Japanese romanization is.
