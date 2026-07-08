package demo.board.adapter.`in`.web

import demo.board.application.port.`in`.ArchiveResult
import demo.board.application.port.`in`.ArchiveStaleBoardsCommand
import demo.board.application.port.`in`.ArchiveStaleBoardsUseCase
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

// 접근 통제(ROLE_ADMIN)는 SecurityConfig(필터 체인)의 책임이라 SecurityIntegrationTest가 검증합니다.
// 여기서는 standalone MockMvc로 핸들러가 요청 본문을 커맨드로 옮겨 유즈케이스를 호출하고
// 결과를 통일 포맷으로 반환하는지, 그리고 잘못된 파라미터가 400으로 매핑되는지만 봅니다.
// 아카이브 유즈케이스는 (코루틴 배치라) suspend이므로 목 스텁/검증은 coEvery/coVerify를 씁니다.
private class AdminBoardArchiveFixture {
    val archiveUseCase = mockk<ArchiveStaleBoardsUseCase>()

    val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AdminBoardArchiveController(archiveUseCase))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
}

class AdminBoardArchiveControllerTest :
    BehaviorSpec({

        Given("오래된 게시글 즉시 아카이브 - POST /api/admin/boards/archive") {
            When("retentionDays와 튜닝 파라미터로 요청하면") {
                val fixture = AdminBoardArchiveFixture()
                val commandSlot = slot<ArchiveStaleBoardsCommand>()
                coEvery { fixture.archiveUseCase.archiveStaleBoards(capture(commandSlot)) } returns
                    ArchiveResult(scanned = 10, deleted = 8, failedChunks = 0)

                Then("200 OK와 아카이브 결과 요약을 반환하고, 본문 값이 커맨드로 전달된다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/admin/boards/archive")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"retentionDays":30,"chunkSize":200,"concurrency":8}"""),
                        ).andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("Success"))
                        .andExpect(jsonPath("$.result.scanned").value(10))
                        .andExpect(jsonPath("$.result.deleted").value(8))
                        .andExpect(jsonPath("$.result.failedChunks").value(0))

                    coVerify { fixture.archiveUseCase.archiveStaleBoards(any()) }
                    commandSlot.captured.retentionDays shouldBe 30L
                    commandSlot.captured.chunkSize shouldBe 200
                    commandSlot.captured.concurrency shouldBe 8
                }
            }

            When("chunkSize/concurrency를 생략하면") {
                val fixture = AdminBoardArchiveFixture()
                val commandSlot = slot<ArchiveStaleBoardsCommand>()
                coEvery { fixture.archiveUseCase.archiveStaleBoards(capture(commandSlot)) } returns
                    ArchiveResult(scanned = 0, deleted = 0, failedChunks = 0)

                Then("커맨드 기본값(500/4)이 적용된다") {
                    fixture.mockMvc
                        .perform(
                            post("/api/admin/boards/archive")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"retentionDays":365}"""),
                        ).andExpect(status().isOk)

                    commandSlot.captured.chunkSize shouldBe 500
                    commandSlot.captured.concurrency shouldBe 4
                }
            }

            When("retentionDays가 음수면") {
                val fixture = AdminBoardArchiveFixture()

                Then("커맨드 자가검증 실패로 400을 반환한다(유즈케이스는 호출되지 않는다)") {
                    fixture.mockMvc
                        .perform(
                            post("/api/admin/boards/archive")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"retentionDays":-1}"""),
                        ).andExpect(status().isBadRequest)

                    coVerify(exactly = 0) { fixture.archiveUseCase.archiveStaleBoards(any()) }
                }
            }
        }
    })
