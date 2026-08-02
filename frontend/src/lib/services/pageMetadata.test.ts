import { describe, expect, it } from "vitest";
import {
  applicationViewForPath,
  canonicalRecoveryPathForPath,
  lessonSlugForPath,
} from "./pageMetadata";

describe("lessonSlugForPath", () => {
  it("extracts the slug from an implemented lesson route", () => {
    expect(lessonSlugForPath("/learn/variables-and-types")).toBe("variables-and-types");
  });

  it("tolerates a trailing slash", () => {
    expect(lessonSlugForPath("/learn/variables-and-types/")).toBe("variables-and-types");
  });

  it("returns null for view paths and chat routes", () => {
    expect(lessonSlugForPath("/learn")).toBeNull();
    expect(lessonSlugForPath("/learn/")).toBeNull();
    expect(lessonSlugForPath("/")).toBeNull();
    expect(lessonSlugForPath("/chat")).toBeNull();
  });

  it("returns null for deeper descendants", () => {
    expect(lessonSlugForPath("/learn/variables-and-types/extra")).toBeNull();
  });

  it("returns null for segments that violate the slug contract", () => {
    expect(lessonSlugForPath("/learn/Variables")).toBeNull();
    expect(lessonSlugForPath("/learn/bad_slug")).toBeNull();
    expect(lessonSlugForPath("/learn/-leading-dash")).toBeNull();
  });

  it("does not treat guided descendants as lesson routes", () => {
    expect(lessonSlugForPath("/guided/variables-and-types")).toBeNull();
  });
});

describe("lesson route resolution", () => {
  it("routes lesson paths to the learn view without recovery", () => {
    expect(applicationViewForPath("/learn/variables-and-types")).toBe("learn");
    expect(canonicalRecoveryPathForPath("/learn/variables-and-types")).toBeNull();
  });

  it("recovers deeper learn descendants to the canonical learn route", () => {
    expect(canonicalRecoveryPathForPath("/learn/variables-and-types/extra")).toBe("/learn");
  });

  it("recovers invalid slug segments to the canonical learn route", () => {
    expect(canonicalRecoveryPathForPath("/learn/Variables")).toBe("/learn");
  });

  it("recovers guided descendants to the canonical learn route", () => {
    expect(canonicalRecoveryPathForPath("/guided/variables-and-types")).toBe("/learn");
  });
});

describe("privacy route resolution", () => {
  it("keeps the exact privacy path separate from chat and learning views", () => {
    expect(applicationViewForPath("/privacy")).toBe("privacy");
    expect(applicationViewForPath("/privacy/")).toBe("privacy");
    expect(applicationViewForPath("/privacy/extra")).toBe("chat");
    expect(canonicalRecoveryPathForPath("/privacy")).toBeNull();
  });
});

describe("contact route resolution", () => {
  it("keeps the exact contact path separate from chat and learning views", () => {
    expect(applicationViewForPath("/contact")).toBe("contact");
    expect(applicationViewForPath("/contact/")).toBe("contact");
    expect(applicationViewForPath("/contact/extra")).toBe("chat");
    expect(canonicalRecoveryPathForPath("/contact")).toBeNull();
  });
});
