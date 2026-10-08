"use strict";
const $ = (id) => document.getElementById(id);
const state = {
  corpus: null,
  graph: null,
  cy: null,
  mode: "LOCAL",
  stream: null,
  sequence: 0,
  trace: null,
  index: 0,
  timer: null,
  comparison: null,
  expected: new Set(),
  epoch: 0,
  busy: false,
  switching: false,
};
const help = {
  upload: [
    "Upload sources",
    "A corpus is one collection of documents. Upload UTF-8 .txt files or searchable .pdf files. Scanned PDFs need OCR first. Passages overlap to preserve nearby context; a separate smaller-chunk index provides the vector baseline. Offline extraction creates explicit sentence co-mentions, not inferred facts.",
  ],
  history: [
    "Corpus history",
    "Each collection is saved independently. Switching restores its graph and sources without rebuilding. The active selection survives application restarts. Interrupted ingestion is marked FAILED; create a new corpus to retry.",
  ],
  modes: [
    "Four ways to retrieve",
    "Local starts with matching entities and expands one hop through relationships. Global selects community summaries and their source passages. DRIFT creates bounded follow-up questions, then runs Local retrieval for each branch. Vector ranks smaller source chunks by cosine similarity. Deterministic offline mode uses keyword graph matching and hashed term vectors.",
  ],
  settings: [
    "Explore the graph",
    "Drag nodes, pan the canvas, scroll to zoom, or use Fit. Search by entity name to center a node. Entity colors distinguish types. Community regions can be hidden independently; this does not remove community data or change retrieval. Search suggestions and the community list provide keyboard navigation.",
  ],
  communities: [
    "Connected communities",
    "A community contains entities that are strongly connected. Neo4j deployments use weighted Leiden detection; the file profile uses deterministic weighted label propagation. Groups with fewer than three members are omitted. Summaries help broad retrieval, but citations always open actual source text.",
  ],
  answers: [
    "Grounded answers",
    "Answers use only the retrieved source context. Numbered citations are validated on the server and open their original passage or vector chunk. Extractive offline answers quote relevant source sentences. Citation presence does not prove that every claim is correct; inspect the source. No evidence produces a clear no-answer result.",
  ],
  replay: [
    "Replay actual retrieval",
    "The timeline records the operations executed for this answer: selected entities, traversed relationships, community choices, follow-ups and sources. Play, pause, or choose any step. Orange marks the active operation. DRIFT shows recorded parent-child branches. This is evidence flow, not private model reasoning. Traces are held in memory and expire after restart or after 1,000 recent answers; corpora remain saved.",
  ],
  comparison: [
    "Compare measured results",
    "Run a graph method against Vector, or all four methods sequentially. Each method retrieves fresh context for the same question and corpus. Inspect source coverage, citations and recorded steps. Retrieval, embedding and generation timings are separate and do not overlap. Small timings can round to zero milliseconds. Successful methods remain visible if another fails.",
  ],
  diagnostics: [
    "Expected evidence diagnostics",
    "Check a source you expected to see. NOT RETRIEVED suggests a retrieval gap. BELOW CUTOFF suggests a ranking gap. IN CONTEXT without a citation suggests a possible generation gap. These are observed states, not proof of correctness. Passage vector states map overlapping chunk character ranges; chunk rows retain the precise source identities.",
  ],
};
function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text != null) n.textContent = text;
  if (cls) n.className = cls;
  return n;
}
function button(text, action, cls) {
  const b = el("button", text, cls);
  b.type = "button";
  b.addEventListener("click", () =>
    Promise.resolve()
      .then(action)
      .catch((error) => notice(error.message, true)),
  );
  return b;
}
function notice(text, error = false) {
  $("notice").hidden = false;
  $("notice").classList.toggle("error", error);
  $("notice").textContent = text;
}
async function api(path, options = {}) {
  const res = await fetch("/api" + path, options);
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || "Request failed");
  return data;
}
const post = (path, data) =>
  api(path, {
    method: "POST",
    ...(data
      ? {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(data),
        }
      : {}),
  });
function detail(title, nodes) {
  $("detail-title").textContent = title;
  $("detail-content").replaceChildren(...nodes);
  $("detail-panel").hidden = false;
  document.querySelector(".workspace").classList.remove("detail-hidden");
}
function showHelp(key) {
  const [title, text] = help[key] || help.modes;
  const current = state.graph
    ? `${state.graph.entities.length} entities, ${state.graph.relationships.length} relationships and ${state.graph.communities.length} communities in the active corpus.`
    : "No corpus is active yet.";
  detail("FEATURE GUIDE", [
    el("h2", title),
    el("p", text),
    el("h3", "Current data"),
    el("p", current),
  ]);
}
function status(c) {
  $("corpus-status").textContent = state.switching
    ? "PREPARING"
    : c
      ? c.status
      : "NO CORPUS";
  $("corpus-status").className = "badge " + (c ? c.status.toLowerCase() : "");
  const ready = c?.status === "READY" && !state.switching;
  $("ask").disabled = !ready || state.busy;
  $("compare-pair").disabled = !ready || state.busy;
  $("compare-all").disabled = !ready || state.busy;
  $("offline-label").textContent = c
    ? c.mode === "DETERMINISTIC"
      ? "DETERMINISTIC OFFLINE · co-mentions & source excerpts"
      : "LIVE MODE · configured models"
    : "Upload a corpus to get started.";
}
async function history() {
  const h = await api("/corpora");
  $("corpus-history").replaceChildren(el("option", "Choose a corpus"));
  $("corpus-history").firstChild.value = "";
  for (const c of h.corpora) {
    const o = el("option", c.name + " · " + c.status);
    o.value = c.id;
    $("corpus-history").append(o);
  }
  if (state.corpus) $("corpus-history").value = state.corpus.id;
  return h;
}
function stopReplay(clear = false) {
  clearInterval(state.timer);
  state.timer = null;
  $("replay-play").textContent = "Play";
  $("replay").hidden = true;
  document.body.classList.remove("replaying");
  state.cy?.elements().removeClass("dim active");
  if (clear) {
    state.trace = null;
    state.index = 0;
  }
  document
    .querySelectorAll(".chunk-row.active")
    .forEach((n) => n.classList.remove("active"));
}
async function activate(id) {
  if (!id) return;
  state.epoch++;
  const epoch = state.epoch;
  state.stream?.close();
  stopReplay(true);
  state.corpus = null;
  status(null);
  state.expected.clear();
  state.comparison = null;
  $("download-comparison").disabled = true;
  $("comparison-results").replaceChildren();
  $("matrix-wrap").hidden = true;
  $("comparison-explanation").textContent = "";
  $("messages").replaceChildren();
  const selected = await post("/corpora/" + id + "/activate");
  if (epoch !== state.epoch) return;
  state.corpus = selected;
  state.switching = false;
  state.graph = null;
  state.cy?.destroy();
  state.cy = null;
  status(state.corpus);
  await loadGraph(epoch, true);
  await history();
  if (state.corpus.status === "BUILDING") listen(id, epoch);
}
async function loadGraph(epoch = state.epoch, initial = false) {
  const id = state.corpus?.id;
  if (!id) return;
  const graph = await api("/corpora/" + id + "/graph");
  if (epoch !== state.epoch) return;
  state.graph = graph;
  state.corpus = graph.corpus;
  status(state.corpus);
  $("graph-count").textContent =
    `${graph.entities.length} entities · ${graph.relationships.length} relations`;
  $("empty-graph").hidden = graph.entities.length > 0;
  renderGraph(initial);
  if (state.corpus.status === "FAILED")
    notice(`${state.corpus.failedStage}: ${state.corpus.reason}`, true);
  if (initial) overview();
}
function listen(id, epoch) {
  state.sequence = 0;
  const stream = new EventSource("/api/corpora/" + id + "/progress");
  state.stream = stream;
  let updating = false,
    again = false;
  async function refresh() {
    if (updating) {
      again = true;
      return;
    }
    updating = true;
    try {
      await loadGraph(epoch);
      if (again) {
        again = false;
        updating = false;
        return refresh();
      }
    } catch (e) {
      if (epoch === state.epoch) notice(e.message, true);
    } finally {
      updating = false;
    }
  }
  stream.addEventListener("progress", async (event) => {
    if (epoch !== state.epoch) return;
    const p = JSON.parse(event.data);
    if (p.sequence <= state.sequence) return;
    state.sequence = p.sequence;
    notice(
      `${p.stage.toUpperCase()} · ${p.message} · ${p.entities} entities · ${p.relationships} relations`,
      p.stage === "error",
    );
    refresh();
    if (p.terminal) {
      stream.close();
      await loadGraph(epoch);
      await history();
      if ($("detail-title").textContent === "CORPUS OVERVIEW") overview();
    }
  });
  stream.onerror = () => {
    if (epoch === state.epoch && state.corpus?.status === "BUILDING")
      notice("Progress connection interrupted. Reconnecting automatically…");
  };
}
const colors = {
  Person: "#80a9d7",
  Place: "#77b9a7",
  Organization: "#dcb57d",
  Unknown: "#ada6c5",
};
function typeColor(type) {
  if (colors[type]) return colors[type];
  let hash = 0;
  for (const char of type) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return `hsl(${Math.abs(hash) % 360}, 38%, 66%)`;
}
const pastels = ["#eaf0fb", "#e9f4ed", "#fbefe3", "#f4eaf3", "#eaf3f5"];
function renderGraph(initial = false) {
  const g = state.graph;
  if (!g) return;
  const layoutKey =
    g.entities
      .map((e) => e.id)
      .sort()
      .join(",") +
    "|" +
    g.communities
      .map((c) => c.id + ":" + c.members.slice().sort().join(","))
      .sort()
      .join("|");
  const layoutChanged = layoutKey !== state.layoutKey;
  state.layoutKey = layoutKey;
  const regions = $("community-regions").checked;
  const parent = new Map();
  g.communities.forEach((c) => c.members.forEach((id) => parent.set(id, c.id)));
  const nodes = [];
  g.communities.forEach((c, i) => {
    if (regions)
      nodes.push({
        data: {
          id: c.id,
          label: c.title.length > 45 ? c.title.slice(0, 43) + "…" : c.title,
          community: true,
          color: pastels[i % pastels.length],
        },
      });
  });
  const groups = new Map();
  g.entities.forEach((e) => {
    const key = parent.get(e.id) || "other";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(e);
  });
  let groupIndex = 0;
  for (const [key, es] of groups) {
    const cx = 190 + (groupIndex % 2) * 380,
      cy = 150 + Math.floor(groupIndex / 2) * 310;
    es.forEach((e, i) =>
      nodes.push({
        data: {
          id: e.id,
          label: e.name,
          type: e.type,
          color: $("type-colors").checked ? typeColor(e.type) : "#8ca6ba",
          ...(regions && parent.has(e.id) ? { parent: parent.get(e.id) } : {}),
        },
        position: {
          x:
            cx +
            Math.cos((i / es.length) * Math.PI * 2) *
              Math.max(60, es.length * 7),
          y:
            cy +
            Math.sin((i / es.length) * Math.PI * 2) *
              Math.max(60, es.length * 7),
        },
      }),
    );
    groupIndex++;
  }
  g.relationships.forEach((r) =>
    nodes.push({
      data: {
        id: r.id,
        source: r.source,
        target: r.target,
        label: r.type,
        weight: r.weight,
      },
    }),
  );
  if (!state.cy) {
    state.cy = cytoscape({
      container: $("cy"),
      elements: nodes,
      layout: { name: "preset", padding: 70 },
      minZoom: 0.2,
      maxZoom: 3,
      wheelSensitivity: 0.2,
      style: [
        {
          selector: "node",
          style: {
            "background-color": "data(color)",
            label: "data(label)",
            color: "#425265",
            "font-size": 10,
            "text-valign": "bottom",
            "text-margin-y": 7,
            width: 21,
            height: 21,
            "border-width": 2,
            "border-color": "white",
          },
        },
        {
          selector: ":parent",
          style: {
            "background-color": "data(color)",
            "background-opacity": 0.8,
            "border-width": 1,
            "border-color": "#dce4e4",
            label: "data(label)",
            "font-size": 10,
            "text-valign": "top",
            "text-halign": "center",
            "text-margin-y": -9,
            padding: 33,
            shape: "round-rectangle",
          },
        },
        {
          selector: "edge",
          style: {
            width: 1,
            "line-color": "#b6c2cc",
            "curve-style": "bezier",
            opacity: 0.7,
          },
        },
        { selector: ".dim", style: { opacity: 0.18 } },
        {
          selector: ".active",
          style: {
            opacity: 1,
            "border-color": "#d78229",
            "border-width": 4,
            "line-color": "#d78229",
            width: 3,
            "z-index": 10,
          },
        },
        { selector: "node.active", style: { width: 25, height: 25 } },
      ],
    });
    state.cy.on("tap", "node", (event) => {
      const n = event.target;
      if (n.data("community")) inspectCommunity(n.id());
      else inspectEntity(n.id());
    });
    state.cy.on("tap", "edge", (event) =>
      inspectRelationship(event.target.id()),
    );
    new ResizeObserver(() => state.cy?.resize()).observe($("cy"));
  } else {
    const cy = state.cy;
    const positions = new Map(
      cy
        .nodes()
        .filter((n) => !n.isParent())
        .map((n) => [n.id(), n.position()]),
    );
    const zoom = cy.zoom(),
      pan = cy.pan();
    cy.elements().remove();
    cy.add(nodes);
    if (!layoutChanged)
      for (const [id, pos] of positions) cy.getElementById(id).position(pos);
    cy.zoom(zoom);
    cy.pan(pan);
    if (initial || layoutChanged) cy.fit(cy.elements(), 65);
  }
  if (state.trace && !$("replay").hidden) highlightStep();
}
function overview() {
  const g = state.graph;
  if (!g) return;
  const stats = el("div", null, "detail-stat");
  for (const [n, label] of [
    [g.documents.length, "documents"],
    [g.entities.length, "entities"],
    [g.relationships.length, "relationships"],
    [g.communities.length, "communities"],
  ]) {
    const stat = el("div", null, "stat");
    stat.append(el("b", n), el("span", label));
    stats.append(stat);
  }
  const nodes = [
    el("h2", g.corpus.name),
    el(
      "p",
      g.corpus.mode === "DETERMINISTIC"
        ? "Deterministic offline corpus. Relationships describe sentence co-mentions."
        : "Processed with configured language and embedding models.",
    ),
    stats,
    el("h3", "Communities"),
  ];
  if (!g.communities.length)
    nodes.push(el("p", "No groups with at least three members have formed."));
  g.communities
    .slice(0, 3)
    .forEach((c) =>
      nodes.push(
        button(
          c.title + " · " + c.members.length,
          () => inspectCommunity(c.id),
          "community-button",
        ),
      ),
    );
  if (g.communities.length > 3) {
    const list = el("details");
    list.append(el("summary", "All communities"));
    g.communities
      .slice(3)
      .forEach((c) =>
        list.append(
          button(c.title, () => inspectCommunity(c.id), "community-button"),
        ),
      );
    nodes.push(list);
  }
  nodes.push(el("h3", "Documents"));
  g.documents.forEach((d) => nodes.push(el("p", d.name)));
  nodes.push(
    el(
      "p",
      "Corpora persist across restarts. Retrieval traces are temporary.",
      "micro",
    ),
  );
  detail("CORPUS OVERVIEW", nodes);
}
function focus(ids) {
  if (!state.cy) return;
  state.cy.elements().removeClass("active dim");
  const selected = state.cy.collection(
    ids.map((id) => state.cy.getElementById(id)).filter((n) => n.length),
  );
  selected.addClass("active");
  if (selected.length)
    state.cy.animate({ center: { eles: selected }, duration: 250 });
}
function sourceButton(id, name, kind = "passage") {
  return button(name || "Open source", () => source(id, kind), "source-link");
}
async function source(id, kind = "passage") {
  const epoch = state.epoch;
  const p = await api(
    "/corpora/" +
      state.corpus.id +
      (kind === "chunk" ? "/chunks/" : "/text-units/") +
      id,
  );
  if (epoch !== state.epoch) return;
  const expected = el("label");
  const checkbox = el("input");
  checkbox.type = "checkbox";
  checkbox.checked = state.expected.has(id);
  checkbox.addEventListener("change", () => {
    checkbox.checked ? state.expected.add(id) : state.expected.delete(id);
    diagnostics();
  });
  expected.append(
    checkbox,
    document.createTextNode(" Mark as expected evidence"),
  );
  detail("SOURCE EVIDENCE", [
    el("h2", p.documentName),
    el(
      "p",
      `${kind === "chunk" ? "Vector chunk" : "Passage"} ${p.ordinal + 1} · ${p.location || "source text"}`,
      "micro",
    ),
    expected,
    el("p", p.text),
  ]);
  if (state.cy && kind === "passage")
    focus(
      state.graph.entities
        .filter((e) => e.passageIds.includes(id))
        .map((e) => e.id),
    );
}
function inspectEntity(id) {
  const e = state.graph.entities.find((e) => e.id === id);
  if (!e) return;
  focus([id]);
  const nodes = [
    el("span", e.type, "mode-tag"),
    el("h2", e.name),
    el("p", e.description),
    el("h3", "Neighbors & relationships"),
  ];
  state.graph.relationships
    .filter((r) => r.source === id || r.target === id)
    .forEach((r) => {
      const other = state.graph.entities.find(
        (e) => e.id === (r.source === id ? r.target : r.source),
      );
      nodes.push(
        button(
          `${r.type} → ${other?.name || "Entity"} · ${r.weight}`,
          () => inspectRelationship(r.id),
          "source-link",
        ),
      );
    });
  nodes.push(el("h3", "Supporting passages"));
  e.passageIds.forEach((pid) => {
    const p = state.graph.passages.find((p) => p.id === pid);
    nodes.push(sourceButton(pid, p?.documentName));
  });
  detail("ENTITY DETAILS", nodes);
}
function inspectRelationship(id) {
  const r = state.graph.relationships.find((r) => r.id === id);
  if (!r) return;
  focus([r.id, r.source, r.target]);
  const names = [r.source, r.target].map(
    (id) => state.graph.entities.find((e) => e.id === id)?.name,
  );
  const nodes = [
    el("h2", names.join(" → ")),
    el("p", r.type + " · weight " + r.weight, "micro"),
    el("p", r.description),
    el("h3", "Supporting sources"),
  ];
  r.passageIds.forEach((pid) =>
    nodes.push(
      sourceButton(
        pid,
        state.graph.passages.find((p) => p.id === pid)?.documentName,
      ),
    ),
  );
  detail("RELATIONSHIP", nodes);
}
function inspectCommunity(id) {
  const c = state.graph.communities.find((c) => c.id === id);
  if (!c) return;
  focus(c.members);
  const nodes = [
    el("h2", c.title),
    el("p", c.summary),
    el("h3", c.members.length + " members"),
  ];
  c.members.forEach((id) => {
    const e = state.graph.entities.find((e) => e.id === id);
    nodes.push(button(e?.name || id, () => inspectEntity(id), "source-link"));
  });
  detail("COMMUNITY", nodes);
}
function answerElement(a) {
  const card = el("article", null, "answer");
  const heading = el("div", null, "answer-heading");
  heading.append(
    el("span", a.mode, "mode-tag " + a.mode),
    el(
      "span",
      `${a.timing.retrievalMs + a.timing.embeddingMs + a.timing.generationMs} ms · ${a.contextSize} sources`,
      "mono",
    ),
  );
  const text = el("div", null, "answer-text");
  const re = /\[(\d+)\]/g;
  let start = 0,
    match;
  while ((match = re.exec(a.text))) {
    text.append(document.createTextNode(a.text.slice(start, match.index)));
    const citation = a.citations.find((c) => c.number === Number(match[1]));
    text.append(
      citation
        ? button(
            match[0],
            () => source(citation.sourceId, citation.sourceKind),
            "citation",
          )
        : document.createTextNode(match[0]),
    );
    start = re.lastIndex;
  }
  text.append(document.createTextNode(a.text.slice(start)));
  const actions = el("div", null, "answer-actions");
  actions.append(button("▷ Replay retrieval", () => replay(a)));
  card.append(heading, text, actions);
  if (a.citations.length) {
    const sources = el("details", null, "sources");
    sources.append(el("summary", `Sources (${a.citations.length})`));
    a.citations.forEach((c) => {
      sources.append(
        sourceButton(
          c.sourceId,
          `[${c.number}] ${c.documentName}`,
          c.sourceKind,
        ),
        el("p", c.excerpt),
      );
    });
    card.append(sources);
  }
  return card;
}
async function ask(event) {
  event.preventDefault();
  if (state.busy || state.corpus?.status !== "READY") return;
  const question = $("question").value.trim();
  if (!question) return;
  const epoch = state.epoch;
  const id = state.corpus.id;
  const mode = state.mode;
  state.busy = true;
  status(state.corpus);
  const message = el("div", question, "message question");
  $("messages").append(message);
  const pending = el("div", "Retrieving source evidence…", "message micro");
  $("messages").append(pending);
  $("messages").scrollTop = $("messages").scrollHeight;
  try {
    const a = await post("/corpora/" + id + "/query", { question, mode });
    if (epoch !== state.epoch) return;
    const m = el("div", null, "message");
    m.append(answerElement(a));
    pending.replaceWith(m);
    $("messages").scrollTop = $("messages").scrollHeight;
  } catch (e) {
    if (epoch === state.epoch) pending.textContent = e.message;
  } finally {
    state.busy = false;
    status(state.corpus);
  }
}
function setTab(compare) {
  $("graph-view").hidden = compare;
  $("compare-view").hidden = !compare;
  $("graph-tab").classList.toggle("active", !compare);
  $("compare-tab").classList.toggle("active", compare);
  $("graph-tab").setAttribute("aria-selected", String(!compare));
  $("compare-tab").setAttribute("aria-selected", String(compare));
  if (!compare) state.cy?.resize();
}
async function compare(all) {
  const question = $("question").value.trim();
  if (!question) {
    notice(
      "Enter a question in the composer to compare retrieval methods.",
      true,
    );
    $("question").focus();
    return;
  }
  if (state.busy || state.corpus?.status !== "READY") return;
  setTab(true);
  state.busy = true;
  status(state.corpus);
  const epoch = state.epoch;
  const id = state.corpus.id;
  notice(
    all
      ? "Running Local, Global, DRIFT and Vector sequentially…"
      : "Running fresh graph and vector retrieval…",
  );
  try {
    const c = await post(
      "/corpora/" + id + (all ? "/compare-all" : "/compare"),
      { question, mode: state.mode === "VECTOR" ? "LOCAL" : state.mode },
    );
    if (epoch !== state.epoch) return;
    state.comparison = c;
    $("comparison-question").textContent = question;
    $("comparison-results").replaceChildren();
    $("comparison-results").classList.toggle("four", all);
    for (const mode of all
      ? ["LOCAL", "GLOBAL", "DRIFT", "VECTOR"]
      : Object.keys(c.results).sort()) {
      const card = el("article", null, "result-card");
      const a = c.results[mode];
      if (!a) {
        card.append(el("h3", mode), el("p", c.errors[mode] || "No result"));
      } else {
        card.append(answerElement(a));
        const metrics = el(
          "div",
          `Retrieval ${a.timing.retrievalMs} ms · Embedding ${a.timing.embeddingMs} ms\nGeneration ${a.timing.generationMs} ms\n${a.contextSize} context items · ${a.documentCoverage} documents`,
          "metrics",
        );
        card.prepend(metrics);
        api("/traces/" + a.traceId)
          .then((t) => {
            if (epoch === state.epoch)
              metrics.append(el("div", `${t.steps.length} recorded steps`));
          })
          .catch(() => {});
        if (a.mode === "VECTOR") card.append(rankedChunks(a));
      }
      $("comparison-results").append(card);
    }
    $("comparison-explanation").textContent = c.explanation;
    matrix(c);
    $("download-comparison").disabled = false;
    notice("Comparison complete. Inspect sources and replay each method.");
  } catch (e) {
    notice(e.message, true);
  } finally {
    state.busy = false;
    status(state.corpus);
  }
}
function rankedChunks(a) {
  const list = el("details", null, "ranked");
  list.open = true;
  list.append(
    el("summary", "Ranked chunks · solid = context, dashed = below cutoff"),
  );
  a.rankedChunks.forEach((r, i) => {
    const row = el("div", null, "chunk-row" + (r.inContext ? "" : " below"));
    row.dataset.chunkId = r.chunk.id;
    row.append(
      el(
        "span",
        `${i + 1} · ${r.score.toFixed(4)} · ${r.inContext ? "IN CONTEXT" : "BELOW CUTOFF"}`,
        "score",
      ),
      sourceButton(r.chunk.id, r.chunk.documentName, "chunk"),
    );
    list.append(row);
  });
  return list;
}
function matrix(c) {
  $("matrix-wrap").hidden = false;
  const modes = ["LOCAL", "GLOBAL", "DRIFT", "VECTOR"].filter(
    (m) => c.results[m] || c.errors[m],
  );
  const head = el("tr");
  head.append(el("th", "Expected"), el("th", "Evidence"));
  modes.forEach((m) => head.append(el("th", m)));
  $("matrix").querySelector("thead").replaceChildren(head);
  $("matrix").querySelector("tbody").replaceChildren();
  c.matrix.forEach((r) => {
    const row = el("tr");
    const check = el("input");
    check.type = "checkbox";
    check.setAttribute(
      "aria-label",
      "Expected evidence: " + r.documentName + " " + r.id.slice(0, 6),
    );
    check.checked = state.expected.has(r.id);
    check.addEventListener("change", () => {
      check.checked ? state.expected.add(r.id) : state.expected.delete(r.id);
      diagnostics();
    });
    const cell = el("td");
    cell.append(check);
    const evidence = el("td", null, "evidence-cell");
    const isChunk = state.comparison.results.VECTOR?.rankedChunks.some(
      (x) => x.chunk.id === r.id,
    );
    evidence.append(
      button(r.documentName + " · " + r.id.slice(0, 6), () =>
        source(r.id, isChunk ? "chunk" : "passage"),
      ),
    );
    evidence.title = r.excerpt;
    row.append(cell, evidence);
    modes.forEach((m) => {
      const td = el("td");
      const value = r.states[m] || "NOT_RETRIEVED";
      td.append(el("span", value.replaceAll("_", " "), "state " + value));
      row.append(td);
    });
    $("matrix").querySelector("tbody").append(row);
  });
  diagnostics();
}
function diagnostics() {
  if (!state.comparison) return;
  const nodes = [];
  for (const id of state.expected) {
    const row = state.comparison.matrix.find((r) => r.id === id);
    if (!row) {
      nodes.push(
        el(
          "p",
          `${id.slice(0, 6)}: expected source was not retrieved by any compared method.`,
        ),
      );
      continue;
    }
    for (const [mode, value] of Object.entries(row.states)) {
      const message =
        value === "CITED"
          ? "retrieved and cited; inspect it for correctness"
          : value === "IN_CONTEXT"
            ? "possible generation gap: included but not cited"
            : value === "BELOW_CUTOFF"
              ? "possible ranking gap: retrieved below cutoff"
              : "possible retrieval gap: not retrieved";
      nodes.push(el("p", `${row.documentName} · ${mode}: ${message}`));
    }
  }
  $("diagnostics").replaceChildren(
    ...(nodes.length ? nodes : [el("p", "No expected evidence selected.")]),
  );
}
async function replay(a) {
  const epoch = state.epoch;
  const trace = await api("/traces/" + a.traceId);
  if (epoch !== state.epoch || trace.corpusId !== state.corpus.id) return;
  clearInterval(state.timer);
  state.timer = null;
  state.trace = trace;
  state.index = 0;
  $("replay-play").textContent = "Play";
  $("replay").hidden = false;
  document.body.classList.add("replaying");
  if (trace.mode !== "VECTOR") {
    setTab(false);
    state.cy?.fit(state.cy.elements(), 75);
  }
  $("timeline").replaceChildren(
    ...trace.steps.map((s, i) => {
      const b = button(i + 1, () => step(i));
      b.title = s.kind + ": " + s.label;
      b.setAttribute("aria-label", "Step " + (i + 1) + ": " + s.label);
      return b;
    }),
  );
  tracePanel(a);
  step(0);
}
function tracePanel(a) {
  const trace = state.trace;
  const list = el("ol", null, "trace-list");
  trace.steps.forEach((s, i) => {
    const li = el("li");
    const b = button(s.label, () => step(i));
    b.dataset.step = i;
    li.append(b);
    list.append(li);
  });
  const nodes = [
    el("h2", trace.mode + " evidence flow"),
    el("p", trace.question),
    el(
      "p",
      "Actual retrieval operations. Traces are temporary; corpora persist.",
      "micro",
    ),
  ];
  if (trace.mode === "DRIFT") {
    nodes.push(el("h3", "Follow-up branches"));
    const root = el("div");
    root.append(el("strong", trace.question));
    function branch(parent) {
      const tree = el("ul", null, "branch-tree");
      trace.steps
        .filter((s) => s.parentId === parent && s.kind !== "ANSWER_SYNTHESIS")
        .forEach((s) => {
          const li = el("li");
          li.append(button(s.label, () => step(trace.steps.indexOf(s))));
          if (trace.steps.some((x) => x.parentId === s.id))
            li.append(branch(s.id));
          tree.append(li);
        });
      return tree;
    }
    root.append(branch(null));
    nodes.push(root);
  }
  if (a?.rankedChunks?.length) nodes.push(rankedChunks(a));
  nodes.push(el("h3", "Ordered operations"), list);
  detail("RETRIEVAL TRACE", nodes);
}
function step(i) {
  if (!state.trace) return;
  state.index = Math.max(0, Math.min(i, state.trace.steps.length - 1));
  const s = state.trace.steps[state.index];
  $("replay-counter").textContent =
    `${state.index + 1} / ${state.trace.steps.length}`;
  $("replay-label").textContent = s.label;
  $("replay-label").title = s.kind + ": " + s.label;
  Array.from($("timeline").children).forEach((b, i) =>
    b.classList.toggle("active", i === state.index),
  );
  document
    .querySelectorAll("[data-step]")
    .forEach((b) =>
      b.classList.toggle("active", Number(b.dataset.step) === state.index),
    );
  highlightStep();
}
function highlightStep() {
  const s = state.trace?.steps[state.index];
  if (!s) return;
  const cy = state.cy;
  if (cy) {
    cy.elements().removeClass("dim active");
    cy.elements().addClass("dim");
    let ids = [...s.targets];
    if (s.kind === "COMMUNITY_SELECTED") {
      const c = state.graph.communities.find((c) => s.targets.includes(c.id));
      if (c) ids.push(...c.members);
    }
    if (s.kind === "SOURCE_PASSAGE_RETRIEVED")
      ids.push(
        ...state.graph.entities
          .filter((e) => e.passageIds.some((id) => s.targets.includes(id)))
          .map((e) => e.id),
      );
    ids.forEach((id) => {
      const element = cy.getElementById(id);
      element.removeClass("dim").addClass("active");
      element.parents().removeClass("dim");
    });
    if (
      s.kind === "ANSWER_SYNTHESIS" ||
      s.kind === "EMPTY" ||
      s.kind === "VECTOR_QUERY_EMBEDDED"
    )
      cy.elements().removeClass("dim");
  }
  document
    .querySelectorAll(".chunk-row")
    .forEach((row) =>
      row.classList.toggle("active", s.targets.includes(row.dataset.chunkId)),
    );
  const active = $("detail-content").querySelector(
    state.trace.mode === "VECTOR" ? ".chunk-row.active" : "[data-step].active",
  );
  active?.scrollIntoView({ block: "nearest" });
}
async function createDemo(offline) {
  state.epoch++;
  state.switching = true;
  status(state.corpus);
  $("upload-error").textContent = "";
  $("demo").disabled = $("demo-offline").disabled = true;
  try {
    const c = await post("/corpora/" + (offline ? "demo-offline" : "demo"));
    $("upload-dialog").close();
    await activate(c.id);
  } catch (e) {
    $("upload-error").textContent = e.message;
  } finally {
    state.switching = false;
    status(state.corpus);
    $("demo").disabled = $("demo-offline").disabled = false;
  }
}
$("question-form").addEventListener("submit", ask);
$("modes").addEventListener("click", (event) => {
  const m = event.target.dataset.mode;
  if (!m) return;
  state.mode = m;
  document
    .querySelectorAll("[data-mode]")
    .forEach((b) =>
      b.setAttribute("aria-pressed", String(b.dataset.mode === m)),
    );
  $("mode-caption").textContent = {
    LOCAL: "Seeds → neighbors → sources",
    GLOBAL: "Communities → themes → sources",
    DRIFT: "Communities → follow-ups → sources",
    VECTOR: "Question vector → ranked chunks",
  }[m];
});
$("corpus-history").addEventListener("change", (e) =>
  activate(e.target.value).catch((e) => notice(e.message, true)),
);
$("entity-search").addEventListener("input", (event) => {
  const text = event.target.value.toLowerCase();
  const matches =
    text && state.graph
      ? state.graph.entities
          .filter((e) => e.name.toLowerCase().includes(text))
          .slice(0, 12)
      : [];
  $("entity-results").replaceChildren(
    ...matches.map((e) =>
      button(e.name, () => {
        inspectEntity(e.id);
        $("entity-results").hidden = true;
      }),
    ),
  );
  $("entity-results").hidden = !matches.length;
});
$("entity-search").addEventListener("keydown", (event) => {
  if (event.key === "ArrowDown") {
    $("entity-results").querySelector("button")?.focus();
    event.preventDefault();
  }
  if (event.key === "Enter") {
    event.preventDefault();
    $("entity-results").querySelector("button")?.click();
  }
  if (event.key === "Escape") $("entity-results").hidden = true;
});
$("entity-results").addEventListener("keydown", (event) => {
  if (event.key === "ArrowDown") {
    event.target.nextElementSibling?.focus();
    event.preventDefault();
  }
  if (event.key === "ArrowUp") {
    event.target.previousElementSibling?.focus();
    event.preventDefault();
  }
  if (event.key === "Escape") {
    $("entity-results").hidden = true;
    $("entity-search").focus();
  }
});
$("type-colors").addEventListener("change", () => renderGraph());
$("community-regions").addEventListener("change", () => renderGraph());
$("fit").onclick = () => state.cy?.fit(state.cy.elements(), 60);
$("zoom-in").onclick = () => state.cy?.zoom(Math.min(3, state.cy.zoom() * 1.2));
$("zoom-out").onclick = () =>
  state.cy?.zoom(Math.max(0.2, state.cy.zoom() / 1.2));
$("graph-tab").onclick = () => setTab(false);
$("compare-tab").onclick = () => setTab(true);
$("compare-pair").onclick = () => compare(false);
$("compare-all").onclick = () => compare(true);
$("close-detail").onclick = () => {
  $("detail-panel").hidden = true;
  document.querySelector(".workspace").classList.add("detail-hidden");
};
$("replay-prev").onclick = () => step(state.index - 1);
$("replay-next").onclick = () => step(state.index + 1);
$("replay-close").onclick = () => stopReplay();
$("replay-play").onclick = () => {
  if (state.timer) {
    clearInterval(state.timer);
    state.timer = null;
    $("replay-play").textContent = "Play";
  } else {
    if (state.index === state.trace.steps.length - 1) step(0);
    $("replay-play").textContent = "Pause";
    state.timer = setInterval(() => {
      if (state.index >= state.trace.steps.length - 1) {
        clearInterval(state.timer);
        state.timer = null;
        $("replay-play").textContent = "Play";
      } else step(state.index + 1);
    }, 1000);
  }
};
$("download-comparison").onclick = () => {
  const blob = new Blob([JSON.stringify(state.comparison, null, 2)], {
    type: "application/json",
  });
  const url = URL.createObjectURL(blob);
  const a = el("a");
  a.href = url;
  a.download = "graphrag-comparison.json";
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
$("new-corpus").onclick = $("empty-start").onclick = () =>
  $("upload-dialog").showModal();
$("upload-close").onclick = () => $("upload-dialog").close();
$("demo").onclick = () => createDemo(false);
$("demo-offline").onclick = () => createDemo(true);
$("help-open").onclick = () => showHelp("modes");
$("upload-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  state.epoch++;
  state.switching = true;
  status(state.corpus);
  const data = new FormData();
  data.append("name", $("corpus-name").value);
  data.append("offline", String($("upload-offline").checked));
  for (const file of $("files").files) data.append("files", file);
  $("upload-submit").disabled = true;
  $("upload-error").textContent = "";
  try {
    const c = await api("/corpora", { method: "POST", body: data });
    $("upload-dialog").close();
    await activate(c.id);
  } catch (e) {
    $("upload-error").textContent = e.message;
  } finally {
    state.switching = false;
    status(state.corpus);
    $("upload-submit").disabled = false;
  }
});
document.addEventListener("click", (event) => {
  const control = event.target.closest("[data-help]");
  if (control) {
    if ($("upload-dialog").open) $("upload-dialog").close();
    showHelp(control.dataset.help);
  }
  const suggestion = event.target.closest("[data-question]");
  if (suggestion) {
    $("question").value = suggestion.dataset.question;
    $("question").focus();
  }
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    $("entity-results").hidden = true;
    stopReplay();
  }
  if (event.ctrlKey && event.key === "Enter")
    $("question-form").requestSubmit();
});
(async () => {
  try {
    const startup = state.epoch;
    const h = await history();
    if (
      startup === state.epoch &&
      h.activeId &&
      h.corpora.some((c) => c.id === h.activeId)
    )
      await activate(h.activeId);
  } catch (e) {
    notice(e.message, true);
  }
})();
