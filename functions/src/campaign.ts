import * as crypto from "node:crypto";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  BARRACKS_CREDITS_AWARD,
  BARRACKS_SCORE_AWARD,
  EmpireId,
  HQ_CREDITS_AWARD,
  HQ_SCORE_AWARD,
  MAX_CREDITS,
  STARTING_CREDITS,
  VALID_EMPIRES,
} from "./catalog";
import { getHellwarDb, safeLog } from "./db";

/**
 * SECURITY ARCHITECTURE NOTE & LIMITATIONS:
 * Because Helicopter War distributes signed APKs directly via GitHub Releases (not strictly Play Store),
 * Play Integrity hardware verdict is not enforced as a hard blocker (sideloaded devices do not hold
 * Play licensing verdicts). Furthermore, client-submitted sortie session telemetry and timing checks
 * are defense-in-depth signals and DO NOT independently prove authentic gameplay on a rooted/modified
 * client. True anti-abuse guarantees are anchored in:
 *  - Firebase Auth UID isolation
 *  - Deterministic append-only IDs (`BARRACKS_<EMPIRE>_<1..50>`, `hq_victories/<EMPIRE>`)
 *  - Server-checked prerequisites (50/50 barracks prior to HQ victory)
 *  - Server-authoritative economy ledger (`creditsSpentCloud` & ECDSA SSV webhook)
 */

async function syncLeaderboardInTx(
  tx: FirebaseFirestore.Transaction,
  db: Firestore,
  uid: string,
  profile: {
    callsign: string;
    highScore: number;
    humanDestroyedCount: number;
    orcDestroyedCount: number;
    dragonDestroyedCount: number;
    alienDestroyedCount: number;
  },
  hqClearedCount: number
): Promise<void> {
  const lbRef = db.collection("leaderboard").doc(uid);
  const totalBarracksDestroyed = Math.max(
    0,
    Math.min(
      200,
      profile.humanDestroyedCount +
        profile.orcDestroyedCount +
        profile.dragonDestroyedCount +
        profile.alienDestroyedCount
    )
  );
  const safeCallsign = profile.callsign.trim().slice(0, 32).padEnd(2, "7");
  tx.set(lbRef, {
    callsign: safeCallsign,
    highScore: Math.max(0, Math.min(MAX_CREDITS, profile.highScore)),
    totalBarracksDestroyed,
    hqClearedCount: Math.max(0, Math.min(4, hqClearedCount)),
    updatedAt: FieldValue.serverTimestamp(),
  });
}

export async function startSortieSessionCore(
  db: Firestore,
  uid: string,
  empireInput: unknown,
  nowMs: number = Date.now()
): Promise<{ sessionId: string; empire: EmpireId; startedAtMs: number }> {
  if (typeof empireInput !== "string" || !VALID_EMPIRES.includes(empireInput as EmpireId)) {
    throw new HttpsError("invalid-argument", "Invalid empire identifier.");
  }
  const empire = empireInput as EmpireId;
  const sessionId = `sortie_${crypto.randomBytes(12).toString("hex")}`;

  await db.collection("sortie_sessions").doc(sessionId).set({
    sessionId,
    userId: uid,
    empire,
    startedAtMs: nowMs,
    status: "ACTIVE",
    createdAt: FieldValue.serverTimestamp(),
  });

  return { sessionId, empire, startedAtMs: nowMs };
}

export async function claimBarracksDestructionCore(
  db: Firestore,
  uid: string,
  empireInput: unknown,
  barracksIndexInput: unknown,
  damageDealtInput: unknown,
  sessionIdInput?: unknown
): Promise<{ alreadyDestroyed: boolean; creditsAwarded: number; scoreAwarded: number; newTotalInEmpire: number }> {
  if (typeof empireInput !== "string" || !VALID_EMPIRES.includes(empireInput as EmpireId)) {
    throw new HttpsError("invalid-argument", "Invalid empire identifier.");
  }
  const empire = empireInput as EmpireId;
  const idx = typeof barracksIndexInput === "number" ? Math.floor(barracksIndexInput) : -1;
  if (idx < 1 || idx > 50) {
    throw new HttpsError("invalid-argument", "barracksIndex must be between 1 and 50.");
  }
  const damage = typeof damageDealtInput === "number" ? damageDealtInput : 0;
  if (!Number.isFinite(damage) || damage < 320) {
    throw new HttpsError("failed-precondition", "Insufficient lethal damage to destroy barracks.");
  }

  const barracksId = `BARRACKS_${empire}_${idx}`;
  const userRef = db.collection("users").doc(uid);
  const barracksRef = userRef.collection("destroyed_barracks").doc(barracksId);
  const hqVictoriesCol = userRef.collection("hq_victories");
  const sessionRef =
    typeof sessionIdInput === "string" && sessionIdInput.length > 0
      ? db.collection("sortie_sessions").doc(sessionIdInput)
      : null;

  return db.runTransaction(async (tx) => {
    const [userSnap, barracksSnap, hqVictoriesSnap, sessionSnap] = await Promise.all([
      tx.get(userRef),
      tx.get(barracksRef),
      tx.get(hqVictoriesCol),
      sessionRef ? tx.get(sessionRef) : Promise.resolve(null),
    ]);

    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized.");
    }
    if (sessionSnap && (!sessionSnap.exists || sessionSnap.get("userId") !== uid)) {
      throw new HttpsError("permission-denied", "Sortie session mismatch.");
    }

    const userData = userSnap.data() ?? {};
    const empireField = `${empire.toLowerCase()}DestroyedCount`;
    const currentEmpireCount = Number(userData[empireField] ?? 0);

    if (barracksSnap.exists) {
      return {
        alreadyDestroyed: true,
        creditsAwarded: 0,
        scoreAwarded: 0,
        newTotalInEmpire: currentEmpireCount,
      };
    }

    const nextEmpireCount = Math.min(50, currentEmpireCount + 1);
    const nextCredits = Math.min(MAX_CREDITS, Number(userData.credits ?? STARTING_CREDITS) + BARRACKS_CREDITS_AWARD);
    const nextHighScore = Math.min(MAX_CREDITS, Number(userData.highScore ?? 0) + BARRACKS_SCORE_AWARD);

    const updatedCounters = {
      callsign: String(userData.callsign ?? "VIPER-7"),
      highScore: nextHighScore,
      humanDestroyedCount: empire === "HUMAN" ? nextEmpireCount : Number(userData.humanDestroyedCount ?? 0),
      orcDestroyedCount: empire === "ORC" ? nextEmpireCount : Number(userData.orcDestroyedCount ?? 0),
      dragonDestroyedCount: empire === "DRAGON" ? nextEmpireCount : Number(userData.dragonDestroyedCount ?? 0),
      alienDestroyedCount: empire === "ALIEN" ? nextEmpireCount : Number(userData.alienDestroyedCount ?? 0),
    };

    tx.set(barracksRef, {
      userId: uid,
      barracksId,
      empire,
      barracksIndex: idx,
      damageDealt: damage,
      creditsAwarded: BARRACKS_CREDITS_AWARD,
      scoreAwarded: BARRACKS_SCORE_AWARD,
      destroyedAt: FieldValue.serverTimestamp(),
    });

    tx.update(userRef, {
      credits: nextCredits,
      highScore: nextHighScore,
      [empireField]: nextEmpireCount,
      updatedAt: FieldValue.serverTimestamp(),
    });

    await syncLeaderboardInTx(tx, db, uid, updatedCounters, hqVictoriesSnap.size);

    return {
      alreadyDestroyed: false,
      creditsAwarded: BARRACKS_CREDITS_AWARD,
      scoreAwarded: BARRACKS_SCORE_AWARD,
      newTotalInEmpire: nextEmpireCount,
    };
  });
}

export async function claimHeadquartersVictoryCore(
  db: Firestore,
  uid: string,
  empireInput: unknown,
  sessionIdInput?: unknown
): Promise<{ alreadyCleared: boolean; creditsAwarded: number; scoreAwarded: number }> {
  if (typeof empireInput !== "string" || !VALID_EMPIRES.includes(empireInput as EmpireId)) {
    throw new HttpsError("invalid-argument", "Invalid empire identifier.");
  }
  const empire = empireInput as EmpireId;

  const userRef = db.collection("users").doc(uid);
  const hqRef = userRef.collection("hq_victories").doc(empire);
  const hqVictoriesCol = userRef.collection("hq_victories");
  const sessionRef =
    typeof sessionIdInput === "string" && sessionIdInput.length > 0
      ? db.collection("sortie_sessions").doc(sessionIdInput)
      : null;

  return db.runTransaction(async (tx) => {
    const [userSnap, hqSnap, allHqSnap, sessionSnap] = await Promise.all([
      tx.get(userRef),
      tx.get(hqRef),
      tx.get(hqVictoriesCol),
      sessionRef ? tx.get(sessionRef) : Promise.resolve(null),
    ]);

    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized.");
    }
    if (sessionSnap && (!sessionSnap.exists || sessionSnap.get("userId") !== uid)) {
      throw new HttpsError("permission-denied", "Sortie session mismatch.");
    }

    if (hqSnap.exists) {
      return { alreadyCleared: true, creditsAwarded: 0, scoreAwarded: 0 };
    }

    const userData = userSnap.data() ?? {};
    const empireCountField = `${empire.toLowerCase()}DestroyedCount`;
    const destroyedInEmpire = Number(userData[empireCountField] ?? 0);
    if (destroyedInEmpire < 50) {
      throw new HttpsError(
        "failed-precondition",
        `Headquarters forcefield active: only ${destroyedInEmpire}/50 barracks destroyed.`
      );
    }

    const nextCredits = Math.min(MAX_CREDITS, Number(userData.credits ?? STARTING_CREDITS) + HQ_CREDITS_AWARD);
    const nextHighScore = Math.min(MAX_CREDITS, Number(userData.highScore ?? 0) + HQ_SCORE_AWARD);

    tx.set(hqRef, {
      userId: uid,
      empire,
      bossId: `BOSS_${empire}`,
      creditsAwarded: HQ_CREDITS_AWARD,
      scoreAwarded: HQ_SCORE_AWARD,
      victoryAt: FieldValue.serverTimestamp(),
    });

    tx.update(userRef, {
      credits: nextCredits,
      highScore: nextHighScore,
      updatedAt: FieldValue.serverTimestamp(),
    });

    await syncLeaderboardInTx(
      tx,
      db,
      uid,
      {
        callsign: String(userData.callsign ?? "VIPER-7"),
        highScore: nextHighScore,
        humanDestroyedCount: Number(userData.humanDestroyedCount ?? 0),
        orcDestroyedCount: Number(userData.orcDestroyedCount ?? 0),
        dragonDestroyedCount: Number(userData.dragonDestroyedCount ?? 0),
        alienDestroyedCount: Number(userData.alienDestroyedCount ?? 0),
      },
      allHqSnap.size + 1
    );

    if (sessionRef && sessionSnap?.exists) {
      tx.update(sessionRef, {
        status: "COMPLETED",
        completedAt: FieldValue.serverTimestamp(),
      });
    }

    return {
      alreadyCleared: false,
      creditsAwarded: HQ_CREDITS_AWARD,
      scoreAwarded: HQ_SCORE_AWARD,
    };
  });
}

export async function updateCallsignCore(
  db: Firestore,
  uid: string,
  callsignInput: unknown
): Promise<{ callsign: string }> {
  if (typeof callsignInput !== "string") {
    throw new HttpsError("invalid-argument", "callsign must be a string.");
  }
  const cleaned = callsignInput
    .replace(/[\r\n\t\u0000-\u001F]/g, "")
    .trim()
    .slice(0, 32)
    .padEnd(2, "7");

  const userRef = db.collection("users").doc(uid);
  const hqVictoriesCol = userRef.collection("hq_victories");

  await db.runTransaction(async (tx) => {
    const [userSnap, hqSnap] = await Promise.all([tx.get(userRef), tx.get(hqVictoriesCol)]);
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized.");
    }
    const userData = userSnap.data() ?? {};
    tx.update(userRef, {
      callsign: cleaned,
      updatedAt: FieldValue.serverTimestamp(),
    });
    await syncLeaderboardInTx(
      tx,
      db,
      uid,
      {
        callsign: cleaned,
        highScore: Number(userData.highScore ?? 0),
        humanDestroyedCount: Number(userData.humanDestroyedCount ?? 0),
        orcDestroyedCount: Number(userData.orcDestroyedCount ?? 0),
        dragonDestroyedCount: Number(userData.dragonDestroyedCount ?? 0),
        alienDestroyedCount: Number(userData.alienDestroyedCount ?? 0),
      },
      hqSnap.size
    );
  });

  return { callsign: cleaned };
}

export const startSortieSession = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Authentication required.");
  try {
    const empire = (request.data as Record<string, unknown> | undefined)?.empire;
    return await startSortieSessionCore(getHellwarDb(), uid, empire);
  } catch (err) {
    safeLog("warn", `startSortieSession failed: ${(err as Error).message}`);
    throw err;
  }
});

export const claimBarracksDestruction = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Authentication required.");
  try {
    const data = (request.data ?? {}) as Record<string, unknown>;
    return await claimBarracksDestructionCore(
      getHellwarDb(),
      uid,
      data.empire,
      data.barracksIndex,
      data.damageDealt,
      data.sessionId
    );
  } catch (err) {
    safeLog("warn", `claimBarracksDestruction failed: ${(err as Error).message}`);
    throw err;
  }
});

export const claimHeadquartersVictory = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Authentication required.");
  try {
    const data = (request.data ?? {}) as Record<string, unknown>;
    return await claimHeadquartersVictoryCore(
      getHellwarDb(),
      uid,
      data.empire,
      data.sessionId
    );
  } catch (err) {
    safeLog("warn", `claimHeadquartersVictory failed: ${(err as Error).message}`);
    throw err;
  }
});

export const updateCallsign = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Authentication required.");
  try {
    const callsign = (request.data as Record<string, unknown> | undefined)?.callsign;
    return await updateCallsignCore(getHellwarDb(), uid, callsign);
  } catch (err) {
    safeLog("warn", `updateCallsign failed: ${(err as Error).message}`);
    throw err;
  }
});
