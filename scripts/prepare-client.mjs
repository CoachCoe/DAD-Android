import { readFile, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { verifyFoundation } from "./check-foundation.mjs";

await verifyFoundation(process.cwd());
const document = JSON.parse(await readFile("contracts/foundation/mobile.openapi.json", "utf8"));
// Phase 1 widens the 4-op base to the full donor + auth/session + events + employee
// surface already present in the vendored contract. Partner-portal ops (partner*) and
// the v2.1 partner push-device pair stay out until Phase 8 so the generated client
// tracks the phases that consume it.
const allowed = new Set([
  // session foundation (unchanged base)
  "profile", "refresh", "logout", "claim",
  // auth / session management (Phase 1 auth shell)
  "login", "logoutAll", "sessions", "revokeSession", "revokeOtherSessions",
  "beginPhoneSignIn", "finishPhoneSignIn", "oidcSignIn",
  "beginDonorPasskeyRegister", "finishDonorPasskeyRegister",
  "beginDonorPasskeySignIn", "finishDonorPasskeySignIn", "donorPasskeys", "deleteDonorPasskey",
  "requestEmailChange", "confirmEmailChange",
  // donor account + giving
  "dashboard", "gifts", "recurring", "cancelRecurring", "changeAmount", "amountOptions",
  "paymentMethod", "beginPaymentUpdate", "receipt", "annualSummary",
  "savePreferences", "saveProfile", "saveSharing", "sharing", "deleteAccount",
  "walletProviders", "applePass", "googlePass",
  // impact / streak / initiatives / rankings
  "todayInitiative", "publishedInitiative", "publishedInitiatives", "initiativeSpend",
  // network / badges / referral / conversions
  "donorNetwork", "donorBadges", "referralCode", "conversions",
  // events (donor) + public calendar
  "donorEvents", "donorEventDetail", "reserveEvent", "cancelEvent", "checkInEvent",
  "inviteOnlyEvents", "eventDetail", "eventCalendar", "donorEventCalendar",
  // push device (donor FCM) + analytics ingest + health
  "registerPushDevice", "unregisterPushDevice", "ingestAnalytics", "live",
  // employee giving & matching (display surface)
  "employeeEnroll", "employeeJoinTeam", "employeeTeam", "employeeTeams",
  "employeeOurPeople", "employeeWorkforce",
]);
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
// The OpenAPI Kotlin generator renders a boolean `const`/single-value enum as an enum whose
// backing `value: Boolean` is initialized with a string literal, which does not compile
// (ARGUMENT_TYPE_MISMATCH). A boolean const carries no choice to model as an enum, so flatten
// it to a plain boolean — the field keeps its type and value, and the client compiles.
function flattenBooleanConsts(value) {
  if (!value || typeof value !== "object") return;
  if (value.type === "boolean" && (value.const !== undefined || Array.isArray(value.enum))) {
    delete value.const;
    delete value.enum;
  }
  Object.values(value).forEach(flattenBooleanConsts);
}
flattenBooleanConsts(document);
// The contract expresses "nullable X" two ways the Kotlin generator cannot render: an
// `anyOf: [X, {type:"null"}]`, and a standalone `{type:"null"}` (a oneOf variant that carries
// no value for that field). Both make the generator reference a nonexistent `Null` type.
// Normalize in place: collapse the anyOf to its non-null branch marked nullable (→ `X?`), and
// turn a standalone null into a nullable string (→ `String?`, always absent in that variant).
function normalizeNullable(value) {
  if (!value || typeof value !== "object") return;
  for (const [key, child] of Object.entries(value)) {
    if (child && typeof child === "object" && !Array.isArray(child)) {
      if (Array.isArray(child.anyOf)) {
        const nonNull = child.anyOf.filter((s) => s && s.type !== "null");
        if (nonNull.length === 1 && nonNull.length < child.anyOf.length) {
          const { anyOf, ...rest } = child;
          value[key] = { ...nonNull[0], ...rest, nullable: true };
        }
      } else if (child.type === "null") {
        const { type, ...rest } = child;
        value[key] = { ...rest, type: "string", nullable: true };
      }
    }
    normalizeNullable(value[key]);
  }
  // A property typed as an array that includes "null" (e.g. type: ["string","null"]) is the
  // JSON-Schema-2020 nullable; reduce it to the concrete type + nullable:true.
  if (Array.isArray(value.type) && value.type.includes("null")) {
    const concrete = value.type.filter((t) => t !== "null");
    if (concrete.length === 1) { value.type = concrete[0]; value.nullable = true; }
  }
}
normalizeNullable(document);
const directory = resolve("app/build/generated/contract");
await mkdir(directory, { recursive: true });
await writeFile(resolve(directory, "openapi.json"), JSON.stringify(document, null, 2) + "\n");
