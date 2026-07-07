package demo.board.indexer.application.port.out

import demo.board.indexer.domain.IndexedBoard

// 검색 인덱스에 게시글을 반영하는 out-port. 저장소가 Elasticsearch인지는 서비스가 모른다(포트-어댑터 경계).
interface BoardIndexPort {
    // 색인(upsert): 같은 id의 문서가 있으면 덮어쓴다(CREATED/UPDATED 공통).
    fun save(board: IndexedBoard)

    // 색인에서 제거(DELETED). 이미 없으면 무시된다(멱등).
    fun deleteById(boardId: Long)
}
