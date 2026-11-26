const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const nowTimestamp = () => new Date(Date.now() - 1000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-heliware-c2397082-887f-480e-b9b0-2eec1df83254";
const ALICE_UID = "alice_pilot_uid";
const BOB_UID = "bob_pilot_uid";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfilePayload(uid, callsign = "VIPER-7") {
  return {
    userId: uid,
    callsign,
    credits: 18500,
    highScore: 0,
    humanDestroyedCount: 0,
    orcDestroyedCount: 0,
    dragonDestroyedCount: 0,
    alienDestroyedCount: 0,
    createdAt: nowTimestamp(),
    updatedAt: nowTimestamp(),
  };
}

function validBarracksPayload(uid, empire = "HUMAN", index = 1) {
  return {
    userId: uid,
    barracksId: `BARRACKS_${empire}_${index}`,
    empire,
    barracksIndex: index,
    damageDealt: 320,
    creditsAwarded: 200,
    scoreAwarded: 1000,
    destroyedAt: nowTimestamp(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      databaseId: DATABASE_ID,
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied all reads and writes", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID)));
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("destroyed_barracks").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("destroyed_barracks").doc("BARRACKS_HUMAN_1").set(validBarracksPayload(ALICE_UID))
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("hq_victories").get());
});

test("Authenticated user: can create and read own initial profile, rejected on credit/ghost-field tampering", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  // Cannot list all users
  await assertFails(aliceDb.collection("users").get());

  // Cannot create profile with forged starting credits or ghost field
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      ...validProfilePayload(BOB_UID),
      credits: 999999,
    })
  );
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      ...validProfilePayload(BOB_UID),
      isAdmin: true,
    })
  );
});

test("Cross-user isolation: Alice cannot read or write Bob's campaign records", async () => {
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(bobDb.collection("users").doc(BOB_UID).set(validProfilePayload(BOB_UID, "FALCON-2")));
  await assertSucceeds(
    bobDb.collection("users").doc(BOB_UID).collection("destroyed_barracks").doc("BARRACKS_HUMAN_1").set(validBarracksPayload(BOB_UID))
  );

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("destroyed_barracks").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("destroyed_barracks").doc("BARRACKS_HUMAN_2").set(validBarracksPayload(BOB_UID, "HUMAN", 2))
  );
});

test("Barracks destruction: valid create succeeds, duplicate update/delete and tampered payloads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const barracksCol = aliceDb.collection("users").doc(ALICE_UID).collection("destroyed_barracks");

  await assertSucceeds(barracksCol.doc("BARRACKS_HUMAN_1").set(validBarracksPayload(ALICE_UID, "HUMAN", 1)));
  await assertSucceeds(barracksCol.get());

  // Cannot overwrite or delete an already destroyed barracks (anti-duplicate reward protection)
  await assertFails(barracksCol.doc("BARRACKS_HUMAN_1").update({ creditsAwarded: 200 }));
  await assertFails(barracksCol.doc("BARRACKS_HUMAN_1").delete());

  // Tampered rewards, insufficient damage, invalid index, or mismatched ID must fail
  await assertFails(
    barracksCol.doc("BARRACKS_HUMAN_2").set({
      ...validBarracksPayload(ALICE_UID, "HUMAN", 2),
      creditsAwarded: 50000,
    })
  );
  await assertFails(
    barracksCol.doc("BARRACKS_HUMAN_3").set({
      ...validBarracksPayload(ALICE_UID, "HUMAN", 3),
      damageDealt: 100,
    })
  );
  await assertFails(
    barracksCol.doc("BARRACKS_HUMAN_51").set({
      ...validBarracksPayload(ALICE_UID, "HUMAN", 51),
    })
  );
  await assertFails(
    barracksCol.doc("BARRACKS_HUMAN_4").set({
      ...validBarracksPayload(ALICE_UID, "ORC", 4),
    })
  );
});

test("Headquarters victory: blocked at 49/50 barracks and allowed strictly at 50/50", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      callsign: "VIPER-7",
      credits: 18500 + 49 * 200,
      highScore: 49 * 1000,
      humanDestroyedCount: 49,
      orcDestroyedCount: 0,
      dragonDestroyedCount: 0,
      alienDestroyedCount: 0,
      createdAt: nowTimestamp(),
      updatedAt: nowTimestamp(),
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const hqPayload = {
    userId: ALICE_UID,
    empire: "HUMAN",
    bossId: "BOSS_HUMAN",
    creditsAwarded: 2500,
    scoreAwarded: 15000,
    victoryAt: nowTimestamp(),
  };

  // At 49/50, HQ victory write MUST be denied
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("hq_victories").doc("HUMAN").set(hqPayload)
  );

  // Increment count to 50 via valid monotonic update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      credits: 18500 + 50 * 200,
      highScore: 50 * 1000,
      humanDestroyedCount: 50,
      updatedAt: nowTimestamp(),
    })
  );

  // At 50/50, HQ victory write succeeds and becomes immutable
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("hq_victories").doc("HUMAN").set(hqPayload)
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("hq_victories").doc("HUMAN").delete()
  );
});

test("Public leaderboard: unauthenticated Top 5 read succeeds while writes, spoofing, and score inflation fail", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (let i = 1; i <= 6; i++) {
      const uid = `pilot_${i}`;
      await db.collection("users").doc(uid).set({
        ...validProfilePayload(uid, `ACE-${i}`),
        highScore: i * 10000,
        humanDestroyedCount: i * 5,
      });
      await db.collection("leaderboard").doc(uid).set({
        callsign: `ACE-${i}`,
        highScore: i * 10000,
        totalBarracksDestroyed: i * 5,
        hqClearedCount: 0,
        updatedAt: nowTimestamp(),
      });
    }
  });

  // Unauthenticated visitor (website) can list Top 5 ordered by highScore DESC, but still cannot access /users
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const top5Snap = await assertSucceeds(
    unauthDb.collection("leaderboard").orderBy("highScore", "desc").limit(5).get()
  );
  if (top5Snap.size !== 5) {
    throw new Error(`Expected 5 leaderboard entries, got ${top5Snap.size}`);
  }
  await assertFails(unauthDb.collection("users").get());
  await assertFails(
    unauthDb.collection("leaderboard").doc("unauth_hacker").set({
      callsign: "HACKER",
      highScore: 99999999,
      totalBarracksDestroyed: 200,
      hqClearedCount: 4,
      updatedAt: nowTimestamp(),
    })
  );

  // Authenticated Alice can sync her verified leaderboard entry matching users/ALICE_UID
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID, "ALICE-1")));
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      credits: 18700,
      highScore: 1000,
      humanDestroyedCount: 1,
      updatedAt: nowTimestamp(),
    })
  );
  await assertSucceeds(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set({
      callsign: "ALICE-1",
      highScore: 1000,
      totalBarracksDestroyed: 1,
      hqClearedCount: 0,
      updatedAt: nowTimestamp(),
    })
  );

  // Reject score inflation, HQ forgery, ghost fields, cross-user overwrites, and deletes
  await assertFails(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set({
      callsign: "ALICE-1",
      highScore: 9999999,
      totalBarracksDestroyed: 1,
      hqClearedCount: 0,
      updatedAt: nowTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set({
      callsign: "ALICE-1",
      highScore: 1000,
      totalBarracksDestroyed: 1,
      hqClearedCount: 4,
      updatedAt: nowTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set({
      callsign: "ALICE-1",
      highScore: 1000,
      totalBarracksDestroyed: 1,
      hqClearedCount: 0,
      email: "leak@example.com",
      updatedAt: nowTimestamp(),
    })
  );
  await assertFails(
    aliceDb.collection("leaderboard").doc("pilot_6").update({
      highScore: 0,
      updatedAt: nowTimestamp(),
    })
  );
  await assertFails(aliceDb.collection("leaderboard").doc(ALICE_UID).delete());
});

test("Stage 1 transitional rules: server-only collections block client writes while allowing owner status reads", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID));
    await db.collection("users").doc(ALICE_UID).collection("hangar").doc("state").set({
      userId: ALICE_UID,
      creditsSpentCloud: 8000,
      selectedHelicopterClass: "HEAVY_GUNSHIP",
      selectedSkin: "TACTICAL_BLACK",
      unlockedHelicopters: ["helo_scout", "helo_heavy"],
    });
    await db.collection("users").doc(ALICE_UID).collection("migration_backups").doc("bak_1").set({ ok: true });
    await db.collection("rewards_ledger").doc("ssv_nonce_123").set({
      nonceId: "ssv_nonce_123",
      userId: ALICE_UID,
      rewardType: "SUPPLY_DROP",
      status: "PENDING_SSV",
    });
    await db.collection("ssv_processed_tx").doc("tx_abc123").set({ transactionId: "tx_abc123" });
    await db.collection("migration_quarantine").doc(ALICE_UID).set({
      userId: ALICE_UID,
      status: "PENDING_REVIEW",
    });
    await db.collection("config").doc("client_policy").set({ minVersionCode: 5 });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Owner can read own hangar/state, reward ledger nonce, quarantine notice, and public client_policy
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("hangar").doc("state").get());
  await assertSucceeds(aliceDb.collection("rewards_ledger").doc("ssv_nonce_123").get());
  await assertSucceeds(aliceDb.collection("migration_quarantine").doc(ALICE_UID).get());
  await assertSucceeds(aliceDb.collection("config").doc("client_policy").get());

  // Owner CANNOT write to server-authoritative collections or read backups/SSV replay table
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("hangar").doc("state").set({ creditsSpentCloud: 0 })
  );
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("migration_backups").doc("bak_1").get());
  await assertFails(aliceDb.collection("ssv_processed_tx").doc("tx_abc123").get());
  await assertFails(aliceDb.collection("rewards_ledger").doc("ssv_nonce_123").update({ status: "VERIFIED_SETTLED" }));
  await assertFails(aliceDb.collection("migration_quarantine").doc(ALICE_UID).delete());

  // Cross-user isolation on new collections
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("hangar").doc("state").get());
  await assertFails(bobDb.collection("rewards_ledger").doc("ssv_nonce_123").get());
  await assertFails(bobDb.collection("migration_quarantine").doc(ALICE_UID).get());
});

test("Stage 3 strict rules (firestore.strict.rules): blocks ALL client writes including profiles, barracks, HQ, and leaderboard", async () => {
  const strictRules = fs.readFileSync("./firestore.strict.rules", "utf8");
  const strictEnv = await initializeTestEnvironment({
    projectId: "demo-strict-hellwar",
    firestore: {
      databaseId: DATABASE_ID,
      rules: strictRules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });

  try {
    await strictEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await db.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID));
      await db.collection("leaderboard").doc(ALICE_UID).set({
        callsign: "VIPER-7",
        highScore: 5000,
        totalBarracksDestroyed: 5,
        hqClearedCount: 0,
        updatedAt: nowTimestamp(),
      });
    });

    const aliceStrict = strictEnv.authenticatedContext(ALICE_UID).firestore();
    const unauthStrict = strictEnv.unauthenticatedContext().firestore();

    // Reads continue to function cleanly
    await assertSucceeds(aliceStrict.collection("users").doc(ALICE_UID).get());
    await assertSucceeds(unauthStrict.collection("leaderboard").orderBy("highScore", "desc").limit(5).get());

    // ALL direct client writes are strictly denied in Stage 3
    await assertFails(aliceStrict.collection("users").doc(BOB_UID).set(validProfilePayload(BOB_UID)));
    await assertFails(aliceStrict.collection("users").doc(ALICE_UID).update({ credits: 99999 }));
    await assertFails(
      aliceStrict.collection("users").doc(ALICE_UID).collection("destroyed_barracks").doc("BARRACKS_HUMAN_1").set(validBarracksPayload(ALICE_UID))
    );
    await assertFails(
      aliceStrict.collection("users").doc(ALICE_UID).collection("endless_waves").doc("endless_s1_W1").set({ waveNumber: 1, creditsAwarded: 9999 })
    );
    await assertFails(
      aliceStrict.collection("endless_sessions").doc("endless_s1").set({ userId: ALICE_UID, status: "ACTIVE" })
    );
    await assertFails(
      aliceStrict.collection("leaderboard").doc(ALICE_UID).set({
        callsign: "VIPER-7",
        highScore: 99999,
        totalBarracksDestroyed: 50,
        hqClearedCount: 1,
        updatedAt: nowTimestamp(),
      })
    );
  } finally {
    await strictEnv.cleanup();
  }
});

test("Endless Survival rules: owner read-only on sessions/waves/stats, blocks ALL client writes and cross-user reads", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID));
    await db.collection("users").doc(ALICE_UID).collection("endless").doc("stats").set({
      userId: ALICE_UID,
      highestWave: 7,
      bestSurvivalScore: 14500,
      totalWavesCompleted: 7,
      totalEndlessCreditsEarned: 1785,
      updatedAt: nowTimestamp(),
    });
    await db.collection("users").doc(ALICE_UID).collection("endless_waves").doc("endless_s1_W1").set({
      userId: ALICE_UID,
      sessionId: "endless_s1",
      waveNumber: 1,
      empire: "DRAGON",
      creditsAwarded: 150,
      scoreAwarded: 1550,
      settledAt: nowTimestamp(),
    });
    await db.collection("endless_sessions").doc("endless_s1").set({
      sessionId: "endless_s1",
      userId: ALICE_UID,
      status: "ACTIVE",
      lastSettledWave: 1,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  // Owner can read own endless stats, waves, and session
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("endless").doc("stats").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("endless_waves").get());
  await assertSucceeds(aliceDb.collection("endless_sessions").doc("endless_s1").get());

  // Owner CANNOT list endless_sessions or write/forge waves, stats, sessions, or user credits
  await assertFails(aliceDb.collection("endless_sessions").get());
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("endless").doc("stats").set({ highestWave: 999 })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("endless_waves").doc("endless_s1_W2").set({ waveNumber: 2, creditsAwarded: 99999 })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("endless_waves").doc("endless_s1_W1").delete()
  );
  await assertFails(
    aliceDb.collection("endless_sessions").doc("endless_s2").set({ userId: ALICE_UID, status: "ACTIVE" })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({ credits: 999999, updatedAt: nowTimestamp() })
  );

  // Cross-user and unauthenticated reads/writes are strictly denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("endless").doc("stats").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("endless_waves").get());
  await assertFails(bobDb.collection("endless_sessions").doc("endless_s1").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("endless").doc("stats").get());
  await assertFails(unauthDb.collection("endless_sessions").doc("endless_s1").get());
});

