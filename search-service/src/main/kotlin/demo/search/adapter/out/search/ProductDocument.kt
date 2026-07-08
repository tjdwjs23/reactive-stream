package demo.search.adapter.out.search

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Mapping
import org.springframework.data.elasticsearch.annotations.Setting
import java.time.LocalDateTime

// 상품 검색 색인 문서. JPA 엔티티(ProductJpaEntity)와 엄격히 분리된 검색 전용 표현이며, 도메인 Product ↔ 이 문서 변환은
// ProductDocumentMapper에서만 일어납니다.
//
// @Document(indexName="products")는 '인덱스'가 아니라 'alias'를 가리킵니다(Board와 동일한 무중단 재색인 전략).
// @Setting: Nori + ICU 초성 분석기 정의를 product-settings.json에서, @Mapping: name(+ name.chosung/name.ngram 다중필드)을
// product-mappings.json에서 로드합니다. 실제 필드 매핑은 그 JSON이 최종 권위이며, name의 초성/부분 서브필드도 거기서 정의됩니다.
// 문서는 name만 저장하고, name.chosung(초성 + edge_ngram)/name.ngram(부분)은 색인 시 분석기가 파생합니다(multi-field).
@Document(indexName = "products")
@Setting(settingPath = "elasticsearch/product-settings.json")
@Mapping(mappingPath = "elasticsearch/product-mappings.json")
data class ProductDocument(
    @Id
    val id: String,
    val name: String,
    val price: Long,
    val createdAt: LocalDateTime,
)
