"""Exercise a running dedicated app before/after an application + database restart."""
import argparse
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("phase", choices=["before", "after"])
parser.add_argument("--url", default="http://localhost:8080")
parser.add_argument("--state", default="/tmp/graphrag-restart-check.json")
args = parser.parse_args()


def request(path, data=None, post=False):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(
        args.url + "/api" + path,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST" if post or body is not None else "GET",
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


for _ in range(120):
    try:
        request("/corpora")
        break
    except urllib.error.URLError:
        time.sleep(0.25)
else:
    raise TimeoutError("Application did not become ready")

if args.phase == "before":
    corpus = request("/corpora/demo-offline", post=True)
    cid = corpus["id"]
    for _ in range(120):
        graph = request(f"/corpora/{cid}/graph")
        if graph["corpus"]["status"] != "BUILDING":
            break
        time.sleep(0.25)
    assert graph["corpus"]["status"] == "READY", graph["corpus"]
    assert len(graph["communities"]) >= 3
    assert graph["entities"] and graph["relationships"]
    question = "What did Sherlock Holmes investigate at London Bank?"
    result = request(f"/corpora/{cid}/compare-all", {"question": question})
    assert len(result["results"]) == 4 and not result["errors"], result["errors"]
    for mode, answer in result["results"].items():
        assert not answer["noAnswer"], mode
        trace = request("/traces/" + answer["traceId"])
        targets = {target for step in trace["steps"] for target in step["targets"]}
        for citation in answer["citations"]:
            assert citation["sourceId"] in targets
            route = "chunks" if citation["sourceKind"] == "chunk" else "text-units"
            source = request(f"/corpora/{cid}/{route}/{citation['sourceId']}")
            assert source["documentName"] == citation["documentName"]
    progress = urllib.request.Request(
        args.url + f"/api/corpora/{cid}/progress", headers={"Last-Event-ID": "2"}
    )
    with urllib.request.urlopen(progress, timeout=10) as response:
        stream = response.read().decode()
    ids = [int(line[3:]) for line in stream.splitlines() if line.startswith("id:")]
    assert ids and all(i > 2 for i in ids) and len(ids) == len(set(ids))
    Path(args.state).write_text(json.dumps({
        "id": cid,
        "entities": sorted(entity["id"] for entity in graph["entities"]),
        "trace": result["results"]["LOCAL"]["traceId"],
    }))
    print("Before restart: four modes, grounded citations, three communities, reconnectable SSE passed.")
else:
    state = json.loads(Path(args.state).read_text())
    graph = request(f"/corpora/{state['id']}/graph")
    assert graph["corpus"]["status"] == "READY"
    assert sorted(entity["id"] for entity in graph["entities"]) == state["entities"]
    assert request("/corpora")["activeId"] == state["id"]
    try:
        request("/traces/" + state["trace"])
        raise AssertionError("Temporary trace unexpectedly survived restart")
    except urllib.error.HTTPError as error:
        assert error.code == 404
    answer = request(f"/corpora/{state['id']}/query", {"question": "Sherlock Holmes", "mode": "VECTOR"})
    assert not answer["noAnswer"]
    print("After restart: corpus, active selection, graph IDs and vector storage persisted; trace expired.")
