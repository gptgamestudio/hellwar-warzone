import { getApp, getApps, initializeApp } from "firebase-admin/app";
import { Firestore, getFirestore } from "firebase-admin/firestore";

export const NAMED_DATABASE_ID = "ai-studio-android-heliware-c2397082-887f-480e-b9b0-2eec1df83254";

let cachedDb: Firestore | null = null;

export function getHellwarDb(): Firestore {
  if (cachedDb) return cachedDb;
  const app = getApps().length > 0 ? getApp() : initializeApp();
  cachedDb = getFirestore(app, NAMED_DATABASE_ID);
  return cachedDb;
}

export function redactPii(input: string): string {
  return input
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[REDACTED_EMAIL]")
    .replace(/\busers\/[A-Za-z0-9_-]+/g, "users/[REDACTED_UID]")
    .replace(/\bleaderboard\/[A-Za-z0-9_-]+/g, "leaderboard/[REDACTED_UID]")
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, "[REDACTED_TOKEN]");
}

export function safeLog(level: "info" | "warn" | "error", message: string): void {
  const sanitized = redactPii(message);
  if (level === "error") {
    console.error(sanitized);
  } else if (level === "warn") {
    console.warn(sanitized);
  } else {
    console.info(sanitized);
  }
}
