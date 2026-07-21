package demo.search.adapter.out.search

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Mapping
import org.springframework.data.elasticsearch.annotations.Setting
import java.time.LocalDateTime

// Elasticsearch 색인 문서. JPA 엔티티(BoardJpaEntity)와 엄격히 분리된, 검색 전용 표현입니다.
// 도메인 Board ↔ 이 문서 변환은 오직 BoardDocumentMapper에서만 일어납니다.
//
// - @Setting: Nori 분석기 정의(analysis)를 board-settings.json에서 로드
// - @Mapping: title/content를 korean(Nori) 분석기로 매핑하는 정의를 board-mappings.json에서 로드
// 분석기·필드 타입은 mappingPath JSON이 최종 권위이고, 아래 프로퍼티는 ES에 실제로 저장/복원되는
// (de)serialization 계약입니다(BoardDocumentMapper가 읽고 씀) — 장식이 아니므로 함부로 지우면 round-trip이 깨집니다.
// _id는 게시글 id(Long)를 문자열로 사용합니다.
//
// title/content만 전문검색(korean 분석기) 대상입니다. createdAt/viewCount는 검색어 매칭에는 쓰이지 않지만,
// 검색 히트에서 DB 왕복 없이 도메인 Board를 그대로 복원(BoardDocumentMapper.toDomain)하기 위해 저장합니다.
// (인기순 정렬 등으로 확장할 때도 이 필드들이 그대로 쓰입니다.)
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
    // 작성자 id. 검색 히트에서 도메인 Board를 그대로 복원하기 위해 저장합니다(작성자 미상은 null).
    val authorId: Long?,
)
