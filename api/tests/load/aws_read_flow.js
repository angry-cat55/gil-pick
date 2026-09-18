import http from "k6/http";
import { check, sleep } from "k6";

const allowedVus = [5, 10, 20, 50, 100];
const targetVus = Number(__ENV.TARGET_VUS || "5");
const duration = __ENV.DURATION || "5m";
const baseUrl = (__ENV.BASE_URL || "").replace(/\/+$/, "");
const accessToken = __ENV.ACCESS_TOKEN || "";
const tripId = __ENV.TRIP_ID || "";
const visitDate = __ENV.VISIT_DATE || "";

if (!allowedVus.includes(targetVus)) {
  throw new Error("TARGET_VUS는 5, 10, 20, 50, 100 중 하나여야 합니다.");
}

for (const [name, value] of Object.entries({
  BASE_URL: baseUrl,
  ACCESS_TOKEN: accessToken,
  TRIP_ID: tripId,
  VISIT_DATE: visitDate,
})) {
  if (!value) {
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    app_read_flow: {
      executor: "constant-vus",
      vus: targetVus,
      duration,
      gracefulStop: "10s",
    },
  },
  thresholds: {
    checks: [{ threshold: "rate>0.99", abortOnFail: true, delayAbortEval: "30s" }],
    http_req_failed: [
      { threshold: "rate<0.01", abortOnFail: true, delayAbortEval: "30s" },
    ],
    http_req_duration: [
      { threshold: "p(95)<1000", abortOnFail: true, delayAbortEval: "30s" },
    ],
  },
};

const requestParams = {
  headers: { Authorization: `Bearer ${accessToken}` },
  timeout: "10s",
};

function pause() {
  sleep(1 + Math.random() * 2);
}

function expectOk(response, name) {
  check(response, { [`${name}: 200`]: (result) => result.status === 200 });
}

export default function () {
  const trips = http.get(`${baseUrl}/trips?limit=100`, {
    ...requestParams,
    tags: { name: "GET /trips" },
  });
  expectOk(trips, "여행 목록");
  pause();

  const [trip, itinerary] = http.batch([
    [
      "GET",
      `${baseUrl}/trips/${tripId}`,
      null,
      { ...requestParams, tags: { name: "GET /trips/:tripId" } },
    ],
    [
      "GET",
      `${baseUrl}/trips/${tripId}/itinerary`,
      null,
      { ...requestParams, tags: { name: "GET /trips/:tripId/itinerary" } },
    ],
  ]);
  expectOk(trip, "여행 상세");
  expectOk(itinerary, "전체 일정");
  pause();

  const [progress, route] = http.batch([
    [
      "GET",
      `${baseUrl}/trips/${tripId}/days/${visitDate}/progress`,
      null,
      { ...requestParams, tags: { name: "GET /trips/:tripId/days/:date/progress" } },
    ],
    [
      "GET",
      `${baseUrl}/trips/${tripId}/days/${visitDate}/route`,
      null,
      { ...requestParams, tags: { name: "GET /trips/:tripId/days/:date/route" } },
    ],
  ]);
  expectOk(progress, "당일 진행");
  expectOk(route, "저장된 경로");
  pause();
}
