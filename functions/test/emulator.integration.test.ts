import assert from "node:assert/strict";
import * as crypto from "node:crypto";
import { after, beforeEach, test } from "node:test";
import { FieldValue } from "firebase-admin/firestore";
import {
  claimBarracksDestructionCore,
  claimHeadquartersVictoryCore,
  startSortieSessionCore,
  updateCallsignCore,
} from "../src/campaign";
import { AD_CONTINUE_WINDOW_MS, STARTING_CREDITS } from "../src/catalog";
import { NAMED_DATABASE_ID, getHellwarDb } from "../src/db";
import {
  equipLoadoutCore,
  getOrInitHangarCore,
  unlockHelicopterCore,
  upgradeHelicopterComponentCore,
} from "../src/hangar";
import {
  dryRunLegacyHangarMigrationCore,
  migrateLegacyHangarCore,
} from "../src/migration";
import {
  claimRewardedOutcomeCore,
  handleAdmobSsvCallbackCore,
  prepareRewardedSessionCore,
} from "../src/ssv";
import {
  claimEndlessWaveCompletionCore,
  startEndlessSessionCore,
} from "../src/endless";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error("FIRESTORE_EMULATOR_HOST must be set (run under firebase emulators:exec).");
}
if (!process.env.GCLOUD_PROJECT) {
  process.env.GCLOUD_PROJECT = "hellwar-warzone";
}

const db = getHellwarDb();

async function seedPilot(uid: string, credits: number = STARTING_CREDITS, callsign = "VIPER-7"): Promise<void> {
  await db.collection("users").doc(uid).set({
    userId: uid,
    callsign,
    credits,
    highScore: 0,
    humanDestroyedCount: 0,
    orcDestroyedCount: 0,
    dragonDestroyedCount: 0,
    alienDestroyedCount: 0,
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  });
}

async function clearCollectionDocs(path: string): Promise<void> {
  const snap = await db.collection(path).get();
  for (const doc of snap.docs) {
    if (path === "users") {
      for (const sub of ["destroyed_barracks", "hq_victories", "hangar", "migration_backups", "endless_waves", "endless"]) {
        const subSnap = await doc.ref.collection(sub).get();
        for (const subDoc of subSnap.docs) {
          await subDoc.ref.delete();
        }
      }
    }
    await doc.ref.delete();
  }
}

beforeEach(async () => {
  await Promise.all([
    clearCollectionDocs("users"),
    clearCollectionDocs("leaderboard"),
    clearCollectionDocs("rewards_ledger"),
    clearCollectionDocs("ssv_processed_tx"),
    clearCollectionDocs("sortie_sessions"),
    clearCollectionDocs("endless_sessions"),
    clearCollectionDocs("migration_quarantine"),
  ]);
});

after(async () => {
  await db.terminate();
});

test("Named DB & concurrent hangar transaction race prevents double-spend", async () => {
  const uid = "pilot_race_1";
  // Give pilot 18,500 CR -> can afford Heavy Gunship (8,000) OR Stealth (15,000), NOT BOTH (23,000)
  await seedPilot(uid, 18500);
  const initial = await getOrInitHangarCore(db, uid);
  assert.equal(initial.availableCredits, 18500);
  assert.deepEqual(initial.unlockedHelicopters, ["helo_scout"]);

  // Locked helicopter upgrade MUST be rejected server-side
  await assert.rejects(
    () => upgradeHelicopterComponentCore(db, uid, "helo_heavy", "WEAPON"),
    /locked/i
  );

  // Fire two concurrent unlocks racing for the same 18,500 CR balance
  const outcomes = await Promise.allSettled([
    unlockHelicopterCore(db, uid, "helo_heavy"),
    unlockHelicopterCore(db, uid, "helo_stealth"),
  ]);

  const fulfilled = outcomes.filter((o) => o.status === "fulfilled");
  const rejected = outcomes.filter((o) => o.status === "rejected");
  assert.equal(fulfilled.length, 1, "Only one concurrent unlock may commit");
  assert.equal(rejected.length, 1, "Second concurrent unlock must fail insufficient funds");

  const afterHangar = await getOrInitHangarCore(db, uid);
  assert.ok(afterHangar.availableCredits >= 0, "Available credits must never go negative");
  assert.equal(afterHangar.unlockedHelicopters.length, 2);

  // Upgrade unlocked helicopter Weapon level 1 -> 2 (cost 1,500 CR) & equip skin
  const unlockedSecond = afterHangar.unlockedHelicopters.find((id) => id !== "helo_scout")!;
  const upgraded = await upgradeHelicopterComponentCore(db, uid, unlockedSecond, "WEAPON");
  assert.equal(upgraded.upgradesByClass[unlockedSecond].weaponLevel, 2);

  const equipped = await equipLoadoutCore(db, uid, unlockedSecond, "BLOOD_DRAGON");
  assert.equal(equipped.selectedSkin, "BLOOD_DRAGON");
  assert.equal(db.databaseId, NAMED_DATABASE_ID);
});

test("AdMob ECDSA SHA-256 SSV webhook verification, replay protection, and 10/24h continue quota", async () => {
  const uid = "pilot_ssv_1";
  await seedPilot(uid, 18500);

  // Generate ephemeral EC P-256 verifier keypair representing Google AdMob key_id=42
  const { publicKey, privateKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
  const publicPem = publicKey.export({ type: "spki", format: "pem" }).toString();
  const keyLookup = async (keyId: string) => (keyId === "42" ? publicPem : null);

  function signAdMobQuery(baseParams: string): string {
    const signer = crypto.createSign("SHA256");
    signer.update(baseParams, "utf8");
    signer.end();
    const sig = signer.sign(privateKey).toString("base64url");
    return `${baseParams}&signature=${sig}&key_id=42`;
  }

  // 1. Prepare Supply Drop session -> initially PENDING_SSV, client CANNOT self-settle credits
  const supplySession = await prepareRewardedSessionCore(db, uid, "SUPPLY_DROP", 1_700_000_000_000);
  const preWebhookCheck = await claimRewardedOutcomeCore(db, uid, supplySession.nonceId, 1_700_000_005_000);
  assert.equal(preWebhookCheck.status, "PENDING_SSV");
  assert.equal(preWebhookCheck.availableCredits, 18500);

  // 2. Forged signature MUST be rejected with 403 and zero credit mutation
  const forgedQuery = `ad_network=5450213213286189855&ad_unit=9371034967&custom_data=${supplySession.nonceId}&reward_amount=1000&reward_item=CR&timestamp=1700000001000&transaction_id=tx_forged_001&user_id=${uid}&signature=Zm9yZ2Vk&key_id=42`;
  const forgedOutcome = await handleAdmobSsvCallbackCore(db, forgedQuery, keyLookup, 1_700_000_010_000);
  assert.equal(forgedOutcome.httpStatus, 403);
  assert.equal(forgedOutcome.status, "REJECTED");

  // 3. Valid ECDSA signature settles +1,000 CR atomically
  const validQuery = signAdMobQuery(
    `ad_network=5450213213286189855&ad_unit=9371034967&custom_data=${supplySession.nonceId}&reward_amount=1000&reward_item=CR&timestamp=1700000002000&transaction_id=tx_valid_001&user_id=${uid}`
  );
  const validOutcome = await handleAdmobSsvCallbackCore(db, validQuery, keyLookup, 1_700_000_015_000);
  assert.equal(validOutcome.httpStatus, 200);
  assert.equal(validOutcome.status, "SETTLED");

  const settledOutcome = await claimRewardedOutcomeCore(db, uid, supplySession.nonceId, 1_700_000_020_000);
  assert.equal(settledOutcome.status, "VERIFIED_SETTLED");
  assert.equal(settledOutcome.settledCredits, 1000);
  assert.equal(settledOutcome.availableCredits, 19500);

  // 4. Replay attack using the same transaction_id MUST be ignored without double-crediting
  const replayOutcome = await handleAdmobSsvCallbackCore(db, validQuery, keyLookup, 1_700_000_025_000);
  assert.equal(replayOutcome.httpStatus, 200);
  assert.equal(replayOutcome.status, "DUPLICATE_IGNORED");
  const afterReplay = await getOrInitHangarCore(db, uid);
  assert.equal(afterReplay.availableCredits, 19500);

  // 5. Server enforces 10-per-24h AD_CONTINUE cap and resets after 24h
  let now = 1_700_000_100_000;
  for (let i = 1; i <= 10; i++) {
    const contSess = await prepareRewardedSessionCore(db, uid, "AD_CONTINUE", now);
    const contQuery = signAdMobQuery(
      `ad_unit=9371034967&custom_data=${contSess.nonceId}&transaction_id=tx_cont_${i}_abcdef&user_id=${uid}`
    );
    const ssvRes = await handleAdmobSsvCallbackCore(db, contQuery, keyLookup, now + 1000);
    assert.equal(ssvRes.status, "SETTLED");
    now += 2000;
  }

  await assert.rejects(
    () => prepareRewardedSessionCore(db, uid, "AD_CONTINUE", now),
    /limit reached/i
  );

  // Advance 24h + 1s -> quota automatically resets to 10/10
  const postWindow = 1_700_000_100_000 + AD_CONTINUE_WINDOW_MS + 1000;
  const resetSess = await prepareRewardedSessionCore(db, uid, "AD_CONTINUE", postWindow);
  assert.equal(resetSess.adContinueRemaining, 10);
});

test("Legacy migration dry-run & non-destructive quarantine preserves conflicting player inventory", async () => {
  const uid = "pilot_legacy_conflict";
  await seedPilot(uid, 18500);

  const legacyConflictSnapshot = {
    creditsSpent: 2000,
    selectedHelicopterClass: "ULTIMATE_WAR",
    selectedSkin: "PLASMA_NEON",
    unlockedHelicopters: ["helo_scout", "helo_ultimate"], // helo_ultimate alone costs 25,000 > 18,500
    upgradesByClass: {
      helo_ultimate: { engineLevel: 3, armorLevel: 2, weaponLevel: 1, defenseLevel: 1, radarLevel: 1 },
    },
  };

  // 1. Dry-run is non-destructive (no hangar doc written)
  const dryRun = await dryRunLegacyHangarMigrationCore(db, uid, legacyConflictSnapshot);
  assert.equal(dryRun.action, "QUARANTINED_PRESERVED");
  assert.equal(dryRun.conflictsDetected, true);
  assert.ok(dryRun.reasonCodes.includes("IMPLIED_COST_EXCEEDS_GROSS_CREDITS"));
  assert.ok(dryRun.preservedUnlockedHelicopters.includes("helo_ultimate"));
  const hangarBefore = await db.collection("users").doc(uid).collection("hangar").doc("state").get();
  assert.equal(hangarBefore.exists, false);

  // 2. Live migration preserves ALL helicopters & upgrades, saves backup, and writes quarantine record
  const migrated = await migrateLegacyHangarCore(db, uid, legacyConflictSnapshot, 1_700_000_500_000);
  assert.equal(migrated.quarantined, true);
  assert.ok(migrated.hangar.unlockedHelicopters.includes("helo_ultimate"));
  assert.equal(migrated.hangar.selectedHelicopterClass, "ULTIMATE_WAR");
  assert.equal(migrated.hangar.selectedSkin, "PLASMA_NEON");
  assert.equal(migrated.hangar.upgradesByClass.helo_ultimate.engineLevel, 3);
  assert.equal(migrated.hangar.quarantineStatus, "PENDING_REVIEW");

  const backupSnap = await db.collection("users").doc(uid).collection("migration_backups").get();
  assert.equal(backupSnap.size, 1);
  const quarantineSnap = await db.collection("migration_quarantine").doc(uid).get();
  assert.equal(quarantineSnap.exists, true);
  assert.equal(quarantineSnap.get("status"), "PENDING_REVIEW");
});

test("Server campaign progression: sortie -> 50 barracks -> HQ unlock -> HQ victory -> leaderboard", async () => {
  const uid = "pilot_campaign_1";
  await seedPilot(uid, 18500, "ALPHA-1");

  const sortie = await startSortieSessionCore(db, uid, "DRAGON");
  assert.equal(sortie.empire, "DRAGON");

  // 1..49 barracks -> HQ blocked
  for (let i = 1; i <= 49; i++) {
    const res = await claimBarracksDestructionCore(db, uid, "DRAGON", i, 350, sortie.sessionId);
    assert.equal(res.alreadyDestroyed, false);
    assert.equal(res.newTotalInEmpire, i);
  }
  await assert.rejects(
    () => claimHeadquartersVictoryCore(db, uid, "DRAGON", sortie.sessionId),
    /49\/50/
  );

  // 50th barracks -> HQ unlocked -> victory + leaderboard updated
  const fiftieth = await claimBarracksDestructionCore(db, uid, "DRAGON", 50, 350, sortie.sessionId);
  assert.equal(fiftieth.newTotalInEmpire, 50);

  // Idempotent repeat of barracks 50 does NOT double-award credits
  const duplicateBarracks = await claimBarracksDestructionCore(db, uid, "DRAGON", 50, 350, sortie.sessionId);
  assert.equal(duplicateBarracks.alreadyDestroyed, true);
  assert.equal(duplicateBarracks.creditsAwarded, 0);

  const victory = await claimHeadquartersVictoryCore(db, uid, "DRAGON", sortie.sessionId);
  assert.equal(victory.alreadyCleared, false);
  assert.equal(victory.creditsAwarded, 2500);

  await updateCallsignCore(db, uid, "PHOENIX-9");
  const lbDoc = await db.collection("leaderboard").doc(uid).get();
  assert.equal(lbDoc.get("callsign"), "PHOENIX-9");
  assert.equal(lbDoc.get("totalBarracksDestroyed"), 50);
  assert.equal(lbDoc.get("hqClearedCount"), 1);
  assert.equal(lbDoc.get("highScore"), 50 * 1000 + 15000);
});

test("Endless Survival Cloud Functions: selected-Empire sessions, escalating rewards, anti-duplicate, sequential check, empireStats, and leaderboard sync", async () => {
  const aliceUid = "pilot_endless_alice";
  const bobUid = "pilot_endless_bob";
  await seedPilot(aliceUid, 18500, "SKY-ACE");
  await seedPilot(bobUid, 18500, "ROGUE-2");

  const startTime = 1_700_001_000_000;

  // Invalid Empire MUST be rejected
  await assert.rejects(
    () => startEndlessSessionCore(db, aliceUid, "MARTIAN", startTime),
    /Invalid Empire/i
  );

  // Start an ALIEN Endless session — must stay ALIEN across all waves without 5-wave auto-rotation
  const alienSession = await startEndlessSessionCore(db, aliceUid, "ALIEN", startTime);
  assert.equal(alienSession.empire, "ALIEN");
  assert.equal(alienSession.currentEmpire, "ALIEN");

  // Submitting wave 2 before wave 1 MUST be rejected
  await assert.rejects(
    () => claimEndlessWaveCompletionCore(db, aliceUid, alienSession.sessionId, 2, 6, startTime + 5000),
    /sequential wave 1/i
  );

  // Submitting faster than MIN_ENDLESS_WAVE_INTERVAL_MS MUST be rejected
  await assert.rejects(
    () => claimEndlessWaveCompletionCore(db, aliceUid, alienSession.sessionId, 1, 4, startTime + 500),
    /minimum combat interval/i
  );

  // Cross-user session hijack MUST be rejected
  await assert.rejects(
    () => claimEndlessWaveCompletionCore(db, bobUid, alienSession.sessionId, 1, 4, startTime + 5000),
    /mismatch/i
  );

  // Run 8 sequential waves verifying ALIEN stays locked without rotating to ORC at wave 6
  let now = startTime + 5000;
  let expectedCredits = 18500;
  let previousWaveCredits = 0;
  let expectedAlienKills = 0;

  for (let w = 1; w <= 8; w++) {
    const waveKills = 4 + w;
    const res = await claimEndlessWaveCompletionCore(db, aliceUid, alienSession.sessionId, w, waveKills, now);
    assert.equal(res.alreadyClaimed, false);
    assert.equal(res.empire, "ALIEN");
    assert.equal(res.nextEmpire, "ALIEN");
    assert.ok(res.creditsAwarded > previousWaveCredits, `Wave ${w} credits should escalate`);
    previousWaveCredits = res.creditsAwarded;
    expectedCredits += res.creditsAwarded;
    expectedAlienKills += waveKills;
    assert.equal(res.newTotalCredits, expectedCredits);
    now += 5000;
  }

  // Duplicate wave 5 claim is idempotent and awards 0 extra credits
  const dup = await claimEndlessWaveCompletionCore(db, aliceUid, alienSession.sessionId, 5, 10, now);
  assert.equal(dup.alreadyClaimed, true);
  assert.equal(dup.creditsAwarded, 0);
  assert.equal(dup.newTotalCredits, expectedCredits);

  // Start a second session in ORC — must preserve ALIEN stats while updating ORC independently
  const orcSession = await startEndlessSessionCore(db, aliceUid, "ORC", now);
  assert.equal(orcSession.empire, "ORC");
  const orcWave1 = await claimEndlessWaveCompletionCore(db, aliceUid, orcSession.sessionId, 1, 10, now + 5000);
  assert.equal(orcWave1.empire, "ORC");
  assert.equal(orcWave1.nextEmpire, "ORC");
  expectedCredits += orcWave1.creditsAwarded;

  // Authoritative stats & per-Empire empireStats updated without altering campaign barracks/HQ counters
  const statsDoc = await db.collection("users").doc(aliceUid).collection("endless").doc("stats").get();
  assert.equal(statsDoc.get("lastActiveEmpire"), "ORC");
  assert.equal(statsDoc.get("highestWave"), 8);
  assert.equal(statsDoc.get("totalWavesCompleted"), 9);

  const empireStats = statsDoc.get("empireStats") as Record<string, Record<string, unknown>>;
  assert.equal(empireStats.ALIEN.highestWave, 8);
  assert.equal(empireStats.ALIEN.totalWavesCompleted, 8);
  assert.equal(empireStats.ALIEN.totalKills, expectedAlienKills);
  assert.equal(empireStats.ORC.highestWave, 1);
  assert.equal(empireStats.ORC.totalWavesCompleted, 1);
  assert.equal(empireStats.ORC.totalKills, 10);

  const userDoc = await db.collection("users").doc(aliceUid).get();
  assert.equal(userDoc.get("credits"), expectedCredits);
  assert.equal(userDoc.get("dragonDestroyedCount"), 0);

  const lbDoc = await db.collection("leaderboard").doc(aliceUid).get();
  assert.equal(lbDoc.get("callsign"), "SKY-ACE");
  assert.ok(Number(lbDoc.get("highScore")) > 0);
  assert.equal(lbDoc.get("totalBarracksDestroyed"), 0);
  assert.equal(lbDoc.get("hqClearedCount"), 0);
});
