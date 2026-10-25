// Repair the Android-authored Test Store wrapper, keeping publisher render bytes intact.
// Both SDK validators require explicit nulls for these optional entitlement values.
import { readFileSync, writeFileSync } from "node:fs";
import { createHash, createPrivateKey, createPublicKey, sign } from "node:crypto";
const fixture = JSON.parse(readFileSync("fixtures/journeys/planes/test-store-orchestration.json", "utf8"));
const key = createPrivateKey({ format: "der", type: "pkcs8", key: Buffer.concat([
  Buffer.from("302e020100300506032b657004220420", "hex"), Buffer.alloc(32, 0x42),
]) });
const publicKey = createPublicKey(key).export({ format: "der", type: "spki" }).subarray(-32).toString("base64");
if (publicKey !== fixture.publicKeyBase64) throw new Error("Fixture signing identity changed");
for (const entry of Object.values(fixture.entries)) {
  const descriptor = JSON.parse(Buffer.from(entry.envelope.descriptorBytesBase64, "base64"));
  for (const product of descriptor.products) for (const entitlement of product.entitlements) {
    for (const field of ["allowanceType", "allowance", "interval"]) {
      if (!(field in entitlement)) entitlement[field] = null;
    }
  }
  const bytes = Buffer.from(JSON.stringify(descriptor));
  Object.assign(entry.envelope, {
    descriptorSha256: createHash("sha256").update(bytes).digest("hex"),
    descriptorSizeBytes: bytes.length, descriptorBytesBase64: bytes.toString("base64"),
  });
  entry.envelope.signature.signatureBase64 = sign(null,
    Buffer.concat([Buffer.from("nuxie.journey-release.v3\0"), bytes]), key).toString("base64");
}
writeFileSync("nuxie-android/src/androidTest/assets/test-store-page-release.json", JSON.stringify(fixture, null, 2) + "\n");
