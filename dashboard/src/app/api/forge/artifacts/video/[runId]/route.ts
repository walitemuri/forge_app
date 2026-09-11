import {
  createReadStream,
} from "node:fs";

import {
  stat,
} from "node:fs/promises";

import path from "node:path";

import {
  Readable,
} from "node:stream";

import type {
  NextRequest,
} from "next/server";

export const runtime = "nodejs";

const ARTIFACT_ROOT =
  "/workspace/shared";

function artifactPath(
  runId: string,
) {
  if (
    !/^[a-f0-9]{8}$/i.test(runId)
  ) {
    return null;
  }

  return path.join(
    ARTIFACT_ROOT,
    `video-${runId}`,
    "forge-demo-output.mp4",
  );
}

function parseRange(
  value: string,
  size: number,
) {
  const match =
    /^bytes=(\d*)-(\d*)$/.exec(
      value,
    );

  if (!match || (!match[1] && !match[2])) {
    return null;
  }

  let start: number;
  let end: number;

  if (
    match[1] === "" &&
    match[2] !== ""
  ) {
    const suffixLength =
      Number(match[2]);

    if (
      !Number.isFinite(
        suffixLength,
      ) ||
      suffixLength <= 0
    ) {
      return null;
    }

    start =
      Math.max(
        size - suffixLength,
        0,
      );

    end = size - 1;
  } else {
    start =
      Number(match[1]);

    end =
      match[2] === ""
        ? size - 1
        : Number(match[2]);
  }

  if (
    !Number.isSafeInteger(start) ||
    !Number.isSafeInteger(end) ||
    start < 0 ||
    end < start ||
    start >= size
  ) {
    return null;
  }

  end =
    Math.min(
      end,
      size - 1,
    );

  return {
    start,
    end,
  };
}

async function getArtifact(
  runId: string,
) {
  const file =
    artifactPath(runId);

  if (!file) {
    return null;
  }

  try {
    const info =
      await stat(file);

    if (!info.isFile()) {
      return null;
    }

    return {
      file,
      size: info.size,
    };
  } catch {
    return null;
  }
}

export async function GET(
  request: NextRequest,
  context: {
    params: Promise<{
      runId: string;
    }>;
  },
) {
  const {
    runId,
  } = await context.params;

  const artifact =
    await getArtifact(runId);

  if (!artifact) {
    return new Response(
      "Video artifact not found",
      {
        status: 404,
      },
    );
  }

  const download =
    request.nextUrl.searchParams.get(
      "download",
    ) === "1";

  const rangeHeader =
    request.headers.get("range");

  const baseHeaders =
    new Headers({
      "Content-Type":
        "video/mp4",

      "Accept-Ranges":
        "bytes",

      "Cache-Control":
        "private, max-age=3600",

      "Content-Disposition":
        `${
          download
            ? "attachment"
            : "inline"
        }; filename="forge-${runId}.mp4"`,
    });

  if (!rangeHeader) {
    baseHeaders.set(
      "Content-Length",
      String(artifact.size),
    );

    const stream =
      createReadStream(
        /* turbopackIgnore: true */ artifact.file,
      );

    return new Response(
      Readable.toWeb(
        stream,
      ) as unknown as BodyInit,
      {
        status: 200,
        headers:
          baseHeaders,
      },
    );
  }

  const range =
    parseRange(
      rangeHeader,
      artifact.size,
    );

  if (!range) {
    return new Response(
      null,
      {
        status: 416,
        headers: {
          "Content-Range":
            `bytes */${artifact.size}`,
        },
      },
    );
  }

  const length =
    range.end -
    range.start +
    1;

  baseHeaders.set(
    "Content-Length",
    String(length),
  );

  baseHeaders.set(
    "Content-Range",
    `bytes ${range.start}-${range.end}/${artifact.size}`,
  );

  const stream =
    createReadStream(
      /* turbopackIgnore: true */ artifact.file,
      {
        start: range.start,
        end: range.end,
      },
    );

  return new Response(
    Readable.toWeb(
      stream,
    ) as unknown as BodyInit,
    {
      status: 206,
      headers:
        baseHeaders,
    },
  );
}
