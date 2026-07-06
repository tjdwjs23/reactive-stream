// 시나리오 공용 헬퍼: 랜덤 한글 게시글 payload 생성, 생성 API 호출, 배열 랜덤 픽 등.
// 한글 단어를 섞어 만드는 이유 — ES(Nori) 검색 시나리오가 형태소 분석할 실제 한글 코퍼스가 필요.

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, JSON_HEADERS, authHeaders } from './config.js';

const SUBJECTS = ['카카오', '메일', '알림', '주소록', '검색', '보안', '배치', '캐시', '큐', '인덱스'];
const VERBS = ['개선', '장애', '점검', '릴리즈', '설계', '테스트', '모니터링', '최적화', '회복', '분석'];
const NOUNS = ['성능', '지연', '처리량', '정합성', '트래픽', '부하', '색인', '조회수', '스케줄러', '파이프라인'];

// 검색 시나리오가 실제로 매칭될 만한 키워드 풀(위 코퍼스와 겹치게).
export const SEARCH_KEYWORDS = ['메일', '검색', '부하', '색인', '조회수', '성능', '큐', '카카오'];

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
export { pick };

// content는 도메인 규칙상 10자 이상이어야 함(Board.MIN_CONTENT_LENGTH). 넉넉히 채웁니다.
export function randomBoardPayload() {
  const title = `${pick(SUBJECTS)} ${pick(VERBS)} ${pick(NOUNS)}`;
  const content =
    `${title} 관련 상세 내용입니다. ` +
    `${pick(NOUNS)} 지표를 ${pick(VERBS)}하고 ${pick(SUBJECTS)} ${pick(NOUNS)}을(를) 점검합니다. ` +
    `추가 설명 ${Math.floor(Math.random() * 100000)}.`;
  return { title, content };
}

// 로그인해 액세스 토큰을 반환(실패 시 null). 응답 봉투는 { code, status, result: { accessToken, ... } }.
export function login(username, password) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ username, password }), {
    headers: JSON_HEADERS,
    tags: { name: 'login' },
  });
  if (res.status !== 200) return null;
  try {
    return res.json('result.accessToken');
  } catch (_) {
    return null;
  }
}

// 가입 후 로그인해 토큰을 반환. 이미 존재하면(409) 무시하고 로그인만 시도합니다.
export function signUpAndLogin(username, password) {
  http.post(`${BASE_URL}/api/auth/signup`, JSON.stringify({ username, password }), {
    headers: JSON_HEADERS,
    tags: { name: 'signup' },
  });
  return login(username, password);
}

// 게시글 1건 생성 후 id 반환(실패 시 null). 쓰기는 인증이 필요하므로 Bearer 토큰을 받습니다.
export function createBoard(token, tags = {}) {
  const res = http.post(`${BASE_URL}/api/boards`, JSON.stringify(randomBoardPayload()), {
    headers: authHeaders(token),
    tags: Object.assign({ name: 'create' }, tags),
  });
  const ok = check(res, { 'create 201': (r) => r.status === 201 });
  if (!ok) return null;
  try {
    return res.json('result.id');
  } catch (_) {
    return null;
  }
}

// setup()에서 부하 대상 시드 데이터를 만들 때 사용. count건 생성하고 id 배열 반환.
export function seedBoards(count, token) {
  const ids = [];
  for (let i = 0; i < count; i++) {
    const id = createBoard(token, { name: 'seed' });
    if (id != null) ids.push(id);
  }
  return ids;
}
