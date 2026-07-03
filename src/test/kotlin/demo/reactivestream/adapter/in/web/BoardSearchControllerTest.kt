package demo.reactivestream.adapter.`in`.web

import demo.reactivestream.application.port.`in`.ReindexBoardsUseCase
import demo.reactivestream.application.port.`in`.ReindexResult
import demo.reactivestream.application.port.`in`.SearchBoardUseCase
import demo.reactivestream.application.port.out.BoardSearchHit
import demo.reactivestream.domain.model.Board
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.springframework.test.web.reactive.server.WebTestClient

private class SearchControllerFixture(
    adminToken: String = "",
) {
    val searchBoardUseCase = mockk<SearchBoardUseCase>()
    val reindexBoardsUseCase = mockk<ReindexBoardsUseCase>()

    val client: WebTestClient =
        WebTestClient
            .bindToController(
                BoardSearchController(
                    searchBoardUseCase,
                    reindexBoardsUseCase,
                    BoardWebMapper(),
                    AdminAccessGuard(adminToken),
                ),
            ).controllerAdvice(GlobalExceptionHandler())
            .build()
}

class BoardSearchControllerTest :
    BehaviorSpec({

        Given("검색어에 매칭되는 게시글이 있을 때 - GET /api/boards/search") {
            val fixture = SearchControllerFixture()
            val hits =
                listOf(
                    BoardSearchHit(
                        board = Board(id = 1L, title = "카카오메일 공지", content = "스팸 필터 개선"),
                        score = 2.0,
                        highlightedTitle = "카카오<em>메일</em> 공지",
                        highlightedContent = null,
                    ),
                    BoardSearchHit(
                        board = Board(id = 2L, title = "주소록 안내", content = "메일과는 무관"),
                        score = 1.0,
                        highlightedTitle = null,
                        highlightedContent = "<em>메일</em>과는 무관",
                    ),
                )
            coEvery { fixture.searchBoardUseCase.search(any()) } returns hits

            When("keyword로 검색하면") {
                Then("200 OK와 관련도순 검색 결과(점수·하이라이트 포함)를 통일 포맷으로 반환한다") {
                    fixture.client
                        .get()
                        .uri("/api/boards/search?keyword=메일&size=5")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Success")
                        .jsonPath("$.result.keyword")
                        .isEqualTo("메일")
                        .jsonPath("$.result.total")
                        .isEqualTo(2)
                        .jsonPath("$.result.items[0].board.id")
                        .isEqualTo(1)
                        .jsonPath("$.result.items[0].score")
                        .isEqualTo(2.0)
                        .jsonPath("$.result.items[0].highlightedTitle")
                        .isEqualTo("카카오<em>메일</em> 공지")
                        .jsonPath("$.result.items[1].highlightedContent")
                        .isEqualTo("<em>메일</em>과는 무관")
                }
            }
        }

        Given("빈 keyword가 주어졌을 때 - GET /api/boards/search") {
            val fixture = SearchControllerFixture()

            When("keyword를 공백으로 요청하면") {
                Then("커맨드 자가검증(IllegalArgumentException)이 400 VALIDATION_ERROR로 매핑된다") {
                    fixture.client
                        .get()
                        .uri("/api/boards/search?keyword=")
                        .exchange()
                        .expectStatus()
                        .isBadRequest
                        .expectBody()
                        .jsonPath("$.status")
                        .isEqualTo("Failure")
                        .jsonPath("$.result.code")
                        .isEqualTo("VALIDATION_ERROR")
                }
            }
        }

        Given("size가 허용 범위를 벗어났을 때 - GET /api/boards/search") {
            val fixture = SearchControllerFixture()

            When("size=0으로 요청하면") {
                Then("400 VALIDATION_ERROR를 반환한다") {
                    fixture.client
                        .get()
                        .uri("/api/boards/search?keyword=메일&size=0")
                        .exchange()
                        .expectStatus()
                        .isBadRequest
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("VALIDATION_ERROR")
                }
            }
        }

        Given("admin 토큰이 설정되지 않았을 때(개발 기본값) - POST /api/boards/search/reindex") {
            val fixture = SearchControllerFixture()
            coEvery { fixture.reindexBoardsUseCase.reindexAll() } returns ReindexResult(indexed = 42L, failed = 3L)

            When("헤더 없이 재색인을 요청하면") {
                Then("200 OK와 색인/실패 건수를 반환한다(무인증 통과)") {
                    fixture.client
                        .post()
                        .uri("/api/boards/search/reindex")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.result.reindexed")
                        .isEqualTo(42)
                        .jsonPath("$.result.failed")
                        .isEqualTo(3)

                    coVerify { fixture.reindexBoardsUseCase.reindexAll() }
                }
            }
        }

        Given("admin 토큰이 설정됐을 때 - POST /api/boards/search/reindex") {
            val fixture = SearchControllerFixture(adminToken = "s3cret")
            coEvery { fixture.reindexBoardsUseCase.reindexAll() } returns ReindexResult(indexed = 1L, failed = 0L)

            When("X-Admin-Token 헤더 없이 요청하면") {
                Then("401 UNAUTHORIZED를 반환한다") {
                    fixture.client
                        .post()
                        .uri("/api/boards/search/reindex")
                        .exchange()
                        .expectStatus()
                        .isUnauthorized
                        .expectBody()
                        .jsonPath("$.result.code")
                        .isEqualTo("UNAUTHORIZED")
                }
            }

            When("올바른 X-Admin-Token 헤더로 요청하면") {
                Then("200 OK로 재색인이 수행된다") {
                    fixture.client
                        .post()
                        .uri("/api/boards/search/reindex")
                        .header("X-Admin-Token", "s3cret")
                        .exchange()
                        .expectStatus()
                        .isOk
                        .expectBody()
                        .jsonPath("$.result.reindexed")
                        .isEqualTo(1)
                }
            }
        }
    })
