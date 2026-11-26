import { FieldValue, Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  AD_CONTINUE_WINDOW_MS,
  CloudHangarDoc,
  HELICOPTER_BY_CLASS,
  HELICOPTER_BY_ID,
  HelicopterClassName,
  HelicopterId,
  HelicopterSkinName,
  HelicopterUpgradesRecord,
  MAX_DAILY_REWARDED_CONTINUES,
  MAX_UPGRADE_LEVEL,
  STARTING_CREDITS,
  UpgradeCategory,
  VALID_SKINS,
  VALID_UPGRADE_CATEGORIES,
  clampUpgradeLevel,
  defaultUpgradesMap,
  resolveHelicopterSpec,
  upgradeCostForLevel,
  upgradeFieldForCategory,
} from "./catalog";
import { getHellwarDb, safeLog } from "./db";

export interface HangarResponse {
  userId: string;
  grossCredits: number;
  creditsSpentCloud: number;
  availableCredits: number;
  selectedHelicopterClass: HelicopterClassName;
  selectedSkin: HelicopterSkinName;
  unlockedHelicopters: HelicopterId[];
  upgradesByClass: CloudHangarDoc["upgradesByClass"];
  adContinueUsedToday: number;
  adContinueRemaining: number;
  adContinueWindowStartMs: number;
  migrationVersion: number;
  quarantineStatus: CloudHangarDoc["quarantineStatus"];
}

export function buildDefaultHangarState(userId: string): CloudHangarDoc {
  return {
    userId,
    creditsSpentCloud: 0,
    selectedHelicopterClass: "SCOUT",
    selectedSkin: "TACTICAL_BLACK",
    unlockedHelicopters: ["helo_scout"],
    upgradesByClass: defaultUpgradesMap(),
    adContinueWindowStartMs: 0,
    adContinueUsedToday: 0,
    migrationVersion: 1,
    quarantineStatus: "NONE",
  };
}

export function normalizeHangarDoc(userId: string, raw?: Partial<CloudHangarDoc> | null): CloudHangarDoc {
  const defaults = buildDefaultHangarState(userId);
  if (!raw) return defaults;

  const unlockedSet = new Set<HelicopterId>(["helo_scout"]);
  if (Array.isArray(raw.unlockedHelicopters)) {
    for (const item of raw.unlockedHelicopters) {
      if (typeof item === "string" && item in HELICOPTER_BY_ID) {
        unlockedSet.add(item as HelicopterId);
      }
    }
  }

  const rawClass = raw.selectedHelicopterClass;
  const selectedClass: HelicopterClassName =
    rawClass && rawClass in HELICOPTER_BY_CLASS && unlockedSet.has(HELICOPTER_BY_CLASS[rawClass].id)
      ? rawClass
      : "SCOUT";

  const rawSkin = raw.selectedSkin;
  const selectedSkin: HelicopterSkinName =
    rawSkin && VALID_SKINS.includes(rawSkin) ? rawSkin : "TACTICAL_BLACK";

  const upgrades = defaultUpgradesMap();
  const rawUpgrades = (raw.upgradesByClass ?? {}) as Partial<
    Record<HelicopterId, Partial<HelicopterUpgradesRecord>>
  >;
  for (const id of Object.keys(HELICOPTER_BY_ID) as HelicopterId[]) {
    const src = rawUpgrades[id];
    if (src) {
      upgrades[id] = {
        engineLevel: clampUpgradeLevel(src.engineLevel),
        armorLevel: clampUpgradeLevel(src.armorLevel),
        weaponLevel: clampUpgradeLevel(src.weaponLevel),
        defenseLevel: clampUpgradeLevel(src.defenseLevel),
        radarLevel: clampUpgradeLevel(src.radarLevel),
      };
    }
  }

  return {
    userId,
    creditsSpentCloud:
      typeof raw.creditsSpentCloud === "number" && Number.isFinite(raw.creditsSpentCloud)
        ? Math.max(0, Math.floor(raw.creditsSpentCloud))
        : 0,
    selectedHelicopterClass: selectedClass,
    selectedSkin,
    unlockedHelicopters: Array.from(unlockedSet),
    upgradesByClass: upgrades,
    adContinueWindowStartMs:
      typeof raw.adContinueWindowStartMs === "number" && Number.isFinite(raw.adContinueWindowStartMs)
        ? Math.max(0, Math.floor(raw.adContinueWindowStartMs))
        : 0,
    adContinueUsedToday:
      typeof raw.adContinueUsedToday === "number" && Number.isFinite(raw.adContinueUsedToday)
        ? Math.max(0, Math.min(MAX_DAILY_REWARDED_CONTINUES, Math.floor(raw.adContinueUsedToday)))
        : 0,
    migrationVersion:
      typeof raw.migrationVersion === "number" && Number.isFinite(raw.migrationVersion)
        ? Math.max(0, Math.floor(raw.migrationVersion))
        : 1,
    quarantineStatus:
      raw.quarantineStatus === "PENDING_REVIEW" || raw.quarantineStatus === "RESOLVED"
        ? raw.quarantineStatus
        : "NONE",
  };
}

export function resolveEffectiveQuota(
  hangar: CloudHangarDoc,
  nowMs: number = Date.now()
): { usedToday: number; remaining: number; windowStartMs: number } {
  let windowStart = hangar.adContinueWindowStartMs;
  let used = hangar.adContinueUsedToday;
  if (windowStart > 0 && nowMs - windowStart >= AD_CONTINUE_WINDOW_MS) {
    windowStart = 0;
    used = 0;
  }
  const safeUsed = Math.max(0, Math.min(MAX_DAILY_REWARDED_CONTINUES, used));
  return {
    usedToday: safeUsed,
    remaining: Math.max(0, MAX_DAILY_REWARDED_CONTINUES - safeUsed),
    windowStartMs: windowStart,
  };
}

export function toHangarResponse(
  grossCredits: number,
  hangar: CloudHangarDoc,
  nowMs: number = Date.now()
): HangarResponse {
  const safeGross = Math.max(0, Math.floor(grossCredits));
  const safeSpent = Math.max(0, Math.floor(hangar.creditsSpentCloud));
  const quota = resolveEffectiveQuota(hangar, nowMs);
  return {
    userId: hangar.userId,
    grossCredits: safeGross,
    creditsSpentCloud: safeSpent,
    availableCredits: Math.max(0, safeGross - safeSpent),
    selectedHelicopterClass: hangar.selectedHelicopterClass,
    selectedSkin: hangar.selectedSkin,
    unlockedHelicopters: hangar.unlockedHelicopters,
    upgradesByClass: hangar.upgradesByClass,
    adContinueUsedToday: quota.usedToday,
    adContinueRemaining: quota.remaining,
    adContinueWindowStartMs: quota.windowStartMs,
    migrationVersion: hangar.migrationVersion,
    quarantineStatus: hangar.quarantineStatus,
  };
}

export async function getOrInitHangarCore(
  db: Firestore,
  uid: string,
  nowMs: number = Date.now()
): Promise<HangarResponse> {
  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");

  return db.runTransaction(async (tx) => {
    const [userSnap, hangarSnap] = await Promise.all([tx.get(userRef), tx.get(hangarRef)]);
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized yet.");
    }
    const grossCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
    if (!hangarSnap.exists) {
      const fresh = buildDefaultHangarState(uid);
      tx.set(hangarRef, { ...fresh, updatedAt: FieldValue.serverTimestamp() });
      return toHangarResponse(grossCredits, fresh, nowMs);
    }
    const normalized = normalizeHangarDoc(uid, hangarSnap.data() as Partial<CloudHangarDoc>);
    return toHangarResponse(grossCredits, normalized, nowMs);
  });
}

export async function unlockHelicopterCore(
  db: Firestore,
  uid: string,
  helicopterInput: unknown,
  nowMs: number = Date.now()
): Promise<HangarResponse> {
  const spec = resolveHelicopterSpec(helicopterInput);
  if (!spec) {
    throw new HttpsError("invalid-argument", "Invalid helicopter class or identifier.");
  }

  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");

  return db.runTransaction(async (tx) => {
    const [userSnap, hangarSnap] = await Promise.all([tx.get(userRef), tx.get(hangarRef)]);
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized yet.");
    }

    const grossCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
    const current = normalizeHangarDoc(
      uid,
      hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : null
    );

    if (current.unlockedHelicopters.includes(spec.id)) {
      current.selectedHelicopterClass = spec.className;
      tx.set(
        hangarRef,
        {
          ...current,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
      return toHangarResponse(grossCredits, current, nowMs);
    }

    const available = Math.max(0, grossCredits - current.creditsSpentCloud);
    if (available < spec.unlockCost) {
      throw new HttpsError(
        "failed-precondition",
        `Insufficient War Credits: need ${spec.unlockCost} CR, available ${available} CR.`
      );
    }

    const updated: CloudHangarDoc = {
      ...current,
      creditsSpentCloud: current.creditsSpentCloud + spec.unlockCost,
      selectedHelicopterClass: spec.className,
      unlockedHelicopters: Array.from(new Set([...current.unlockedHelicopters, spec.id])),
    };

    tx.set(hangarRef, {
      ...updated,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return toHangarResponse(grossCredits, updated, nowMs);
  });
}

export async function upgradeHelicopterComponentCore(
  db: Firestore,
  uid: string,
  helicopterInput: unknown,
  categoryInput: unknown,
  nowMs: number = Date.now()
): Promise<HangarResponse> {
  const spec = resolveHelicopterSpec(helicopterInput);
  if (!spec) {
    throw new HttpsError("invalid-argument", "Invalid helicopter identifier.");
  }
  if (
    typeof categoryInput !== "string" ||
    !VALID_UPGRADE_CATEGORIES.includes(categoryInput as UpgradeCategory)
  ) {
    throw new HttpsError("invalid-argument", "Invalid upgrade category.");
  }
  const category = categoryInput as UpgradeCategory;
  const fieldKey = upgradeFieldForCategory(category);

  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");

  return db.runTransaction(async (tx) => {
    const [userSnap, hangarSnap] = await Promise.all([tx.get(userRef), tx.get(hangarRef)]);
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized yet.");
    }

    const grossCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
    const current = normalizeHangarDoc(
      uid,
      hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : null
    );

    if (!current.unlockedHelicopters.includes(spec.id)) {
      throw new HttpsError(
        "failed-precondition",
        `Airframe ${spec.id} is locked. Unlock airframe before upgrading.`
      );
    }

    const currentHeloUpgrades = { ...current.upgradesByClass[spec.id] };
    const currentLevel = clampUpgradeLevel(currentHeloUpgrades[fieldKey]);
    if (currentLevel >= MAX_UPGRADE_LEVEL) {
      throw new HttpsError("failed-precondition", `${category} is already at maximum level 5.`);
    }

    const cost = upgradeCostForLevel(currentLevel);
    const available = Math.max(0, grossCredits - current.creditsSpentCloud);
    if (available < cost) {
      throw new HttpsError(
        "failed-precondition",
        `Insufficient War Credits: need ${cost} CR, available ${available} CR.`
      );
    }

    currentHeloUpgrades[fieldKey] = currentLevel + 1;
    const updated: CloudHangarDoc = {
      ...current,
      creditsSpentCloud: current.creditsSpentCloud + cost,
      upgradesByClass: {
        ...current.upgradesByClass,
        [spec.id]: currentHeloUpgrades,
      },
    };

    tx.set(hangarRef, {
      ...updated,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return toHangarResponse(grossCredits, updated, nowMs);
  });
}

export async function equipLoadoutCore(
  db: Firestore,
  uid: string,
  helicopterInput: unknown,
  skinInput: unknown,
  nowMs: number = Date.now()
): Promise<HangarResponse> {
  const spec = helicopterInput != null ? resolveHelicopterSpec(helicopterInput) : null;
  if (helicopterInput != null && !spec) {
    throw new HttpsError("invalid-argument", "Invalid helicopter identifier.");
  }
  const skinValid =
    typeof skinInput === "string" && VALID_SKINS.includes(skinInput as HelicopterSkinName);
  if (skinInput != null && !skinValid) {
    throw new HttpsError("invalid-argument", "Invalid helicopter skin identifier.");
  }

  const userRef = db.collection("users").doc(uid);
  const hangarRef = userRef.collection("hangar").doc("state");

  return db.runTransaction(async (tx) => {
    const [userSnap, hangarSnap] = await Promise.all([tx.get(userRef), tx.get(hangarRef)]);
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "Pilot campaign profile not initialized yet.");
    }

    const grossCredits = Number(userSnap.get("credits") ?? STARTING_CREDITS);
    const current = normalizeHangarDoc(
      uid,
      hangarSnap.exists ? (hangarSnap.data() as Partial<CloudHangarDoc>) : null
    );

    if (spec && !current.unlockedHelicopters.includes(spec.id)) {
      throw new HttpsError("failed-precondition", "Cannot equip locked helicopter airframe.");
    }

    const updated: CloudHangarDoc = {
      ...current,
      selectedHelicopterClass: spec ? spec.className : current.selectedHelicopterClass,
      selectedSkin: skinValid ? (skinInput as HelicopterSkinName) : current.selectedSkin,
    };

    tx.set(hangarRef, {
      ...updated,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return toHangarResponse(grossCredits, updated, nowMs);
  });
}

function requireAuthUid(request: { auth?: { uid?: string } }): string {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }
  return uid;
}

export const getHangarState = onCall(async (request) => {
  const uid = requireAuthUid(request);
  try {
    return await getOrInitHangarCore(getHellwarDb(), uid);
  } catch (err) {
    safeLog("warn", `getHangarState failed: ${(err as Error).message}`);
    throw err;
  }
});

export const unlockHelicopter = onCall(async (request) => {
  const uid = requireAuthUid(request);
  try {
    const helicopterInput = (request.data as Record<string, unknown> | undefined)?.helicopterId ??
      (request.data as Record<string, unknown> | undefined)?.helicopterClass;
    return await unlockHelicopterCore(getHellwarDb(), uid, helicopterInput);
  } catch (err) {
    safeLog("warn", `unlockHelicopter failed: ${(err as Error).message}`);
    throw err;
  }
});

export const upgradeHelicopterComponent = onCall(async (request) => {
  const uid = requireAuthUid(request);
  try {
    const data = (request.data ?? {}) as Record<string, unknown>;
    return await upgradeHelicopterComponentCore(
      getHellwarDb(),
      uid,
      data.helicopterId ?? data.helicopterClass,
      data.category
    );
  } catch (err) {
    safeLog("warn", `upgradeHelicopterComponent failed: ${(err as Error).message}`);
    throw err;
  }
});

export const equipLoadout = onCall(async (request) => {
  const uid = requireAuthUid(request);
  try {
    const data = (request.data ?? {}) as Record<string, unknown>;
    return await equipLoadoutCore(
      getHellwarDb(),
      uid,
      data.helicopterId ?? data.helicopterClass ?? null,
      data.skin ?? null
    );
  } catch (err) {
    safeLog("warn", `equipLoadout failed: ${(err as Error).message}`);
    throw err;
  }
});
