(() => {
  // src/client/cache-db.js
  var DB_VERSION = 4;
  var PARTITIONS = "partitions";
  var SESSIONS = "sessions";
  var RECORDS = "records";
  var PAGES = "pages";
  var STAGING = "staging";
  var STAGING_RECORDS = "stagingRecords";
  var STAGING_PAGES = "stagingPages";
  function requestResult(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error("IndexedDB request failed"));
    });
  }
  function transaction(db, stores, mode) {
    if (mode === "readonly") return db.transaction(stores, mode);
    try {
      return db.transaction(stores, mode, { durability: "strict" });
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
      return db.transaction(stores, mode);
    }
  }
  function transactionDone(transaction2) {
    const done = new Promise((resolve, reject) => {
      transaction2.oncomplete = () => resolve();
      transaction2.onabort = () => reject(transaction2.error || new Error("IndexedDB transaction aborted"));
      transaction2.onerror = () => reject(transaction2.error || new Error("IndexedDB transaction failed"));
    });
    done.catch(() => {
    });
    return done;
  }
  function abort(transaction2, error) {
    try {
      transaction2.abort();
    } catch {
    }
    throw error;
  }
  function tupleKey(value) {
    return JSON.stringify(value);
  }
  function validateRecords(from, to, records) {
    if (!Number.isSafeInteger(from) || !Number.isSafeInteger(to) || from < 0 || to < from) {
      throw new RangeError("invalid_sequence_range");
    }
    if (!Array.isArray(records) || records.length !== to - from + 1) throw new Error("non_contiguous_records");
    for (let i = 0; i < records.length; i += 1) {
      const record2 = records[i];
      if (!record2 || record2.type !== "event" || record2.event?.seq !== from + i) throw new Error("non_contiguous_records");
    }
  }
  function mergeCoverage(ranges = [], from, to) {
    const merged = [];
    for (const range of [...ranges, [from, to]].sort((a, b) => a[0] - b[0])) {
      const last = merged.at(-1);
      if (!last || range[0] > last[1] + 1) merged.push([range[0], range[1]]);
      else last[1] = Math.max(last[1], range[1]);
    }
    return merged;
  }
  function isCovered(ranges, from, to) {
    return ranges.some(([start, end]) => start <= from && end >= to);
  }
  function subtractCoverage(ranges, from, to) {
    const next = [];
    for (const [start, end] of ranges) {
      if (end < from || start > to) next.push([start, end]);
      else {
        if (start < from) next.push([start, from - 1]);
        if (end > to) next.push([to + 1, end]);
      }
    }
    return next;
  }
  async function verifyPartition(tx, partitionKey, generation) {
    const row = await requestResult(tx.objectStore(PARTITIONS).get(partitionKey));
    if (row?.retired) abort(tx, new Error("partition_retired"));
    if (generation !== void 0 && row?.generation !== generation) abort(tx, new Error("partition_generation_changed"));
    return row;
  }
  function pageRequestKey(request = {}) {
    return tupleKey({ throughSeq: request.throughSeq, beforeSeq: request.beforeSeq ?? null, maxMessages: request.maxMessages ?? null, turnWindow: request.turnWindow ?? null });
  }
  function openCacheDatabase({ indexedDB = globalThis.indexedDB, name = "dsh-session-cache-sync-v1" } = {}) {
    if (!indexedDB?.open) return Promise.reject(new Error("indexeddb_unavailable"));
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(name, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(PARTITIONS)) db.createObjectStore(PARTITIONS, { keyPath: "key" });
        if (!db.objectStoreNames.contains(SESSIONS)) db.createObjectStore(SESSIONS, { keyPath: ["partitionKey", "addressKey"] });
        if (!db.objectStoreNames.contains(RECORDS)) {
          const records = db.createObjectStore(RECORDS, { keyPath: ["partitionKey", "addressKey", "epoch", "seq"] });
          records.createIndex("bySession", ["partitionKey", "addressKey", "epoch", "seq"]);
        }
        if (!db.objectStoreNames.contains(PAGES)) db.createObjectStore(PAGES, { keyPath: ["partitionKey", "addressKey", "epoch", "requestKey"] });
        if (!db.objectStoreNames.contains(STAGING)) db.createObjectStore(STAGING, { keyPath: ["partitionKey", "addressKey"] });
        if (!db.objectStoreNames.contains(STAGING_RECORDS)) db.createObjectStore(STAGING_RECORDS, { keyPath: ["partitionKey", "addressKey", "epoch", "seq"] });
        if (!db.objectStoreNames.contains(STAGING_PAGES)) db.createObjectStore(STAGING_PAGES, { keyPath: ["partitionKey", "addressKey", "epoch", "requestKey"] });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error("indexeddb_open_failed"));
      request.onblocked = () => reject(new Error("indexeddb_upgrade_blocked"));
    });
  }
  function cachePartitionKey(origin2, resumeScope) {
    if (typeof origin2 !== "string" || !/^https?:\/\//.test(origin2)) throw new TypeError("invalid_origin");
    if (typeof resumeScope !== "string" || !/^[a-f0-9]{48}$/.test(resumeScope)) throw new TypeError("invalid_resume_scope");
    return tupleKey([new URL(origin2).origin, resumeScope]);
  }
  function sessionAddressKey(address) {
    if (address?.kind === "session" && typeof address.sessionId === "string") {
      return tupleKey(["session", address.sessionId]);
    }
    if (address?.kind === "subagent" && typeof address.parentSessionId === "string" && typeof address.childSessionId === "string") {
      return tupleKey(["subagent", address.parentSessionId, address.childSessionId, address.mode || "unknown"]);
    }
    throw new TypeError("invalid_session_address");
  }
  async function activatePartition(db, partitionKey) {
    const tx = transaction(db, [PARTITIONS], "readwrite");
    const done = transactionDone(tx);
    const store = tx.objectStore(PARTITIONS);
    const prior = await requestResult(store.get(partitionKey));
    const generation = prior?.generation ?? 0;
    store.put({ key: partitionKey, generation, retired: false, touchedAt: Date.now() });
    await done;
    return generation;
  }
  async function readSession(db, { partitionKey, address, epoch, from = 0, to = Number.MAX_SAFE_INTEGER, generation }) {
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS], "readonly");
    const done = transactionDone(tx);
    const partition = await requestResult(tx.objectStore(PARTITIONS).get(partitionKey));
    if (partition?.retired && generation === void 0) {
      await done;
      return { metadata: null, records: [] };
    }
    if (partition?.retired || generation !== void 0 && partition?.generation !== generation) {
      await done;
      throw new Error(partition?.retired ? "partition_retired" : "partition_generation_changed");
    }
    const sessions = tx.objectStore(SESSIONS);
    const row = await requestResult(sessions.get([partitionKey, addressKey]));
    if (!row || epoch !== void 0 && row.epoch !== epoch) {
      await done;
      return { metadata: row || null, records: [] };
    }
    if (from > to) {
      await done;
      return { metadata: row, covered: true, records: [] };
    }
    const recordStore = tx.objectStore(RECORDS);
    const range = IDBKeyRange.bound([partitionKey, addressKey, row.epoch, from], [partitionKey, addressKey, row.epoch, to]);
    const records = await requestResult(recordStore.getAll(range));
    await done;
    return {
      metadata: row,
      covered: isCovered(row.coverageRanges || [], from, to),
      records: records.map((item) => item.record)
    };
  }
  async function commitDelta(db, {
    partitionKey,
    address,
    epoch,
    from,
    to,
    records,
    nextResumeToken,
    header,
    projections,
    assistantStream,
    hasMore,
    generation
  }) {
    validateRecords(from, to, records);
    if (typeof partitionKey !== "string" || !partitionKey || typeof epoch !== "string" || !epoch) throw new TypeError("invalid_cache_identity");
    if (typeof nextResumeToken !== "string" || !nextResumeToken) throw new TypeError("invalid_resume_token");
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS], "readwrite");
    const done = transactionDone(tx);
    const partition = await verifyPartition(tx, partitionKey, generation);
    const sessions = tx.objectStore(SESSIONS);
    const prior = await requestResult(sessions.get([partitionKey, addressKey]));
    if (prior && prior.epoch === epoch && prior.cursor + 1 !== from) {
      abort(tx, new Error(from <= prior.cursor ? "stale_delta" : "delta_gap"));
    }
    if (prior && prior.epoch !== epoch && from !== 0) abort(tx, new Error("epoch_requires_reset"));
    const partitionStore = tx.objectStore(PARTITIONS);
    partitionStore.put({ key: partitionKey, generation: partition?.generation || 0, retired: false, touchedAt: Date.now() });
    const recordStore = tx.objectStore(RECORDS);
    for (const record2 of records) {
      const seq = record2.event.seq;
      recordStore.put({ partitionKey, addressKey, epoch, seq, record: record2 });
    }
    const cursor = to;
    const oldestSeq = prior?.epoch === epoch ? Math.min(prior.oldestSeq ?? from, from) : from;
    sessions.put({
      partitionKey,
      addressKey,
      address,
      epoch,
      cursor,
      resumeToken: nextResumeToken,
      oldestSeq,
      coverageRanges: mergeCoverage(prior?.epoch === epoch ? prior.coverageRanges : [], from, to),
      hasMore: hasMore ?? prior?.hasMore ?? false,
      header: header ?? prior?.header,
      projections: projections ?? prior?.projections,
      assistantStream: assistantStream ?? prior?.assistantStream,
      touchedAt: Date.now()
    });
    await done;
    return { cursor, records: records.length };
  }
  async function commitOpening(db, {
    partitionKey,
    address,
    epoch,
    cursor,
    resumeToken,
    header,
    projections,
    assistantStream,
    hasMore,
    generation
  }) {
    if (!Number.isSafeInteger(cursor) || cursor < -1) throw new RangeError("invalid_cursor");
    if (typeof partitionKey !== "string" || !partitionKey || typeof epoch !== "string" || !epoch) throw new TypeError("invalid_cache_identity");
    if (typeof resumeToken !== "string" || !resumeToken) throw new TypeError("invalid_resume_token");
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS], "readwrite");
    const done = transactionDone(tx);
    const partition = await verifyPartition(tx, partitionKey, generation);
    const sessions = tx.objectStore(SESSIONS);
    const prior = await requestResult(sessions.get([partitionKey, addressKey]));
    if (prior && prior.epoch !== epoch) abort(tx, new Error("epoch_requires_reset"));
    tx.objectStore(PARTITIONS).put({ key: partitionKey, generation: partition?.generation || 0, retired: false, touchedAt: Date.now() });
    sessions.put({
      partitionKey,
      addressKey,
      address,
      epoch,
      cursor: prior?.cursor ?? -1,
      resumeToken: prior?.resumeToken ?? `${epoch}:-1`,
      openingCut: cursor,
      oldestSeq: prior?.oldestSeq ?? -1,
      coverageRanges: prior?.coverageRanges || [],
      hasMore: hasMore ?? prior?.hasMore ?? false,
      header: header ?? prior?.header,
      projections: projections ?? prior?.projections,
      assistantStream: assistantStream ?? prior?.assistantStream,
      touchedAt: Date.now()
    });
    await done;
  }
  async function stageOpening(db, { partitionKey, address, epoch, cursor, header, projections, assistantStream, hasMore, preserveRanges = [], generation }) {
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS, PAGES, STAGING, STAGING_RECORDS, STAGING_PAGES], "readwrite");
    const done = transactionDone(tx);
    await verifyPartition(tx, partitionKey, generation);
    const stages = tx.objectStore(STAGING);
    stages.delete([partitionKey, addressKey]);
    const stagingRecords = tx.objectStore(STAGING_RECORDS);
    for (const row of await requestResult(stagingRecords.getAll())) if (row.partitionKey === partitionKey && row.addressKey === addressKey) stagingRecords.delete([row.partitionKey, row.addressKey, row.epoch, row.seq]);
    const stagingPages = tx.objectStore(STAGING_PAGES);
    for (const row of await requestResult(stagingPages.getAll())) if (row.partitionKey === partitionKey && row.addressKey === addressKey) stagingPages.delete([row.partitionKey, row.addressKey, row.epoch, row.requestKey]);
    const active = await requestResult(tx.objectStore(SESSIONS).get([partitionKey, addressKey]));
    if (active) {
      const sourceRecords = tx.objectStore(RECORDS);
      const targetRecords = stagingRecords;
      for (const [from, to] of preserveRanges) {
        const range = IDBKeyRange.bound([partitionKey, addressKey, active.epoch, from], [partitionKey, addressKey, active.epoch, to]);
        for (const row of await requestResult(sourceRecords.getAll(range))) targetRecords.put({ ...row, epoch });
      }
      const targetPages = stagingPages;
      for (const page of await requestResult(tx.objectStore(PAGES).getAll())) {
        if (page.partitionKey !== partitionKey || page.addressKey !== addressKey || page.epoch !== active.epoch || page.empty) continue;
        if (preserveRanges.some(([from, to]) => from <= page.from && to >= page.to)) targetPages.put({ ...page, epoch });
      }
    }
    const coverageRanges = preserveRanges.reduce((ranges, [from, to]) => mergeCoverage(ranges, from, to), []);
    stages.put({
      partitionKey,
      addressKey,
      address,
      epoch,
      cursor: -1,
      targetCut: cursor,
      resumeToken: `${epoch}:-1`,
      coverageRanges,
      oldestSeq: coverageRanges[0]?.[0] ?? -1,
      header,
      projections,
      assistantStream,
      hasMore,
      touchedAt: Date.now()
    });
    await done;
  }
  async function stageRange(db, { partitionKey, address, epoch, from, to, records, generation }) {
    validateRecords(from, to, records);
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, STAGING, STAGING_RECORDS], "readwrite");
    const done = transactionDone(tx);
    await verifyPartition(tx, partitionKey, generation);
    const stages = tx.objectStore(STAGING);
    const stage = await requestResult(stages.get([partitionKey, addressKey]));
    if (!stage || stage.epoch !== epoch) abort(tx, new Error("stale_staging_range"));
    const store = tx.objectStore(STAGING_RECORDS);
    for (const record2 of records) store.put({ partitionKey, addressKey, epoch, seq: record2.event.seq, record: record2 });
    stage.coverageRanges = mergeCoverage(stage.coverageRanges, from, to);
    stage.oldestSeq = stage.oldestSeq < 0 ? from : Math.min(stage.oldestSeq, from);
    stage.touchedAt = Date.now();
    stages.put(stage);
    await done;
  }
  async function activateStaging(db, { partitionKey, address, epoch, cursor, resumeToken, requiredFrom, requiredRanges = [], header, projections, assistantStream, hasMore, generation }) {
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS, PAGES, STAGING, STAGING_RECORDS, STAGING_PAGES], "readwrite");
    const done = transactionDone(tx);
    await verifyPartition(tx, partitionKey, generation);
    const stages = tx.objectStore(STAGING);
    const stage = await requestResult(stages.get([partitionKey, addressKey]));
    if (!stage || stage.epoch !== epoch || cursor !== stage.targetCut) abort(tx, new Error("stale_staging_activation"));
    if (requiredFrom <= cursor && cursor >= 0 && !isCovered(stage.coverageRanges, requiredFrom, cursor)) abort(tx, new Error("staging_cut_not_covered"));
    if (requiredRanges.some(([from, to]) => from <= to && !isCovered(stage.coverageRanges, from, to))) abort(tx, new Error("staging_ranges_not_covered"));
    const rows = await requestResult(tx.objectStore(STAGING_RECORDS).getAll());
    const recordStore = tx.objectStore(RECORDS);
    const pageStore = tx.objectStore(PAGES);
    for (const row of await requestResult(recordStore.getAll())) if (row.partitionKey === partitionKey && row.addressKey === addressKey) recordStore.delete([row.partitionKey, row.addressKey, row.epoch, row.seq]);
    for (const row of await requestResult(pageStore.getAll())) if (row.partitionKey === partitionKey && row.addressKey === addressKey) pageStore.delete([row.partitionKey, row.addressKey, row.epoch, row.requestKey]);
    for (const row of rows) if (row.partitionKey === partitionKey && row.addressKey === addressKey && row.epoch === epoch) recordStore.put(row);
    for (const row of await requestResult(tx.objectStore(STAGING_PAGES).getAll())) if (row.partitionKey === partitionKey && row.addressKey === addressKey && row.epoch === epoch) pageStore.put(row);
    tx.objectStore(SESSIONS).put({
      partitionKey,
      addressKey,
      address,
      epoch,
      cursor,
      resumeToken,
      openingCut: stage.targetCut,
      oldestSeq: stage.oldestSeq,
      coverageRanges: stage.coverageRanges,
      header: header ?? stage.header,
      projections: projections ?? stage.projections,
      assistantStream: assistantStream ?? stage.assistantStream,
      hasMore: hasMore ?? stage.hasMore,
      touchedAt: Date.now()
    });
    for (const row of rows) if (row.partitionKey === partitionKey && row.addressKey === addressKey) tx.objectStore(STAGING_RECORDS).delete([row.partitionKey, row.addressKey, row.epoch, row.seq]);
    for (const row of await requestResult(tx.objectStore(STAGING_PAGES).getAll())) if (row.partitionKey === partitionKey && row.addressKey === addressKey) tx.objectStore(STAGING_PAGES).delete([row.partitionKey, row.addressKey, row.epoch, row.requestKey]);
    stages.delete([partitionKey, addressKey]);
    await done;
  }
  async function commitRange(db, { partitionKey, address, epoch, from, to, records, generation }) {
    validateRecords(from, to, records);
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS], "readwrite");
    const done = transactionDone(tx);
    await verifyPartition(tx, partitionKey, generation);
    const sessions = tx.objectStore(SESSIONS);
    const session = await requestResult(sessions.get([partitionKey, addressKey]));
    if (!session || session.epoch !== epoch) abort(tx, new Error("stale_range"));
    const recordStore = tx.objectStore(RECORDS);
    for (const record2 of records) recordStore.put({ partitionKey, addressKey, epoch, seq: record2.event.seq, record: record2 });
    session.coverageRanges = mergeCoverage(session.coverageRanges, from, to);
    session.oldestSeq = session.oldestSeq < 0 ? from : Math.min(session.oldestSeq, from);
    session.touchedAt = Date.now();
    sessions.put(session);
    await done;
    return { cursor: session.cursor, covered: true };
  }
  async function removeRange(db, { partitionKey, address, epoch, from, to, generation }) {
    if (!Number.isSafeInteger(from) || !Number.isSafeInteger(to) || from < 0 || to < from) throw new RangeError("invalid_sequence_range");
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS], "readwrite");
    const done = transactionDone(tx);
    await verifyPartition(tx, partitionKey, generation);
    const sessions = tx.objectStore(SESSIONS);
    const session = await requestResult(sessions.get([partitionKey, addressKey]));
    if (!session || session.epoch !== epoch) abort(tx, new Error("stale_range"));
    const recordStore = tx.objectStore(RECORDS);
    const range = IDBKeyRange.bound([partitionKey, addressKey, epoch, from], [partitionKey, addressKey, epoch, to]);
    const keys = await requestResult(recordStore.getAllKeys(range));
    for (const key of keys) recordStore.delete(key);
    session.coverageRanges = subtractCoverage(session.coverageRanges || [], from, to);
    session.touchedAt = Date.now();
    sessions.put(session);
    await done;
    return { cursor: session.cursor, coverageRanges: session.coverageRanges };
  }
  async function commitCut(db, { partitionKey, address, epoch, cursor, resumeToken, header, projections, assistantStream, hasMore, requiredFrom = cursor, requiredRanges = [], generation }) {
    if (!Number.isSafeInteger(cursor) || cursor < -1 || typeof resumeToken !== "string" || !resumeToken) throw new TypeError("invalid_commit_cut");
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [SESSIONS, PARTITIONS], "readwrite");
    const done = transactionDone(tx);
    await verifyPartition(tx, partitionKey, generation);
    const sessions = tx.objectStore(SESSIONS);
    const session = await requestResult(sessions.get([partitionKey, addressKey]));
    if (!session || session.epoch !== epoch || cursor < session.cursor) abort(tx, new Error("stale_commit_cut"));
    if (requiredFrom <= cursor && cursor >= 0 && !isCovered(session.coverageRanges || [], requiredFrom, cursor)) abort(tx, new Error("commit_cut_not_covered"));
    if (requiredRanges.some(([from, to]) => from <= to && !isCovered(session.coverageRanges || [], from, to))) abort(tx, new Error("commit_cut_ranges_not_covered"));
    if (session.cursor >= 0 && cursor > session.cursor && !isCovered(session.coverageRanges || [], session.cursor + 1, cursor)) abort(tx, new Error("commit_cut_gap"));
    session.cursor = cursor;
    session.resumeToken = resumeToken;
    if (header !== void 0) session.header = header;
    if (projections !== void 0) session.projections = projections;
    if (assistantStream !== void 0) session.assistantStream = assistantStream;
    if (hasMore !== void 0) session.hasMore = hasMore;
    session.touchedAt = Date.now();
    sessions.put(session);
    const partitionStore = tx.objectStore(PARTITIONS);
    const partition = await requestResult(partitionStore.get(partitionKey));
    partitionStore.put({ key: partitionKey, generation: partition?.generation || 0, touchedAt: Date.now() });
    await done;
    return { cursor, resumeToken };
  }
  async function commitPage(db, { partitionKey, address, epoch, records, hasMore, request = {}, generation }) {
    if (!Array.isArray(records) || records.some((record2) => record2?.type !== "event" || !Number.isSafeInteger(record2.event?.seq))) {
      throw new TypeError("invalid_page_records");
    }
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS, PAGES, STAGING, STAGING_RECORDS, STAGING_PAGES], "readwrite");
    const done = transactionDone(tx);
    await verifyPartition(tx, partitionKey, generation);
    const sessions = tx.objectStore(SESSIONS);
    const session = await requestResult(sessions.get([partitionKey, addressKey]));
    if (!session || session.epoch !== epoch) abort(tx, new Error("stale_page"));
    const sorted = [...records].sort((a, b) => a.event.seq - b.event.seq);
    if (sorted.length) validateRecords(sorted[0].event.seq, sorted.at(-1).event.seq, sorted);
    const recordStore = tx.objectStore(RECORDS);
    for (const record2 of sorted) recordStore.put({ partitionKey, addressKey, epoch, seq: record2.event.seq, record: record2 });
    if (sorted.length) session.oldestSeq = Math.min(session.oldestSeq ?? sorted[0].event.seq, sorted[0].event.seq);
    if (sorted.length) session.coverageRanges = mergeCoverage(session.coverageRanges, sorted[0].event.seq, sorted.at(-1).event.seq);
    session.hasMore = Boolean(hasMore);
    session.touchedAt = Date.now();
    sessions.put(session);
    tx.objectStore(PAGES).put({
      partitionKey,
      addressKey,
      epoch,
      requestKey: pageRequestKey(request),
      from: sorted[0]?.event.seq ?? null,
      to: sorted.at(-1)?.event.seq ?? null,
      empty: sorted.length === 0,
      hasMore: Boolean(hasMore),
      touchedAt: Date.now()
    });
    await done;
    return { records: sorted.length, oldestSeq: session.oldestSeq, cursor: session.cursor, resumeToken: session.resumeToken };
  }
  async function readPage(db, { partitionKey, address, epoch, request, generation }) {
    const addressKey = sessionAddressKey(address);
    const tx = transaction(db, [PARTITIONS, SESSIONS, PAGES, RECORDS], "readonly");
    const done = transactionDone(tx);
    const partition = await requestResult(tx.objectStore(PARTITIONS).get(partitionKey));
    if (partition?.retired || generation !== void 0 && partition?.generation !== generation) {
      await done;
      throw new Error(partition?.retired ? "partition_retired" : "partition_generation_changed");
    }
    const session = await requestResult(tx.objectStore(SESSIONS).get([partitionKey, addressKey]));
    const keyEpoch = epoch ?? session?.epoch;
    const row = keyEpoch ? await requestResult(tx.objectStore(PAGES).get([partitionKey, addressKey, keyEpoch, pageRequestKey(request)])) : void 0;
    if (!row || !row.empty && (!session || session.epoch !== keyEpoch || !isCovered(session.coverageRanges || [], row.from, row.to))) {
      await done;
      return null;
    }
    const records = row.empty ? [] : await requestResult(tx.objectStore(RECORDS).getAll(IDBKeyRange.bound([partitionKey, addressKey, keyEpoch, row.from], [partitionKey, addressKey, keyEpoch, row.to])));
    await done;
    return { records: records.map((item) => item.record), hasMore: row.hasMore };
  }
  async function clearPartition(db, partitionKey) {
    const tx = transaction(db, [PARTITIONS, SESSIONS, RECORDS, PAGES, STAGING, STAGING_RECORDS, STAGING_PAGES], "readwrite");
    const done = transactionDone(tx);
    const sessions = tx.objectStore(SESSIONS);
    const records = tx.objectStore(RECORDS);
    const pages = tx.objectStore(PAGES);
    const staging = tx.objectStore(STAGING);
    const stagingRecords = tx.objectStore(STAGING_RECORDS);
    const stagingPages = tx.objectStore(STAGING_PAGES);
    const sessionRows = await requestResult(sessions.getAll());
    const recordRows = await requestResult(records.getAll());
    const pageRows = await requestResult(pages.getAll());
    const stagingRows = await requestResult(stagingRecords.getAll());
    const stagingPageRows = await requestResult(stagingPages.getAll());
    for (const row of sessionRows) if (row.partitionKey === partitionKey) sessions.delete([row.partitionKey, row.addressKey]);
    for (const row of recordRows) if (row.partitionKey === partitionKey) records.delete([row.partitionKey, row.addressKey, row.epoch, row.seq]);
    for (const row of pageRows) if (row.partitionKey === partitionKey) pages.delete([row.partitionKey, row.addressKey, row.epoch, row.requestKey]);
    for (const row of stagingRows) if (row.partitionKey === partitionKey) stagingRecords.delete([row.partitionKey, row.addressKey, row.epoch, row.seq]);
    for (const row of stagingPageRows) if (row.partitionKey === partitionKey) stagingPages.delete([row.partitionKey, row.addressKey, row.epoch, row.requestKey]);
    for (const row of await requestResult(staging.getAll())) if (row.partitionKey === partitionKey) staging.delete([row.partitionKey, row.addressKey]);
    const prior = await requestResult(tx.objectStore(PARTITIONS).get(partitionKey));
    tx.objectStore(PARTITIONS).put({ key: partitionKey, generation: (prior?.generation || 0) + 1, retired: true, touchedAt: Date.now() });
    await done;
  }
  function closeCacheDatabase(db) {
    db?.close();
  }

  // src/record-hash.js
  function canonicalJson(value) {
    if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
    if (value && typeof value === "object") {
      return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
    }
    return JSON.stringify(value);
  }

  // src/client/sync-owner.js
  var MAX_WIRE_FRAME_BYTES = 5 * 1024 * 1024;
  var MAX_RECORD_CHUNKS = 2048;
  var MAX_CHUNKED_RECORD_BYTES = 48 * 1024 * 1024;
  function fromBase64(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }
  async function hashRecord(record2, cryptoApi) {
    const bytes = new TextEncoder().encode(canonicalJson(record2));
    const digest = await cryptoApi.subtle.digest("SHA-256", bytes);
    return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  }
  async function cacheManifest(db, { partitionKey, address, cryptoApi, generation }) {
    const cached = await readSession(db, { partitionKey, address, generation });
    const hashes = {};
    for (const record2 of cached.records) hashes[record2.event.seq] = await hashRecord(record2, cryptoApi);
    return { metadata: cached.metadata, coverageRanges: cached.metadata?.coverageRanges || [], hashes };
  }
  function createSyncOwner({ db, origin: origin2, hanaccountAuth, remote, cryptoApi = globalThis.crypto, protocol = 1, onEpochReset }) {
    if (!db || !hanaccountAuth?.requestRpcGrant || !remote?.sessionCacheSync) throw new TypeError("sync_owner_dependencies_missing");
    const operations = /* @__PURE__ */ new Map();
    const activeOperations = /* @__PURE__ */ new Set();
    const knownPartitions = /* @__PURE__ */ new Map();
    const retireTasks = /* @__PURE__ */ new Set();
    let disposed = false;
    let lifetimeController = new AbortController();
    let authSnapshot = hanaccountAuth.getSnapshot?.() || null;
    function linkSignals(...signals) {
      const active = signals.filter(Boolean);
      const controller = new AbortController();
      const handlers = /* @__PURE__ */ new Map();
      for (const signal of active) {
        const abort2 = () => controller.abort(signal.reason);
        handlers.set(signal, abort2);
        if (signal.aborted) abort2();
        else signal.addEventListener("abort", abort2, { once: true });
      }
      return { signal: controller.signal, dispose() {
        for (const [signal, abort2] of handlers) signal.removeEventListener("abort", abort2);
      } };
    }
    function fenceScope(scope2) {
      const partitionKey = knownPartitions.get(scope2);
      for (const operation of operations.values()) if (operation.scope === scope2) operation.controller.abort(new Error("auth_scope_changed"));
      if (partitionKey) {
        const task = clearPartition(db, partitionKey).catch(() => {
        });
        retireTasks.add(task);
        task.finally(() => retireTasks.delete(task));
      }
    }
    function observeAuth(next) {
      const prior = authSnapshot;
      authSnapshot = next;
      if (prior?.scope && (next?.status === "unauthenticated" || next?.status === "authenticated" && next.scope !== prior.scope)) {
        lifetimeController.abort(new Error("auth_scope_changed"));
        lifetimeController = new AbortController();
        fenceScope(prior.scope);
      }
    }
    const unsubscribeAuth = hanaccountAuth.subscribe?.(observeAuth);
    async function identity(address, signal) {
      if (disposed) throw new Error("session_cache_owner_disposed");
      const grant = await hanaccountAuth.requestRpcGrant({ signal });
      const partitionKey = cachePartitionKey(origin2, grant.scope);
      knownPartitions.set(grant.scope, partitionKey);
      const generation = await activatePartition(db, partitionKey);
      return { grant, partitionKey, address, generation };
    }
    async function* followAttempt(request, signal, operation) {
      const { grant, partitionKey, generation } = await identity(request.address, signal);
      operation.scope = grant.scope;
      const manifest = await cacheManifest(db, { partitionKey, address: request.address, cryptoApi, generation });
      const wire = remote.sessionCacheSync.open({
        protocol,
        grant: grant.grant,
        scope: grant.scope,
        address: request.address,
        ...manifest.metadata?.epoch ? { epoch: manifest.metadata.epoch } : {},
        cursor: manifest.metadata?.cursor ?? -1,
        coverageRanges: manifest.coverageRanges,
        hashes: manifest.hashes,
        ...request.maxMessages === void 0 ? {} : { maxMessages: request.maxMessages },
        ...request.turnWindow === void 0 ? {} : { turnWindow: request.turnWindow }
      }, signal);
      let opening;
      let staging = false;
      let caughtUp = false;
      const chunks = /* @__PURE__ */ new Map();
      for await (const frame of wire) {
        signal?.throwIfAborted();
        if (frame.type === "opening") {
          opening = frame;
          const prior = manifest.metadata;
          staging = frame.reset || Boolean(prior && prior.epoch !== frame.epoch);
          if (staging && prior && prior.epoch !== frame.epoch && !frame.reset) throw Object.assign(new Error("unannounced_epoch_reset"), { code: "unannounced_epoch_reset" });
          const openingArgs = {
            partitionKey,
            address: request.address,
            epoch: frame.epoch,
            cursor: frame.cut,
            resumeToken: `${frame.epoch}:-1`,
            header: frame.header,
            generation,
            projections: frame.projections,
            assistantStream: frame.assistantStream,
            hasMore: frame.hasMore,
            requiredRanges: frame.requiredRanges
          };
          if (staging) await stageOpening(db, { ...openingArgs, preserveRanges: frame.unchangedRanges });
          else {
            await commitOpening(db, openingArgs);
            for (const [from, to] of frame.removedRanges) await removeRange(db, { partitionKey, address: request.address, epoch: frame.epoch, from, to, generation });
          }
          continue;
        }
        if (!opening) throw new Error("cache_opening_frame_missing");
        if (frame.type === "records") {
          const commit = staging ? stageRange : commitRange;
          await commit(db, { partitionKey, address: request.address, epoch: opening.epoch, from: frame.from, to: frame.to, records: frame.records, generation });
        } else if (frame.type === "record-chunk") {
          if (new TextEncoder().encode(JSON.stringify(frame)).byteLength > MAX_WIRE_FRAME_BYTES || frame.total > MAX_RECORD_CHUNKS || frame.data.length > MAX_WIRE_FRAME_BYTES) throw new Error("record_chunk_exceeds_client_limit");
          let part = chunks.get(frame.seq);
          if (!part) {
            part = { total: frame.total, sha256: frame.sha256, live: Boolean(frame.live), resumeToken: frame.resumeToken, data: new Array(frame.total).fill(void 0), receivedCount: 0, encodedBytes: 0 };
            chunks.set(frame.seq, part);
          }
          if (part.total !== frame.total || part.sha256 !== frame.sha256 || part.live !== Boolean(frame.live) || part.resumeToken !== frame.resumeToken || frame.index >= part.total || part.data[frame.index] !== void 0) throw new Error("invalid_record_chunk_sequence");
          part.data[frame.index] = frame.data;
          part.receivedCount += 1;
          part.encodedBytes += frame.data.length;
          if (part.encodedBytes > MAX_CHUNKED_RECORD_BYTES) throw new Error("record_chunked_record_too_large");
          if (part.receivedCount === part.total && part.data.every((item) => item !== void 0)) {
            const jsonBytes = fromBase64(part.data.join(""));
            const digest = await cryptoApi.subtle.digest("SHA-256", jsonBytes);
            const actualHash = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
            if (actualHash !== part.sha256) throw new Error("record_chunk_hash_mismatch");
            const record2 = JSON.parse(new TextDecoder().decode(jsonBytes));
            if (record2?.event?.seq !== frame.seq) throw new Error("record_chunk_sequence_mismatch");
            if (part.live) {
              if (staging) throw new Error("live_event_during_epoch_staging");
              await commitDelta(db, { partitionKey, address: request.address, epoch: opening.epoch, from: frame.seq, to: frame.seq, records: [record2], nextResumeToken: part.resumeToken, generation });
              yield record2;
            } else {
              const commit = staging ? stageRange : commitRange;
              await commit(db, { partitionKey, address: request.address, epoch: opening.epoch, from: frame.seq, to: frame.seq, records: [record2], generation });
            }
            chunks.delete(frame.seq);
          }
        } else if (frame.type === "caught-up") {
          if (chunks.size) throw new Error("incomplete_record_chunks");
          const cutArgs = {
            partitionKey,
            address: request.address,
            epoch: opening.epoch,
            cursor: frame.cut,
            resumeToken: frame.resumeToken,
            header: opening.header,
            projections: opening.projections,
            assistantStream: opening.assistantStream,
            hasMore: opening.hasMore,
            requiredFrom: opening.windowFrom,
            requiredRanges: opening.requiredRanges,
            generation
          };
          if (staging) {
            await activateStaging(db, cutArgs);
            if (onEpochReset) throw Object.assign(new Error("session_epoch_reset_remount"), { code: "session_epoch_reset_remount" });
          } else await commitCut(db, cutArgs);
          caughtUp = true;
          const metadata = await readSession(db, { partitionKey, address: request.address, epoch: opening.epoch, generation });
          const tailRange = metadata.metadata?.coverageRanges?.find(([from, to]) => from <= opening.cut && to >= opening.cut);
          const visibleFrom = tailRange ? Math.min(opening.windowFrom, tailRange[0]) : opening.windowFrom;
          const snapshot = opening.cut < visibleFrom ? { records: [], covered: true } : await readSession(db, { partitionKey, address: request.address, epoch: opening.epoch, from: visibleFrom, to: opening.cut, generation });
          if (!snapshot.covered) throw new Error("cache_snapshot_window_gap");
          yield {
            type: "snapshot",
            header: opening.header,
            cursor: opening.cut,
            records: snapshot.records,
            hasMore: opening.hasMore && visibleFrom > 0,
            projections: opening.projections,
            assistantStream: opening.assistantStream
          };
        } else if (frame.type === "event") {
          if (!caughtUp) throw new Error("live_event_before_catchup");
          const record2 = frame.record;
          await commitDelta(db, {
            partitionKey,
            address: request.address,
            epoch: opening.epoch,
            from: record2.event.seq,
            to: record2.event.seq,
            records: [record2],
            nextResumeToken: frame.resumeToken,
            generation
          });
          yield record2;
        } else if (frame.type === "assistant-stream") {
          if (!caughtUp) throw new Error("assistant_frame_before_catchup");
          yield { type: "assistant-stream", frame: frame.frame };
        }
      }
      if (!opening || !caughtUp) throw new Error("cache_stream_ended_before_commit");
    }
    async function* follow(request, signal) {
      const key = JSON.stringify(request.address);
      if (operations.has(key)) throw Object.assign(new Error("session_sync_already_active"), { code: "session_sync_already_active" });
      let finishOperation;
      const operation = { scope: null, controller: new AbortController(), done: new Promise((resolve) => {
        finishOperation = resolve;
      }) };
      operations.set(key, operation);
      activeOperations.add(operation);
      const linked = linkSignals(signal, lifetimeController.signal, operation.controller.signal);
      try {
        for (let attempt = 0; attempt < 2; attempt += 1) {
          try {
            yield* followAttempt(request, linked.signal, operation);
            return;
          } catch (error) {
            if (error?.code === "session_epoch_reset_remount" && onEpochReset) {
              if (operations.get(key) === operation) operations.delete(key);
              await onEpochReset({ address: request.address });
              return;
            }
            if (error?.code !== "session_cut_changed_retry" || attempt > 0) throw error;
            linked.signal.throwIfAborted();
          }
        }
      } finally {
        linked.dispose();
        if (operations.get(key) === operation) operations.delete(key);
        activeOperations.delete(operation);
        finishOperation();
      }
    }
    async function page(request, signal) {
      const linked = linkSignals(signal, lifetimeController.signal);
      if (disposed) throw new Error("session_cache_owner_disposed");
      const operation = { controller: { abort: () => {
      } }, done: null };
      let finishOperation;
      operation.done = new Promise((resolve) => {
        finishOperation = resolve;
      });
      activeOperations.add(operation);
      try {
        const { grant, partitionKey, generation } = await identity(request.address, linked.signal);
        operation.scope = grant.scope;
        const cached = await readPage(db, { partitionKey, address: request.address, request, generation });
        if (cached) return cached;
        const response = await remote.sessionCacheSync.page({ protocol, grant: grant.grant, scope: grant.scope, ...request }, linked.signal);
        if (!response?.ok) throw response?.error || new Error("session_cache_page_failed");
        const page2 = response.value;
        const metadata = await readSession(db, { partitionKey, address: request.address, generation });
        if (!metadata.metadata) throw new Error("cache_session_open_required");
        await commitPage(db, { partitionKey, address: request.address, epoch: metadata.metadata.epoch, records: page2.records, hasMore: page2.hasMore, request, generation });
        return page2;
      } finally {
        linked.dispose();
        activeOperations.delete(operation);
        finishOperation();
      }
    }
    async function dispose() {
      if (disposed) return;
      disposed = true;
      unsubscribeAuth?.();
      lifetimeController.abort(new Error("session_cache_owner_disposed"));
      for (const operation of operations.values()) operation.controller.abort(new Error("session_cache_owner_disposed"));
      await Promise.allSettled([...retireTasks, ...[...activeOperations].map((operation) => operation.done)]);
    }
    return Object.freeze({ follow, page, dispose, cachePartitionKey, hashRecord: (record2) => hashRecord(record2, cryptoApi), operations });
  }

  // fixtures/device-cache-browser.js
  var origin = globalThis.location.origin;
  var scope = "e".repeat(48);
  var addresses = ["A", "B"].map((sessionId) => ({ kind: "session", sessionId }));
  var dbName = "dsh-session-cache-device-fixture-v1";
  var record = (sessionId, seq) => ({
    type: "event",
    event: { type: "user/message", seq, time: 18e11 + seq, data: { content: `${sessionId}-message-${seq}` } }
  });
  var allRecords = new Map(addresses.map(({ sessionId }) => [sessionId, Array.from({ length: 6 }, (_, seq) => record(sessionId, seq))]));
  var windows = /* @__PURE__ */ new Map([["A", { from: 3, cut: 5 }], ["B", { from: 2, cut: 5 }]]);
  function framesFor(address) {
    const { sessionId } = address;
    const records = allRecords.get(sessionId);
    const { from, cut } = windows.get(sessionId);
    return [
      {
        type: "opening",
        epoch: `epoch-${sessionId}`,
        reset: false,
        cut,
        windowFrom: from,
        requiredRanges: [[from, cut]],
        unchangedRanges: [],
        changedRanges: [[from, cut]],
        removedRanges: [],
        header: { id: sessionId },
        projections: { asOfSeq: cut, values: { title: `${sessionId} fixture` } },
        hasMore: true,
        assistantStream: null
      },
      { type: "records", from, to: cut, records: records.slice(from, cut + 1), cut, resumeToken: `epoch-${sessionId}:${cut}` },
      { type: "caught-up", cut, resumeToken: `epoch-${sessionId}:${cut}` }
    ];
  }
  async function seedUsingSyncOwner(db) {
    const owner = createSyncOwner({
      db,
      origin,
      hanaccountAuth: { getSnapshot: () => ({ status: "authenticated", scope }), requestRpcGrant: async () => ({ grant: "device-fixture-memory-grant", scope }) },
      remote: { sessionCacheSync: {
        open(request) {
          return (async function* () {
            yield* framesFor(request.address);
          })();
        },
        async page(request) {
          const { sessionId } = request.address;
          const records = allRecords.get(sessionId);
          const before = request.beforeSeq;
          const from = Math.max(0, before - (sessionId === "A" ? 3 : 2));
          return { ok: true, value: { records: records.slice(from, before), hasMore: from > 0 } };
        }
      } }
    });
    try {
      for (const address of addresses) {
        const stream = owner.follow({ address });
        const snapshot = await stream.next();
        if (snapshot.done || snapshot.value.type !== "snapshot") throw new Error(`snapshot_missing:${address.sessionId}`);
        await stream.next();
        const beforeSeq = windows.get(address.sessionId).from;
        const requested = address.sessionId === "A" ? 3 : 2;
        const page = await owner.page({ address, throughSeq: 5, beforeSeq, maxMessages: requested });
        if (page.records.length !== requested) throw new Error(`old_page_missing:${address.sessionId}`);
      }
    } finally {
      await owner.dispose();
    }
  }
  async function readPersistedFixture(db) {
    const partitionKey = cachePartitionKey(origin, scope);
    const sessions = {};
    for (const address of addresses) {
      const { sessionId } = address;
      const { metadata, records } = await readSession(db, { partitionKey, address });
      const from = sessionId === "A" ? 0 : 0;
      const pageRequest = { throughSeq: 5, beforeSeq: windows.get(sessionId).from, maxMessages: sessionId === "A" ? 3 : 2 };
      const page = await readPage(db, { partitionKey, address, epoch: `epoch-${sessionId}`, request: pageRequest });
      if (!metadata || metadata.cursor !== 5 || metadata.resumeToken !== `epoch-${sessionId}:5`) throw new Error(`metadata_mismatch:${sessionId}`);
      if (records.length !== 6 || records[0].event.data.content !== `${sessionId}-message-${from}`) throw new Error(`history_mismatch:${sessionId}`);
      if (!page || page.records.length !== pageRequest.maxMessages || page.records[0].event.seq !== 0) throw new Error(`page_mismatch:${sessionId}`);
      sessions[sessionId] = {
        cursor: metadata.cursor,
        resumeToken: metadata.resumeToken,
        coverageRanges: metadata.coverageRanges,
        seqs: records.map((item) => item.event.seq),
        oldPageSeqs: page.records.map((item) => item.event.seq)
      };
    }
    return sessions;
  }
  globalThis.runSessionCacheDeviceFixture = async (operation) => {
    const db = await openCacheDatabase({ name: dbName });
    try {
      if (operation === "write") {
        await seedUsingSyncOwner(db);
        const results = await readPersistedFixture(db);
        return { operation, results };
      }
      if (operation === "read") return { operation, results: await readPersistedFixture(db) };
      throw new Error(`unknown_operation:${operation}`);
    } finally {
      closeCacheDatabase(db);
    }
  };
})();
