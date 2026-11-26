import { FieldValue, Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  CloudHangarDoc,
  HELICOPTER_BY_CLASS,
  HELICOPTER_BY_ID,
  HelicopterClassName,
  HelicopterId,
  HelicopterSkinName,
  HelicopterUpgradesRecord,
  STARTING_CREDITS,
  VALID_SKINS,
  clampUpgradeLevel,
  computeImpliedHangarCost,
  defaultUpgradesMap,
} from "./catalog";
import { getHellwarDb, safeLog } from "./db";
import { HangarResponse, normalizeHangarDoc, toHangarResponse } from "./hangar";

export interface LegacyLocalSnapshotInput {
  creditsSpent?: number;
  selectedHelicopterClass?: string;
  selectedSkin?: string;
  unlockedHelicopters?: string[];
  upgradesByClass?: Record<string, Partial<HelicopterUpgradesRecord>>;
}

export interface MigrationDryRunReport {
  userId: string;
  action: "ALREADY_MIGRATED" | "CLEAN_MIGRATE" | "QUARANTINED_PRESERVED";
  grossCredits: number;
  claimedSpent: number;
  recomputedImpliedCost: number;
  proposedCreditsSpentCloud: number;
  proposedAvailableCredits: number;
  preservedUnlockedHelicopters: HelicopterId[];
  conflictsDetected: boolean;
  reasonCodes: string[];
  proposedHangar: CloudHangarDoc;
}

/**
 * Evaluates a legacy v1.0.3 local+cloud hangar snapshot without mutating Firestore.
 * Guarantees that legitimately owned helicopters, upgrades, and skins are NEVER silently deleted:
 * when local/cloud balances conflict (e.g., implied inventory cost exceeds gross credits or
 * claimed spent mismatches implied inventory cost), all claimed ownership is preserved and
 * flagged for non-destructive quarantine review.
 */
export function evaluateLegacyHangarMigration(
  userId: string,
  grossCredits: number,
  existingHangar: Partial<CloudHangarDoc> | null,
  legacyLocal?: LegacyLocalSnapshotInput | null
): MigrationDryRunReport {
  const safeGross = Math.max(0, Math.floor(grossCredits));
  const normalizedExisting = normalizeHangarDoc(userId, existingHangar);

  if (existingHangar && (existingHangar.migrationVersion ?? 0) >= 1) {
    const existingImplied = computeImpliedHangarCost(
      normalizedExisting.unlockedHelicopters,
      normalizedExisting.upgradesByClass
    );
    return {
      userId,
      action: "ALREADY_MIGRATED",
      grossCredits: safeGross,
      claimedSpent: normalizedExisting.creditsSpentCloud,
      recomputedImpliedCost: existingImplied,
      proposedCreditsSpentCloud: normalizedExisting.creditsSpentCloud,
      proposedAvailableCredits: Math.max(0, safeGross - normalizedExisting.creditsSpentCloud),
      preservedUnlockedHelicopters: normalizedExisting.unlockedHelicopters,
      conflictsDetected: normalizedExisting.quarantineStatus === "PENDING_REVIEW",
      reasonCodes: [],
      proposedHangar: normalizedExisting,
    };
  }

  // Merge union of unlocked helicopters from cloud (if any) and legacy local snapshot
  const mergedUnlocked = new Set<HelicopterId>(["helo_scout", ...normalizedExisting.unlockedHelicopters]);
  if (Array.isArray(legacyLocal?.unlockedHelicopters)) {
    for (const id of legacyLocal.unlockedHelicopters) {
      if (typeof id === "string" && id in HELICOPTER_BY_ID) {
        mergedUnlocked.add(id as HelicopterId);
      }
    }
  }

  // Merge upgrades using monotonic max across local and existing cloud tiers (never downgrade)
  const mergedUpgrades = defaultUpgradesMap();
  for (const id of Object.keys(HELICOPTER_BY_ID) as HelicopterId[]) {
    const exUp = normalizedExisting.upgradesByClass[id];
    const locUp = legacyLocal?.upgradesByClass?.[id];
    mergedUpgrades[id] = {
      engineLevel: Math.max(clampUpgradeLevel(exUp?.engineLevel), clampUpgradeLevel(locUp?.engineLevel)),
      armorLevel: Math.max(clampUpgradeLevel(exUp?.armorLevel), clampUpgradeLevel(locUp?.armorLevel)),
      weaponLevel: Math.max(clampUpgradeLevel(exUp?.weaponLevel), clampUpgradeLevel(locUp?.weaponLevel)),
      defenseLevel: Math.max(clampUpgradeLevel(exUp?.defenseLevel), clampUpgradeLevel(locUp?.defenseLevel)),
      radarLevel: Math.max(clampUpgradeLevel(exUp?.radarLevel), clampUpgradeLevel(locUp?.radarLevel)),
    };
  }

  const localClass = legacyLocal?.selectedHelicopterClass;
  const resolvedClass: HelicopterClassName =
    localClass &&
    localClass in HELICOPTER_BY_CLASS &&
    mergedUnlocked.has(HELICOPTER_BY_CLASS[localClass as HelicopterClassName].id)
      ? (localClass as HelicopterClassName)
      : normalizedExisting.selectedHelicopterClass;

  const localSkin = legacyLocal?.selectedSkin;
  const resolvedSkin: HelicopterSkinName =
    localSkin && VALID_SKINS.includes(localSkin as HelicopterSkinName)
      ? (localSkin as HelicopterSkinName)
      : normalizedExisting.selectedSkin;

  const unlockedList = Array.from(mergedUnlocked);
  const recomputedImpliedCost = computeImpliedHangarCost(unlockedList, mergedUpgrades);
  const claimedSpent =
    typeof legacyLocal?.creditsSpent === "number" && Number.isFinite(legacyLocal.creditsSpent)
      ? Math.max(0, Math.floor(legacyLocal.creditsSpent))
      : 0;

  const reasonCodes: string[] = [];
  if (recomputedImpliedCost > safeGross) {
    reasonCodes.push("IMPLIED_COST_EXCEEDS_GROSS_CREDITS");
  }
  if (claimedSpent > 0 && Math.abs(claimedSpent - recomputedImpliedCost) > 0) {
    reasonCodes.push("CLAIMED_SPENT_MISMATCH_WITH_INVENTORY");
  }
  if (claimedSpent > safeGross) {
    reasonCodes.push("CLAIMED_SPENT_EXCEEDS_GROSS_CREDITS");
  }

  const hasConflict = reasonCodes.length > 0;
  // Cap cloud spent at gross credits so legitimate players are never pushed negative;
  // quarantine dossier records exact divergence for audit/reconciliation.
  const proposedSpent = hasConflict
    ? Math.min(safeGross, Math.max(claimedSpent, recomputedImpliedCost))
    : recomputedImpliedCost;

  const proposedHangar: CloudHangarDoc = {
    ...normalizedExisting,
    creditsSpentCloud: proposedSpent,
    selectedHelicopterClass: resolvedClass,
    selectedSkin: resolvedSkin,
    unlockedHelicopters: unlockedList,
    upgradesByClass: mergedUpgrades,
    migrationVersion: 1,
    quarantineStatus: hasConflict ? "PENDING_REVIEW" : "NONE",
  };

  return {
    userId,
    action: hasConflict ? "QUARANTINED_PRESERVED" : "CLEAN_MIGRATE",
    grossCredits: safeGross,
    claimedSpent,
    recomputedImpliedCost,
    proposedCreditsSpentCloud: proposedSpent,
    proposedAvailableCredits: Math.max(0, safeGross - proposedSpent),
    preservedUnlockedHelicopters: unlockedList,
    conflictsDetected: hasConflict,
    reasonCodes,
    proposedHangar,
  };
}

export async function dryRunLegacyHangarMigrationCore(
  db: Firestore,
  uid: string,
  legacyLocal?: LegacyLocalSnapshotInput | null
): Promise<MigrationDryRunReport> {
  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");
  const [userSnap, hangarSnap] = await Promise.all([userRef.get(), hangarRef.get()]);
  if (!userSnap.exists) {
    throw new HttpsError("failed-precondition", "Pilot campaign profile does not exist.");
  }
  const grossCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
  const existingHangar = hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : null;
  return evaluateLegacyHangarMigration(uid, grossCredits, existingHangar, legacyLocal);
}

export interface MigrateLegacyHangarResult {
  hangar: HangarResponse;
  report: MigrationDryRunReport;
  quarantined: boolean;
}

export async function migrateLegacyHangarCore(
  db: Firestore,
  uid: string,
  legacyLocal?: LegacyLocalSnapshotInput | null,
  nowMs: number = Date.now()
): Promise<MigrateLegacyHangarResult> {
  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");
  const backupRef = userRef.collection("migration_backups").doc(`legacy_v103_${nowMs}`);
  const quarantineRef = db.collection("migration_quarantine").doc(uid);

  return db.runTransaction(async (tx) => {
    const [userSnap, hangarSnap] = await Promise.all([tx.get(userRef), tx.get(hangarRef)]);
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized yet.");
    }

    const grossCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
    const existingHangar = hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : null;
    const report = evaluateLegacyHangarMigration(uid, grossCredits, existingHangar, legacyLocal);

    if (report.action === "ALREADY_MIGRATED") {
      return {
        hangar: toHangarResponse(grossCredits, report.proposedHangar, nowMs),
        report,
        quarantined: report.conflictsDetected,
      };
    }

    // 1. Always capture pre-migration backup snapshot prior to applying migration
    tx.set(backupRef, {
      userId: uid,
      capturedAt: FieldValue.serverTimestamp(),
      userProfileCredits: grossCredits,
      existingCloudHangar: existingHangar ?? null,
      legacyLocalSnapshot: legacyLocal ?? null,
      evaluation: {
        action: report.action,
        recomputedImpliedCost: report.recomputedImpliedCost,
        claimedSpent: report.claimedSpent,
        reasonCodes: report.reasonCodes,
      },
    });

    // 2. Persist preserved inventory & upgrades into /users/{uid}/hangar/state
    tx.set(hangarRef, {
      ...report.proposedHangar,
      updatedAt: FieldValue.serverTimestamp(),
    });

    // 3. If conflicting records detected, record quarantine dossier (items stay intact & usable)
    if (report.conflictsDetected) {
      tx.set(quarantineRef, {
        userId: uid,
        status: "PENDING_REVIEW",
        grossCredits: report.grossCredits,
        claimedSpent: report.claimedSpent,
        recomputedImpliedCost: report.recomputedImpliedCost,
        preservedUnlockedHelicopters: report.preservedUnlockedHelicopters,
        reasonCodes: report.reasonCodes,
        backupDocId: backupRef.id,
        flaggedAt: FieldValue.serverTimestamp(),
      });
    }

    return {
      hangar: toHangarResponse(grossCredits, report.proposedHangar, nowMs),
      report,
      quarantined: report.conflictsDetected,
    };
  });
}

export const dryRunLegacyHangarMigration = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
  try {
    const legacyLocal = (request.data ?? null) as LegacyLocalSnapshotInput | null;
    return await dryRunLegacyHangarMigrationCore(getHellwarDb(), uid, legacyLocal);
  } catch (err) {
    safeLog("warn", `dryRunLegacyHangarMigration failed: ${(err as Error).message}`);
    throw err;
  }
});

export const migrateLegacyHangar = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
  try {
    const legacyLocal = (request.data ?? null) as LegacyLocalSnapshotInput | null;
    return await migrateLegacyHangarCore(getHellwarDb(), uid, legacyLocal);
  } catch (err) {
    safeLog("warn", `migrateLegacyHangar failed: ${(err as Error).message}`);
    throw err;
  }
});
