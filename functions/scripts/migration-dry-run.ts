import { CloudHangarDoc, STARTING_CREDITS } from "../src/catalog";
import { NAMED_DATABASE_ID, getHellwarDb, safeLog } from "../src/db";
import { MigrationDryRunReport, evaluateLegacyHangarMigration } from "../src/migration";

async function main(): Promise<void> {
  const db = getHellwarDb();
  const usersSnap = await db.collection("users").get();

  const reports: Array<Omit<MigrationDryRunReport, "userId" | "proposedHangar"> & { maskedUserId: string }> = [];
  let cleanCount = 0;
  let alreadyMigratedCount = 0;
  let quarantineCount = 0;

  for (const userDoc of usersSnap.docs) {
    const uid = userDoc.id;
    const grossCredits = Number(userDoc.get("credits") ?? STARTING_CREDITS);
    const hangarSnap = await userDoc.ref.collection("hangar").doc("state").get();
    const existingHangar = hangarSnap.exists
      ? (hangarSnap.data() as Partial<CloudHangarDoc>)
      : null;

    const evalReport = evaluateLegacyHangarMigration(uid, grossCredits, existingHangar, null);
    if (evalReport.action === "ALREADY_MIGRATED") alreadyMigratedCount++;
    else if (evalReport.action === "CLEAN_MIGRATE") cleanCount++;
    else quarantineCount++;

    reports.push({
      maskedUserId: `${uid.slice(0, 4)}...`,
      action: evalReport.action,
      grossCredits: evalReport.grossCredits,
      claimedSpent: evalReport.claimedSpent,
      recomputedImpliedCost: evalReport.recomputedImpliedCost,
      proposedCreditsSpentCloud: evalReport.proposedCreditsSpentCloud,
      proposedAvailableCredits: evalReport.proposedAvailableCredits,
      preservedUnlockedHelicopters: evalReport.preservedUnlockedHelicopters,
      conflictsDetected: evalReport.conflictsDetected,
      reasonCodes: evalReport.reasonCodes,
    });
  }

  safeLog(
    "info",
    JSON.stringify(
      {
        mode: "DRY_RUN_READ_ONLY",
        databaseId: NAMED_DATABASE_ID,
        totalUsersScanned: usersSnap.size,
        summary: {
          alreadyMigrated: alreadyMigratedCount,
          cleanMigrate: cleanCount,
          quarantinedPreserved: quarantineCount,
        },
        users: reports,
      },
      null,
      2
    )
  );
}

main().catch((err) => {
  safeLog("error", `Dry-run audit failed: ${(err as Error).message}`);
  process.exitCode = 1;
});
