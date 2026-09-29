import { describe, expect, it } from "vitest";
import { localeFromSearchParam } from "../localeFromSearchParam";

describe("localeFromSearchParam", () => {
  it("accepts allowlisted locales case-insensitively", () => {
    expect(localeFromSearchParam("en")).toBe("en");
    expect(localeFromSearchParam("EN")).toBe("en");
    expect(localeFromSearchParam(" tr ")).toBe("tr");
  });

  it("falls back to null for missing or unsupported values", () => {
    expect(localeFromSearchParam(null)).toBeNull();
    expect(localeFromSearchParam(undefined)).toBeNull();
    expect(localeFromSearchParam("")).toBeNull();
    expect(localeFromSearchParam("de")).toBeNull();
    expect(localeFromSearchParam("en-US")).toBeNull();
  });
});
