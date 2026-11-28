export type EmpireId = "HUMAN" | "ORC" | "DRAGON" | "ALIEN";
export type HelicopterClassName = "SCOUT" | "HEAVY_GUNSHIP" | "STEALTH" | "ULTIMATE_WAR";
export type HelicopterId = "helo_scout" | "helo_heavy" | "helo_stealth" | "helo_ultimate";
export type HelicopterSkinName =
  | "TACTICAL_BLACK"
  | "MILITARY_CAMO"
  | "ARCTIC_WHITE"
  | "BLOOD_DRAGON"
  | "PLASMA_NEON";
export type UpgradeCategory = "ENGINE" | "ARMOR" | "WEAPON" | "DEFENSE" | "RADAR";

export const VALID_EMPIRES: readonly EmpireId[] = ["HUMAN", "ORC", "DRAGON", "ALIEN"];
export const VALID_SKINS: readonly HelicopterSkinName[] = [
  "TACTICAL_BLACK",
  "MILITARY_CAMO",
  "ARCTIC_WHITE",
  "BLOOD_DRAGON",
  "PLASMA_NEON",
];
export const VALID_UPGRADE_CATEGORIES: readonly UpgradeCategory[] = [
  "ENGINE",
  "ARMOR",
  "WEAPON",
  "DEFENSE",
  "RADAR",
];

export interface HelicopterSpec {
  id: HelicopterId;
  className: HelicopterClassName;
  unlockCost: number;
}

export const HELICOPTER_BY_ID: Record<HelicopterId, HelicopterSpec> = {
  helo_scout: { id: "helo_scout", className: "SCOUT", unlockCost: 0 },
  helo_heavy: { id: "helo_heavy", className: "HEAVY_GUNSHIP", unlockCost: 8000 },
  helo_stealth: { id: "helo_stealth", className: "STEALTH", unlockCost: 15000 },
  helo_ultimate: { id: "helo_ultimate", className: "ULTIMATE_WAR", unlockCost: 25000 },
};

export const HELICOPTER_BY_CLASS: Record<HelicopterClassName, HelicopterSpec> = {
  SCOUT: HELICOPTER_BY_ID.helo_scout,
  HEAVY_GUNSHIP: HELICOPTER_BY_ID.helo_heavy,
  STEALTH: HELICOPTER_BY_ID.helo_stealth,
  ULTIMATE_WAR: HELICOPTER_BY_ID.helo_ultimate,
};

export const STARTING_CREDITS = 18500;
export const MAX_CREDITS = 100_000_000;
export const MAX_UPGRADE_LEVEL = 5;
export const BARRACKS_CREDITS_AWARD = 200;
export const BARRACKS_SCORE_AWARD = 1000;
export const HQ_CREDITS_AWARD = 2500;
export const HQ_SCORE_AWARD = 15000;
export const REWARDED_SUPPLY_DROP_CREDITS = 1000;
export const MAX_DAILY_REWARDED_CONTINUES = 10;
export const AD_CONTINUE_WINDOW_MS = 24 * 60 * 60 * 1000;

export const ENDLESS_EMPIRE_KILL_TARGET = 250_000;
export const ENDLESS_TOTAL_KILL_TARGET = 1_000_000;
export const MAX_ENDLESS_WAVE = 1000;
export const MIN_ENDLESS_WAVE_INTERVAL_MS = 3000;

export function parseValidEmpire(input: unknown): EmpireId | null {
  if (typeof input !== "string") return null;
  const normalized = input.trim().toUpperCase();
  return (VALID_EMPIRES as readonly string[]).includes(normalized)
    ? (normalized as EmpireId)
    : null;
}

export function calculateEndlessWaveCredits(wave: number): number {
  const safeWave = Math.max(1, Math.min(MAX_ENDLESS_WAVE, Math.floor(wave)));
  return 150 + Math.min(25, safeWave - 1) * 35;
}

export function calculateEndlessWaveScore(wave: number, waveKills: number): number {
  const safeWave = Math.max(1, Math.min(MAX_ENDLESS_WAVE, Math.floor(wave)));
  const safeKills = Math.max(0, Math.min(40, Math.floor(waveKills)));
  return 500 + safeWave * 250 + safeKills * 200;
}

export interface HelicopterUpgradesRecord {
  engineLevel: number;
  armorLevel: number;
  weaponLevel: number;
  defenseLevel: number;
  radarLevel: number;
}

export interface CloudHangarDoc {
  userId: string;
  creditsSpentCloud: number;
  selectedHelicopterClass: HelicopterClassName;
  selectedSkin: HelicopterSkinName;
  unlockedHelicopters: HelicopterId[];
  upgradesByClass: Record<HelicopterId, HelicopterUpgradesRecord>;
  adContinueWindowStartMs: number;
  adContinueUsedToday: number;
  migrationVersion: number;
  quarantineStatus: "NONE" | "PENDING_REVIEW" | "RESOLVED";
  updatedAt?: FirebaseFirestore.FieldValue | FirebaseFirestore.Timestamp;
}

export function defaultUpgradesRecord(): HelicopterUpgradesRecord {
  return {
    engineLevel: 1,
    armorLevel: 1,
    weaponLevel: 1,
    defenseLevel: 1,
    radarLevel: 1,
  };
}

export function defaultUpgradesMap(): Record<HelicopterId, HelicopterUpgradesRecord> {
  return {
    helo_scout: defaultUpgradesRecord(),
    helo_heavy: defaultUpgradesRecord(),
    helo_stealth: defaultUpgradesRecord(),
    helo_ultimate: defaultUpgradesRecord(),
  };
}

export function clampUpgradeLevel(raw: unknown): number {
  const n = typeof raw === "number" && Number.isFinite(raw) ? Math.floor(raw) : 1;
  return Math.max(1, Math.min(MAX_UPGRADE_LEVEL, n));
}

export function upgradeCostForLevel(currentLevel: number): number {
  return currentLevel * 1500;
}

export function upgradeFieldForCategory(category: UpgradeCategory): keyof HelicopterUpgradesRecord {
  switch (category) {
    case "ENGINE":
      return "engineLevel";
    case "ARMOR":
      return "armorLevel";
    case "WEAPON":
      return "weaponLevel";
    case "DEFENSE":
      return "defenseLevel";
    case "RADAR":
      return "radarLevel";
  }
}

export function computeImpliedHangarCost(
  unlockedHelicopters: Iterable<string>,
  upgradesByClass: Partial<Record<string, Partial<HelicopterUpgradesRecord>>>
): number {
  let total = 0;
  const seen = new Set<string>();
  for (const id of unlockedHelicopters) {
    if (seen.has(id)) continue;
    seen.add(id);
    const spec = HELICOPTER_BY_ID[id as HelicopterId];
    if (spec) {
      total += spec.unlockCost;
    }
  }
  for (const id of Object.keys(HELICOPTER_BY_ID) as HelicopterId[]) {
    const up = upgradesByClass[id];
    if (!up) continue;
    const levels = [
      clampUpgradeLevel(up.engineLevel),
      clampUpgradeLevel(up.armorLevel),
      clampUpgradeLevel(up.weaponLevel),
      clampUpgradeLevel(up.defenseLevel),
      clampUpgradeLevel(up.radarLevel),
    ];
    for (const lvl of levels) {
      for (let tier = 1; tier < lvl; tier++) {
        total += upgradeCostForLevel(tier);
      }
    }
  }
  return total;
}

export function resolveHelicopterSpec(input: unknown): HelicopterSpec | null {
  if (typeof input !== "string") return null;
  const trimmed = input.trim();
  if (trimmed in HELICOPTER_BY_ID) {
    return HELICOPTER_BY_ID[trimmed as HelicopterId];
  }
  if (trimmed in HELICOPTER_BY_CLASS) {
    return HELICOPTER_BY_CLASS[trimmed as HelicopterClassName];
  }
  return null;
}
