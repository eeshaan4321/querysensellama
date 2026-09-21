import './MongoFindingsList.css';

export function MongoFindingsList({ analysis }) {
  const findings = [
    analysis.hasUnindexedFilter && {
      title: 'Unindexed filter field',
      detail: `Filtering on '${analysis.filteredColumn}' has no matching index in the placeholder index list.`,
    },
    analysis.hasUnanchoredRegex && {
      title: 'Unanchored regular expression',
      detail: '$regex is missing a leading ^ anchor, so MongoDB cannot use an index for this match.',
    },
  ].filter(Boolean);

  if (!findings.length) {
    return <p className="mongo-findings__clear">No structural findings for this query.</p>;
  }

  return (
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
  );
}
