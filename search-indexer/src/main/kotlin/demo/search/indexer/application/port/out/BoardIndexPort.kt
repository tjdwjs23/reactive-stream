package demo.search.indexer.application.port.out

import demo.search.indexer.domain.IndexedBoard

// 검색 인덱스에 게시글을 반영하는 out-port. 저장소가 Elasticsearch인지는 서비스가 모른다(포트-어댑터 경계).
// 배치 소비(한 poll로 받은 여러 레코드)를 벌크로 반영하기 위해 컬렉션 단위 연산만 노출한다.
interface BoardIndexPort {
    // 벌크 색인(upsert): 같은 id의 문서가 있으면 덮어쓴다(CREATED/UPDATED 공통). 한 번의 ES bulk 요청으로 처리한다.
    fun saveAll(boards: List<IndexedBoard>)

    // 벌크 삭제(DELETED). 이미 없는 id는 무시된다(멱등).
    fun deleteAllById(boardIds: List<Long>)
}
