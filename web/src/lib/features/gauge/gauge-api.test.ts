import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import { calculateGauge } from "./gauge-api";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("gauge calculator API", () => {
  it("sends numeric values to Rust and preserves exact result strings", async () => {
    const input = {
      pattern_gauge_stitches: 20,
      pattern_gauge_rows: 28,
      user_gauge_stitches: 22,
      user_gauge_rows: 30,
      pattern_cast_on_stitches: 80,
      pattern_row_count: 100,
    };
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: {
          adjusted_stitches: "88",
          adjusted_rows: "107",
        },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await calculateGauge(input);

    expect(result).toEqual({
      adjusted_stitches: "88",
      adjusted_rows: "107",
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v2/tools/gauge");
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      method: "POST",
      credentials: "same-origin",
      body: JSON.stringify(input),
    });
  });

  it("omits the optional row count and keeps a null row result", async () => {
    const input = {
      pattern_gauge_stitches: 20,
      pattern_gauge_rows: 28,
      user_gauge_stitches: 22,
      user_gauge_rows: 30,
      pattern_cast_on_stitches: 80,
    };
    const fetchMock = vi.fn().mockResolvedValue(
      Response.json({
        data: { adjusted_stitches: "88", adjusted_rows: null },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await calculateGauge(input);

    expect(result.adjusted_rows).toBeNull();
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual(
      input,
    );
  });

  it("preserves exact large Rust output strings without converting to Number", async () => {
    const exactCount = "18446744073709551615";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json({
          data: { adjusted_stitches: exactCount, adjusted_rows: null },
        }),
      ),
    );

    const result = await calculateGauge({
      pattern_gauge_stitches: 1,
      pattern_gauge_rows: 1,
      user_gauge_stitches: 1,
      user_gauge_rows: 1,
      pattern_cast_on_stitches: 1,
    });

    expect(result.adjusted_stitches).toBe(exactCount);
  });

  it("surfaces structured validation and request-size errors without retrying", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json(
          {
            error: {
              code: "validation_error",
              message: "One or more fields are invalid",
              fields: { pattern_gauge_stitches: "Must be a positive integer" },
            },
          },
          { status: 422 },
        ),
      )
      .mockResolvedValueOnce(
        Response.json(
          {
            error: {
              code: "payload_too_large",
              message: "Request body is too large",
            },
          },
          { status: 413 },
        ),
      );
    vi.stubGlobal("fetch", fetchMock);
    const input = {
      pattern_gauge_stitches: 20,
      pattern_gauge_rows: 28,
      user_gauge_stitches: 22,
      user_gauge_rows: 30,
      pattern_cast_on_stitches: 80,
    };

    const validationError = await calculateGauge(input).catch(
      (error: unknown) => error,
    );
    expect(validationError).toBeInstanceOf(ApiError);
    expect(validationError).toMatchObject({
      status: 422,
      code: "validation_error",
      fields: { pattern_gauge_stitches: "Must be a positive integer" },
    });
    const payloadError = await calculateGauge(input).catch(
      (error: unknown) => error,
    );
    expect(payloadError).toBeInstanceOf(ApiError);
    expect(payloadError).toMatchObject({
      status: 413,
      code: "payload_too_large",
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("rejects malformed success payloads", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(Response.json({ data: {} })),
    );

    await expect(
      calculateGauge({
        pattern_gauge_stitches: 20,
        pattern_gauge_rows: 28,
        user_gauge_stitches: 22,
        user_gauge_rows: 30,
        pattern_cast_on_stitches: 80,
      }),
    ).rejects.toThrow("The calculator returned an unreadable response.");
  });
});
