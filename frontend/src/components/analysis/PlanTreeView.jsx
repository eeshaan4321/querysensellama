import './PlanTreeView.css';

function PlanNode({ node, isMongo }) {
  const children = node.children || [];
  const isTable = isMongo || node.kind === 'table';
  const flags = isMongo
    ? [node.usesTemporaryTable && 'Spilled to disk', node.usesFilesort && 'In-memory sort'].filter(Boolean)
    : [node.usesTemporaryTable && 'Using temporary', node.usesFilesort && 'Using filesort'].filter(Boolean);

  return (
    <li className={`plan-tree__node plan-tree__node--${isTable ? 'table' : 'op'}`}>
      <div className="plan-tree__row">
        <span className="plan-tree__label">{isMongo ? node.kind : node.label || node.kind}</span>

        {isMongo && node.label && node.label !== node.keyUsed && node.label !== node.kind && (
          <span className="plan-tree__chip" title="The collection this stage reads">
            {node.label}
          </span>
        )}
        {!isMongo && isTable && node.accessType && (
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
          <span className="plan-tree__rows">
            {node.estimatedRows.toLocaleString()} {isMongo ? 'documents' : 'rows'}
          </span>
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
            <PlanNode key={`${child.kind}-${child.label}-${i}`} node={child} isMongo={isMongo} />
          ))}
        </ul>
      )}
    </li>
  );
}

/** The execution plan as the tree it actually is: MySQL's operations, or MongoDB's stages. */
export function PlanTreeView({ tree, dialect }) {
  const isMongo = dialect === 'MONGODB';

  if (!tree) {
    return (
      <p className="plan-tree__empty">
        {isMongo ? (
          'No plan tree for this query: MongoDB returned no winning plan to draw.'
        ) : (
          <>
            No plan tree for this query. It comes from a side <code>EXPLAIN FORMAT=JSON</code> probe;
            when that probe cannot run, the per-table plan above is still complete.
          </>
        )}
      </p>
    );
  }

  return (
    <ul className="plan-tree">
      <PlanNode node={tree} isMongo={isMongo} />
    </ul>
  );
}
