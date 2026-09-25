import './MongoFindingsList.css';

const SCOPE_NOTE =
  'These are text checks over the query. MongoDB is never connected and the query was '
  + 'never run, so this is not an execution plan — "no findings" means these two checks '
  + 'found nothing, not that the query is fast.';

export function MongoFindingsList({ analysis }) {
  const collection = analysis.tableName;
  const findings = [
    analysis.hasUnindexedFilter && {
      title: 'Unindexed filter field',
      detail: `Filtering on '${analysis.filteredColumn}'${
        collection ? ` in ${collection}` : ''
      } has no matching index in the placeholder index list.`,
    },
    analysis.hasUnanchoredRegex && {
      title: 'Unanchored regular expression',
      detail: '$regex is missing a leading ^ anchor, so MongoDB cannot use an index for this match.',
    },
  ].filter(Boolean);

  return (
    <div>
      {findings.length === 0 ? (
        <p className="mongo-findings__clear">Neither text check found anything in this query.</p>
      ) : (
        <ul className="mongo-findings">
          {findings.map((f, i) => (
            <li key={i} className="mongo-findings__item">
              <span className="mongo-findings__dot" aria-hidden="true" />
              <div>
                <div className="mongo-findings__title">{f.title}</div>
                <div className="mongo-findings__detail">{f.detail}</div>
              </div>
            </li>
          ))}
        </ul>
      )}

      <p className="mongo-findings__scope">{SCOPE_NOTE}</p>
    </div>
  );
}
