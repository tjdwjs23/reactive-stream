package demo.board.indexer.adapter.out.search

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Mapping
import org.springframework.data.elasticsearch.annotations.Setting
import java.time.LocalDateTime

// ES 색인 문서(상품). board-service의 ProductDocument와 "동일한 products 인덱스"를 공유하므로 스키마가 일치해야 합니다
// (search-indexer=writer, board-service=reader). 알려진 tradeoff: ProductDocument/설정 JSON이 두 모듈에 중복됩니다.
// @Setting/@Mapping은 Nori + ICU 초성 정의(product-settings/mappings.json)를 가리킵니다. name.chosung/name.ngram은 색인 시 파생됩니다.
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
