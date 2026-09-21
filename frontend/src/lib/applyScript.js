const PLACEHOLDER = /<[^>]+>/;

function isRunnable(action, isMongo) {
  if (!action) return false;
  if (PLACEHOLDER.test(action)) return false;

  const trimmed = action.trim();
  return isMongo ? trimmed.startsWith('db.') : /^create\s/i.test(trimmed);
}

function commentLines(text, marker, width = 72) {
  const words = text.split(/\s+/);
  const lines = [];
  let current = '';

  for (const word of words) {
    if (current && (current + ' ' + word).length > width) {
      lines.push(`${marker} ${current}`);
      current = word;
    } else {
      current = current ? `${current} ${word}` : word;
    }
  }
  if (current) lines.push(`${marker} ${current}`);

  return lines;
}

export function buildApplyScript(recommendations, context = {}) {
  const { databaseType = 'MySQL', catalog = null } = context;
  const isMongo = String(databaseType).toLowerCase() === 'mongodb';
  const marker = isMongo ? '//' : '--';
  const dialect = isMongo ? 'MONGODB' : 'SQL';

  const list = recommendations || [];
  const runnable = list.filter((r) => isRunnable(r.suggestedAction, isMongo));
  const review = list.filter((r) => !isRunnable(r.suggestedAction, isMongo));

  if (list.length === 0) {
    return { script: '', runnableCount: 0, reviewCount: 0, dialect };
  }

  const lines = [];

  const target = catalog || 'the connected database';
  const counts = [
    `${runnable.length} statement${runnable.length === 1 ? '' : 's'}`,
    review.length ? `${review.length} note${review.length === 1 ? '' : 's'}` : null,
  ]
    .filter(Boolean)
    .join(', ');

  lines.push(`${marker} QuerySense schema advisor · ${counts}`);
  lines.push(`${marker} Anchored to EXPLAIN over ${target}.`);
  lines.push(
    ...commentLines(
      'Each recommendation is emitted independently — review for overlap before running.',
      marker
    )
  );

  if (runnable.length) {
    lines.push('');
    for (const rec of runnable) {
      lines.push(...commentLines(rec.summary || rec.reason, marker));
      lines.push(rec.suggestedAction.trim());
      lines.push('');
    }
  }

  if (review.length) {
    lines.push('');
    lines.push(`${marker} REVIEW (not runnable):`);
    for (const rec of review) {
      const label = [rec.targetTable, rec.targetColumn].filter(Boolean).join('.');
      lines.push(`${marker}`);
      if (label) lines.push(`${marker} ${label}`);
      lines.push(...commentLines(rec.suggestedAction, marker));
    }
  }

  return {
    script: lines.join('\n').trim(),
    runnableCount: runnable.length,
    reviewCount: review.length,
    dialect,
  };
}
