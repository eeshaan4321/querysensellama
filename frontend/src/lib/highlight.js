/**
 * Minimal hand-rolled tokenizer for SQL and Mongo-shell-style query strings.
 *
 * Deliberately not a dependency — a tiny regex pass mirrors the project's
 * own "explainable, rule-based first" ethos better than pulling in a
 * generic syntax-highlighting library for a handful of token classes.
 */

const SQL_KEYWORDS = /\b(SELECT|FROM|WHERE|AND|OR|ORDER BY|GROUP BY|LIMIT|JOIN|ON|INSERT INTO|VALUES|UPDATE|SET|DELETE|COUNT|AS|DISTINCT|HAVING|LIKE|IN|NOT|NULL|IS)\b/gi;
const MONGO_KEYWORDS = /\b(find|aggregate|match|group|sort|limit|regex|gt|gte|lt|lte|ne|in|nin|exists)\b/g;

const RULES = [
  { type: 'string', re: /'[^']*'|"[^"]*"/g },
  { type: 'number', re: /\b\d+(\.\d+)?\b/g },
  { type: 'punct', re: /[(){}[\],;.]/g },
];

export function tokenize(query, dialect) {
  const keywordRe = dialect === 'MONGODB' ? MONGO_KEYWORDS : SQL_KEYWORDS;
  const markers = [];

  function mark(re, type) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(query))) {
      markers.push({ start: m.index, end: m.index + m[0].length, type });
      if (m[0].length === 0) re.lastIndex++;
    }
  }

  mark(keywordRe, 'keyword');
  RULES.forEach(({ re, type }) => mark(re, type));

  markers.sort((a, b) => a.start - b.start || b.end - a.end);
  const kept = [];
  let cursor = 0;
  for (const m of markers) {
    if (m.start >= cursor) {
      kept.push(m);
      cursor = m.end;
    }
  }

  const tokens = [];
  let pos = 0;
  for (const m of kept) {
    if (m.start > pos) tokens.push({ type: 'plain', text: query.slice(pos, m.start) });
    tokens.push({ type: m.type, text: query.slice(m.start, m.end) });
    pos = m.end;
  }
  if (pos < query.length) tokens.push({ type: 'plain', text: query.slice(pos) });
  return tokens;
}
