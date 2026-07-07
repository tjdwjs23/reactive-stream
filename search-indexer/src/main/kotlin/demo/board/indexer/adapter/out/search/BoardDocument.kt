package demo.board.indexer.adapter.out.search

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Mapping
import org.springframework.data.elasticsearch.annotations.Setting
import java.time.LocalDateTime

// Elasticsearch 색인 문서. board-service의 BoardDocument와 "동일한 boards 인덱스"를 공유하므로 스키마가 일치해야 합니다
// (search-indexer가 writer, board-service의 검색/재색인이 reader). 도메인 IndexedBoard ↔ 이 문서 변환은
// ElasticsearchBoardIndexAdapter의 private IndexedBoard.toDocument()에서만 일어나며, ES 애노테이션은 포트/서비스로 새어 나가지 않습니다.
//
// - @Setting: Nori 분석기 정의(analysis)를 board-settings.json에서 로드
// - @Mapping: title/content를 korean(Nori) 분석기로 매핑하는 정의를 board-mappings.json에서 로드
// _id는 게시글 id(Long)를 문자열로 사용합니다.
@Document(indexName = "boards")
@Setting(settingPath = "elasticsearch/board-settings.json")
@Mapping(mappingPath = "elasticsearch/board-mappings.json")
data class BoardDocument(
    @Id
    val id: String,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime,
    val viewCount: Long,
    val authorId: Long?,
)
