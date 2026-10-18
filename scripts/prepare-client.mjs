import { readFile, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { verifyFoundation } from "./check-foundation.mjs";

await verifyFoundation(process.cwd());
const document = JSON.parse(await readFile("contracts/foundation/mobile.openapi.json", "utf8"));
const allowed = new Set(["profile", "refresh", "logout", "claim"]);
for (const [path, item] of Object.entries(document.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    if (!allowed.has(operation.operationId)) delete item[method];
  }
  if (!Object.keys(item).length) delete document.paths[path];
}
// This client always sends X-DAD-Client: native, whose documented claim variant is NativeSession.
const claim = document.paths["/donor/claim"].post.responses["200"].content["application/json"].schema;
if (!claim.oneOf.some((variant) => variant.$ref === "#/components/schemas/NativeSession")) throw new Error("Native claim contract changed");
document.paths["/donor/claim"].post.responses["200"].content["application/json"].schema = { $ref: "#/components/schemas/NativeSession" };
const logout = document.paths["/donor/logout"].post.responses["200"].content["application/json"];
document.components.schemas.LogoutAcknowledgement = logout.schema;
logout.schema = { $ref: "#/components/schemas/LogoutAcknowledgement" };
const components = { securitySchemes: document.components.securitySchemes };
function visit(value) {
  if (!value || typeof value !== "object") return;
  if (value.$ref) {
    const [, , group, name] = value.$ref.split("/");
    if (!components[group]?.[name]) {
      components[group] ??= {};
      components[group][name] = document.components[group][name];
      visit(components[group][name]);
    }
  }
  Object.values(value).forEach(visit);
}
visit(document.paths);
document.components = components;
const directory = resolve("app/build/generated/contract");
await mkdir(directory, { recursive: true });
await writeFile(resolve(directory, "openapi.json"), JSON.stringify(document, null, 2) + "\n");
