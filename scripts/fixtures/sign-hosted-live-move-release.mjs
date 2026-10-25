// Mirrors iOS 22adcc29 ExperiencePageHostedFixture.hostedLiveMoveFixture.
// Only Android test metadata is generated; the A/B runtime artifact is unchanged.
import { readFileSync, writeFileSync } from "node:fs";
import { createHash, createPrivateKey, createPublicKey, sign } from "node:crypto";
const base = JSON.parse(readFileSync("fixtures/journeys/planes/release.json", "utf8"));
const entry = base.renderedEntry;
const descriptor = JSON.parse(Buffer.from(entry.envelope.descriptorBytesBase64, "base64"));
const scene = readFileSync("fixtures/runtime/page-host-live-moves/page2.riv");
const lifecycle = JSON.parse(readFileSync("fixtures/runtime/page-host-live-moves/lifecycle2.json", "utf8")).steps;
const sha256 = bytes => createHash("sha256").update(bytes).digest("hex");
descriptor.leg.screens = ["A", "B"].map((id, index) => ({ id, defaultViewModelName: `Screen${index}`,
  defaultInstanceId: `row-${id.toLowerCase()}`, responseCaptures: [] }));
descriptor.leg.steps = [{ id: "present", kind: "action", action: { type: "navigate", screenId: "A" }, outlets: {} }];
descriptor.leg.entryStepId = "present";
descriptor.leg.routes = [];
descriptor.viewModelValues = [];
descriptor.screenBehaviors = ["A", "B"].map(screenId => ({ screenId, controls: [] }));
descriptor.render = {
  renderer: "nux", nux: { key: `renders/sha256/${sha256(scene)}.nux`, sha256: sha256(scene),
    sizeBytes: scene.length, contentType: "application/vnd.nuxie.scene" },
  assets: [], textInputs: [], screens: ["A", "B"].map(id => ({ id, artboardId: id, artboardName: id, width: 800, height: 600 })),
  transitions: [{ id: "t1", kind: "page", sourceScreenId: "A", destinationScreenId: "B", durationMs: lifecycle.t1.durationMs,
    source: { completeEventName: "nx_exit_done:t1" }, destination: { completeEventName: "nx_enter_done:t1" },
    reverse: { durationMs: lifecycle.back.durationMs, source: { completeEventName: "nx_exit_done:t1.reverse" },
      destination: { completeEventName: "nx_enter_done:t1.reverse" } } }],
};
const key = createPrivateKey({ format: "der", type: "pkcs8", key: Buffer.concat([
  Buffer.from("302e020100300506032b657004220420", "hex"), Buffer.alloc(32, 0x42),
]) });
const publicKeyBase64 = createPublicKey(key).export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
if (publicKeyBase64 !== base.publicKeyBase64) throw new Error("Fixture signing identity changed");
const bytes = Buffer.from(JSON.stringify(descriptor));
Object.assign(entry.envelope, { descriptorSha256: sha256(bytes), descriptorSizeBytes: bytes.length,
  descriptorBytesBase64: bytes.toString("base64") });
entry.envelope.signature.signatureBase64 = sign(null, Buffer.concat([Buffer.from("nuxie.journey-release.v3\0"), bytes]), key).toString("base64");
writeFileSync("nuxie-android/src/androidTest/assets/hosted-live-move-release.json", JSON.stringify({ publicKeyBase64, entry }, null, 2) + "\n");
