import * as crypto from "node:crypto";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import {
  AD_CONTINUE_WINDOW_MS,
  CloudHangarDoc,
  MAX_CREDITS,
  MAX_DAILY_REWARDED_CONTINUES,
  REWARDED_SUPPLY_DROP_CREDITS,
  STARTING_CREDITS,
} from "./catalog";
import { getHellwarDb, safeLog } from "./db";
import { buildDefaultHangarState, normalizeHangarDoc, resolveEffectiveQuota } from "./hangar";

export type RewardedSessionType = "SUPPLY_DROP" | "AD_CONTINUE";
export type RewardLedgerStatus = "PENDING_SSV" | "VERIFIED_SETTLED" | "EXPIRED" | "REJECTED";

const ADMOB_VERIFIER_KEYS_URL = "https://gstatic.com/admob/reward/verifier-keys.json";
const REWARD_SESSION_TTL_MS = 15 * 60 * 1000;

interface AdMobVerifierKeyEntry {
  keyId: number;
  pem: string;
}

let cachedGoogleKeys: { keys: AdMobVerifierKeyEntry[]; fetchedAtMs: number } | null = null;

export async function fetchGoogleAdMobPublicKey(keyId: string): Promise<string | null> {
  const now = Date.now();
  if (!cachedGoogleKeys || now - cachedGoogleKeys.fetchedAtMs > 12 * 60 * 60 * 1000) {
    const response = await fetch(ADMOB_VERIFIER_KEYS_URL);
    if (!response.ok) {
      throw new Error(`Failed to fetch AdMob verifier keys: HTTP ${response.status}`);
    }
    const payload = (await response.json()) as { keys?: AdMobVerifierKeyEntry[] };
    cachedGoogleKeys = {
      keys: Array.isArray(payload.keys) ? payload.keys : [],
      fetchedAtMs: now,
    };
  }
  const numericId = Number(keyId);
  const found = cachedGoogleKeys.keys.find(
    (entry) => String(entry.keyId) === keyId || entry.keyId === numericId
  );
  return found?.pem ?? null;
}

/**
 * Extracts raw SSV signed content according to Google AdMob specification:
 * all query parameters appearing prior to `signature=` in exact URL query order.
 */
export function extractAdMobSignedContentAndParams(rawQuery: string): {
  signedContent: string;
  params: URLSearchParams;
} | null {
  const cleaned = rawQuery.startsWith("?") ? rawQuery.slice(1) : rawQuery;
  const sigIndex = cleaned.indexOf("signature=");
  if (sigIndex <= 0) {
    return null;
  }
  const preceding = cleaned.charAt(sigIndex - 1);
  if (preceding !== "&") {
    return null;
  }
  const signedContent = cleaned.slice(0, sigIndex - 1);
  if (!signedContent) {
    return null;
  }
  const params = new URLSearchParams(cleaned);
  return { signedContent, params };
}

export function verifyAdMobEcdsaSignature(
  signedContent: string,
  signatureBase64Url: string,
  publicKeyPem: string
): boolean {
  try {
    const verifier = crypto.createVerify("SHA256");
    verifier.update(signedContent, "utf8");
    verifier.end();
    const signatureBuf = Buffer.from(signatureBase64Url, "base64url");
    return verifier.verify(publicKeyPem, signatureBuf);
  } catch {
    return false;
  }
}

export async function prepareRewardedSessionCore(
  db: Firestore,
  uid: string,
  rewardTypeInput: unknown,
  nowMs: number = Date.now()
): Promise<{
  nonceId: string;
  rewardType: RewardedSessionType;
  creditsAmount: number;
  adContinueRemaining: number;
  expiresAtMs: number;
}> {
  if (rewardTypeInput !== "SUPPLY_DROP" && rewardTypeInput !== "AD_CONTINUE") {
    throw new HttpsError("invalid-argument", "rewardType must be SUPPLY_DROP or AD_CONTINUE.");
  }
  const rewardType: RewardedSessionType = rewardTypeInput;
  const nonceId = `ssv_${crypto.randomBytes(16).toString("hex")}`;

  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");
  const ledgerRef = db.collection("rewards_ledger").doc(nonceId);

  return db.runTransaction(async (tx) => {
    const [userSnap, hangarSnap] = await Promise.all([tx.get(userRef), tx.get(hangarRef)]);
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized yet.");
    }
    const currentHangar = normalizeHangarDoc(
      uid,
      hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : buildDefaultHangarState(uid)
    );
    const quota = resolveEffectiveQuota(currentHangar, nowMs);

    if (rewardType === "AD_CONTINUE" && quota.remaining <= 0) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily 10/10 rewarded continue limit reached. Resets in 24 hours."
      );
    }

    const creditsAmount = rewardType === "SUPPLY_DROP" ? REWARDED_SUPPLY_DROP_CREDITS : 0;
    const expiresAtMs = nowMs + REWARD_SESSION_TTL_MS;

    tx.set(ledgerRef, {
      nonceId,
      userId: uid,
      rewardType,
      status: "PENDING_SSV" as RewardLedgerStatus,
      creditsAmount,
      continueGranted: false,
      createdAtMs: nowMs,
      expiresAtMs,
      createdAt: FieldValue.serverTimestamp(),
    });

    return {
      nonceId,
      rewardType,
      creditsAmount,
      adContinueRemaining: quota.remaining,
      expiresAtMs,
    };
  });
}

export interface SsvSettlementResult {
  httpStatus: number;
  status: "SETTLED" | "DUPLICATE_IGNORED" | "REJECTED";
  reason?: string;
  nonceId?: string;
  transactionId?: string;
}

/**
 * Verifies Google AdMob ECDSA SHA-256 signature, rejects replay transactions via `ssv_processed_tx`,
 * and atomically settles either +1,000 CR supply drop credits OR +1 daily continue consumption.
 */
export async function handleAdmobSsvCallbackCore(
  db: Firestore,
  rawQuery: string,
  keyLookup: (keyId: string) => Promise<string | null> = fetchGoogleAdMobPublicKey,
  nowMs: number = Date.now()
): Promise<SsvSettlementResult> {
  const parsed = extractAdMobSignedContentAndParams(rawQuery);
  if (!parsed) {
    return { httpStatus: 400, status: "REJECTED", reason: "MISSING_SIGNED_PAYLOAD" };
  }

  const { signedContent, params } = parsed;
  const signature = params.get("signature");
  const keyId = params.get("key_id");
  const transactionId = params.get("transaction_id");
  const customDataNonce = params.get("custom_data");
  const callbackUserId = params.get("user_id");

  if (!signature || !keyId || !transactionId || !customDataNonce) {
    return { httpStatus: 400, status: "REJECTED", reason: "MISSING_REQUIRED_SSV_FIELDS" };
  }

  if (!/^[a-zA-Z0-9_-]{8,128}$/.test(transactionId) || !/^[a-zA-Z0-9_-]{8,128}$/.test(customDataNonce)) {
    return { httpStatus: 400, status: "REJECTED", reason: "MALFORMED_IDENTIFIERS" };
  }

  const publicKeyPem = await keyLookup(keyId);
  if (!publicKeyPem) {
    return { httpStatus: 403, status: "REJECTED", reason: "UNKNOWN_KEY_ID" };
  }

  const validSig = verifyAdMobEcdsaSignature(signedContent, signature, publicKeyPem);
  if (!validSig) {
    return { httpStatus: 403, status: "REJECTED", reason: "INVALID_ECDSA_SIGNATURE" };
  }

  const processedTxRef = db.collection("ssv_processed_tx").doc(transactionId);
  const ledgerRef = db.collection("rewards_ledger").doc(customDataNonce);

  return db.runTransaction(async (tx) => {
    const [txSnap, ledgerSnap] = await Promise.all([tx.get(processedTxRef), tx.get(ledgerRef)]);

    if (txSnap.exists) {
      return {
        httpStatus: 200,
        status: "DUPLICATE_IGNORED",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    if (!ledgerSnap.exists) {
      return {
        httpStatus: 404,
        status: "REJECTED",
        reason: "UNKNOWN_NONCE",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    const ledgerData = ledgerSnap.data() ?? {};
    const uid = String(ledgerData.userId ?? "");
    const rewardType = ledgerData.rewardType as RewardedSessionType;
    const status = ledgerData.status as RewardLedgerStatus;
    const expiresAtMs = Number(ledgerData.expiresAtMs ?? 0);

    if (!uid || (callbackUserId && callbackUserId !== uid)) {
      return {
        httpStatus: 403,
        status: "REJECTED",
        reason: "USER_MISMATCH",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    if (status === "VERIFIED_SETTLED") {
      tx.set(processedTxRef, {
        transactionId,
        nonceId: customDataNonce,
        userId: uid,
        processedAt: FieldValue.serverTimestamp(),
      });
      return {
        httpStatus: 200,
        status: "DUPLICATE_IGNORED",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    if (expiresAtMs > 0 && nowMs > expiresAtMs) {
      tx.update(ledgerRef, { status: "EXPIRED", updatedAt: FieldValue.serverTimestamp() });
      return {
        httpStatus: 410,
        status: "REJECTED",
        reason: "NONCE_EXPIRED",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    const userRef = db.collection("users").doc(uid);
    const hangarRef = userRef.collection("hangar").doc("state");
    const [userSnap, hangarSnap] = await Promise.all([tx.get(userRef), tx.get(hangarRef)]);
    if (!userSnap.exists) {
      return {
        httpStatus: 404,
        status: "REJECTED",
        reason: "USER_PROFILE_NOT_FOUND",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    if (rewardType === "SUPPLY_DROP") {
      const currentCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
      const nextCredits = Math.min(MAX_CREDITS, currentCredits + REWARDED_SUPPLY_DROP_CREDITS);

      tx.update(userRef, {
        credits: nextCredits,
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.set(processedTxRef, {
        transactionId,
        nonceId: customDataNonce,
        userId: uid,
        rewardType,
        processedAt: FieldValue.serverTimestamp(),
      });
      tx.update(ledgerRef, {
        status: "VERIFIED_SETTLED" as RewardLedgerStatus,
        transactionId,
        settledCredits: REWARDED_SUPPLY_DROP_CREDITS,
        settledAt: FieldValue.serverTimestamp(),
      });

      return {
        httpStatus: 200,
        status: "SETTLED",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    // AD_CONTINUE settlement: atomically check & consume 24h continue quota on server
    const hangar = normalizeHangarDoc(
      uid,
      hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : buildDefaultHangarState(uid)
    );
    const effective = resolveEffectiveQuota(hangar, nowMs);
    if (effective.remaining <= 0) {
      tx.update(ledgerRef, { status: "REJECTED", updatedAt: FieldValue.serverTimestamp() });
      return {
        httpStatus: 429,
        status: "REJECTED",
        reason: "DAILY_CONTINUE_QUOTA_EXHAUSTED",
        nonceId: customDataNonce,
        transactionId,
      };
    }

    const nextWindowStart = effective.windowStartMs > 0 ? effective.windowStartMs : nowMs;
    const nextUsed = Math.min(MAX_DAILY_REWARDED_CONTINUES, effective.usedToday + 1);

    tx.set(
      hangarRef,
      {
        ...hangar,
        adContinueWindowStartMs: nextWindowStart,
        adContinueUsedToday: nextUsed,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
    tx.set(processedTxRef, {
      transactionId,
      nonceId: customDataNonce,
      userId: uid,
      rewardType,
      processedAt: FieldValue.serverTimestamp(),
    });
    tx.update(ledgerRef, {
      status: "VERIFIED_SETTLED" as RewardLedgerStatus,
      transactionId,
      continueGranted: true,
      settledAt: FieldValue.serverTimestamp(),
    });

    return {
      httpStatus: 200,
      status: "SETTLED",
      nonceId: customDataNonce,
      transactionId,
    };
  });
}

/**
 * Read-only outcome check callable by the Android client.
 * CRITICAL: Never settles or grants rewards directly on client request — strictly reflects whether
 * Google AdMob's cryptographic SSV callback has settled `rewards_ledger/{nonceId}`.
 */
export async function claimRewardedOutcomeCore(
  db: Firestore,
  uid: string,
  nonceIdInput: unknown,
  nowMs: number = Date.now()
): Promise<{
  nonceId: string;
  status: RewardLedgerStatus;
  rewardType: RewardedSessionType;
  settledCredits: number;
  continueGranted: boolean;
  availableCredits: number;
  adContinueUsedToday: number;
  adContinueRemaining: number;
}> {
  if (typeof nonceIdInput !== "string" || !/^[a-zA-Z0-9_-]{8,128}$/.test(nonceIdInput)) {
    throw new HttpsError("invalid-argument", "Invalid reward nonceId.");
  }

  const ledgerRef = db.collection("rewards_ledger").doc(nonceIdInput);
  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");

  const [ledgerSnap, userSnap, hangarSnap] = await Promise.all([
    ledgerRef.get(),
    userRef.get(),
    hangarRef.get(),
  ]);

  if (!ledgerSnap.exists) {
    throw new HttpsError("not-found", "Reward session nonce not found.");
  }
  const ledger = ledgerSnap.data() ?? {};
  if (ledger.userId !== uid) {
    throw new HttpsError("permission-denied", "Reward nonce does not belong to current pilot.");
  }

  const grossCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
  const hangar = normalizeHangarDoc(
    uid,
    hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : buildDefaultHangarState(uid)
  );
  const availableCredits = Math.max(0, grossCredits - hangar.creditsSpentCloud);
  const quota = resolveEffectiveQuota(hangar, nowMs);

  let status = (ledger.status as RewardLedgerStatus) ?? "PENDING_SSV";
  const expiresAtMs = Number(ledger.expiresAtMs ?? 0);
  if (status === "PENDING_SSV" && expiresAtMs > 0 && nowMs > expiresAtMs) {
    status = "EXPIRED";
  }

  return {
    nonceId: nonceIdInput,
    status,
    rewardType: ledger.rewardType as RewardedSessionType,
    settledCredits: status === "VERIFIED_SETTLED" ? Number(ledger.settledCredits ?? 0) : 0,
    continueGranted: status === "VERIFIED_SETTLED" && Boolean(ledger.continueGranted),
    availableCredits,
    adContinueUsedToday: quota.usedToday,
    adContinueRemaining: quota.remaining,
  };
}

export const prepareRewardedSession = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
  try {
    const rewardType = (request.data as Record<string, unknown> | undefined)?.rewardType;
    return await prepareRewardedSessionCore(getHellwarDb(), uid, rewardType);
  } catch (err) {
    safeLog("warn", `prepareRewardedSession failed: ${(err as Error).message}`);
    throw err;
  }
});

export const claimRewardedOutcome = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
  try {
    const nonceId = (request.data as Record<string, unknown> | undefined)?.nonceId;
    return await claimRewardedOutcomeCore(getHellwarDb(), uid, nonceId);
  } catch (err) {
    safeLog("warn", `claimRewardedOutcome failed: ${(err as Error).message}`);
    throw err;
  }
});

export const admobSsvWebhook = onRequest(async (req, res) => {
  if (req.method !== "GET") {
    res.status(405).json({ error: "Method Not Allowed" });
    return;
  }
  const original = req.originalUrl || req.url || "";
  const qIdx = original.indexOf("?");
  const rawQuery = qIdx >= 0 ? original.slice(qIdx + 1) : "";
  try {
    const outcome = await handleAdmobSsvCallbackCore(getHellwarDb(), rawQuery);
    res.status(outcome.httpStatus).json({
      status: outcome.status,
      reason: outcome.reason ?? null,
    });
  } catch (err) {
    safeLog("error", `admobSsvWebhook error: ${(err as Error).message}`);
    res.status(500).json({ status: "ERROR" });
  }
});
