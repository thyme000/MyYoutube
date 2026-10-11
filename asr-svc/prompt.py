import re


def prefix(text, budget, count):
    low, high = 0, len(text)
    while low < high:
        mid = (low + high + 1) // 2
        if count(text[:mid]) <= budget:
            low = mid
        else:
            high = mid - 1
    return text[:low]


def excerpt(text, budget, count):
    if count(text) <= budget:
        return text
    sentences = [s.strip() for s in re.split(r"(?<=[。！？.!?\n])", text) if s.strip()]
    if len(sentences) < 3:
        sentences = [text[:len(text) // 3], text[len(text) // 3:2 * len(text) // 3], text[2 * len(text) // 3:]]
    candidates = [sentences[0], sentences[len(sentences) // 2], sentences[-1]]
    parts = [prefix(s, max(0, (budget - count(" … … ")) // 3), count) for s in candidates]
    return prefix(" … ".join(p for p in parts if p), budget, count)


def build_prompt(title, tags, description, count, budget=224):
    result = ""
    for index, text in enumerate((title or "", tags or "", description or "")):
        separator = "\n" if result else ""
        remaining = budget - count(result + separator)
        if remaining <= 0:
            break
        fragment = excerpt(text, remaining, count) if index == 2 else prefix(text, remaining, count)
        result = prefix(result + separator + fragment, budget, count)
    return result
