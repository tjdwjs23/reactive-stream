package demo.board.adapter.out.search

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Mapping
import org.springframework.data.elasticsearch.annotations.Setting
import java.time.LocalDateTime

// Elasticsearch 색인 문서. R2DBC 엔티티(BoardR2dbcEntity)와 엄격히 분리된, 검색 전용 표현입니다.
// 도메인 Board ↔ 이 문서 변환은 오직 BoardDocumentMapper에서만 일어납니다.
//
// - @Setting: Nori 분석기 정의(analysis)를 board-settings.json에서 로드
// - @Mapping: title/content를 korean(Nori) 분석기로 매핑하는 정의를 board-mappings.json에서 로드
// 실제 필드 매핑은 mappingPath JSON이 최종 권위이며, 아래 프로퍼티는 문서 구조 문서화 용도입니다.
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
)
