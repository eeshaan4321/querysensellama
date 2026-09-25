import './PlanTreeView.css';

function PlanNode({ node }) {
  const children = node.children || [];
  const isTable = node.kind === 'table';
  const flags = [
    node.usesTemporaryTable && 'Using temporary',
    node.usesFilesort && 'Using filesort',
  ].filter(Boolean);

  return (
    <li className={`plan-tree__node plan-tree__node--${isTable ? 'table' : 'op'}`}>
      <div className="plan-tree__row">
        <span className="plan-tree__label">{node.label || node.kind}</span>

        {isTable && node.accessType && (
          <span className="plan-tree__chip" title="How MySQL reaches rows in this table">
            {node.accessType}
          </span>
        )}
        {isTable && node.keyUsed && (
          <span className="plan-tree__chip plan-tree__chip--key" title="Index used">
            {node.keyUsed}
          </span>
        )}
        {node.estimatedRows != null && (
          <span className="plan-tree__rows">{node.estimatedRows.toLocaleString()} rows</span>
        )}
        {node.cost != null && (
          <span className="plan-tree__cost" title="MySQL's own cost estimate — unitless">
            cost {node.cost.toLocaleString()}
          </span>
        )}
        {flags.map((flag) => (
          <span key={flag} className="plan-tree__chip plan-tree__chip--warn">{flag}</span>
        ))}
      </div>

      {children.length > 0 && (
        <ul className="plan-tree__children">
          {children.map((child, i) => (
            <PlanNode key={`${child.kind}-${child.label}-${i}`} node={child} />
          ))}
        </ul>
      )}
    </li>
  );
}

/** MySQL's execution plan as the tree it actually is, rather than a flat table list. */
export function PlanTreeView({ tree }) {
  if (!tree) {
    return (
      <p className="plan-tree__empty">
        No plan tree for this query. It comes from a side <code>EXPLAIN FORMAT=JSON</code> probe;
        when that probe cannot run, the per-table plan above is still complete.
      </p>
    );
  }

  return (
    <ul className="plan-tree">
      <PlanNode node={tree} />
    </ul>
  );
}
