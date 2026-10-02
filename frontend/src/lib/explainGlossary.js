export const ACCESS_TYPE_GLOSS = {
  ALL: 'Reads every row in the table, one at a time.',
  index: 'Scans the whole index rather than the table — cheaper than reading every row, but still a scan.',
  range: 'Reads a contiguous slice of an index, such as one date range.',
  ref: 'Looks rows up through an index.',
  eq_ref: 'Finds at most one matching row per row of the table it is joined to.',
  const: 'Finds at most one matching row, resolved once.',
  system: 'The table holds a single row, read once.',
  NULL: 'MySQL answered without reading this table at all.',
};

export function accessTypeGloss(accessType) {
  if (!accessType) return null;
  const exact = ACCESS_TYPE_GLOSS[accessType];
  if (exact) return exact;
  const key = Object.keys(ACCESS_TYPE_GLOSS).find(
    (k) => k.toLowerCase() === String(accessType).toLowerCase(),
  );
  return key ? ACCESS_TYPE_GLOSS[key] : null;
}

export const FIELD_GLOSS = {
  keyUsed: 'The index MySQL actually chose for this table.',
  possibleKeys:
    'Indexes MySQL judged usable for this query. If this lists an index but none was chosen, the optimiser estimated that reading the table directly was cheaper.',
  estimatedRows: 'How many rows MySQL expects to read from this table.',
  estimatedRowsReturned: 'How many of those rows it expects to survive the WHERE clause.',
  ref: 'What the index was compared against — usually a column from the other side of a join.',
  fullTableScan: 'Whether MySQL read the table end to end instead of using an index.',
  usesFilesort:
    'Results had to be sorted after being read, because no index supplied them in the order this query asked for. Despite the name, this usually happens in memory rather than on disk.',
  usesTemporaryTable:
    'MySQL built an intermediate result set to finish the GROUP BY or DISTINCT, rather than producing rows directly.',
};

export const QUERY_COST_CAVEAT =
  "MySQL's own estimate of the effort this plan takes. It has no unit — it is only meaningful compared against another plan for the same query.";

export const MONGO_ACCESS_TYPE_GLOSS = {
  COLLSCAN: 'Reads every document in the collection, one at a time.',
  IXSCAN: 'Walks an index to find the matching documents.',
  EXPRESS_IXSCAN: "Finds a single document through a unique index, on MongoDB's fast path.",
  EXPRESS_IDHACK: "Finds a single document by its _id, on MongoDB's fast path.",
  IDHACK: 'Finds a single document by its _id.',
  FETCH: 'Reads the documents an index scan pointed to.',
  EQ_LOOKUP: 'Joins in documents from another collection ($lookup).',
  COUNT_SCAN: 'Counts index entries without reading the documents.',
};

export function mongoAccessTypeGloss(stage) {
  return stage ? MONGO_ACCESS_TYPE_GLOSS[stage] ?? null : null;
}

export const MONGO_FIELD_GLOSS = {
  keyUsed: 'The index MongoDB actually used.',
  possibleKeys:
    'Indexes that a plan MongoDB tried and rejected would have used. If one is listed but none was chosen, MongoDB found that reading the collection directly did less work.',
  estimatedRows: 'How many documents MongoDB examined, counted while running the plan with explain.',
  estimatedRowsReturned: 'How many of those documents matched and were returned.',
  fullTableScan: 'Whether MongoDB read every document in the collection instead of using an index.',
  usesFilesort:
    'Documents had to be sorted in memory after being read, because no index supplied them in the order this query asked for.',
  usesTemporaryTable:
    'A grouping stage needed more memory than MongoDB allows one stage, so part of the work was written to disk and read back.',
  executionMillis:
    'How long MongoDB took to run the plan under explain. Nothing was written, and no documents were sent back to QuerySense.',
};

export const MONGO_PLAN_FOOTNOTE =
  "These figures are MongoDB's own, counted while running the plan with explain at executionStats — measured, not estimated. MongoDB reports no cost figure for a plan, so none is shown. Nothing is written: a delete is planned, not run.";
