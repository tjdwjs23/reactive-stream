// ★ 관측성 데모 시나리오 — 대시보드의 "모든 섹션"을 의도적으로 켠다.
// mixed.js가 깨끗한 해피패스라 에러/경고로그 패널이 비는 문제를 보완합니다.
// 정상 트래픽 + 의도적 에러(401/403/404/400) + 포화 구간을 한 번에 발생시켜
// 트래픽 · 에러율 · 지연 · 포화도 · 비즈니스 · 로그(WARN) · 트레이스 패널을 모두 채웁니다.
//
//   BASE_URL=http://localhost:8080 k6 run load/scenarios/signals.js
//
// 무엇이 어떤 패널을 켜는가:
//   - 정상 읽기/쓰기/검색      → 트래픽·지연·비즈니스(board_*)·R2DBC/Redis 풀 패널
//   - 고부하 인기글 읽기        → 조회수 INCR 200ms 예산 초과 → "view count increment timed out" WARN 로그
//   - 포화 스파이크(SPIKE_RATE) → CPU/힙/스레드/R2DBC pending 상승 = 포화도 패널
//   - 의도적 에러 요청          → 응답 상태(status별)·에러율(4xx/5xx) 패널
//   - 모든 요청                 → Tempo 트레이스(포화 시 duration>50ms 느린 트레이스 다수)
//
// 강도/비율(환경변수):
//   HOT_COUNT   인기 글 시드 수                       기본 30
//   PEAK_RATE   정상 구간 목표 RPS                    기본 800
//   SPIKE_RATE  포화 스파이크 목표 RPS               기본 PEAK_RATE*3
//   ERROR_PCT   전체 중 의도적 에러 요청 비중(%)     기본 20

import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL,
  authHeaders,
  JSON_HEADERS,
  LOAD_USERNAME,
  LOAD_PASSWORD,
  ADMIN_USERNAME,
  ADMIN_PASSWORD,
} from '../lib/config.js';
import { seedBoards, signUpAndLogin, login, randomBoardPayload, pick, SEARCH_KEYWORDS } from '../lib/helpers.js';

const HOT_COUNT = Number(__ENV.HOT_COUNT || 30);
const PEAK_RATE = Number(__ENV.PEAK_RATE || 800);
const SPIKE_RATE = Number(__ENV.SPIKE_RATE || PEAK_RATE * 3);
const ERROR_PCT = Number(__ENV.ERROR_PCT || 20);
const PRE_VUS = Number(__ENV.PRE_VUS || Math.min(4000, Math.max(500, SPIKE_RATE)));

export const options = {
  scenarios: {
    signals: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: 6000,
      stages: [
        { target: PEAK_RATE, duration: '45s' }, // 램프업 — 정상 트래픽
        { target: PEAK_RATE, duration: '1m' }, // 정상 지속
        { target: SPIKE_RATE, duration: '30s' }, // ★ 포화 스파이크 — 조회수 타임아웃 WARN + 느린 트레이스 + 포화도
        { target: SPIKE_RATE, duration: '30s' }, // 포화 유지
        { target: PEAK_RATE, duration: '30s' }, // 회복
        { target: 0, duration: '20s' }, // 램프다운
      ],
    },
  },
  // 이 시나리오는 에러를 "일부러" 만들므로 전역 실패율 게이트를 걸지 않습니다.
  // 대신 정상 트래픽(expect:ok)에만 기준을 걸어 진짜 회귀만 잡습니다.
  thresholds: {
    'http_req_failed{expect:ok}': ['rate<0.02'],
    'checks{kind:expectation}': ['rate>0.95'], // "기대한 상태코드가 왔는가"는 에러 요청에도 성립해야 함
  },
};

export function setup() {
  const userToken = signUpAndLogin(LOAD_USERNAME, LOAD_PASSWORD);
  if (!userToken) throw new Error('로드 유저 로그인 실패: 서버/PostgreSQL 확인.');
  // IDOR(403)용 두 번째 사용자 — user1이 만든 글을 user2가 수정 시도.
  const otherToken = signUpAndLogin(`${LOAD_USERNAME}2`, `${LOAD_PASSWORD}2`);
  const adminToken = ADMIN_PASSWORD ? login(ADMIN_USERNAME, ADMIN_PASSWORD) : null;

  const ids = seedBoards(HOT_COUNT, userToken);
  if (ids.length === 0) throw new Error('시드 실패: 서버/PostgreSQL 확인.');
  console.log(`[setup] 인기 글 ${ids.length}건 시드 (other=${otherToken ? 'ok' : 'none'}, admin=${adminToken ? 'ok' : 'none'})`);
  return { ids, userToken, otherToken, adminToken };
}

export default function (data) {
  const roll = Math.random() * 100;

  // ── 의도적 에러 요청 (ERROR_PCT%) — 에러율/응답상태 패널을 켠다 ────────────────────────────
  if (roll < ERROR_PCT) {
    const kind = Math.floor(Math.random() * 4);
    if (kind === 0) {
      // 401: 토큰 없이 쓰기 시도
      const r = http.post(`${BASE_URL}/api/boards`, JSON.stringify(randomBoardPayload()), {
        headers: JSON_HEADERS,
        tags: { name: 'err_401', expect: 'err' },
      });
      check(r, { '401 미인증': (x) => x.status === 401 }, { kind: 'expectation' });
    } else if (kind === 1) {
      // 403: 남의 글 수정(IDOR) — otherToken이 있을 때만, 없으면 401로 대체
      const id = pick(data.ids);
      const headers = data.otherToken ? authHeaders(data.otherToken) : JSON_HEADERS;
      const expected = data.otherToken ? 403 : 401;
      const r = http.put(`${BASE_URL}/api/boards/${id}`, JSON.stringify(randomBoardPayload()), {
        headers,
        tags: { name: 'err_403', expect: 'err' },
      });
      check(r, { '403/401 인가거부': (x) => x.status === expected }, { kind: 'expectation' });
    } else if (kind === 2) {
      // 404: 존재하지 않는 id 조회
      const r = http.get(`${BASE_URL}/api/boards/999999999`, {
        tags: { name: 'err_404', expect: 'err' },
      });
      check(r, { '404 없음': (x) => x.status === 404 }, { kind: 'expectation' });
    } else {
      // 400: 숫자가 아닌 id(TypeMismatch) → InvalidParameter
      const r = http.get(`${BASE_URL}/api/boards/not-a-number`, {
        tags: { name: 'err_400', expect: 'err' },
      });
      check(r, { '400 잘못된 파라미터': (x) => x.status === 400 }, { kind: 'expectation' });
    }
    return;
  }

  // ── 정상 트래픽 (나머지) — 읽기 우위로 조회수 경합을 유발 ──────────────────────────────────
  const g = Math.random() * 100;
  if (g < 70) {
    // 읽기: 인기 글 단건 조회(→ Redis 조회수 INCR, 포화 시 200ms 예산 초과 → WARN 로그)
    const id = pick(data.ids);
    const r = http.get(`${BASE_URL}/api/boards/${id}`, { tags: { name: 'get', op: 'read', expect: 'ok' } });
    check(r, { 'read 200': (x) => x.status === 200 }, { kind: 'expectation' });
  } else if (g < 88) {
    // 쓰기: 새 글(→ DB write + ES 인라인 색인)
    const r = http.post(`${BASE_URL}/api/boards`, JSON.stringify(randomBoardPayload()), {
      headers: authHeaders(data.userToken),
      tags: { name: 'create', op: 'write', expect: 'ok' },
    });
    check(r, { 'write 201': (x) => x.status === 201 }, { kind: 'expectation' });
  } else {
    // 검색: 한글 전문검색(→ ES/Nori)
    const kw = encodeURIComponent(pick(SEARCH_KEYWORDS));
    const r = http.get(`${BASE_URL}/api/boards/search?keyword=${kw}&size=20`, {
      tags: { name: 'search', op: 'search', expect: 'ok' },
    });
    check(r, { 'search 200': (x) => x.status === 200 }, { kind: 'expectation' });
  }
}

// 종료 후 조회수 델타를 DB로 flush(누적 델타 write-back까지 마무리).
export function teardown(data) {
  if (!data.adminToken) {
    console.log('[teardown] admin 토큰 없음 → view-count flush 생략 (ADMIN_PASSWORD 설정 시 수행)');
    return;
  }
  const r = http.post(`${BASE_URL}/api/admin/view-counts/flush`, null, {
    headers: authHeaders(data.adminToken),
    tags: { name: 'flush' },
  });
  console.log(`[teardown] view-count flush status=${r.status}`);
}
