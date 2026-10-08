const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
async function demo(page) {
  await page.goto("/");
  await page.getByRole("button", { name: "+ New corpus", exact: true }).click();
  await page
    .getByRole("button", { name: "Offline showcase ↗", exact: true })
    .click();
  await expect(page.locator("#corpus-status")).toHaveText("READY");
}
test("demo graph, keyboard inspection, citations and recorded replay", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await demo(page);
  await expect(page.locator("#graph-count")).not.toContainText("0 entities");
  await page
    .getByRole("textbox", { name: "Search entities", exact: true })
    .fill("Sherlock");
  await page
    .getByRole("textbox", { name: "Search entities", exact: true })
    .press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page.locator("#detail-content h2")).toHaveText(
    "Sherlock Holmes",
  );
  await page
    .getByRole("textbox", { name: "Question", exact: true })
    .fill("How did Sherlock Holmes solve the bank robbery?");
  await page.getByRole("button", { name: "Ask ↗", exact: true }).click();
  await expect(page.locator(".answer-text")).toContainText("Sherlock Holmes");
  await page.locator(".citation").first().click();
  await expect(page.locator("#detail-title")).toHaveText("SOURCE EVIDENCE");
  await expect(page.locator("#detail-content")).toContainText("bank.txt");
  await page
    .getByRole("button", { name: "▷ Replay retrieval", exact: true })
    .click();
  await expect(page.locator("#replay")).toBeVisible();
  await expect(page.locator("#replay-counter")).toContainText("1 /");
  await page
    .getByRole("button", { name: "Next retrieval step", exact: true })
    .click();
  await expect(page.locator("#replay-counter")).toContainText("2 /");
  await page.getByRole("button", { name: "Play", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Pause", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await page.setViewportSize({ width: 1280, height: 800 });
  await expect(page.locator("#replay-counter")).toContainText("2 /");
  await page.getByRole("button", { name: "Close replay", exact: true }).click();
  await expect(page.locator("#replay")).toBeHidden();
  await page
    .getByRole("button", { name: "▷ Replay retrieval", exact: true })
    .click();
  await expect(page.locator("#replay")).toBeVisible();
  fs.mkdirSync("docs/screenshots", { recursive: true });
  await page.screenshot({ path: "docs/screenshots/workspace.png" });
  expect(errors).toEqual([]);
});
test("DRIFT branches, four-way comparison, diagnostics, vector replay and JSON", async ({
  page,
}) => {
  await demo(page);
  await page.getByRole("button", { name: "DRIFT", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Question", exact: true })
    .fill("What did Sherlock Holmes investigate at London Bank?");
  await page.getByRole("button", { name: "Ask ↗", exact: true }).click();
  await expect(page.locator(".answer")).toBeVisible();
  await page
    .getByRole("button", { name: "▷ Replay retrieval", exact: true })
    .click();
  await expect(page.locator(".branch-tree").first()).toBeVisible();
  await page.getByRole("button", { name: "Close replay", exact: true }).click();
  await page.getByRole("button", { name: "Compare", exact: true }).click();
  await page.getByRole("button", { name: "Run all four", exact: true }).click();
  await expect(page.locator(".result-card")).toHaveCount(4);
  await expect(page.locator("#matrix tbody tr")).not.toHaveCount(0);
  await page.locator("#matrix input[type=checkbox]").first().check();
  await expect(page.locator("#diagnostics")).toContainText("bank.txt");
  const vector = page
    .locator(".result-card")
    .filter({ has: page.locator(".mode-tag.VECTOR") });
  await vector
    .getByRole("button", { name: "▷ Replay retrieval", exact: true })
    .click();
  await expect(page.locator("#detail-content .chunk-row")).not.toHaveCount(0);
  await page
    .getByRole("button", { name: "Next retrieval step", exact: true })
    .click();
  await expect(page.locator("#detail-content .chunk-row.active")).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "JSON ↓", exact: true }).click();
  expect((await download).suggestedFilename()).toBe("graphrag-comparison.json");
  await page.screenshot({ path: "docs/screenshots/comparison.png" });
});
test("upload safe rendering, history restoration and corpus switch clears replay", async ({
  page,
}) => {
  await demo(page);
  const previous = await page.locator("#corpus-history").inputValue();
  await page.getByRole("button", { name: "+ New corpus", exact: true }).click();
  await page.getByLabel("Corpus name", { exact: true }).fill("Uploaded test");
  await page
    .locator("#files")
    .setInputFiles({
      name: "source.txt",
      mimeType: "text/plain",
      buffer: Buffer.from(
        "Alice Smith met Bob Jones and Carol Brown. Alice Smith wrote <script>window.XSS=true</script> in a notebook.",
      ),
    });
  await page
    .getByLabel("Use deterministic offline processing", { exact: true })
    .check();
  await page
    .getByRole("button", { name: "Process documents ↗", exact: true })
    .click();
  await expect(page.locator("#corpus-status")).toHaveText("READY");
  await page
    .getByRole("textbox", { name: "Question", exact: true })
    .fill("Alice Smith");
  await page.getByRole("button", { name: "Ask ↗", exact: true }).click();
  await expect(page.locator(".answer-text")).toContainText("Alice Smith");
  expect(await page.evaluate(() => window.XSS)).toBeUndefined();
  await page
    .getByRole("button", { name: "▷ Replay retrieval", exact: true })
    .click();
  await page.locator("#corpus-history").selectOption(previous);
  await expect(page.locator("#replay")).toBeHidden();
  await expect(page.locator("#detail-content h2")).toContainText("Holmes");
  await page.reload();
  await expect(page.locator("#corpus-status")).toHaveText("READY");
  await expect(page.locator("#corpus-history")).toHaveValue(previous);
});
test("composer and graph controls remain usable at desktop and small widths", async ({
  page,
}) => {
  await demo(page);
  for (const width of [1440, 1024, 600]) {
    await page.setViewportSize({ width, height: 900 });
    const ask = await page.locator("#ask").boundingBox();
    expect(ask.y + ask.height).toBeLessThanOrEqual(900);
    await expect(page.locator("#fit")).toBeVisible();
    await page
      .getByRole("button", { name: "Help with graph settings", exact: true })
      .click();
    await expect(page.locator("#detail-title")).toHaveText("FEATURE GUIDE");
    await page
      .getByRole("button", { name: "Close detail panel", exact: true })
      .click();
    await expect(page.locator("#ask")).toBeVisible();
  }
  await page.screenshot({ path: "docs/screenshots/small-screen.png" });
});
