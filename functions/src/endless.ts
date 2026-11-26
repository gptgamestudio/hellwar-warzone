import * as crypto from "node:crypto";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  calculateEndlessWaveCredits,
  calculateEndlessWaveScore,
  EmpireId,
  endlessEmpireForWave,
  MAX_CREDITS,
  MAX_ENDLESS_WAVE,
  MIN_ENDLESS_WAVE_INTERVAL_MS,
  STARTING_CREDITS,
} from "./catalog";
import { getHellwarDb, safeLog } from "./db";

/**
 * ENDLESS SURVIVAL SECURITY ARCHITECTURE & LIMITATIONS:
 * Client-reported wave completion counts and timestamps cannot independently prove authentic
 * combat on a rooted/modified APK. Server-side integrity is enforced via:
 *  - Firebase Auth UID isolation on sessions, wave ledgers, and stats
 *  - Sequential wave progression (`waveNumber === lastSettledWave + 1`) — cannot skip waves
 *  - Append-only idempotent wave documents (`users/{uid}/endless_waves/{sessionId}_W{wave}`)
 *  - Minimum inter-wave cadence (`MIN_ENDLESS_WAVE_INTERVAL_MS`) and bounded per-wave credits/score
 *  - Atomic Firestore transactions updating `users/{uid}.credits`, `users/{uid}.highScore`,
 *    `users/{uid}/endless/stats`, and `leaderboard/{uid}` (zero direct client writes permitted)
 */

export interface EndlessSessionResult {
  sessionId: string;
  currentEmpire: EmpireId;
  startedAtMs: number;
}

export interface EndlessWaveClaimResult {
  alreadyClaimed: boolean;
  waveNumber: number;
  empire: EmpireId;
  nextEmpire: EmpireId;
  creditsAwarded: number;
  scoreAwarded: number;
  newTotalCredits: number;
  sessionScore: number;
  highestWave: number;
  bestSurvivalScore: number;
}

export async function startEndlessSessionCore(
  db: Firestore,
  uid: string,
  nowMs: number = Date.now()
): Promise<EndlessSessionResult> {
  const userSnap = await db.collection("users").doc(uid).get();
  if (!userSnap.exists) {
    throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized.");
  }

  const sessionId = `endless_${crypto.randomBytes(12).toString("hex")}`;
  const initialEmpire = endlessEmpireForWave(1);

  await db.collection("endless_sessions").doc(sessionId).set({
    sessionId,
    userId: uid,
    status: "ACTIVE",
    currentEmpire: initialEmpire,
    startedAtMs: nowMs,
    lastClaimedAtMs: nowMs,
    lastSettledWave: 0,
    totalWavesCredited: 0,
    sessionCreditsAwarded: 0,
    sessionScore: 0,
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  });

  return {
    sessionId,
    currentEmpire: initialEmpire,
    startedAtMs: nowMs,
  };
}

export async function claimEndlessWaveCompletionCore(
  db: Firestore,
  uid: string,
  sessionIdInput: unknown,
  waveNumberInput: unknown,
  waveKillsInput: unknown,
  nowMs: number = Date.now()
): Promise<EndlessWaveClaimResult> {
  if (
    typeof sessionIdInput !== "string" ||
    sessionIdInput.length < 4 ||
    sessionIdInput.length > 128 ||
    !/^[a-zA-Z0-9_-]+$/.test(sessionIdInput)
  ) {
    throw new HttpsError("invalid-argument", "Invalid endless sessionId.");
  }

  const waveNumber = typeof waveNumberInput === "number" && Number.isFinite(waveNumberInput)
    ? Math.floor(waveNumberInput)
    : -1;
  if (waveNumber < 1 || waveNumber > MAX_ENDLESS_WAVE) {
    throw new HttpsError("invalid-argument", `waveNumber must be between 1 and ${MAX_ENDLESS_WAVE}.`);
  }

  const waveKills = typeof waveKillsInput === "number" && Number.isFinite(waveKillsInput)
    ? Math.floor(waveKillsInput)
    : -1;
  if (waveKills < 1 || waveKills > 100) {
    throw new HttpsError("invalid-argument", "waveKills must be between 1 and 100.");
  }

  const sessionId = sessionIdInput;
  const waveDocId = `${sessionId}_W${waveNumber}`;
  const userRef = db.collection("users").doc(uid);
  const sessionRef = db.collection("endless_sessions").doc(sessionId);
  const waveRef = userRef.collection("endless_waves").doc(waveDocId);
  const statsRef = userRef.collection("endless").doc("stats");
  const hqVictoriesCol = userRef.collection("hq_victories");
  const lbRef = db.collection("leaderboard").doc(uid);

  return db.runTransaction(async (tx) => {
    const [userSnap, sessionSnap, waveSnap, statsSnap, hqSnap] = await Promise.all([
      tx.get(userRef),
      tx.get(sessionRef),
      tx.get(waveRef),
      tx.get(statsRef),
      tx.get(hqVictoriesCol),
    ]);

    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized.");
    }
    if (!sessionSnap.exists || sessionSnap.get("userId") !== uid) {
      throw new HttpsError("permission-denied", "Endless session mismatch or not found.");
    }
    if (sessionSnap.get("status") !== "ACTIVE") {
      throw new HttpsError("failed-precondition", "Endless session is no longer active.");
    }

    const userData = userSnap.data() ?? {};
    const sessionData = sessionSnap.data() ?? {};
    const statsData = statsSnap.exists ? statsSnap.data() ?? {} : {};

    const currentCredits = Number(userData.credits ?? STARTING_CREDITS);
    const currentHighScore = Number(userData.highScore ?? 0);
    const lastSettledWave = Number(sessionData.lastSettledWave ?? 0);
    const prevSessionScore = Number(sessionData.sessionScore ?? 0);
    const prevSessionCredits = Number(sessionData.sessionCreditsAwarded ?? 0);
    const prevBestWave = Number(statsData.highestWave ?? 0);
    const prevBestScore = Number(statsData.bestSurvivalScore ?? 0);
    const prevTotalWaves = Number(statsData.totalWavesCompleted ?? 0);
    const prevTotalEndlessCredits = Number(statsData.totalEndlessCreditsEarned ?? 0);

    const empire = endlessEmpireForWave(waveNumber);
    const nextEmpire = endlessEmpireForWave(waveNumber + 1);

    // Idempotent duplicate wave claim guard
    if (waveSnap.exists || waveNumber <= lastSettledWave) {
      return {
        alreadyClaimed: true,
        waveNumber,
        empire,
        nextEmpire,
        creditsAwarded: 0,
        scoreAwarded: 0,
        newTotalCredits: currentCredits,
        sessionScore: prevSessionScore,
        highestWave: prevBestWave,
        bestSurvivalScore: prevBestScore,
      };
    }

    if (waveNumber !== lastSettledWave + 1) {
      throw new HttpsError(
        "failed-precondition",
        `Expected sequential wave ${lastSettledWave + 1}, received ${waveNumber}.`
      );
    }

    const lastClaimedAtMs = Number(sessionData.lastClaimedAtMs ?? sessionData.startedAtMs ?? 0);
    if (nowMs - lastClaimedAtMs < MIN_ENDLESS_WAVE_INTERVAL_MS) {
      throw new HttpsError(
        "failed-precondition",
        "Wave completion submitted faster than minimum combat interval."
      );
    }

    const creditsAwarded = calculateEndlessWaveCredits(waveNumber);
    const scoreAwarded = calculateEndlessWaveScore(waveNumber, waveKills);

    const nextCredits = Math.min(MAX_CREDITS, currentCredits + creditsAwarded);
    const nextSessionScore = Math.min(MAX_CREDITS, prevSessionScore + scoreAwarded);
    const nextHighScore = Math.min(MAX_CREDITS, Math.max(currentHighScore, nextSessionScore));
    const nextBestWave = Math.max(prevBestWave, waveNumber);
    const nextBestScore = Math.max(prevBestScore, nextSessionScore);

    tx.set(waveRef, {
      userId: uid,
      sessionId,
      waveNumber,
      empire,
      waveKills,
      creditsAwarded,
      scoreAwarded,
      settledAt: FieldValue.serverTimestamp(),
    });

    tx.update(sessionRef, {
      lastSettledWave: waveNumber,
      totalWavesCredited: Number(sessionData.totalWavesCredited ?? 0) + 1,
      sessionCreditsAwarded: prevSessionCredits + creditsAwarded,
      sessionScore: nextSessionScore,
      lastClaimedAtMs: nowMs,
      currentEmpire: nextEmpire,
      updatedAt: FieldValue.serverTimestamp(),
    });

    tx.set(
      statsRef,
      {
        userId: uid,
        highestWave: nextBestWave,
        bestSurvivalScore: nextBestScore,
        totalWavesCompleted: prevTotalWaves + 1,
        totalEndlessCreditsEarned: Math.min(MAX_CREDITS, prevTotalEndlessCredits + creditsAwarded),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    tx.update(userRef, {
      credits: nextCredits,
      highScore: nextHighScore,
      updatedAt: FieldValue.serverTimestamp(),
    });

    const totalBarracksDestroyed = Math.max(
      0,
      Math.min(
        200,
        Number(userData.humanDestroyedCount ?? 0) +
          Number(userData.orcDestroyedCount ?? 0) +
          Number(userData.dragonDestroyedCount ?? 0) +
          Number(userData.alienDestroyedCount ?? 0)
      )
    );
    const safeCallsign = String(userData.callsign ?? "VIPER-7").trim().slice(0, 32).padEnd(2, "7");
    tx.set(lbRef, {
      callsign: safeCallsign,
      highScore: nextHighScore,
      totalBarracksDestroyed,
      hqClearedCount: Math.max(0, Math.min(4, hqSnap.size)),
      updatedAt: FieldValue.serverTimestamp(),
    });

    return {
      alreadyClaimed: false,
      waveNumber,
      empire,
      nextEmpire,
      creditsAwarded,
      scoreAwarded,
      newTotalCredits: nextCredits,
      sessionScore: nextSessionScore,
      highestWave: nextBestWave,
      bestSurvivalScore: nextBestScore,
    };
  });
}

export const startEndlessSession = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Authentication required.");
  try {
    return await startEndlessSessionCore(getHellwarDb(), uid);
  } catch (err) {
    safeLog("warn", `startEndlessSession failed: ${(err as Error).message}`);
    throw err;
  }
});

export const claimEndlessWaveCompletion = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Authentication required.");
  try {
    const data = (request.data ?? {}) as Record<string, unknown>;
    return await claimEndlessWaveCompletionCore(
      getHellwarDb(),
      uid,
      data.sessionId,
      data.waveNumber,
      data.waveKills
    );
  } catch (err) {
    safeLog("warn", `claimEndlessWaveCompletion failed: ${(err as Error).message}`);
    throw err;
  }
});
