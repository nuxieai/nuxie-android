// Test-only Ed25519 signatures for the unchanged F5 render with Journey test routes.
// AndroidKeyStore cannot buffer the larger F5 descriptor for Ed25519 signing.
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { createHash, generateKeyPairSync, sign } from "node:crypto";
const read = path => JSON.parse(readFileSync(path, "utf8"));
const entry = read("fixtures/journeys/rendered-text-input/release-entry.json");
const original = JSON.parse(Buffer.from(entry.envelope.descriptorBytesBase64, "base64"));
const published = read("fixtures/runtime/forms-saves/release.json");
const compatibility = readFileSync("nuxie-android/src/main/kotlin/ai/nuxie/sdk/runtime/NuxieEmbeddedRuntimeCompatibility.kt", "utf8");
const revision = compatibility.match(/SOURCE_REVISION = "([^"]+)"/)[1];
const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const signatures = {};
for (const name of ["feedback", "departure", "departure-c11"]) {
  const form = name === "departure-c11" ? "departure" : name;
  const screen = `scr_screens_s${form}`;
  const descriptor = { ...published, identity: original.identity,
    requirements: { ...original.requirements, runtimeRevision: revision, requiredCapabilities: ["nux", "system-fonts"] },
    leg: { ...published.leg, id: original.leg.id, policy: original.leg.policy, entryCondition: original.leg.entryCondition, entryStepId: "show",
      steps: [{ id: "show", kind: "action", action: { type: "navigate", screenId: screen }, outlets: {} },
        { kind: "complete", id: "done", outcome: "done" }],
      routes: [{ entryStepId: "done", eventName: form === "feedback" ? "sent" : "continue",
        host: { kind: "screen", screenId: screen } }] } };
  if (name === "departure-c11") {
    descriptor.leg.steps = published.leg.steps;
    descriptor.leg.routes = published.leg.routes;
    descriptor.leg.entryStepId = published.leg.steps.find(step => step.action?.screenId === screen).id;
  }
  const bytes = Buffer.from(JSON.stringify(descriptor));
  signatures[name] = { descriptorSha256: createHash("sha256").update(bytes).digest("hex"),
    signature: sign(null, Buffer.concat([Buffer.from("nuxie.journey-release.v3\0"), bytes]), privateKey).toString("base64") };
}
const target = "nuxie-android/src/androidTest/assets/f5-journey-signatures.json";
mkdirSync("nuxie-android/src/androidTest/assets", { recursive: true });
writeFileSync(target, JSON.stringify({ publicKey: publicKey.export({ type: "spki", format: "der" }).subarray(-32).toString("base64"), signatures }, null, 2) + "\n");
