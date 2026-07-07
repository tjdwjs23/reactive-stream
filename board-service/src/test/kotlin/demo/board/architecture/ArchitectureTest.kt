package demo.board.architecture

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

        "레이어 의존성은 안쪽으로만 향한다 (Adapter → Application → Domain)" {
            project.assertArchitecture {
                val domain = Layer("Domain", "demo.board.domain..")
                val application = Layer("Application", "demo.board.application..")
                val adapter = Layer("Adapter", "demo.board.adapter..")

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
                .scopeFromPackage("demo.board.domain..")
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
                .scopeFromPackage("demo.board.domain.model..")
                .classes()
                .assertFalse { clazz ->
                    clazz.hasAnnotation { annotation ->
                        annotation.name in listOf("Table", "Id", "Column", "Document", "Entity", "Field")
                    }
                }
        }

        "웹 컨트롤러는 서비스 구현이 아니라 UseCase(in-port)에 의존한다" {
            Konsist
                .scopeFromPackage("demo.board.adapter.in.web..")
                .files
                .assertFalse { file ->
                    file.hasImport { import -> import.name.startsWith("demo.board.application.service") }
                }
        }

        "R2DBC @Table 엔티티는 영속성 어댑터 패키지 안에만 존재한다" {
            Konsist
                .scopeFromProject()
                .classes()
                .filter { clazz -> clazz.hasAnnotation { it.name == "Table" } }
                .assertFalse { clazz -> !clazz.resideInPackage("demo.board.adapter.out.persistence..") }
        }

        "ES @Document 문서는 검색 어댑터 패키지 안에만 존재한다" {
            Konsist
                .scopeFromProject()
                .classes()
                .filter { clazz -> clazz.hasAnnotation { it.name == "Document" } }
                .assertFalse { clazz -> !clazz.resideInPackage("demo.board.adapter.out.search..") }
        }
    })
