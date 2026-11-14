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
