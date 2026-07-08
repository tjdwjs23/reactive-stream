package demo.board.adapter.out.persistence

import demo.board.application.port.out.TransactionRunnerPort
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

// TransactionRunnerPort의 Spring 구현. Boot가 JPA 스택에서 자동 구성하는 PlatformTransactionManager(JpaTransactionManager)로
// TransactionTemplate을 만들어, block을 하나의 트랜잭션 안에서 실행합니다. 블록 내 JPA 저장과 JdbcTemplate 네이티브
// (아웃박스 INSERT/조회수 UPDATE)는 같은 커넥션/트랜잭션을 공유합니다(Transactional Outbox 원자성).
// 트랜잭션 기술이 이 어댑터에만 갇혀, 애플리케이션 서비스는 프레임워크 없이 원자성만 요청합니다.
@Component
class SpringTransactionRunner(
    transactionManager: PlatformTransactionManager,
) : TransactionRunnerPort {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Suppress("UNCHECKED_CAST")
    override fun <T> execute(block: () -> T): T = transactionTemplate.execute { block() } as T
}
