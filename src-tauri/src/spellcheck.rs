//! Touch Up: fixes misspelled words in a dictation the user already pasted. Spell-check only: no
//! rephrasing, no grammar, no AI (Yash, 2026-10-06). The lite model writes real words almost always,
//! so this only catches true typos ("recieve" -> "receive"), and it is built to leave everything else alone:
//! - only plain lowercase words of 3+ letters are checked (names, acronyms, numbers, emails, file
//!   names and contractions are never touched);
//! - a word the dictionary knows, or one on the keep list (Indian English, Hinglish, chat words), stays;
//! - the fix must be a common word (`MIN_COUNT`), 1 edit away, or 2 edits away for words of 6+ letters;
//!   among candidates, the most frequent one wins.
//! Word list: SymSpell's English frequency dictionary (80,000 words, MIT, data/en-80k.LICENSE.txt).

use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

const WORDS: &str = include_str!("../data/en-80k.txt");
/// A replacement must be at least this common in the frequency list (counts run 3,840 .. 2.6e10).
const MIN_COUNT: u64 = 100_000;
const KEEP: &str = "lakh lakhs crore crores rupees paise ji haan han nahi nahin achha acha accha theek thik hai hain kya \
    yaar yar bhai bhaiya didi dada dadi nani nana mausi mama mami chacha chachi bua beta beti amma appa anna akka \
    thatha paati ammi abba bhabhi jiju arre arey bas chalo matlab jugaad namaste namaskar chai masala paneer dosa \
    idli vada sambar biryani roti chapati naan paratha dal daal chutney samosa pakora lassi ghee jeera haldi dhaba \
    kirana mandi bazaar puja pooja diwali holi rakhi gmail whatsapp swiggy zomato paytm phonepe gpay upi emi gst \
    aadhaar ifsc neft rtgs imps otp kyc lol omg wfh fyi asap";

/// Technical words the 80k list lacks or ranks low. They count as correct (so "nodejs" is never "fixed" to "nodes")
/// and as fixes ("dokcer" -> "docker").
const TECH: &str = "docker kubernetes kubectl postgres postgresql mysql sqlite mongodb redis nginx kafka graphql json yaml toml     html css http https api apis sdk cli ide github gitlab bitbucket npm yarn pnpm webpack vite react reactjs nodejs vue     angular svelte nextjs nuxt django flask fastapi pytorch tensorflow numpy pandas jupyter linux ubuntu debian macos     android ios iphone ipad chatgpt openai anthropic gemini llama qwen terraform ansible jenkins aws azure gcp firebase     supabase vercel netlify cloudflare localhost frontend backend devops microservice microservices middleware typescript     javascript python rust golang kotlin tailwind bootstrap eslint prettier webhook webhooks oauth jwt sql nosql regex     async boolean enum struct tauri electron vulkan cuda gpu cpu ram ssd usb wifi bluetooth hdmi repo repos config     configs dockerfile dataset datasets username usernames login logout signup unicode utf ascii bash zsh powershell     vscode intellij pycharm figma jira slack zoom notion linkedin youtube instagram reddit whatsapp telegram";

fn dict() -> &'static HashMap<&'static str, u64> {
    static D: OnceLock<HashMap<&'static str, u64>> = OnceLock::new();
    D.get_or_init(|| {
        WORDS
            .lines()
            .filter_map(|l| {
                let mut p = l.split_whitespace();
                Some((p.next()?, p.next()?.parse().ok()?))
            })
            .chain(TECH.split_whitespace().map(|w| (w, 1_000_000_000u64)))
            .collect()
    })
}

fn keep() -> &'static HashSet<&'static str> {
    static K: OnceLock<HashSet<&'static str>> = OnceLock::new();
    K.get_or_init(|| KEEP.split_whitespace().collect())
}

/// The kinds of one-edit change, in the order a typist makes them: two letters swapped, a letter left out, a
/// letter added, a letter wrong. The first kind that gives a common word wins, so "wnat" is "want" (a swap), not
/// "what" (an added letter that happens to be more frequent), and "mising" is "missing", not "rising".
fn edits1_by_kind(w: &str) -> [Vec<String>; 4] {
    let b = w.as_bytes();
    let (mut swap, mut missing, mut extra, mut wrong) = (vec![], vec![], vec![], vec![]);
    for i in 0..=b.len() {
        let (l, r) = (&b[..i], &b[i..]);
        if r.len() > 1 {
            swap.push([l, &[r[1], r[0]], &r[2..]].concat());
        }
        if !r.is_empty() {
            extra.push([l, &r[1..]].concat());
        }
        for c in b'a'..=b'z' {
            missing.push([l, &[c], r].concat());
            if !r.is_empty() && r[0] != c {
                wrong.push([l, &[c], &r[1..]].concat());
            }
        }
    }
    // The first letter is almost never the typo; a candidate that changes it is a different word.
    let ok = |v: Vec<Vec<u8>>| -> Vec<String> {
        v.into_iter().filter(|c| c.first() == b.first()).filter_map(|c| String::from_utf8(c).ok()).collect()
    };
    [ok(swap), ok(missing), ok(extra), ok(wrong)]
}

fn most_common<'a>(cands: impl Iterator<Item = &'a String>) -> Option<String> {
    let d = dict();
    cands
        .filter_map(|c| d.get(c.as_str()).map(|&n| (c, n)))
        .filter(|(_, n)| *n >= MIN_COUNT)
        .max_by_key(|(_, n)| *n)
        .map(|(c, _)| c.clone())
}

/// The fix for one lowercase word, or None to leave it exactly as it is. `allow_two`: also try two edits (6+ letters).
fn fix_lower(w: &str, allow_two: bool) -> Option<String> {
    if w.len() < 3 || !w.bytes().all(|b| b.is_ascii_lowercase()) || dict().contains_key(w) || keep().contains(w) {
        return None;
    }
    let kinds = edits1_by_kind(w);
    // A short word with a letter left out or a wrong letter has too many neighbours ("dgo" -> "do", "mny" -> "may").
    for (i, k) in kinds.iter().enumerate() {
        if i >= 2 && w.len() < 5 {
            continue;
        }
        if let Some(c) = most_common(k.iter()) {
            return Some(c);
        }
    }
    if !allow_two || w.len() < 6 {
        return None;
    }
    let e2: Vec<String> = kinds.iter().flatten().flat_map(|e| edits1_by_kind(e).into_iter().flatten().collect::<Vec<_>>()).collect();
    most_common(e2.iter())
}

/// The fix for one word, or None to leave it exactly as it is.
pub fn fix_word(w: &str) -> Option<String> {
    fix_lower(w, true)
}

/// Spell-fixes `text`, keeping every space, line break and punctuation mark where it was.
pub fn touch_up(text: &str) -> String {
    touch_up_with(text, &[])
}

/// Same, and never touches a word from `extra_keep` (the user's personal dictionary).
pub fn touch_up_with(text: &str, extra_keep: &[String]) -> String {
    let extra: HashSet<String> = extra_keep.iter().flat_map(|w| w.split_whitespace()).map(|w| w.to_lowercase()).collect();
    crate::rulebooks::per_line(text, |line| {
        let mut sentence_start = true;
        line.split(' ')
            .map(|tok| {
                let (l, core, r) = crate::rulebooks::split3(tok);
                let lower = core.to_lowercase();
                let fixed = if extra.contains(&lower) {
                    None
                } else if core == lower {
                    fix_lower(core, true)
                } else if sentence_start && core.len() >= 3 && core.is_ascii() && core[1..] == lower[1..] {
                    // "Recieve the..." at the start of a sentence. One edit only: a capitalised word anywhere else
                    // (or a name) is left alone.
                    fix_lower(&lower, false).map(|f| crate::rulebooks::cap_first(&f))
                } else {
                    None
                };
                if !core.is_empty() {
                    sentence_start = r.contains(['.', '!', '?']);
                }
                match fixed {
                    Some(f) => format!("{l}{f}{r}"),
                    None => tok.to_string(),
                }
            })
            .collect::<Vec<_>>()
            .join(" ")
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixes_real_typos() {
        assert_eq!(touch_up("I will recieve it tommorow."), "I will receive it tomorrow.");
        assert_eq!(touch_up("teh report is definately seperate"), "the report is definitely separate");
    }

    #[test]
    fn leaves_everything_else_alone() {
        let keep = [
            "Meet Ankit in Gachibowli at 7:30 PM.",
            "Send the PDF to kavitha.rao@gmail.com and open auth_service.py.",
            "I don't think we're gonna make it, yaar.",
            "Get chai and paneer, it's 18 lakhs, haan.",
            "Didi said the EMI is due.",
            "Line one\nline  two with two spaces",
        ];
        for s in keep {
            assert_eq!(touch_up(s), s, "touch up changed {s:?}");
        }
    }

    #[test]
    fn idempotent() {
        let once = touch_up("recieve teh seperate wierd things");
        assert_eq!(touch_up(&once), once);
    }

    #[test]
    fn tech_words_survive_and_get_fixed() {
        assert_eq!(touch_up("we use reactjs and nodejs with docker"), "we use reactjs and nodejs with docker");
        assert_eq!(touch_up("dokcer and kuberentes and postgress"), "docker and kubernetes and postgres");
    }

    #[test]
    fn picks_the_typists_fix() {
        assert_eq!(touch_up("I wnat the mising file"), "I want the missing file");
        assert_eq!(touch_up("Recieve it. Teh end."), "Receive it. The end.");
    }

    #[test]
    fn names_and_personal_dictionary_stay() {
        let keep = "Priya said Ankit and Kavitha met Rohan, Meera and Suresh. Anirudh agreed. Raj and Ali, Ravi too. Ron left.";
        assert_eq!(touch_up(keep), keep);
        assert_eq!(touch_up_with("the kubeflo job", &["kubeflo".to_string()]), "the kubeflo job");
    }
}
