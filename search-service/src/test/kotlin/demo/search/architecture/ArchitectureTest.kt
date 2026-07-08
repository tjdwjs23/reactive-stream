package demo.search.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.verify.assertFalse
import io.kotest.core.spec.style.StringSpec

// CLAUDE.md가 규정하는 헥사고날 규칙을 "사람의 규율"이 아니라 "깨지면 실패하는 테스트"로 강제합니다.
// (리뷰/관례에만 기대면 규칙은 조용히 샙니다 — 이 테스트가 회귀를 막습니다.)
class ArchitectureTest :
    StringSpec({

        val project = Konsist.scopeFromProject()

        // 모노레포 주의: scopeFromProject()는 루트(.git/settings 기준) 아래 전 모듈을 스캔합니다. search-service의
        // 아키텍처 규칙은 search-service만 governance해야 하므로, demo.search 접두사를 공유하는 다른 모듈
        // (search-indexer = demo.search.indexer..)은 아래 엔티티 위치 규칙에서 resideInPackage 부정으로 제외합니다.

        "레이어 의존성은 안쪽으로만 향한다 (Adapter → Application → Domain)" {
            project.assertArchitecture {
                val domain = Layer("Domain", "demo.search.domain..")
                val application = Layer("Application", "demo.search.application..")
                val adapter = Layer("Adapter", "demo.search.adapter..")

                // 도메인은 어떤 바깥 계층에도 의존하지 않는다(가장 안쪽).
                domain.dependsOnNothing()
                // 애플리케이션은 도메인에만 의존한다(어댑터를 몰라야 한다).
                application.dependsOn(domain)
                // 어댑터는 애플리케이션(포트)과 도메인에 의존한다.
                adapter.dependsOn(application, domain)
            }
        }

        "도메인 계층은 Spring/영속성 프레임워크에 의존하지 않는다" {
            Konsist
                .scopeFromPackage("demo.search.domain..")
                .files
                .assertFalse { file ->
                    file.hasImport { import ->
                        import.name.startsWith("org.springframework") ||
                            import.name.startsWith("jakarta.persistence") ||
                            import.name.startsWith("co.elastic") ||
                            import.name.startsWith("io.r2dbc")
                    }
                }
        }

        "도메인 모델에는 영속성/ES 애노테이션이 새어 들어가지 않는다" {
            Konsist
                .scopeFromPackage("demo.search.domain.model..")
                .classes()
                .assertFalse { clazz ->
                    clazz.hasAnnotation { annotation ->
                        annotation.name in listOf("Table", "Id", "Column", "Document", "Entity", "Field")
                    }
                }
        }

        "웹 컨트롤러는 서비스 구현이 아니라 UseCase(in-port)에 의존한다" {
            Konsist
                .scopeFromPackage("demo.search.adapter.in.web..")
                .files
                .assertFalse { file ->
                    file.hasImport { import -> import.name.startsWith("demo.search.application.service") }
                }
        }

        "JPA @Table 엔티티는 영속성 어댑터 패키지 안에만 존재한다" {
            Konsist
                .scopeFromProject()
                .classes()
                .filter { clazz -> !clazz.resideInPackage("demo.search.indexer..") } // 다른 모듈(search-indexer) 제외
                .filter { clazz -> clazz.hasAnnotation { it.name == "Table" } }
                .assertFalse { clazz -> !clazz.resideInPackage("demo.search.adapter.out.persistence..") }
        }

        "ES @Document 문서는 검색 어댑터 패키지 안에만 존재한다" {
            Konsist
                .scopeFromProject()
                .classes()
                .filter { clazz -> !clazz.resideInPackage("demo.search.indexer..") } // 다른 모듈(search-indexer) 제외
                .filter { clazz -> clazz.hasAnnotation { it.name == "Document" } }
                .assertFalse { clazz -> !clazz.resideInPackage("demo.search.adapter.out.search..") }
        }
    })
